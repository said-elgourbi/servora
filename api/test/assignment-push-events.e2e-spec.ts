import type { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { and, eq } from 'drizzle-orm';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import { AppModule } from '../src/app.module.js';
import {
  customers,
  followUpVisitRequests,
  jobs,
  organizationMembers,
  properties,
  pushAssignmentEvents,
  visits,
  visitTechnicians,
  visitTechnicianHistory,
} from '../src/database/schema.js';
import * as eventWriter from '../src/jobs/assignment-push-events.js';
import {
  JobsService,
  VisitVersionConflictError,
} from '../src/jobs/jobs.service.js';
import type { RequestedVisitTechnician } from '../src/jobs/follow-up-visit-request.dto.js';
import {
  createFoundationTestDatabase,
  createTestOrganization,
  createTestOrganizationRole,
  createTestUser,
  expectPostgresError,
  type FoundationTestDatabase,
} from './support/foundation-database.js';

describe('transactional assignment push events (PostgreSQL)', () => {
  let database: FoundationTestDatabase;
  let app: INestApplication;
  let service: JobsService;
  let jobNumber = 0;

  beforeAll(async () => {
    vi.stubEnv('PUSH_PROVIDER', 'disabled');
    database = await createFoundationTestDatabase();
    const module = await Test.createTestingModule({
      imports: [AppModule],
    }).compile();
    app = module.createNestApplication({ logger: false });
    await app.init();
    service = app.get(JobsService);
  });
  afterAll(async () => {
    await app?.close();
    await database?.dispose();
    vi.unstubAllEnvs();
  });

  async function fixture(withVisit = true) {
    const organization = await createTestOrganization(database.db);
    database.cleanup.trackOrganization(organization.id);
    const scope = { organizationId: organization.id };
    const role = await createTestOrganizationRole(database.db, organization.id);
    const members = [];
    for (let i = 0; i < 3; i++) {
      const user = await createTestUser(database.db);
      database.cleanup.trackUser(user.id);
      const [member] = await database.db
        .insert(organizationMembers)
        .values({
          ...scope,
          userId: user.id,
          roleId: role.id,
        })
        .returning();
      members.push(member);
    }
    const [actor, first, second] = members;
    const [customer] = await database.db
      .insert(customers)
      .values({
        ...scope,
        type: 'COMPANY',
        displayName: 'Push event fixture',
      })
      .returning();
    const [property] = await database.db
      .insert(properties)
      .values({
        ...scope,
        addressLine1: '10 Test Street',
        city: 'Ottawa',
        province: 'ON',
        postalCode: 'K1A 0B1',
      })
      .returning();
    const [job] = await database.db
      .insert(jobs)
      .values({
        ...scope,
        customerId: customer.id,
        propertyId: property.id,
        propertyAddressSnapshot: {
          addressLine1: '10 Test Street',
          city: 'Ottawa',
          province: 'ON',
          postalCode: 'K1A 0B1',
          country: 'CA',
        },
        jobNumber: ++jobNumber,
        title: 'Assignment test',
      })
      .returning();
    const [visit] = withVisit
      ? await database.db
          .insert(visits)
          .values({ ...scope, jobId: job.id })
          .returning()
      : [];
    return { scope, actor, first, second, job, visit };
  }
  type Fixture = Awaited<ReturnType<typeof fixture>>;
  function crew(...members: Fixture['actor'][]): RequestedVisitTechnician[] {
    return members.map((member, i) => ({
      membershipId: member.id,
      roleCode: i === 0 ? 'LEAD' : 'TECHNICIAN',
    }));
  }
  function assign(
    f: Fixture,
    technicians = crew(f.first),
    expectedVersion: number | null = null,
  ) {
    return service.assignVisitTechnicians(
      f.scope,
      f.job.id,
      f.visit.id,
      f.actor.id,
      {
        technicians,
        expectedVersion,
        confirmConflicts: true,
      },
    );
  }
  function events(f: Fixture) {
    return database.db
      .select()
      .from(pushAssignmentEvents)
      .where(
        and(
          eq(pushAssignmentEvents.organizationId, f.scope.organizationId),
          eq(pushAssignmentEvents.jobId, f.job.id),
        ),
      );
  }
  function writeInput(f: Fixture) {
    return {
      ...f.scope,
      jobId: f.job.id,
      visitId: f.visit.id,
      assignmentVersion: 2,
      actorMembershipId: f.actor.id,
      addedMembershipIds: [f.first.id],
    };
  }

  it('persists one intent per new recipient without devices and excludes the actor by user', async () => {
    const f = await fixture();
    await assign(f, crew(f.actor, f.first, f.second), 1);
    const rows = await events(f);
    expect(rows.map((row) => row.recipientMembershipId).sort()).toEqual(
      [f.first.id, f.second.id].sort(),
    );
    for (const row of rows) {
      expect(row).toMatchObject({
        organizationId: f.scope.organizationId,
        jobId: f.job.id,
        visitId: f.visit.id,
        assignmentVersion: 2,
        actorUserId: f.actor.userId,
        eventType: 'VISIT_ASSIGNED',
        expandedAt: null,
      });
      expect(row.expiresAt.getTime() - row.occurredAt.getTime()).toBe(
        24 * 60 * 60 * 1000,
      );
    }
  });

  it('unchanged replay and role-only changes do not notify existing crew', async () => {
    const f = await fixture();
    await assign(f, crew(f.first, f.second), 1);
    const initial = await events(f);
    await assign(f, crew(f.first, f.second), 2);
    await assign(f, crew(f.second, f.first), 2);
    expect(await events(f)).toEqual(initial);
    const [visit] = await database.db
      .select()
      .from(visits)
      .where(eq(visits.id, f.visit.id));
    expect(visit.version).toBe(3);
  });

  it('removing and readding a member creates a fresh versioned event', async () => {
    const f = await fixture();
    await assign(f, crew(f.first, f.second));
    await assign(f, crew(f.first));
    await assign(f, crew(f.first, f.second));
    const rows = (await events(f)).filter(
      (row) => row.recipientMembershipId === f.second.id,
    );
    expect(rows.map((row) => row.assignmentVersion).sort()).toEqual([2, 4]);
    expect(new Set(rows.map((row) => row.id)).size).toBe(2);
  });

  it('rejects stale versions without changing crew or event identity', async () => {
    const f = await fixture();
    await assign(f, crew(f.first), 1);
    const before = await events(f);
    await expect(assign(f, crew(f.second), 1)).rejects.toBeInstanceOf(
      VisitVersionConflictError,
    );
    expect(await events(f)).toEqual(before);
  });

  it('serializes concurrent versioned edits and enqueues only the winner', async () => {
    const f = await fixture();
    const results = await Promise.allSettled([
      assign(f, crew(f.first), 1),
      assign(f, crew(f.second), 1),
    ]);
    expect(
      results.filter((result) => result.status === 'fulfilled'),
    ).toHaveLength(1);
    const rejected = results.find(
      (result) => result.status === 'rejected',
    ) as PromiseRejectedResult;
    expect(rejected.reason).toBeInstanceOf(VisitVersionConflictError);
    const rows = await events(f);
    expect(rows).toHaveLength(1);
    const assigned = await database.db
      .select()
      .from(visitTechnicians)
      .where(eq(visitTechnicians.visitId, f.visit.id));
    expect(rows[0].recipientMembershipId).toBe(
      assigned[0].technicianMembershipId,
    );
  });

  it('serializes unversioned duplicate crew requests without duplicate history or intents', async () => {
    const f = await fixture();
    await Promise.all([assign(f), assign(f)]);
    expect(await events(f)).toHaveLength(1);
    const [visit] = await database.db
      .select()
      .from(visits)
      .where(eq(visits.id, f.visit.id));
    expect(visit.version).toBe(2);
    expect(
      await database.db
        .select()
        .from(visitTechnicianHistory)
        .where(eq(visitTechnicianHistory.visitId, f.visit.id)),
    ).toHaveLength(1);
  });

  it('rolls back crew version and intent together after an enqueue failure', async () => {
    const f = await fixture();
    const original = eventWriter.enqueueAssignmentPushEvents;
    const spy = vi
      .spyOn(eventWriter, 'enqueueAssignmentPushEvents')
      .mockImplementationOnce(async (tx, input) => {
        await original(tx, input);
        throw new Error('Simulated failure after enqueue');
      });
    try {
      await expect(assign(f)).rejects.toThrow(
        'Simulated failure after enqueue',
      );
    } finally {
      spy.mockRestore();
    }
    expect(await events(f)).toHaveLength(0);
    expect(
      await database.db
        .select()
        .from(visitTechnicians)
        .where(eq(visitTechnicians.visitId, f.visit.id)),
    ).toHaveLength(0);
    const [visit] = await database.db
      .select()
      .from(visits)
      .where(eq(visits.id, f.visit.id));
    expect(visit.version).toBe(1);
  });

  it('exact writer replay keeps the event UUID and expiry unchanged', async () => {
    const f = await fixture();
    await database.db.transaction((tx) =>
      eventWriter.enqueueAssignmentPushEvents(tx, writeInput(f)),
    );
    const before = await events(f);
    await database.db.transaction((tx) =>
      eventWriter.enqueueAssignmentPushEvents(tx, writeInput(f)),
    );
    expect(await events(f)).toEqual(before);
  });

  it('initial scheduled crew uses the same writer and suppresses actor assignment', async () => {
    const f = await fixture(false);
    await service.createScheduledVisit(f.scope, f.job.id, f.actor.id, {
      purposeCode: 'WORK',
      scheduledStart: new Date('2027-01-10T12:00:00Z'),
      scheduledEnd: new Date('2027-01-10T13:00:00Z'),
      technicians: crew(f.actor, f.first),
      confirmConflicts: true,
    });
    const rows = await events(f);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({
      recipientMembershipId: f.first.id,
      assignmentVersion: 1,
    });
    const [visit] = await database.db
      .select()
      .from(visits)
      .where(eq(visits.id, rows[0].visitId));
    expect(visit.status).toBe('SCHEDULED');
  });

  it('rolls back initial Visit creation if enqueue fails after inserting an event', async () => {
    const f = await fixture(false);
    const original = eventWriter.enqueueAssignmentPushEvents;
    const spy = vi
      .spyOn(eventWriter, 'enqueueAssignmentPushEvents')
      .mockImplementationOnce(async (tx, input) => {
        await original(tx, input);
        throw new Error('Simulated create failure');
      });
    try {
      await expect(
        service.createScheduledVisit(f.scope, f.job.id, f.actor.id, {
          purposeCode: 'WORK',
          scheduledStart: new Date('2027-01-10T12:00:00Z'),
          scheduledEnd: new Date('2027-01-10T13:00:00Z'),
          technicians: crew(f.first),
          confirmConflicts: true,
        }),
      ).rejects.toThrow('Simulated create failure');
    } finally {
      spy.mockRestore();
    }
    expect(await events(f)).toHaveLength(0);
    expect(
      await database.db.select().from(visits).where(eq(visits.jobId, f.job.id)),
    ).toHaveLength(0);
    const [job] = await database.db
      .select()
      .from(jobs)
      .where(eq(jobs.id, f.job.id));
    expect(job.status).toBe('NEW');
  });

  it('follow-up approval enqueues initial crew exactly once across approval replay', async () => {
    const f = await fixture(false);
    const [request] = await database.db
      .insert(followUpVisitRequests)
      .values({
        ...f.scope,
        jobId: f.job.id,
        requestingTechnicianMembershipId: f.first.id,
        proposedStart: new Date('2027-01-10T12:00:00Z'),
        proposedEnd: new Date('2027-01-10T13:00:00Z'),
        reason: 'Follow up',
      })
      .returning();
    const input = {
      technicians: crew(f.first),
      scheduledStart: null,
      scheduledEnd: null,
      confirmConflicts: true,
      note: null,
      expectedStatus: null,
      expectedVersion: 1,
    };
    await service.approveFollowUpVisitRequest(
      f.scope,
      f.job.id,
      request.id,
      f.actor.id,
      input,
    );
    const initial = await events(f);
    await service.approveFollowUpVisitRequest(
      f.scope,
      f.job.id,
      request.id,
      f.actor.id,
      input,
    );
    expect(initial).toHaveLength(1);
    expect(await events(f)).toEqual(initial);
  });

  it('rejects cross-tenant actors and does not select cross-tenant recipients', async () => {
    const f = await fixture();
    const other = await fixture();
    await expect(
      database.db.transaction((tx) =>
        eventWriter.enqueueAssignmentPushEvents(tx, {
          ...writeInput(f),
          actorMembershipId: other.actor.id,
        }),
      ),
    ).rejects.toThrow('Assignment actor is outside the organization');
    await database.db.transaction((tx) =>
      eventWriter.enqueueAssignmentPushEvents(tx, {
        ...writeInput(f),
        addedMembershipIds: [other.first.id],
      }),
    );
    expect(await events(f)).toHaveLength(0);
  });

  it('enforces tenant ownership Visit-to-Job relationship business uniqueness and valid expiry in SQL', async () => {
    const f = await fixture();
    const other = await fixture();
    await assign(f);
    const [event] = await events(f);
    const { id: _id, ...values } = event;
    const [sameOrganizationJob] = await database.db
      .insert(jobs)
      .values({
        ...f.scope,
        customerId: f.job.customerId,
        jobNumber: ++jobNumber,
        title: 'Other Job',
      })
      .returning();
    await expectPostgresError(
      () => database.db.insert(pushAssignmentEvents).values(values),
      '23505',
    );
    for (const change of [
      { recipientMembershipId: other.first.id },
      { jobId: other.job.id },
      { visitId: other.visit.id },
      { jobId: sameOrganizationJob.id },
    ]) {
      await expectPostgresError(
        () =>
          database.db
            .insert(pushAssignmentEvents)
            .values({ ...values, assignmentVersion: 5, ...change }),
        '23503',
      );
    }
    for (const change of [
      { assignmentVersion: 0 },
      { eventType: 'VISIT_REMOVED' },
      { expiresAt: event.occurredAt },
    ]) {
      await expectPostgresError(
        () =>
          database.db
            .insert(pushAssignmentEvents)
            .values({ ...values, assignmentVersion: 5, ...change }),
        '23514',
      );
    }
  });
});
