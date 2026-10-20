import { and, asc, eq, sql } from 'drizzle-orm';
import type { DatabaseService } from '../database/database.service.js';
import { pushInstallations, pushDeliveries } from '../database/schema.js';

type Database = DatabaseService['db'];

/** Bounded, tenant-scoped cleanup. Installation-first lock order matches registration/completion. */
export async function maintainPushState(
  db: Database,
  organizationId: string,
  batchSize = 100,
): Promise<void> {
  if (!Number.isInteger(batchSize) || batchSize < 1 || batchSize > 100)
    throw new Error('Push maintenance batch size must be between 1 and 100.');
  await db.transaction(async (tx) => {
    const stale = await tx
      .select()
      .from(pushInstallations)
      .where(
        and(
          eq(pushInstallations.organizationId, organizationId),
          sql`${pushInstallations.registeredAt} <= now() - interval '30 days'`,
          sql`${pushInstallations.targetValue} is not null`,
        ),
      )
      .orderBy(asc(pushInstallations.registeredAt), asc(pushInstallations.id))
      .limit(batchSize)
      .for('update', { skipLocked: true });
    for (const installation of stale) {
      await tx
        .update(pushInstallations)
        .set({
          state: 'INVALID',
          targetValue: null,
          targetDigest: null,
          eligibleSince: null,
          revision: installation.revision + 1,
          updatedAt: sql`now()`,
        })
        .where(
          and(
            eq(pushInstallations.id, installation.id),
            eq(pushInstallations.organizationId, organizationId),
          ),
        );
      await tx
        .update(pushDeliveries)
        .set({
          state: 'SUPPRESSED',
          terminalAt: sql`now()`,
          updatedAt: sql`now()`,
          claimId: null,
          leaseExpiresAt: null,
          registrationRevision: null,
        })
        .where(
          and(
            eq(pushDeliveries.organizationId, organizationId),
            eq(pushDeliveries.installationId, installation.id),
            eq(pushDeliveries.bindingId, installation.bindingId),
            sql`${pushDeliveries.state} in ('PENDING', 'CLAIMED')`,
          ),
        );
    }
    await tx.execute(sql`delete from push_deliveries where organization_id = ${organizationId} and id in (
      select id from push_deliveries where organization_id = ${organizationId}
      and terminal_at <= now() - interval '30 days'
      and state in ('ACCEPTED', 'SUPPRESSED', 'EXPIRED', 'FAILED')
      order by terminal_at, id limit ${batchSize} for update skip locked
    )`);
    // An event survives until every delivery has been retained and removed. Empty snapshots age too.
    await tx.execute(sql`delete from push_assignment_events where organization_id = ${organizationId} and id in (
      select e.id from push_assignment_events e where e.organization_id = ${organizationId}
      and e.expanded_at is not null and e.expires_at <= now() - interval '30 days'
      and not exists (select 1 from push_deliveries d where d.event_id = e.id and d.organization_id = e.organization_id)
      order by e.expires_at, e.id limit ${batchSize} for update skip locked
    )`);
  });
}
