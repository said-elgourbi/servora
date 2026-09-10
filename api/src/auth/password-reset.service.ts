import { Inject, Injectable, Logger } from '@nestjs/common';
import { and, desc, eq, gt, isNull, sql } from 'drizzle-orm';
import { DatabaseService } from '../database/database.service.js';
import {
  passwordResetTokens,
  userProfiles,
  users,
} from '../database/schema.js';
import { EmailDeliveryError } from '../email/email-provider.js';
import {
  PASSWORD_RESET_NOTIFIER,
  type PasswordResetNotifier,
} from '../notifications/password-reset-notifier.js';
import { hashPassword } from '../users/password-hasher.js';
import {
  authCodeDigestsEqual,
  generateAuthCode,
  hashAuthCode,
} from './auth-code.js';
import { AUTH_CONFIG } from './auth-config.provider.js';
import type { AuthConfig } from './auth-config.js';
import {
  passwordResetMessage,
  resolveAuthMessageLocale,
} from './auth-message.js';
import { AuthApiError } from './auth-error.js';
import { AuthRateLimiter } from './auth-rate-limiter.js';
import type {
  PasswordResetCompleteRequestDto,
  PasswordResetRequestDto,
  PasswordResetVerifyRequestDto,
} from './auth-request.dto.js';
import type { PasswordResetToken } from './auth.types.js';

/** The purpose these digests are bound to; see `auth-code.ts`. */
const CODE_SCOPE = 'PASSWORD_RESET' as const;

/**
 * A well-formed id that no account can have. The unknown-identity branch of verification
 * queries with it so both branches perform the same work, rather than one of them returning
 * measurably sooner (`BR-044`).
 */
const NO_ACCOUNT_ID = '00000000-0000-0000-0000-000000000000';

interface PasswordResetAccount {
  readonly id: string;
  readonly email: string;
  readonly locale: string | null;
}

/**
 * Password reset (`BR-043`).
 *
 * Every externally visible outcome is generic: a request answers identically whether or not
 * the address exists, and a verification failure is one code and one message whatever the
 * reason (`BR-044`). No session is touched — whether a reset revokes an existing session is
 * undecided, so nothing here assumes an answer.
 */
@Injectable()
export class PasswordResetService {
  private readonly logger = new Logger(PasswordResetService.name);

  constructor(
    private readonly database: DatabaseService,
    @Inject(AUTH_CONFIG) private readonly config: AuthConfig,
    private readonly rateLimiter: AuthRateLimiter,
    @Inject(PASSWORD_RESET_NOTIFIER)
    private readonly notifier: PasswordResetNotifier,
  ) {}

  /**
   * Starts a reset: throttles, then issues one credential and delivers it.
   *
   * Returns nothing in both cases, so a caller cannot accidentally turn the presence of an
   * account into a response difference.
   */
  async request(
    input: PasswordResetRequestDto,
    now: Date = new Date(),
  ): Promise<void> {
    // Counted before any account is examined, and counted for an unknown identity too: a
    // throttling response must not reveal whether an address exists (`BR-044`, `BR-045`).
    await this.rateLimiter.consume({
      scope: 'PASSWORD_RESET_REQUEST',
      subject: input.email,
      limits: this.rateLimiter.passwordResetRequestLimits,
      now,
    });

    const account = await this.findAccount(input.email);
    if (account === null) {
      // Indistinguishable from the delivered case, and nothing is delivered (`BR-044`).
      return;
    }

    const code = generateAuthCode();

    await this.database.db.transaction(async (tx) => {
      // Requesting a credential supersedes the outstanding one (`BR-043`) without deleting
      // it, so "a credential existed and was replaced" stays answerable (`BR-033`).
      await tx
        .update(passwordResetTokens)
        .set({ usedAt: now })
        .where(
          and(
            eq(passwordResetTokens.userId, account.id),
            isNull(passwordResetTokens.usedAt),
          ),
        );

      await tx.insert(passwordResetTokens).values({
        userId: account.id,
        tokenHash: hashAuthCode(
          this.config.jwtSecret,
          CODE_SCOPE,
          account.id,
          code,
        ),
        expiresAt: new Date(
          now.getTime() + this.config.passwordResetTokenLifetimeMs,
        ),
      });
    });

    const message = passwordResetMessage(
      resolveAuthMessageLocale(account.locale),
      code,
      this.lifetimeMinutes,
    );

    await this.deliverResetMessage(account.email, message.subject, message.body, code);
  }

