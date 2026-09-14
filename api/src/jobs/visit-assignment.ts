import { and, asc, desc, eq, inArray, isNotNull, ne, sql } from 'drizzle-orm';
import type { DatabaseService } from '../database/database.service.js';
import {
  organizationMembers,
  userProfiles,
  visitTechnicians,
  visits,
} from '../database/schema.js';
import { memberName } from '../members/member-name.js';
import type { OrganizationScope } from '../tenancy/tenant-scope.js';
import type { AssignmentRoleCode, VisitStatus } from './job.types.js';

/**
 * The Visit assignment reads (`BR-068`, `BR-081`).
 *
 * Two reads project the same two facts — which single Visit represents a Job, and who is assigned to
 * a Visit — so they are defined once here instead of being written a second time in each read module.
 * Both are tenant-scoped by `organization_id` (`BR-001`, `src/tenancy/tenant-scope.ts`): nothing here
 * fetches a Job or a Visit by primary key alone.
 */

/** One technician currently assigned to a Visit (`BR-068`). */
export interface AssignedTechnician {
  readonly membershipId: string;
  /** Resolved from the member's profile; `null` when the member has no profile yet. */
  readonly name: string | null;
  readonly roleCode: AssignmentRoleCode;
}

/** The single Visit a Job is represented by (`BR-081`). */
export interface SelectedVisit {
  readonly visitId: string;
  readonly status: VisitStatus;
  readonly scheduledStart: Date;
  readonly scheduledEnd: Date;
  /** The Visit's version, which a reschedule or assignment echoes back (`BR-086`). */
  readonly version: number;
}

/**
 * Chooses each Job's single **selected Visit** (`BR-081`).
 *
 * The selected Visit is the Job's earliest upcoming non-canceled Visit by scheduled start, falling
 * back to the most recent past Visit by scheduled start. A Visit with no scheduled start is never
 * selected: the schedule is exactly what the Visit would have to supply. A Visit always has both
 * ends of its schedule when it has one, so the pair is read together.
 *
 * Upcoming and past are settled with `DISTINCT ON` so the choice is made in the database and the
 * result stays one row per Job.
 */
export async function selectVisitsForJobs(
  db: DatabaseService['db'],
  scope: OrganizationScope,
  jobIds: readonly string[],
): Promise<Map<string, SelectedVisit>> {
  if (jobIds.length === 0) {
    return new Map();
  }

  const selection = {
    jobId: visits.jobId,
    visitId: visits.id,
    status: visits.status,
    scheduledStart: visits.scheduledStart,
    scheduledEnd: visits.scheduledEnd,
    version: visits.version,
  };
  const scoped = and(
    eq(visits.organizationId, scope.organizationId),
    inArray(visits.jobId, [...jobIds]),
    isNotNull(visits.scheduledStart),
    isNotNull(visits.scheduledEnd),
  );

  const [upcoming, past] = await Promise.all([
    db
      .selectDistinctOn([visits.jobId], selection)
      .from(visits)
      .where(
        and(
          scoped,
          sql`${visits.scheduledStart} >= now()`,
          ne(visits.status, 'CANCELED'),
        ),
      )
      .orderBy(visits.jobId, asc(visits.scheduledStart)),
    db
      .selectDistinctOn([visits.jobId], selection)
      .from(visits)
      .where(and(scoped, sql`${visits.scheduledStart} < now()`))
      .orderBy(visits.jobId, desc(visits.scheduledStart)),
  ]);

  const selected = new Map<string, SelectedVisit>();
  // The past Visit is the fallback, so it is written first and an upcoming Visit overrides it.
  for (const row of [...past, ...upcoming]) {
    selected.set(row.jobId, {
      visitId: row.visitId,
      status: row.status as VisitStatus,
      scheduledStart: row.scheduledStart as Date,
      scheduledEnd: row.scheduledEnd as Date,
      version: row.version,
    });
  }
  return selected;
}

/**
 * The technicians currently assigned to each Visit, Lead first (`BR-068`).
 *
 * Every assigned technician is a member of the organization with a stable assignment role code
 * (`LEAD` or `TECHNICIAN`); Servora has no other assignment role and never uses the term "Helper".
 * A member without a profile contributes no name, not a fabricated one (`BR-020`).
 */
export async function readAssignedTechnicians(
  db: DatabaseService['db'],
  scope: OrganizationScope,
  visitIds: readonly string[],
): Promise<Map<string, AssignedTechnician[]>> {
  if (visitIds.length === 0) {
    return new Map();
  }

  const rows = await db
    .select({
      visitId: visitTechnicians.visitId,
      membershipId: visitTechnicians.technicianMembershipId,
      roleCode: visitTechnicians.roleCode,
      displayName: userProfiles.displayName,
      firstName: userProfiles.firstName,
      lastName: userProfiles.lastName,
    })
    .from(visitTechnicians)
    .innerJoin(
      organizationMembers,
      eq(organizationMembers.id, visitTechnicians.technicianMembershipId),
    )
    .leftJoin(userProfiles, eq(userProfiles.userId, organizationMembers.userId))
    .where(
      and(
        eq(visitTechnicians.organizationId, scope.organizationId),
        inArray(visitTechnicians.visitId, [...visitIds]),
      ),
    )
    .orderBy(
      sql`case when ${visitTechnicians.roleCode} = 'LEAD' then 0 else 1 end`,
      asc(visitTechnicians.createdAt),
    );

  const byVisit = new Map<string, AssignedTechnician[]>();
  for (const row of rows) {
    const assigned = byVisit.get(row.visitId) ?? [];
    assigned.push({
      membershipId: row.membershipId,
      name: memberName(row),
      roleCode: row.roleCode as AssignmentRoleCode,
    });
    byVisit.set(row.visitId, assigned);
  }
  return byVisit;
}
