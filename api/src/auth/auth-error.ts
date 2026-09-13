import { HttpException, HttpStatus } from '@nestjs/common';

/**
 * Stable, machine-readable authentication error codes.
 *
 * Clients branch on `code`, never on the human-readable `message`: the message is
 * presentation text that may be localized, while the code is a stable domain value
 * (`Project.md` §10, `dev.md` §8).
 */
export const AUTH_ERROR_CODES = {
  VALIDATION_FAILED: 'VALIDATION_FAILED',
  INVALID_CREDENTIALS: 'INVALID_CREDENTIALS',
  REFRESH_TOKEN_INVALID: 'REFRESH_TOKEN_INVALID',
  UNAUTHENTICATED: 'UNAUTHENTICATED',
  FORBIDDEN: 'FORBIDDEN',
  /**
   * A password-reset credential was wrong, expired, superseded, consumed or no longer
   * eligible. One code for all of those, so the response cannot be used to learn whether
   * an account exists (`BR-044`).
   */
  RESET_CODE_INVALID: 'RESET_CODE_INVALID',
  /** The same, for an SMS one-time password (`BR-019`, `BR-044`). */
  OTP_CODE_INVALID: 'OTP_CODE_INVALID',
  /** An authentication attempt exceeded a configured limit (`BR-045`). */
  TOO_MANY_REQUESTS: 'TOO_MANY_REQUESTS',
} as const;

export type AuthErrorCode =
  (typeof AUTH_ERROR_CODES)[keyof typeof AUTH_ERROR_CODES];

/**
 * Error envelope shared by every `/auth` failure (`docs/api/authentication.md` §2 C1).
 *
 * It keeps Nest's shape (`statusCode`, `message`) and adds the stable `code` the
 * clients need. No other field is added, so the envelope stays small enough to
 * mirror in the Angular and Android clients.
 */
export interface AuthErrorBody {
  readonly statusCode: number;
  readonly code: AuthErrorCode;
  readonly message: string;
}

/**
 * The single exception type for authentication failures.
 *
 * Every factory produces a deliberately vague message: an authentication error
 * must never disclose whether an email address exists, whether a session was
 * revoked, or why a token was rejected.
 */
export class AuthApiError extends HttpException {
  readonly code: AuthErrorCode;

  constructor(status: HttpStatus, code: AuthErrorCode, message: string) {
    super(
      { statusCode: status, code, message } satisfies AuthErrorBody,
      status,
    );
    this.code = code;
  }

  static validationFailed(message: string): AuthApiError {
    return new AuthApiError(
      HttpStatus.BAD_REQUEST,
      AUTH_ERROR_CODES.VALIDATION_FAILED,
      message,
    );
  }

  static invalidCredentials(): AuthApiError {
    return new AuthApiError(
      HttpStatus.UNAUTHORIZED,
      AUTH_ERROR_CODES.INVALID_CREDENTIALS,
      'The email address or password is incorrect.',
    );
  }

  static refreshTokenInvalid(): AuthApiError {
    return new AuthApiError(
      HttpStatus.UNAUTHORIZED,
      AUTH_ERROR_CODES.REFRESH_TOKEN_INVALID,
      'The refresh token is not valid. Sign in again.',
    );
  }

  static unauthenticated(): AuthApiError {
    return new AuthApiError(
      HttpStatus.UNAUTHORIZED,
      AUTH_ERROR_CODES.UNAUTHENTICATED,
      'Authentication is required.',
    );
  }

  static forbidden(): AuthApiError {
    return new AuthApiError(
      HttpStatus.FORBIDDEN,
      AUTH_ERROR_CODES.FORBIDDEN,
      'You do not have permission to perform this action.',
    );
  }

  /**
   * Wrong, expired, superseded, consumed or ineligible reset credential.
   *
   * Deliberately one factory and one message: distinguishing those cases would let an
   * unauthenticated caller discover whether an account exists, or how far a guessed code
   * got (`BR-044`).
   */
  static resetCodeInvalid(): AuthApiError {
    return new AuthApiError(
      HttpStatus.UNAUTHORIZED,
      AUTH_ERROR_CODES.RESET_CODE_INVALID,
      'The reset code is invalid or has expired. Request a new code.',
    );
  }

  /** Wrong, expired, superseded, consumed or ineligible one-time password (`BR-019`). */
  static otpCodeInvalid(): AuthApiError {
    return new AuthApiError(
      HttpStatus.UNAUTHORIZED,
      AUTH_ERROR_CODES.OTP_CODE_INVALID,
      'The verification code is invalid or has expired. Request a new code.',
    );
  }

  /** A configured attempt limit was exceeded (`BR-045`). */
  static tooManyRequests(): AuthApiError {
    return new AuthApiError(
      HttpStatus.TOO_MANY_REQUESTS,
      AUTH_ERROR_CODES.TOO_MANY_REQUESTS,
      'Too many attempts. Wait before trying again.',
    );
  }
}
