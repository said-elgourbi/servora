import { Injectable } from '@nestjs/common';
import { and, eq, getTableColumns, sql } from 'drizzle-orm';
import type { SQL } from 'drizzle-orm';
import { DatabaseService } from '../database/database.service.js';
import {
  customers,
  jobPropertyHistory,
  jobs,
  properties,
  propertyCustomerRelationships,
  propertyLifecycleHistory,
  visitLocationHistory,
  visits,
} from '../database/schema.js';
import type { OrganizationScope } from '../tenancy/tenant-scope.js';
import type { Property } from './customer.types.js';
import { asDate } from './customers.service.js';
import type {
  PropertyDetail,
  PropertyLifecycleDto,
  UpdatePropertyDto,
} from './property.dto.js';

/**
 * Raised when an organization-owned Property is not visible to the caller's tenant scope, or is not
 * part of the Customer context the request addresses.
 *
 * Callers treat this as "not found" (never "forbidden") so the API does not leak the existence of
 * another organization's records (`BR-001`).
 */
export class PropertyNotFoundError extends Error {
  constructor(propertyId: string) {
    super(
      `Property ${propertyId} was not found in the requested organization or customer context.`,
    );
    this.name = 'PropertyNotFoundError';
  }
}

/**
 * Raised when a mutation carries a version the Property has already moved past.
 *
 * The API is the final authority: a mutation against newer state is rejected rather than applied
 * (`BR-086`, `BR-031`).
 */
export class PropertyVersionConflictError extends Error {
  readonly currentVersion: number;

  constructor(propertyId: string, currentVersion: number) {
    super(
      `Property ${propertyId} has moved past the version the request carried (current ${currentVersion}).`,
    );
    this.name = 'PropertyVersionConflictError';
    this.currentVersion = currentVersion;
  }
}

/**
 * Raised when a Property may not be permanently deleted because another business record references
 * it (`BR-082`).
 *
 * [referenceKinds] names the reference families found, so the API can report the operational impact
 * without exposing database detail (`dev.md` §7).
 */
export class PropertyHasReferencesError extends Error {
  readonly referenceKinds: readonly string[];

  constructor(propertyId: string, referenceKinds: readonly string[]) {
    super(
      `Property ${propertyId} is referenced by ${referenceKinds.join(', ')} and cannot be deleted.`,
    );
    this.name = 'PropertyHasReferencesError';
    this.referenceKinds = [...referenceKinds];
  }
}

/**
 * Raised when work may not start at a Property because it is not `ACTIVE` (`BR-083`).
 *
 * Archiving blocks *new* Jobs while leaving existing Jobs and Visits untouched, so this is the
 * check a Job-creation path performs before relating a Job to a Property.
 */
export class PropertyNotAvailableForNewWorkError extends Error {
  constructor(propertyId: string) {
    super(`Property ${propertyId} is not available for new work.`);
    this.name = 'PropertyNotAvailableForNewWorkError';
  }
}

/**
 * The Job statuses that count as open work for the archive warning (`BR-083`).
 *
 * This is the Job lifecycle's non-terminal set (`BR-058`). It is the `archiveWarningOpenWork`
 * classification, which is deliberately not the derived `needsSchedulingActiveVisit` set (`BR-060`).
 */
const ARCHIVE_WARNING_OPEN_JOB_STATUSES = [
  'NEW',
  'SCHEDULED',
  'IN_PROGRESS',
  'PENDING_REVIEW',
] as const;

/**
 * The Visit statuses that are historical (`BR-074`).
 *
 * A Visit in any other status counts as open work for the archive warning (`BR-083`), including a
 * `DRAFT` Visit that is not yet scheduled.
 */
const ARCHIVE_WARNING_TERMINAL_VISIT_STATUSES = [
  'COMPLETED',
  'CANCELED',
  'NO_SHOW',
] as const;

/** The database handle a read may use: the pool itself or the transaction it runs inside. */
type Executor = Pick<
  DatabaseService['db'],
  'select' | 'insert' | 'update' | 'delete'
>;

/**
 * Owns the Property lifecycle: edit, archive, restore and permanent deletion (`BR-082` – `BR-086`).
 *
 * TENANCY: every read/write is scoped by `(organization_id, id)` and, where the request addresses a
 * Customer, by that Customer's relationship to the Property. There is deliberately no method that
 * fetches a Property by primary key alone.
 *
 * The lifecycle rules are enforced here, not in a client: the API decides whether an edit, an
 * archive, a restore or a deletion is allowed (`BR-007`).
 */
