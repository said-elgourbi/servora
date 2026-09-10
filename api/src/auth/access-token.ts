import { SignJWT, jwtVerify, type JWTPayload } from 'jose';

/**
 * Access tokens are short-lived HS256 JWTs (`ADR-004` D3).
 *
 * A signed token proves only *who* signed in and *which session* the token
 * belongs to. Whether that session is still active is answered by the
 * `auth_sessions` row, never by the token itself: a revoked session must stop
 * working immediately, not when its access token happens to expire.
 */
export const ACCESS_TOKEN_ALGORITHM = 'HS256';

export interface AccessTokenClaims {
  readonly userId: string;
  readonly sessionId: string;
}

/** Raised for every rejected access token; the caller maps it to `401`. */
export class InvalidAccessTokenError extends Error {
  constructor() {
    super('The access token is missing, malformed, expired or not authentic.');
    this.name = 'InvalidAccessTokenError';
  }
}

function signingKey(secret: string): Uint8Array {
  return new TextEncoder().encode(secret);
}

export interface IssueAccessTokenInput {
  readonly userId: string;
  readonly sessionId: string;
  readonly secret: string;
  readonly lifetimeMs: number;
  readonly now?: Date;
}

export interface IssuedAccessToken {
  readonly accessToken: string;
  readonly expiresAt: Date;
}

/** Signs an access token; the returned expiry mirrors the token's `exp` claim. */
export async function issueAccessToken(
  input: IssueAccessTokenInput,
): Promise<IssuedAccessToken> {
  const issuedAt = input.now ?? new Date();
  const expiresAt = new Date(issuedAt.getTime() + input.lifetimeMs);

  const accessToken = await new SignJWT({ sid: input.sessionId })
    .setProtectedHeader({ alg: ACCESS_TOKEN_ALGORITHM, typ: 'JWT' })
    .setSubject(input.userId)
    .setIssuedAt(Math.floor(issuedAt.getTime() / 1000))
    .setExpirationTime(Math.floor(expiresAt.getTime() / 1000))
    .sign(signingKey(input.secret));

  return { accessToken, expiresAt };
}

/**
 * Verifies the signature and `exp`, then requires both claims the API depends on.
 * Anything unexpected (wrong algorithm, missing claim, unparsable value) fails
 * closed with a single error type.
 */
export async function verifyAccessToken(
  accessToken: string,
  secret: string,
): Promise<AccessTokenClaims> {
  try {
    const { payload } = await jwtVerify(accessToken, signingKey(secret), {
      algorithms: [ACCESS_TOKEN_ALGORITHM],
    });

    const userId = payload.sub;
    const sessionId = (payload as JWTPayload & { sid?: unknown }).sid;

    if (typeof userId !== 'string' || userId.length === 0) {
      throw new InvalidAccessTokenError();
    }
    if (typeof sessionId !== 'string' || sessionId.length === 0) {
      throw new InvalidAccessTokenError();
    }

    return { userId, sessionId };
  } catch {
    throw new InvalidAccessTokenError();
  }
}
