import { Inject, Injectable, Logger } from '@nestjs/common';
import { and, desc, eq, gt, isNull, sql } from 'drizzle-orm';
import { DatabaseService } from '../database/database.service.js';
import { phoneOtpChallenges, userProfiles, users } from '../database/schema.js';
import {
  SMS_PROVIDER,
  SmsDeliveryError,
  type SmsProvider,
} from '../sms/sms-provider.js';
import { canUserSignIn } from '../users/user-status-rules.js';
import type { UserStatus } from '../users/user.types.js';
import {
  authCodeDigestsEqual,
  generateAuthCode,
  hashAuthCode,
} from './auth-code.js';
import { AUTH_CONFIG } from './auth-config.provider.js';
import type { AuthConfig } from './auth-config.js';
import { resolveAuthMessageLocale, smsOtpMessage } from './auth-message.js';
import { AuthApiError } from './auth-error.js';
import { AuthRateLimiter } from './auth-rate-limiter.js';
import type {
  SmsOtpRequestDto,
  SmsOtpVerifyRequestDto,
} from './auth-request.dto.js';
import { AuthService, type SignInResponse } from './auth.service.js';
import type { PhoneOtpChallenge } from './auth.types.js';
import type { ClientContext } from './client-context.js';

/** The purpose these digests are bound to; see `auth-code.ts`. */
const CODE_SCOPE = 'SMS_OTP' as const;

/** See `password-reset.service.ts`: keeps the unknown-identity branch equally expensive. */
const NO_ACCOUNT_ID = '00000000-0000-0000-0000-000000000000';

/** How many rows to read when checking that a number is unambiguous. */
const MAX_CANDIDATE_ACCOUNTS = 2;

interface PhoneAccount {
  readonly id: string;
  readonly locale: string | null;
}

/**
 * Phone/SMS authentication (`BR-019`).
 *
 * Behaviour worth stating once:
 *
 * - **Nothing is created.** A phone number only ever authenticates an account that already
 *   exists, so no branch of this service inserts a user.
 * - **Ambiguity fails closed.** A number that matches no account, or more than one, is treated
 *   as unknown: no message is sent and verification cannot succeed (`ADR-006` D5).
 * - **Unknown numbers look identical to known ones.** The request endpoint answers the same way
 *   either way, and the rate-limit ledger counts both (`BR-044`, `BR-045`).
 * - **The provider cannot leak the code.** A delivery failure is logged without the message
 *   body and is not surfaced as a different response, because a different response for a
 *   known number would be an account oracle (`BR-044`).
 */
@Injectable()
export class SmsOtpService {
  private readonly logger = new Logger(SmsOtpService.name);

  constructor(
    private readonly database: DatabaseService,
    @Inject(AUTH_CONFIG) private readonly config: AuthConfig,
    @Inject(SMS_PROVIDER) private readonly smsProvider: SmsProvider,
    private readonly rateLimiter: AuthRateLimiter,
    private readonly auth: AuthService,
  ) {}

  /** Issues and delivers one one-time password, or reports nothing at all. */
  async request(
    input: SmsOtpRequestDto,
    now: Date = new Date(),
  ): Promise<void> {
    // Throttling, including the resend cooldown, is decided before the account is even
    // looked up, so a `429` cannot distinguish a real number from an unknown one.
    await this.rateLimiter.consume({
      scope: 'SMS_OTP_REQUEST',
      subject: input.phone,
      limits: this.rateLimiter.smsOtpRequestLimits,
      minIntervalMs: this.config.smsOtpResendCooldownMs,
      now,
    });

    const account = await this.findEligibleAccount(input.phone);
    if (account === null) {
      // Same response as the delivered case, and nothing is sent (`BR-044`).
      return;
    }

    const code = generateAuthCode();

    await this.database.db.transaction(async (tx) => {
      // "Requesting a new OTP invalidates the previous OTP" (`BR-019`): the outstanding
      // challenge is consumed rather than overwritten, so it cannot be replayed afterwards.
      await tx
        .update(phoneOtpChallenges)
        .set({ consumedAt: now })
        .where(
          and(
            eq(phoneOtpChallenges.userId, account.id),
            isNull(phoneOtpChallenges.consumedAt),
          ),
        );

      await tx.insert(phoneOtpChallenges).values({
        userId: account.id,
        codeHash: hashAuthCode(
          this.config.jwtSecret,
          CODE_SCOPE,
          account.id,
          code,
        ),
        expiresAt: new Date(now.getTime() + this.config.smsOtpLifetimeMs),
      });
    });

    try {
      await this.smsProvider.send({
        to: input.phone,
        body: smsOtpMessage(
          resolveAuthMessageLocale(account.locale),
          code,
          this.lifetimeMinutes,
        ),
      });
    } catch (error) {
      // Reported without the recipient and without the body (`BR-046`). The request still
      // answers like a delivered one: telling the caller that delivery failed would reveal
      // that the number belongs to an account.
      if (error instanceof SmsDeliveryError) {
        this.logger.error(
          'The SMS provider did not accept a one-time password message.',
        );
        return;
      }
      throw error;
    }
  }