@Injectable()
export class PropertiesService {
  constructor(private readonly database: DatabaseService) {}

  private get db() {
    return this.database.db;
  }

  /**
   * The Property plus the derived values a lifecycle screen renders, or `null` when the caller's
   * organization does not own it or it is not part of the addressed Customer.
   */
  async findPropertyDetailInOrganization(
    scope: OrganizationScope,
    customerId: string,
    propertyId: string,
  ): Promise<PropertyDetail | null> {
    const property = await this.resolvePropertyForCustomer(
      this.db,
      scope,
      customerId,
      propertyId,
    );
    if (property === null) {
      return null;
    }
    return this.readPropertyDetail(this.db, scope, propertyId);
  }

  /**
   * Edits a Property whether it is `ACTIVE` or `ARCHIVED` (`BR-084`).
   *
   * Editing never changes `status`: an archived Property stays archived until an explicit restore,
   * and no historical address snapshot held by a Job or a Visit is rewritten (`BR-057`, `BR-084`).
   */
  async updateProperty(
    scope: OrganizationScope,
    customerId: string,
    propertyId: string,
    input: UpdatePropertyDto,
  ): Promise<PropertyDetail> {
    await this.db.transaction(async (tx) => {
      const property = await this.requirePropertyForCustomer(
        tx,
        scope,
        customerId,
        propertyId,
      );
      this.assertVersion(property, input.expectedVersion);

      await tx
        .update(properties)
        .set({
          name: input.name ?? null,
          addressLine1: input.addressLine1,
          addressLine2: input.addressLine2 ?? null,
          city: input.city,
          province: input.province,
          postalCode: input.postalCode,
          notes: input.notes ?? null,
          version: sql`${properties.version} + 1`,
        })
        .where(
          and(
            eq(properties.organizationId, scope.organizationId),
            eq(properties.id, propertyId),
          ),
        );
    });

    return this.requireDetail(this.db, scope, propertyId);
  }

  /**
   * Archives a Property: it leaves active use but remains a retrievable record (`BR-082`).
   *
   * Archiving is idempotent. A Property already `ARCHIVED` is returned unchanged and no second
   * lifecycle event is appended; a replayed offline operation carrying the same `clientOperationId`
   * is recognised as already applied and produces no second event (`BR-031`). Existing Jobs and
   * Visits are never modified by this operation (`BR-083`).
   */
  async archiveProperty(
    scope: OrganizationScope & { membershipId: string },
    customerId: string,
    propertyId: string,
    input: PropertyLifecycleDto,
    now: Date = new Date(),
  ): Promise<PropertyDetail> {
    return this.applyLifecycleTransition(
      scope,
      customerId,
      propertyId,
      'ARCHIVED',
      input,
      now,
    );
  }

  /**
   * Restores an archived Property to active use (`BR-082`).
   *
   * Restoring never creates a Property and never changes its identity. It is idempotent in the same
   * way as archiving: an `ACTIVE` Property is returned unchanged and a replayed operation appends no
   * second event.
   */
  async restoreProperty(
    scope: OrganizationScope & { membershipId: string },
    customerId: string,
    propertyId: string,
    input: PropertyLifecycleDto,
    now: Date = new Date(),
  ): Promise<PropertyDetail> {
    return this.applyLifecycleTransition(
      scope,
      customerId,
      propertyId,
      'RESTORED',
      input,
      now,
    );
  }

  /**
   * Permanently deletes a Property that no other business record has ever referenced (`BR-082`).
   *
   * The reference check and the delete run in one transaction under the Property row lock, so a
   * reference created concurrently cannot slip between the check and the delete
   * (`docs/domain/job-visit-domain-model.md` §15). A referenced Property is refused; nothing
   * cascades to Jobs, Visits, notes, attachments or history.
   */
  async deleteProperty(
    scope: OrganizationScope,
    customerId: string,
    propertyId: string,
  ): Promise<void> {
    await this.db.transaction(async (tx) => {
      await this.requirePropertyForCustomer(
        tx,
        scope,
        customerId,
        propertyId,
        true,
      );

      const referenceKinds = await this.findReferenceKinds(
        tx,
        scope,
        propertyId,
      );
      if (referenceKinds.length > 0) {
        throw new PropertyHasReferencesError(propertyId, referenceKinds);
      }

      try {
        await tx
          .delete(properties)
          .where(
            and(
              eq(properties.organizationId, scope.organizationId),
              eq(properties.id, propertyId),
            ),
          );
      } catch (error) {
        // Fail closed: the pre-check runs under the row lock, but a reference this slice does not
        // know about still refuses the delete at the database level. That must be reported as a
        // conflict, never as a server failure (`BR-082`).
        if (isForeignKeyViolation(error)) {
          throw new PropertyHasReferencesError(propertyId, [
            'unknownReference',
          ]);
        }
        throw error;
      }
    });
  }

