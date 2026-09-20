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
import type {
  AssignmentRoleCode,
  VisitOutcomeCode,
  VisitStatus,
} from './job.types.js';

/**
 * The Visit reads (`BR-068`, `BR-081`).
 *
 * Three facts are projected from the same records — which single Visit represents a Job, who is
 * assigned to a Visit, and which Visits a Job has in its own visit sequence — so each is defined once
 * here instead of being written a second time in each read module. All of them are tenant-scoped by
 * `organization_id` (`BR-001`, `src/tenancy/tenant-scope.ts`): nothing here fetches a Job or a Visit by
 * primary key alone.
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
 * One Visit of a Job, with the sequence the Job's own order gives it (`BR-047`, `BR-071`).
 *
 * A Job may have several Visits over time and each one is a field attempt of its own (`BR-051`,
 * `BR-071`), so a read that presents the Job rather than a single field attempt needs the whole list.
 * [scheduledStart] and [scheduledEnd] are `null` for a Visit that has no schedule yet — a `DRAFT`
 * Visit exists before it is scheduled (`BR-072`) — and are always both present or both absent, which
 * the `visits_schedule_pair_check` constraint enforces.
 */
export interface JobVisit {
  readonly visitId: string;
  /**
   * The outcome the Visit's completion recorded, or `null` when the Visit has none (`BR-077`,
   * `BR-078`).
   *
   * It is the Visit's **current** outcome and not its outcome history: a Visit that was completed and
   * then reopened holds no current outcome until it is completed again, while the outcome it recorded
   * stays in append-only history (`BR-074`, `BR-079`). Reading it from the Visit rather than from the
   * activity timeline is therefore the difference between "what resulted from this field attempt" and
   * "what once resulted from it" — a distinction only the authoritative record can make (`BR-001`).
   */
  readonly outcomeCode: VisitOutcomeCode | null;
  /**
   * The Visit's stable, human-readable sequence within the Job — `1` for the Job's first Visit, `2`
   * for the second, and so on.
   *
   * It is the same `Visit N` label Job Activity carries (`docs/api/job-activity.md` §3.3): derived
   * from the Visit's creation order within the Job, never stored, never an identifier and never a
   * cross-system key (`BR-052`, `docs/domain/job-visit-domain-model.md` §6).
   */
  readonly sequence: number;
  readonly status: VisitStatus;
  readonly scheduledStart: Date | null;
  readonly scheduledEnd: Date | null;
  readonly version: number;
}

/**
 * Every Visit of one Job, in the Job's own visit sequence (`BR-047`, `BR-071`).
 *
 * The sequence is derived from the Visit's creation order within the Job, which is the one definition
 * of `Visit N` this codebase keeps: Job Activity reads it through this function so a Job's Visit
 * cannot be `Visit 2` in one projection and `Visit 3` in another (`BR-041`).
 *
 * It answers "which Visits does this Job have" rather than "which Visit represents it": a Job with no
 * Visit answers an empty list, and a Visit with no schedule is included because it is still a field
 * attempt the Job holds (`BR-051`, `BR-072`).
 */
export async function readJobVisits(
  db: DatabaseService['db'],
  scope: OrganizationScope,
  jobId: string,
): Promise<JobVisit[]> {
  const rows = await db
    .select({
      visitId: visits.id,
      status: visits.status,
      scheduledStart: visits.scheduledStart,
      scheduledEnd: visits.scheduledEnd,
      version: visits.version,
      outcomeCode: visits.outcomeCode,
    })
    .from(visits)
    .where(
      and(
        eq(visits.organizationId, scope.organizationId),
        eq(visits.jobId, jobId),
      ),
    )
    .orderBy(asc(visits.createdAt), asc(visits.id));

  return rows.map((row, index) => ({
    visitId: row.visitId,
    sequence: index + 1,
    status: row.status as VisitStatus,
    scheduledStart: row.scheduledStart,
    scheduledEnd: row.scheduledEnd,
    version: row.version,
    outcomeCode: row.outcomeCode as VisitOutcomeCode | null,
  }));
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
