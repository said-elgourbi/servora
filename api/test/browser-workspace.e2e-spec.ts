import { FakeObjectStorage } from './support/fake-object-storage.js';
import type { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { execFile } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { resolve } from 'node:path';
import { promisify } from 'node:util';
import { eq } from 'drizzle-orm';
import { afterAll, beforeAll, describe, it } from 'vitest';
import { AppModule } from '../src/app.module.js';
import { BROWSER_AUTH_CONFIG } from '../src/auth/browser-auth.config.js';
import {
  customers,
  jobs,
  organizationMemberPermissions,
  organizationMembers,
  organizationJobNumberCounters,
  permissions,
  properties,
  visits,
  visitTechnicians,
} from '../src/database/schema.js';
import { hashPassword } from '../src/users/password-hasher.js';
import {
  createFoundationTestDatabase,
  createTestOrganization,
  createTestOrganizationRole,
  createTestUser,
  uniqueEmail,
  type FoundationTestDatabase,
} from './support/foundation-database.js';

// Opt-in: requires the web production build and a Playwright Chromium installation.
describe.skipIf(process.env.RUN_CONNECTED_BROWSER !== '1')(
  'connected browser workspace',
  () => {
    let app: INestApplication | undefined;
    let database: FoundationTestDatabase | undefined;
    let fixture: Record<string, string>;
    let apiOrigin: string;

    beforeAll(async () => {
      database = await createFoundationTestDatabase();
      const db = database.db;
      const organization = await createTestOrganization(db);
      database.cleanup.trackOrganization(organization.id);
      const role = await createTestOrganizationRole(db, organization.id);
      const password = randomUUID();
      const passwordHash = await hashPassword(password);
      async function member(code: string) {
        const user = await createTestUser(db, {
          email: uniqueEmail('web-acceptance'),
          passwordHash,
        });
        database!.cleanup.trackUser(user.id);
        const [membership] = await db
          .insert(organizationMembers)
          .values({
            organizationId: organization.id,
            userId: user.id,
            roleId: role.id,
          })
          .returning();
        const [permission] = await db
          .select()
          .from(permissions)
          .where(eq(permissions.code, code));
        if (!permission) throw new Error(`Missing permission: ${code}`);
        await db.insert(organizationMemberPermissions).values({
          organizationId: organization.id,
          memberId: membership.id,
          permissionId: permission.id,
        });
        return { email: user.email!, id: membership.id };
      }
      const office = await member('customers.view');
      for (const code of [
        'visits.review_requests',
        'customers.create',
        'customers.edit',
        'customers.archive',
        'customers.contacts.create',
        'customers.contacts.edit',
        'customers.contacts.remove',
        'properties.view',
        'properties.create',
        'properties.edit',
        'properties.archive',
        'properties.delete',
        'JOB_CREATE',
        'JOB_UPDATE',
        'evidence.view',
        'evidence.photo.remove',
        'evidence.audio.remove',
      ]) {
        const [permission] = await db
          .select()
          .from(permissions)
          .where(eq(permissions.code, code));
        if (!permission) throw new Error(`Missing permission: ${code}`);
        await db.insert(organizationMemberPermissions).values({
          organizationId: organization.id,
          memberId: office.id,
          permissionId: permission.id,
        });
      }
      const field = await member('VISIT_VIEW_ASSIGNED');
      for (const code of [
        'VISIT_ADD_NOTE',
        'VISIT_UPDATE_ASSIGNED_STATUS',
        'VISIT_RECORD_OUTCOME',
        'evidence.photo.add',
        'evidence.audio.add',
        'evidence.view',
        'visits.report_ad_hoc_work',
        'visits.request_follow_up',
      ]) {
        const [permission] = await db
          .select()
          .from(permissions)
          .where(eq(permissions.code, code));
        if (!permission) throw new Error(`Missing permission ${code}`);
        await db.insert(organizationMemberPermissions).values({
          organizationId: organization.id,
          memberId: field.id,
          permissionId: permission.id,
        });
      }
      const colleague = await member('VISIT_VIEW_ASSIGNED');
      const [customer] = await db
        .insert(customers)
        .values({
          organizationId: organization.id,
          type: 'COMPANY',
          displayName: 'Connected customer',
        })
        .returning();
      const [property] = await db
        .insert(properties)
        .values({
          organizationId: organization.id,
          addressLine1: '987 Cedar Lane',
          city: 'Montreal',
          province: 'QC',
          postalCode: 'H3A 2T6',
        })
        .returning();
      async function assignedJob(
        number: number,
        title: string,
        membershipId: string,
      ) {
        const [job] = await db
          .insert(jobs)
          .values({
            organizationId: organization.id,
            customerId: customer.id,
            propertyId: property.id,
            propertyAddressSnapshot: { addressLine1: '987 Cedar Lane' },
            jobNumber: number,
            title,
            status: 'ACTIVE',
          })
          .returning();
        const start = new Date();
        start.setUTCHours(12, 0, 0, 0);
        const [visit] = await db
          .insert(visits)
          .values({
            organizationId: organization.id,
            jobId: job.id,
            propertyId: property.id,
            locationAddressSnapshot: { addressLine1: '987 Cedar Lane' },
            status: 'SCHEDULED',
            scheduledStart: start,
            scheduledEnd: new Date(start.getTime() + 3600000),
          })
          .returning();
        await db.insert(visitTechnicians).values({
          organizationId: organization.id,
          visitId: visit.id,
          technicianMembershipId: membershipId,
          roleCode: 'LEAD',
        });
        return job.id;
      }
      fixture = {
        office: office.email,
        field: field.email,
        password,
        jobId: await assignedJob(1, 'Connected furnace repair', field.id),
        colleagueJobId: await assignedJob(
          2,
          'Colleague private job',
          colleague.id,
        ),
      };
      // The two hand-built Jobs above occupy numbers 1 and 2. Real creation must start after them.
      await db
        .insert(organizationJobNumberCounters)
        .values({ organizationId: organization.id, lastJobNumber: 2 });
      const module = await Test.createTestingModule({ imports: [AppModule] })
        .overrideProvider(FakeObjectStorage.token)
        .useValue(new FakeObjectStorage())
        .overrideProvider(BROWSER_AUTH_CONFIG)
        .useValue({ origin: 'http://127.0.0.1:4300', secure: false })
        .compile();
      app = module.createNestApplication({ logger: false });
      await app.listen(0, '127.0.0.1');
      apiOrigin = await app.getUrl();
    }, 30000);

    afterAll(async () => {
      try {
        await app?.close();
      } finally {
        await database?.dispose();
      }
    });

    it('passes office and assigned-only browser journeys without mocked responses', async () => {
      const { stdout, stderr } = await promisify(execFile)(
        process.execPath,
        [
          'node_modules/@playwright/test/cli.js',
          'test',
          '--config',
          'playwright.connected.config.ts',
        ],
        {
          cwd: resolve('../web'),
          timeout: 100000,
          env: {
            ...process.env,
            SERVORA_CONNECTED_API: apiOrigin,
            SERVORA_CONNECTED_OFFICE: fixture.office,
            SERVORA_CONNECTED_FIELD: fixture.field,
            SERVORA_CONNECTED_PASSWORD: fixture.password,
            SERVORA_CONNECTED_JOB_ID: fixture.jobId,
            SERVORA_CONNECTED_COLLEAGUE_JOB_ID: fixture.colleagueJobId,
          },
        },
      ).catch((error: unknown) => {
        if (typeof error === 'object' && error !== null && 'stdout' in error) {
          console.error(error.stdout);
        }
        throw error;
      });
      console.log(stdout);
      if (stderr) console.error(stderr);
    }, 110000);
  },
);
