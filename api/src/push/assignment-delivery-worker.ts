import { randomUUID } from 'node:crypto';
import { Logger } from '@nestjs/common';
import { and, asc, eq, or, sql } from 'drizzle-orm';
import type { DatabaseService } from '../database/database.service.js';
import {
  pushAssignmentEvents,
  pushDeliveries,
  pushInstallations,
} from '../database/schema.js';
import { assignmentInstallationEligibility } from './assignment-installation-eligibility.js';
import { expandAssignmentEvents } from './expand-assignment-events.js';
import {
  PushDeliveryError,
  type PushProvider,
  type PushFailureKind,
} from './push-provider.js';
import type { PushRegistrationConfig } from './push-registration-config.js';

type Database = DatabaseService['db'];
type Delivery = typeof pushDeliveries.$inferSelect;
type Scope = { organizationId: string };
const logger = new Logger('AssignmentDeliveryWorker');
const clearClaim = {
  claimId: null,
  leaseExpiresAt: null,
  registrationRevision: null,
};

export function assignmentRetryDelayMs(
  attempt: number,
  jitter = Math.random(),
  retryAfterMs = 0,
): number {
  if (
    !Number.isInteger(attempt) ||
    attempt < 1 ||
    attempt > 7 ||
    !Number.isFinite(jitter) ||
    jitter < 0 ||
    jitter > 1
  )
    throw new Error('Invalid push retry parameters.');
  const base = [1, 2, 4, 8, 16, 32, 60][attempt - 1] * 60_000;
  return Math.ceil(
    Math.max(
      base * (1 + jitter * 0.2),
      Number.isFinite(retryAfterMs) ? retryAfterMs : 0,
    ),
  );
}

function ownedClaim(claim: Delivery) {
  return and(
    eq(pushDeliveries.organizationId, claim.organizationId),
    eq(pushDeliveries.id, claim.id),
    eq(pushDeliveries.bindingId, claim.bindingId),
    eq(pushDeliveries.state, 'CLAIMED'),
    eq(pushDeliveries.claimId, claim.claimId!),
    eq(pushDeliveries.registrationRevision, claim.registrationRevision!),
    sql`${pushDeliveries.leaseExpiresAt} > clock_timestamp()`,
  );
}

/** Bounded claim transaction; targets never escape in claim records. Abandoned attempts count. */
export async function claimAssignmentDeliveries(
  db: Database,
  scope: Scope,
  batchSize = 25,
): Promise<Delivery[]> {
  if (!Number.isInteger(batchSize) || batchSize < 1 || batchSize > 100)
    throw new Error('Push claim batch size must be between 1 and 100.');
  return db.transaction(async (tx) => {
    const due = await tx
      .select({
        delivery: pushDeliveries,
        revision: pushInstallations.revision,
      })
      .from(pushDeliveries)
      .leftJoin(
        pushInstallations,
        and(
          eq(pushInstallations.id, pushDeliveries.installationId),
          eq(pushInstallations.organizationId, pushDeliveries.organizationId),
          eq(pushInstallations.bindingId, pushDeliveries.bindingId),
        ),
      )
      .where(
        and(
          eq(pushDeliveries.organizationId, scope.organizationId),
          or(
            and(
              eq(pushDeliveries.state, 'PENDING'),
              sql`${pushDeliveries.nextAttemptAt} <= now()`,
            ),
            and(
              eq(pushDeliveries.state, 'CLAIMED'),
              sql`${pushDeliveries.leaseExpiresAt} <= now()`,
            ),
          ),
        ),
      )
      .orderBy(asc(pushDeliveries.nextAttemptAt), asc(pushDeliveries.id))
      .limit(batchSize)
      .for('update', { of: pushDeliveries, skipLocked: true });
    const claims: Delivery[] = [];
    for (const { delivery, revision } of due) {
      if (!revision || delivery.attempts >= 8) {
        await tx
          .update(pushDeliveries)
          .set({
            ...clearClaim,
            state: revision ? 'FAILED' : 'SUPPRESSED',
            terminalAt: sql`now()`,
            updatedAt: sql`now()`,
            lastFailure: revision ? 'retryable' : null,
          })
          .where(
            and(
              eq(pushDeliveries.organizationId, scope.organizationId),
              eq(pushDeliveries.id, delivery.id),
            ),
          );
        continue;
      }
      const [claim] = await tx
        .update(pushDeliveries)
        .set({
          state: 'CLAIMED',
          attempts: delivery.attempts + 1,
          claimId: randomUUID(),
          leaseExpiresAt: sql`clock_timestamp() + interval '120 seconds'`,
          registrationRevision: revision,
          updatedAt: sql`now()`,
        })
        .where(
          and(
            eq(pushDeliveries.organizationId, scope.organizationId),
            eq(pushDeliveries.id, delivery.id),
          ),
        )
        .returning();
      claims.push(claim);
    }
    return claims;
  });
}