  /**
   * Hands one reset message to the notifier without letting a transport failure become an account
   * oracle.
   *
   * A different response for an address that has an account would disclose that it has one
   * (`BR-044`), which is why a provider rejection is logged and swallowed here rather than
   * propagated: the API contract answers `202` whether or not delivery succeeded. This mirrors the
   * OTP request path, and nothing about the message is logged, because it carries the code
   * (`BR-046`). Failures the port does not classify stay loud, since they are deployment faults
   * rather than an expected delivery outcome.
   */
  private async deliverResetMessage(
    to: string,
    subject: string,
    body: string,
    code: string,
  ): Promise<void> {
    try {
      await this.notifier.send({ to, code, subject, body });
    } catch (error) {
      if (!(error instanceof EmailDeliveryError)) {
        throw error;
      }

      this.logger.error(
        'The email provider did not accept a password-reset message.',
      );
    }
  }

  /**
   * Checks a credential without consuming it, so a client can advance to the new-password
   * step. Failed attempts are counted either way, so the five-attempt limit covers the whole
   * flow rather than each endpoint separately (`BR-043`).
   */
  async verify(
    input: PasswordResetVerifyRequestDto,
    now: Date = new Date(),
  ): Promise<void> {
    await this.assertCredential(input.email, input.code, now);
  }

  /**
   * Redeems a credential and stores the new password.
   *
   * The password is hashed with the existing mechanism, the credential and every other
   * outstanding one for the account are consumed, and no session is created (`BR-043`).
   * A dedicated password-change audit record is not written because the project has no
   * audit/event store yet (`BR-033` is open); `users.updated_at` records that the row changed.
   */
  async complete(
    input: PasswordResetCompleteRequestDto,
    now: Date = new Date(),
  ): Promise<void> {
    const { userId } = await this.assertCredential(
      input.email,
      input.code,
      now,
    );
    const passwordHash = await hashPassword(input.newPassword);

    await this.database.db.transaction(async (tx) => {
      await tx.update(users).set({ passwordHash }).where(eq(users.id, userId));

      await tx
        .update(passwordResetTokens)
        .set({ usedAt: now })
        .where(
          and(
            eq(passwordResetTokens.userId, userId),
            isNull(passwordResetTokens.usedAt),
          ),
        );
    });
  }

  /** Minutes reported in the delivered message, derived from the configured lifetime. */
  private get lifetimeMinutes(): number {
    return Math.max(
      1,
      Math.round(this.config.passwordResetTokenLifetimeMs / 60_000),
    );
  }

  /**
   * Resolves and checks the outstanding credential.
   *
   * One rejection for every failure — unknown account, no credential, expired, superseded,
   * consumed, attempts exhausted, wrong code — so nothing about the account, and nothing
   * about how far a guess got, is disclosed (`BR-044`).
   */
  private async assertCredential(
    email: string,
    code: string,
    now: Date,
  ): Promise<{ userId: string }> {
    const account = await this.findAccount(email);
    const userId = account?.id ?? NO_ACCOUNT_ID;
    const credential = await this.findOutstandingCredential(userId, now);

    if (
      credential === null ||
      credential.attempts >= this.config.passwordResetMaxAttempts
    ) {
      throw AuthApiError.resetCodeInvalid();
    }

    const presented = hashAuthCode(
      this.config.jwtSecret,
      CODE_SCOPE,
      userId,
      code,
    );
    if (!authCodeDigestsEqual(credential.tokenHash, presented)) {
      // The increment is expressed in SQL so two concurrent guesses cannot both write the
      // same value and hide an attempt from the limit.
      await this.database.db
        .update(passwordResetTokens)
        .set({ attempts: sql`${passwordResetTokens.attempts} + 1` })
        .where(eq(passwordResetTokens.id, credential.id));

      throw AuthApiError.resetCodeInvalid();
    }

    return { userId };
  }

  private async findAccount(
    email: string,
  ): Promise<PasswordResetAccount | null> {
    const [account] = await this.database.db
      .select({
        id: users.id,
        email: users.email,
        locale: userProfiles.locale,
      })
      .from(users)
      .leftJoin(userProfiles, eq(userProfiles.userId, users.id))
      .where(eq(users.email, email))
      .limit(1);

    return account ?? null;
  }

  /**
   * The newest credential that is still redeemable.
   *
   * A superseded or consumed credential has `used_at` set and is excluded by the predicate
   * rather than by inspecting it afterwards, so "single use" is enforced by the query that
   * finds it.
   */
  private async findOutstandingCredential(
    userId: string,
    now: Date,
  ): Promise<PasswordResetToken | null> {
    const [credential] = await this.database.db
      .select()
      .from(passwordResetTokens)
      .where(
        and(
          eq(passwordResetTokens.userId, userId),
          isNull(passwordResetTokens.usedAt),
          gt(passwordResetTokens.expiresAt, now),
        ),
      )
      .orderBy(desc(passwordResetTokens.createdAt))
      .limit(1);

    return credential ?? null;
  }
}