  /**
   * Fails when a Property may not receive new work because it is not `ACTIVE` (`BR-083`).
   *
   * Archiving blocks *new* Jobs only; existing Jobs, their Visits and their rescheduling are
   * untouched. This is the check the Job-creation path performs, so the rule is enforced by the
   * backend rather than by a client hiding a selector (`BR-007`).
   */
  async assertPropertyAvailableForNewWork(
    scope: OrganizationScope,
    propertyId: string,
  ): Promise<Property> {
    const [property] = await this.db
      .select()
      .from(properties)
      .where(
        and(
          eq(properties.organizationId, scope.organizationId),
          eq(properties.id, propertyId),
        ),
      )
      .limit(1);
    if (property === undefined) {
      throw new PropertyNotFoundError(propertyId);
    }
    if (property.status !== 'ACTIVE') {
      throw new PropertyNotAvailableForNewWorkError(propertyId);
    }
    return property;
  }

  /**
   * Applies one archive or restore transition, or recognises it as already applied.
   *
   * Both directions share one implementation because they differ only in the target state and the
   * event they append. Running it in a transaction under the row lock makes the "already in the
   * target state" and "operation already applied" checks authoritative even under concurrent
   * attempts (`BR-086`).
   */
  private async applyLifecycleTransition(
    scope: OrganizationScope & { membershipId: string },
    customerId: string,
    propertyId: string,
    action: 'ARCHIVED' | 'RESTORED',
    input: PropertyLifecycleDto,
    now: Date,
  ): Promise<PropertyDetail> {
    await this.db.transaction(async (tx) => {
      const property = await this.requirePropertyForCustomer(
        tx,
        scope,
        customerId,
        propertyId,
        true,
      );

      // A replayed offline operation must not apply twice (`BR-031`). The unique
      // `(organization_id, client_operation_id)` index is the backstop; this check makes the replay
      // deterministic instead of relying on a constraint violation.
      if (input.clientOperationId != null) {
        const [applied] = await tx
          .select({ id: propertyLifecycleHistory.id })
          .from(propertyLifecycleHistory)
          .where(
            and(
              eq(propertyLifecycleHistory.organizationId, scope.organizationId),
              eq(
                propertyLifecycleHistory.clientOperationId,
                input.clientOperationId,
              ),
            ),
          )
          .limit(1);
        if (applied !== undefined) {
          return;
        }
      }

      this.assertVersion(property, input.expectedVersion);

      // Idempotent in state: archiving an archived Property (or restoring an active one) is a no-op
      // that records no second lifecycle event and leaves the version untouched.
      if (property.status === (action === 'ARCHIVED' ? 'ARCHIVED' : 'ACTIVE')) {
        return;
      }

      await tx
        .update(properties)
        .set(
          action === 'ARCHIVED'
            ? {
                status: 'ARCHIVED',
                archivedAt: now,
                archivedByMembershipId: scope.membershipId,
                version: sql`${properties.version} + 1`,
              }
            : {
                status: 'ACTIVE',
                archivedAt: null,
                archivedByMembershipId: null,
                version: sql`${properties.version} + 1`,
              },
        )
        .where(
          and(
            eq(properties.organizationId, scope.organizationId),
            eq(properties.id, propertyId),
          ),
        );

      await tx.insert(propertyLifecycleHistory).values({
        organizationId: scope.organizationId,
        propertyId,
        action,
        actorMembershipId: scope.membershipId,
        note: input.note ?? null,
        recordedAt: now,
        capturedAt:
          input.capturedAt === null || input.capturedAt === undefined
            ? null
            : new Date(input.capturedAt),
        clientOperationId: input.clientOperationId ?? null,
      });
    });

    return this.requireDetail(this.db, scope, propertyId);
  }

