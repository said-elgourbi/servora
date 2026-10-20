import { and, asc, eq, isNull, sql } from 'drizzle-orm';
import type { DatabaseService } from '../database/database.service.js';
import {
  pushAssignmentEvents,
  pushDeliveries,
  pushInstallations,
} from '../database/schema.js';
import type { PushRegistrationConfig } from './push-registration-config.js';
import { assignmentInstallationEligibility } from './assignment-installation-eligibility.js';

type Database = DatabaseService['db'];

/** One bounded, atomic expansion step for the scoped worker. No scheduler or provider I/O. */
export async function expandAssignmentEvents(
  db: Database,
  config: PushRegistrationConfig,
  scope: { organizationId: string },
  batchSize = 25,
): Promise<number> {
  if (!Number.isInteger(batchSize) || batchSize < 1 || batchSize > 100)
    throw new Error('Push expansion batch size must be between 1 and 100.');
  return db.transaction(async (tx) => {
    const events = await tx
      .select()
      .from(pushAssignmentEvents)
      .where(
        and(
          eq(pushAssignmentEvents.organizationId, scope.organizationId),
          isNull(pushAssignmentEvents.expandedAt),
        ),
      )
      .orderBy(
        asc(pushAssignmentEvents.occurredAt),
        asc(pushAssignmentEvents.id),
      )
      .limit(batchSize)
      .for('update', { skipLocked: true });
    for (const event of events) {
      // Evaluate in SQL using the transaction clock. Do not load provider targets into memory.
      // Permission grants match AuthorizationService: role grants union direct member grants.
      const installations = await tx
        .select({
          id: pushInstallations.id,
          bindingId: pushInstallations.bindingId,
        })
        .from(pushInstallations)
        .where(assignmentInstallationEligibility(event, config))
        .for('share');
      if (installations.length) {
        await tx
          .insert(pushDeliveries)
          .values(
            installations.map((installation) => ({
              organizationId: event.organizationId,
              eventId: event.id,
              installationId: installation.id,
              bindingId: installation.bindingId,
              // Disabled transport consumes intent; enabling later must never drain an old backlog.
              state: config.transportEnabled ? 'PENDING' : 'SUPPRESSED',
              terminalAt: config.transportEnabled ? null : sql`now()`,
              lastFailure: config.transportEnabled ? null : 'disabled',
            })),
          )
          .onConflictDoNothing({
            target: [
              pushDeliveries.eventId,
              pushDeliveries.installationId,
              pushDeliveries.bindingId,
            ],
          });
      }
      // Zero-device, expired and no-longer-authorized events are consumed as well.
      await tx
        .update(pushAssignmentEvents)
        .set({ expandedAt: sql`now()` })
        .where(
          and(
            eq(pushAssignmentEvents.organizationId, event.organizationId),
            eq(pushAssignmentEvents.id, event.id),
          ),
        );
    }
    return events.length;
  });
}