/** No network transaction: reread scope, access, binding, revision and TTL immediately before I/O. */
export async function sendAssignmentClaim(
  db: Database,
  config: PushRegistrationConfig,
  provider: PushProvider,
  claim: Delivery,
): Promise<void> {
  // Callers cannot use a pending/terminal row as send authority.
  if (
    claim.state !== 'CLAIMED' ||
    !claim.claimId ||
    !claim.registrationRevision
  )
    return;
  const [event] = await db
    .select()
    .from(pushAssignmentEvents)
    .where(
      and(
        eq(pushAssignmentEvents.organizationId, claim.organizationId),
        eq(pushAssignmentEvents.id, claim.eventId),
      ),
    );
  if (!event) return;
  const preflightStarted = performance.now();
  const [current] = await db
    .select({
      token: pushInstallations.targetValue,
      revision: pushInstallations.revision,
      eligible: sql<boolean>`coalesce(${assignmentInstallationEligibility(event, config)}, false)`,
      leaseMs: sql<number>`floor(extract(epoch from (${pushDeliveries.leaseExpiresAt} - clock_timestamp())) * 1000)::double precision`,
      ttlMs: sql<number>`floor(extract(epoch from (${pushAssignmentEvents.expiresAt} - clock_timestamp())) * 1000)::double precision`,
    })
    .from(pushDeliveries)
    .innerJoin(
      pushInstallations,
      and(
        eq(pushInstallations.id, pushDeliveries.installationId),
        eq(pushInstallations.organizationId, pushDeliveries.organizationId),
        eq(pushInstallations.bindingId, pushDeliveries.bindingId),
      ),
    )
    .innerJoin(
      pushAssignmentEvents,
      and(
        eq(pushAssignmentEvents.organizationId, pushDeliveries.organizationId),
        eq(pushAssignmentEvents.id, pushDeliveries.eventId),
      ),
    )
    .where(ownedClaim(claim));
  if (!current) {
    // A rebound installation is outside this recipient's scope. Never load its new target.
    await db
      .update(pushDeliveries)
      .set({
        ...clearClaim,
        state: 'SUPPRESSED',
        terminalAt: sql`now()`,
        updatedAt: sql`now()`,
      })
      .where(ownedClaim(claim));
    return;
  }
  let state: string | undefined;
  if (current.ttlMs <= 0) state = 'EXPIRED';
  else if (!config.transportEnabled || !current.eligible || !current.token)
    state = 'SUPPRESSED';
  if (!state && current.revision !== claim.registrationRevision)
    state = claim.attempts < 8 ? 'PENDING' : 'FAILED';
  if (state) {
    await db
      .update(pushDeliveries)
      .set({
        ...clearClaim,
        state,
        terminalAt: state === 'PENDING' ? null : sql`now()`,
        updatedAt: sql`now()`,
        lastFailure: !config.transportEnabled ? 'disabled' : null,
      })
      .where(ownedClaim(claim));
    return;
  }
  const ttlMs = Math.floor(
    current.ttlMs - (performance.now() - preflightStarted),
  );
  if (ttlMs <= 0) {
    await db
      .update(pushDeliveries)
      .set({
        ...clearClaim,
        state: 'EXPIRED',
        terminalAt: sql`now()`,
        updatedAt: sql`now()`,
      })
      .where(ownedClaim(claim));
    return;
  }
  // Leave too-short leases for recovery rather than starting I/O that can outlive ownership.
  if (current.leaseMs - (performance.now() - preflightStarted) <= 30_000)
    return;
  let outcome: 'accepted' | 'disabled' | PushFailureKind;
  let retryAfterMs = 0;
  try {
    const result = await withProviderDeadline(() =>
      provider.send({
        registrationToken: current.token!,
        eventId: event.id,
        ttlMs,
        data: {
          version: '1',
          eventType: 'VISIT_ASSIGNED',
          eventId: event.id,
          bindingId: claim.bindingId,
          organizationId: event.organizationId,
          jobId: event.jobId,
          visitId: event.visitId,
          expiresAt: event.expiresAt.toISOString(),
        },
      }),
    );
    outcome = result.status;
  } catch (error: unknown) {
    outcome = error instanceof PushDeliveryError ? error.kind : 'retryable';
    if (error instanceof PushDeliveryError)
      retryAfterMs = error.retryAfterMs ?? 0;
    // Provider text can contain tokens/payloads. Log only a controlled classification.
    logger.warn({ deliveryId: claim.id, failure: outcome });
  }
  await db.transaction(async (tx) => {
    // Registration locks installation before deliveries. Keep that order, with no I/O under locks.
    const [installation] = await tx
      .select()
      .from(pushInstallations)
      .where(
        and(
          eq(pushInstallations.id, claim.installationId),
          eq(pushInstallations.organizationId, claim.organizationId),
        ),
      )
      .for('update');
    if (
      !installation ||
      installation.bindingId !== claim.bindingId ||
      installation.revision !== claim.registrationRevision
    )
      return;
    const [owned] = await tx
      .select()
      .from(pushDeliveries)
      .where(ownedClaim(claim))
      .for('update');
    if (!owned) return;
    if (outcome === 'invalid-registration') {
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
            eq(pushInstallations.organizationId, claim.organizationId),
            eq(pushInstallations.bindingId, claim.bindingId),
            eq(pushInstallations.revision, claim.registrationRevision!),
          ),
        );
      // Preserve this delivery's failure; other outstanding work for the invalidated binding is canceled.
      await tx
        .update(pushDeliveries)
        .set({
          ...clearClaim,
          state: 'SUPPRESSED',
          terminalAt: sql`now()`,
          updatedAt: sql`now()`,
        })
        .where(
          and(
            eq(pushDeliveries.organizationId, claim.organizationId),
            eq(pushDeliveries.installationId, claim.installationId),
            eq(pushDeliveries.bindingId, claim.bindingId),
            sql`${pushDeliveries.id} <> ${claim.id}`,
            sql`${pushDeliveries.state} in ('PENDING', 'CLAIMED')`,
          ),
        );
    }
    const retry = outcome === 'retryable' && claim.attempts < 8;
    const delay = retry
      ? assignmentRetryDelayMs(claim.attempts, Math.random(), retryAfterMs)
      : 0;
    const [{ expired }] = await tx
      .select({
        expired: sql<boolean>`${pushAssignmentEvents.expiresAt} <= clock_timestamp()`,
      })
      .from(pushAssignmentEvents)
      .where(
        and(
          eq(pushAssignmentEvents.organizationId, claim.organizationId),
          eq(pushAssignmentEvents.id, claim.eventId),
        ),
      );
    const finalState =
      outcome === 'accepted'
        ? 'ACCEPTED'
        : outcome === 'disabled'
          ? 'SUPPRESSED'
          : expired
            ? 'EXPIRED'
            : retry
              ? 'PENDING'
              : 'FAILED';
    await tx
      .update(pushDeliveries)
      .set({
        ...clearClaim,
        state: finalState,
        terminalAt: finalState === 'PENDING' ? null : sql`now()`,
        updatedAt: sql`now()`,
        nextAttemptAt: retry
          ? sql`least(clock_timestamp() + ${delay} * interval '1 millisecond', ${event.expiresAt.toISOString()}::timestamptz)`
          : owned.nextAttemptAt,
        lastFailure: outcome === 'accepted' ? null : outcome,
      })
      .where(ownedClaim(claim));
  });
}

/** The timer bounds worker ownership, not the underlying SDK request; a late acceptance is possible. */
export async function withProviderDeadline<T>(
  send: () => Promise<T>,
): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      Promise.resolve().then(send),
      new Promise<never>((_, reject) => {
        timer = setTimeout(
          () => reject(new PushDeliveryError('retryable')),
          30_000,
        );
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}

/** One explicitly scoped iteration. No scheduler/startup hook; does not change transport configuration. */
export async function runAssignmentDeliveryBatch(
  db: Database,
  config: PushRegistrationConfig,
  provider: PushProvider,
  scope: Scope,
  batchSize = 25,
): Promise<void> {
  await expandAssignmentEvents(db, config, scope, batchSize);
  const claims = await claimAssignmentDeliveries(db, scope, batchSize);
  // Start the bounded batch together so queued sends do not outlive their leases.
  await Promise.all(
    claims.map((claim) => sendAssignmentClaim(db, config, provider, claim)),
  );
}
