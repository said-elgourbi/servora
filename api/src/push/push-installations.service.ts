import { HttpException, Injectable } from '@nestjs/common';
import { createHash, randomUUID, timingSafeEqual } from 'node:crypto';
import { and, asc, eq, gt, isNull, sql } from 'drizzle-orm';
import { AuthApiError } from '../auth/auth-error.js';
import type { RequestAuth } from '../auth/auth.guard.js';
import { DatabaseService } from '../database/database.service.js';
import {
  authSessions,
  organizationMembers,
  organizationRoles,
  organizations,
  pushInstallations,
  pushDeliveries,
  users,
} from '../database/schema.js';
import { loadPushRegistrationConfig } from './push-registration-config.js';
import type {
  PushRegistrationRequest,
  PushInstallationDto,
} from './push-registration.dto.js';

type Transaction = Parameters<
  Parameters<DatabaseService['db']['transaction']>[0]
>[0];
type Installation = typeof pushInstallations.$inferSelect;
interface Scope {
  organizationId: string;
  membershipId: string;
}

@Injectable()
export class PushInstallationsService {
  private readonly config = loadPushRegistrationConfig();
  constructor(private readonly database: DatabaseService) {}

  async register(
    auth: RequestAuth,
    id: string,
    secret: string,
    input: PushRegistrationRequest,
  ): Promise<PushInstallationDto> {
    const { projectId, applicationId } = this.config;
    if (!projectId || !applicationId)
      throw apiError(
        503,
        'PUSH_REGISTRATION_UNAVAILABLE',
        'Push registration is not configured.',
      );
    if (input.applicationId !== applicationId)
      throw AuthApiError.validationFailed(
        'The application ID is not supported.',
      );
    return this.transaction(auth, id, async (tx, scope) => {
      // Advisory locking also serializes two first registrations, before a row exists.
      const [existing] = await tx
        .select()
        .from(pushInstallations)
        .where(eq(pushInstallations.id, id))
        .for('update');
      if (existing && !proofMatches(existing, secret)) throw notFound();
      if ((existing?.revision ?? 0) !== input.expectedRevision)
        throw conflict();
      if (existing && existing.sessionId !== auth.sessionId) {
        // A late request from an older login cannot regain a newer binding even with proof.
        const [newer] = await tx
          .select({ id: authSessions.id })
          .from(authSessions)
          .where(
            and(
              eq(authSessions.id, auth.sessionId),
              sql`${authSessions.createdAt} > (select created_at from auth_sessions where id = ${existing.sessionId})`,
            ),
          );
        if (!newer) throw conflict();
      }
      const eligible =
        input.alertsEnabled &&
        input.notificationPermission === 'GRANTED' &&
        input.channelEnabled;
      const sameBinding =
        existing?.sessionId === auth.sessionId &&
        existing.organizationId === scope.organizationId;
      const values = {
        ...scope,
        userId: auth.userId,
        sessionId: auth.sessionId,
        bindingId:
          existing?.sessionId === auth.sessionId &&
          existing.organizationId === scope.organizationId
            ? existing.bindingId
            : randomUUID(),
        revision: input.expectedRevision + 1,
        projectId,
        applicationId,
        targetKind: input.registration.kind,
        targetValue: input.registration.value,
        targetDigest: digest(input.registration.value),
        locale: input.locale,
        alertsEnabled: input.alertsEnabled,
        notificationPermission: input.notificationPermission,
        channelEnabled: input.channelEnabled,
        state: eligible ? 'ACTIVE' : 'DISABLED',
        eligibleSince: !eligible
          ? null
          : sameBinding && existing.state === 'ACTIVE'
            ? sql`case when ${pushInstallations.registeredAt} > now() - interval '30 days' then coalesce(${pushInstallations.eligibleSince}, now()) else now() end`
            : sql`now()`,
        registeredAt: sql`now()`,
      };
      const [row] = existing
        ? await tx
            .update(pushInstallations)
            .set(values)
            .where(eq(pushInstallations.id, id))
            .returning()
        : await tx
            .insert(pushInstallations)
            .values({ id, secretDigest: digest(secret), ...values })
            .returning();
      if (existing && (!eligible || !sameBinding)) {
        await suppressInstallationDeliveries(tx, existing);
      }
      return this.toDto(row);
    });
  }

  get(
    auth: RequestAuth,
    id: string,
    secret: string,
  ): Promise<PushInstallationDto> {
    return this.transaction(auth, id, async (tx, scope) =>
      this.toDto(await owned(tx, auth, scope, id, secret)),
    );
  }

  unregister(auth: RequestAuth, id: string, secret: string): Promise<void> {
    return this.transaction(auth, id, async (tx, scope) => {
      const row = await owned(tx, auth, scope, id, secret);
      if (row.state === 'UNREGISTERED') return;
      await suppressInstallationDeliveries(tx, row);
      await tx
        .update(pushInstallations)
        .set({
          state: 'UNREGISTERED',
          targetValue: null,
          targetDigest: null,
          alertsEnabled: false,
          eligibleSince: null,
          revision: row.revision + 1,
        })
        .where(
          and(
            eq(pushInstallations.id, id),
            eq(pushInstallations.organizationId, scope.organizationId),
            eq(pushInstallations.sessionId, auth.sessionId),
          ),
        );
    });
  }

