import { Inject, Injectable } from '@nestjs/common';
import { and, eq, gte, lt, max } from 'drizzle-orm';
import { createHmac } from 'node:crypto';
import { DatabaseService } from '../database/database.service.js';
import { authRateLimitEvents } from '../database/schema.js';
import { AUTH_CONFIG } from './auth-config.provider.js';
import type { AuthConfig } from './auth-config.js';
import { AuthApiError } from './auth-error.js';
import type { AuthRateLimitScope } from './auth.types.js';

/**
 * One rolling window and the number of attempts it allows (`BR-045`).
 *
 * The approved limits are two windows (short and long) evaluated together, so a burst and
 * a sustained drip are both bounded.
 */
export interface RateLimitRule {
  readonly windowMs: number;
  readonly maxAttempts: number;
}

export interface RateLimitInput {
  readonly scope: AuthRateLimitScope;
  /** The identity being attempted: an email address, a phone number, … never logged. */
  readonly subject: string;
  readonly limits: readonly RateLimitRule[];
  /** Enforces "no sooner than N ms after the previous attempt" when supplied. */
  readonly minIntervalMs?: number;
  readonly now?: Date;
}

/**
 * Persisted rolling-window rate limiter for authentication attempts (`BR-045`, `ADR-006` D6).
 *
 * Why persistence rather than process memory: a limit that disappears on restart, or that
 * each API instance counts separately, is not the server-enforced limit `BR-045` requires.
 *
 * Why the subject is hashed: the ledger has to count attempts per email address or phone
 * number, but it does not have to remember either. `HMAC-SHA256(JWT_SECRET, scope:subject)`
 * makes two identical subjects countable while leaving no personal data in the table.
 *
 * The count and the insert share a transaction, so the ledger cannot drift; two genuinely
 * concurrent attempts may still both pass the last free slot, which is inherent to a
 * counting limiter and bounds the overshoot by the number of in-flight requests.
 */
@Injectable()
export class AuthRateLimiter {
  constructor(
    private readonly database: DatabaseService,
    @Inject(AUTH_CONFIG) private readonly config: AuthConfig,
  ) {}

  /**
   * Records one attempt, or rejects it with `429` when a limit is already reached.
   *
   * Callers must invoke this **before** performing the operation, so a rejected attempt
   * produces no side effect (`BR-045`). Unknown identities are counted exactly like known
   * ones, which is what stops a `429` from revealing whether an account exists (`BR-044`).
   */
  async consume(input: RateLimitInput): Promise<void> {
    const now = input.now ?? new Date();
    const subjectHash = this.hashSubject(input.scope, input.subject);
    const longestWindowMs = Math.max(
      ...input.limits.map((rule) => rule.windowMs),
      input.minIntervalMs ?? 0,
    );
    const oldestRelevant = new Date(now.getTime() - longestWindowMs);

    await this.database.db.transaction(async (tx) => {
      const events = await tx
        .select({ createdAt: authRateLimitEvents.createdAt })
        .from(authRateLimitEvents)
        .where(
          and(
            eq(authRateLimitEvents.scope, input.scope),
            eq(authRateLimitEvents.subjectHash, subjectHash),
            gte(authRateLimitEvents.createdAt, oldestRelevant),
          ),
        );

      const attemptTimes = events.map((event) => event.createdAt.getTime());

      if (input.minIntervalMs !== undefined && attemptTimes.length > 0) {
        const previousAttempt = Math.max(...attemptTimes);
        if (now.getTime() - previousAttempt < input.minIntervalMs) {
          throw AuthApiError.tooManyRequests();
        }
      }

      for (const rule of input.limits) {
        const attemptsInWindow = attemptTimes.filter(
          (attempt) => now.getTime() - attempt < rule.windowMs,
        ).length;
        if (attemptsInWindow >= rule.maxAttempts) {
          throw AuthApiError.tooManyRequests();
        }
      }

      await tx
        .insert(authRateLimitEvents)
        .values({ scope: input.scope, subjectHash, createdAt: now });

      // Opportunistic housekeeping of this subject's own history: indexed, cheap, and it
      // keeps the ledger bounded without deciding a retention policy (`BR-027` is open).
      await tx
        .delete(authRateLimitEvents)
        .where(
          and(
            eq(authRateLimitEvents.scope, input.scope),
            eq(authRateLimitEvents.subjectHash, subjectHash),
            lt(authRateLimitEvents.createdAt, oldestRelevant),
          ),
        );
    });
  }

  /**
   * When the subject was last attempted, or `null`.
   *
   * Exposed so a caller can report how long a client must wait (the OTP resend cooldown,
   * `BR-019`) without re-deriving the ledger itself.
   */
  async lastAttemptAt(
    scope: AuthRateLimitScope,
    subject: string,
  ): Promise<Date | null> {
    const [row] = await this.database.db
      .select({ lastAttemptAt: max(authRateLimitEvents.createdAt) })
      .from(authRateLimitEvents)
      .where(
        and(
          eq(authRateLimitEvents.scope, scope),
          eq(authRateLimitEvents.subjectHash, this.hashSubject(scope, subject)),
        ),
      );

    return row?.lastAttemptAt === undefined || row.lastAttemptAt === null
      ? null
      : new Date(row.lastAttemptAt);
  }

  /** The approved rate limits for password-reset requests (`BR-045`). */
  get passwordResetRequestLimits(): readonly RateLimitRule[] {
    return this.windowLimits();
  }

  /** The approved rate limits for SMS OTP requests (`BR-045`). */
  get smsOtpRequestLimits(): readonly RateLimitRule[] {
    return this.windowLimits();
  }

  /**
   * Both flows share the approved limits, but each exposes its own accessor: `BR-019` and
   * `BR-043` may diverge later, and a caller should not depend on them staying equal.
   */
  private windowLimits(): readonly RateLimitRule[] {
    return [
      {
        windowMs: this.config.authRateLimitShortWindowMs,
        maxAttempts: this.config.authRateLimitShortMaxAttempts,
      },
      {
        windowMs: this.config.authRateLimitLongWindowMs,
        maxAttempts: this.config.authRateLimitLongMaxAttempts,
      },
    ];
  }

  /** Stable, non-reversible subject key; the identity itself is never stored. */
  private hashSubject(scope: AuthRateLimitScope, subject: string): string {
    return createHmac('sha256', this.config.jwtSecret)
      .update(`servora-auth-rate-limit:v1:${scope}:${subject}`, 'utf8')
      .digest('hex');
  }
}