  /**
   * Verifies an OTP and, on success, opens the same session `POST /auth/sign-in` opens
   * (`BR-019`: one authentication result, one authorization model).
   */
  async verify(
    input: SmsOtpVerifyRequestDto,
    context: ClientContext,
    now: Date = new Date(),
  ): Promise<SignInResponse> {
    const account = await this.findEligibleAccount(input.phone);
    const userId = account?.id ?? NO_ACCOUNT_ID;
    const challenge = await this.findOutstandingChallenge(userId, now);

    if (
      challenge === null ||
      challenge.attempts >= this.config.smsOtpMaxAttempts
    ) {
      throw AuthApiError.otpCodeInvalid();
    }

    const presented = hashAuthCode(
      this.config.jwtSecret,
      CODE_SCOPE,
      userId,
      input.code,
    );
    if (!authCodeDigestsEqual(challenge.codeHash, presented)) {
      await this.database.db
        .update(phoneOtpChallenges)
        .set({ attempts: sql`${phoneOtpChallenges.attempts} + 1` })
        .where(eq(phoneOtpChallenges.id, challenge.id));

      throw AuthApiError.otpCodeInvalid();
    }

    // Success immediately invalidates the challenge (`BR-019`), before the session exists, so
    // a verification that fails later cannot leave a reusable code behind.
    await this.database.db
      .update(phoneOtpChallenges)
      .set({ consumedAt: now })
      .where(eq(phoneOtpChallenges.id, challenge.id));

    return this.auth.openSession(userId, input.device, context, now);
  }

  /** Minutes reported in the message, derived from the configured lifetime. */
  private get lifetimeMinutes(): number {
    return Math.max(1, Math.round(this.config.smsOtpLifetimeMs / 60_000));
  }

  /**
   * The one account a phone number may authenticate, or `null`.
   *
   * `null` covers "no such number", "more than one account has it" and "the account may not
   * sign in" — three different situations that must be indistinguishable to the caller
   * (`BR-044`), which is why this method does not tell them apart either.
   */
  private async findEligibleAccount(
    phone: string,
  ): Promise<PhoneAccount | null> {
    const candidates = await this.database.db
      .select({
        id: users.id,
        status: users.status,
        locale: userProfiles.locale,
      })
      .from(users)
      .leftJoin(userProfiles, eq(userProfiles.userId, users.id))
      .where(eq(users.phone, phone))
      .limit(MAX_CANDIDATE_ACCOUNTS);

    if (candidates.length !== 1) {
      return null;
    }

    const [account] = candidates;
    if (account === undefined || !canUserSignIn(account.status as UserStatus)) {
      return null;
    }

    return { id: account.id, locale: account.locale };
  }

  /**
   * The newest challenge that is still usable.
   *
   * Consumed and expired challenges are excluded by the query, so a superseded code is
   * unusable rather than merely unlikely to be found.
   */
  private async findOutstandingChallenge(
    userId: string,
    now: Date,
  ): Promise<PhoneOtpChallenge | null> {
    const [challenge] = await this.database.db
      .select()
      .from(phoneOtpChallenges)
      .where(
        and(
          eq(phoneOtpChallenges.userId, userId),
          isNull(phoneOtpChallenges.consumedAt),
          gt(phoneOtpChallenges.expiresAt, now),
        ),
      )
      .orderBy(desc(phoneOtpChallenges.createdAt))
      .limit(1);

    return challenge ?? null;
  }
}