  /**
   * Resolves a Property that the addressed Customer owns, or `null`.
   *
   * The Property is always resolved by `(organizationId, id)`. When it has Customer relationships,
   * none of them may belong to a different Customer than the request addresses; a Property with no
   * relationship at all is still reachable, because `BR-082` keeps permanent deletion open for it.
   */
  private async resolvePropertyForCustomer(
    executor: Executor,
    scope: OrganizationScope,
    customerId: string,
    propertyId: string,
    lock = false,
  ): Promise<Property | null> {
    const query = executor
      .select()
      .from(properties)
      .where(
        and(
          eq(properties.organizationId, scope.organizationId),
          eq(properties.id, propertyId),
        ),
      )
      .limit(1);
    // Locking the Property row makes the concurrency-sensitive checks authoritative: a reference
    // insert that needs the row's foreign-key share lock waits, so it cannot slip between the
    // reference check and the delete (`BR-086`, `docs/domain/job-visit-domain-model.md` §15).
    const rows = lock ? await query.for('update') : await query;
    const property = rows[0];
    if (property === undefined) {
      return null;
    }

    const relationships = await executor
      .select({ customerId: propertyCustomerRelationships.customerId })
      .from(propertyCustomerRelationships)
      .where(
        and(
          eq(
            propertyCustomerRelationships.organizationId,
            scope.organizationId,
          ),
          eq(propertyCustomerRelationships.propertyId, propertyId),
        ),
      );
    if (relationships.length > 0) {
      return relationships.some((row) => row.customerId === customerId)
        ? property
        : null;
    }

    // A Property with no relationship belongs to no customer's context, so the request must still
    // name one of the organization's own customers.
    const [customer] = await executor
      .select({ id: customers.id })
      .from(customers)
      .where(
        and(
          eq(customers.organizationId, scope.organizationId),
          eq(customers.id, customerId),
        ),
      )
      .limit(1);
    return customer === undefined ? null : property;
  }

  /** Resolves a Property inside the Customer context or fails closed as "not found". */
  private async requirePropertyForCustomer(
    executor: Executor,
    scope: OrganizationScope,
    customerId: string,
    propertyId: string,
    lock = false,
  ): Promise<Property> {
    const property = await this.resolvePropertyForCustomer(
      executor,
      scope,
      customerId,
      propertyId,
      lock,
    );
    if (property === null) {
      throw new PropertyNotFoundError(propertyId);
    }
    return property;
  }

  /**
   * Derives a Property's row values from the authoritative tables (`BR-080`, `BR-081`, `BR-083`).
   *
   * `jobCount` counts every Job associated with the Property whatever its status; `lastServiceAt` is
   * the most recent `COMPLETED` Visit's scheduled start. The two open-work counts use the
   * `archiveWarningOpenWork` classification and are scoped to the Property's Jobs, so an archived
   * Property's existing work is reported without modifying it.
   */
  private async readPropertyDetail(
    executor: Executor,
    scope: OrganizationScope,
    propertyId: string,
  ): Promise<PropertyDetail | null> {
    const openJobs = statusList(ARCHIVE_WARNING_OPEN_JOB_STATUSES);
    const terminalVisits = statusList(ARCHIVE_WARNING_TERMINAL_VISIT_STATUSES);
    const [row] = await executor
      .select({
        property: getTableColumns(properties),
        jobCount: sql<number>`count(distinct ${jobs.id})`.mapWith(Number),
        lastServiceAt: sql<
          Date | string | null
        >`max(case when ${visits.status} = 'COMPLETED' then ${visits.scheduledStart} end)`,
        activeJobCount:
          sql<number>`count(distinct case when ${jobs.status} in ${openJobs} then ${jobs.id} end)`.mapWith(
            Number,
          ),
        activeVisitCount:
          sql<number>`count(distinct case when ${visits.status} not in ${terminalVisits} then ${visits.id} end)`.mapWith(
            Number,
          ),
      })
      .from(properties)
      .leftJoin(
        jobs,
        and(
          eq(jobs.organizationId, scope.organizationId),
          eq(jobs.propertyId, properties.id),
        ),
      )
      .leftJoin(
        visits,
        and(
          eq(visits.organizationId, scope.organizationId),
          eq(visits.jobId, jobs.id),
        ),
      )
      .where(
        and(
          eq(properties.organizationId, scope.organizationId),
          eq(properties.id, propertyId),
        ),
      )
      .groupBy(properties.id);

    if (row === undefined) {
      return null;
    }

    const referenceKinds = await this.findReferenceKinds(
      executor,
      scope,
      propertyId,
    );
    return {
      property: row.property,
      jobCount: row.jobCount,
      lastServiceAt: asDate(row.lastServiceAt),
      activeJobCount: row.activeJobCount,
      activeVisitCount: row.activeVisitCount,
      canBePermanentlyDeleted: referenceKinds.length === 0,
    };
  }