  private async transaction<T>(
    auth: RequestAuth,
    id: string,
    action: (tx: Transaction, scope: Scope) => Promise<T>,
  ): Promise<T> {
    try {
      return await this.database.db.transaction(async (tx) => {
        // Same lock order as revocation: session first, then installation. Recheck after AuthGuard.
        const [session] = await tx
          .select()
          .from(authSessions)
          .where(
            and(
              eq(authSessions.id, auth.sessionId),
              eq(authSessions.userId, auth.userId),
              isNull(authSessions.revokedAt),
              gt(authSessions.expiresAt, sql`now()`),
            ),
          )
          .for('update');
        if (!session) throw AuthApiError.unauthenticated();
        if (session.platform !== 'ANDROID') throw AuthApiError.forbidden();
        const [scope] = await tx
          .select({
            organizationId: organizationMembers.organizationId,
            membershipId: organizationMembers.id,
          })
          .from(organizationMembers)
          .innerJoin(
            organizations,
            eq(organizations.id, organizationMembers.organizationId),
          )
          .innerJoin(users, eq(users.id, organizationMembers.userId))
          .innerJoin(
            organizationRoles,
            and(
              eq(organizationRoles.id, organizationMembers.roleId),
              eq(
                organizationRoles.organizationId,
                organizationMembers.organizationId,
              ),
            ),
          )
          .where(
            and(
              eq(organizationMembers.userId, auth.userId),
              eq(organizationMembers.status, 'ACTIVE'),
              eq(organizations.status, 'ACTIVE'),
              eq(users.status, 'ACTIVE'),
              eq(organizationRoles.status, 'ACTIVE'),
            ),
          )
          .orderBy(
            asc(organizationMembers.joinedAt),
            asc(organizationMembers.id),
          )
          .limit(1)
          .for('share');
        if (!scope) throw AuthApiError.forbidden();
        await tx.execute(
          sql`select pg_advisory_xact_lock(hashtextextended(${id}, 0))`,
        );
        return action(tx, scope);
      });
    } catch (error) {
      if (error instanceof HttpException) throw error;
      if (postgresCode(error) === '23505') throw conflict();
      // Drizzle errors contain SQL parameters (including provider targets). Never propagate them.
      throw apiError(
        500,
        'PUSH_REGISTRATION_FAILED',
        'Push registration could not be saved.',
      );
    }
  }

  private toDto(row: Installation): PushInstallationDto {
    return {
      installationId: row.id,
      organizationId: row.organizationId,
      bindingId: row.bindingId,
      revision: row.revision,
      alertsEnabled: row.alertsEnabled,
      deliveryEnabled:
        row.state === 'ACTIVE' &&
        row.alertsEnabled &&
        row.notificationPermission === 'GRANTED' &&
        row.channelEnabled &&
        this.config.transportEnabled &&
        row.projectId === this.config.projectId &&
        row.applicationId === this.config.applicationId,
      registeredAt: row.registeredAt.toISOString(),
    };
  }
}

async function owned(
  tx: Transaction,
  auth: RequestAuth,
  scope: Scope,
  id: string,
  secret: string,
) {
  const [row] = await tx
    .select()
    .from(pushInstallations)
    .where(
      and(
        eq(pushInstallations.id, id),
        eq(pushInstallations.organizationId, scope.organizationId),
        eq(pushInstallations.membershipId, scope.membershipId),
        eq(pushInstallations.userId, auth.userId),
        eq(pushInstallations.sessionId, auth.sessionId),
      ),
    )
    .for('update');
  if (!row || !proofMatches(row, secret)) throw notFound();
  return row;
}
function digest(value: string) {
  return createHash('sha256').update(value).digest('hex');
}
function proofMatches(row: Installation, secret: string) {
  return timingSafeEqual(
    Buffer.from(row.secretDigest, 'hex'),
    Buffer.from(digest(secret), 'hex'),
  );
}
function apiError(status: number, code: string, message: string) {
  return new HttpException({ statusCode: status, code, message }, status);
}
function conflict() {
  return apiError(
    409,
    'PUSH_REGISTRATION_CONFLICT',
    'Push registration conflicts with current state.',
  );
}
function notFound() {
  return apiError(
    404,
    'PUSH_INSTALLATION_NOT_FOUND',
    'Push installation was not found.',
  );
}
function postgresCode(error: unknown): string | undefined {
  if (!error || typeof error !== 'object') return undefined;
  if ('code' in error && typeof error.code === 'string') return error.code;
  return 'cause' in error ? postgresCode(error.cause) : undefined;
}

/** Keep old binding snapshots terminal even if the installation later becomes eligible again. */
async function suppressInstallationDeliveries(
  tx: Transaction,
  row: Installation,
): Promise<void> {
  await tx
    .update(pushDeliveries)
    .set({
      state: 'SUPPRESSED',
      terminalAt: sql`now()`,
      claimId: null,
      leaseExpiresAt: null,
      registrationRevision: null,
    })
    .where(
      and(
        eq(pushDeliveries.organizationId, row.organizationId),
        eq(pushDeliveries.installationId, row.id),
        eq(pushDeliveries.bindingId, row.bindingId),
        sql`${pushDeliveries.state} in ('PENDING', 'CLAIMED')`,
      ),
    );
}