  /** Reads the detail or fails, which cannot happen after a successful in-transaction mutation. */
  private async requireDetail(
    executor: Executor,
    scope: OrganizationScope,
    propertyId: string,
  ): Promise<PropertyDetail> {
    const detail = await this.readPropertyDetail(executor, scope, propertyId);
    if (detail === null) {
      throw new PropertyNotFoundError(propertyId);
    }
    return detail;
  }

  /** Rejects a mutation that names a version the Property has already moved past (`BR-086`). */
  private assertVersion(
    property: Property,
    expectedVersion: number | undefined,
  ): void {
    if (expectedVersion !== undefined && expectedVersion !== property.version) {
      throw new PropertyVersionConflictError(property.id, property.version);
    }
  }

  /**
   * The business reference families that block a Property's permanent deletion (`BR-082`).
   *
   * The Property's **own** lifecycle history is deliberately absent: it follows the Property and is
   * removed with it, so archiving and restoring an otherwise unused Property does not trap it.
   * Every other reference family fails the delete closed rather than cascading.
   */
  private async findReferenceKinds(
    executor: Executor,
    scope: OrganizationScope,
    propertyId: string,
  ): Promise<string[]> {
    const organizationId = scope.organizationId;
    const [job, visit, relationship, jobProperty, visitLocation] =
      await Promise.all([
        executor
          .select({ id: jobs.id })
          .from(jobs)
          .where(
            and(
              eq(jobs.organizationId, organizationId),
              eq(jobs.propertyId, propertyId),
            ),
          )
          .limit(1),
        executor
          .select({ id: visits.id })
          .from(visits)
          .where(
            and(
              eq(visits.organizationId, organizationId),
              eq(visits.propertyId, propertyId),
            ),
          )
          .limit(1),
        executor
          .select({ id: propertyCustomerRelationships.id })
          .from(propertyCustomerRelationships)
          .where(
            and(
              eq(propertyCustomerRelationships.organizationId, organizationId),
              eq(propertyCustomerRelationships.propertyId, propertyId),
            ),
          )
          .limit(1),
        executor
          .select({ id: jobPropertyHistory.id })
          .from(jobPropertyHistory)
          .where(
            and(
              eq(jobPropertyHistory.organizationId, organizationId),
              sql`(${jobPropertyHistory.previousPropertyId} = ${propertyId} or ${jobPropertyHistory.newPropertyId} = ${propertyId})`,
            ),
          )
          .limit(1),
        executor
          .select({ id: visitLocationHistory.id })
          .from(visitLocationHistory)
          .where(
            and(
              eq(visitLocationHistory.organizationId, organizationId),
              sql`(${visitLocationHistory.previousPropertyId} = ${propertyId} or ${visitLocationHistory.newPropertyId} = ${propertyId})`,
            ),
          )
          .limit(1),
      ]);

    const kinds: string[] = [];
    if (job.length > 0) {
      kinds.push('jobs');
    }
    if (visit.length > 0) {
      kinds.push('visits');
    }
    if (relationship.length > 0) {
      kinds.push('customerRelationships');
    }
    if (jobProperty.length > 0) {
      kinds.push('jobPropertyHistory');
    }
    if (visitLocation.length > 0) {
      kinds.push('visitLocationHistory');
    }
    return kinds;
  }
}

/**
 * Renders a closed status vocabulary as a SQL `in` list built from module constants, never from
 * request input (`dev.md` §11).
 */
function statusList(statuses: readonly string[]): SQL {
  return sql`(${sql.join(
    statuses.map((status) => sql`${status}`),
    sql`, `,
  )})`;
}

/** Whether a driver error is PostgreSQL's foreign-key violation (`23503`). */
function isForeignKeyViolation(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) {
    return false;
  }
  const direct = (error as { code?: unknown }).code;
  if (direct === '23503') {
    return true;
  }
  const cause = (error as { cause?: unknown }).cause;
  return (
    typeof cause === 'object' &&
    cause !== null &&
    (cause as { code?: unknown }).code === '23503'
  );
}
