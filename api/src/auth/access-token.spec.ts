import { SignJWT } from 'jose';
import {
  ACCESS_TOKEN_ALGORITHM,
  InvalidAccessTokenError,
  issueAccessToken,
  verifyAccessToken,
} from './access-token.js';

const SECRET = 'test-secret-that-is-long-enough-for-hs256';
const USER_ID = '11111111-1111-1111-1111-111111111111';
const SESSION_ID = '22222222-2222-2222-2222-222222222222';
const LIFETIME_MS = 15 * 60_000;
// Derived from the real clock: a fixed date would make the token look expired (or
// issued in the future) as soon as the machine clock moves past it.
const NOW = new Date();

function base64Url(value: string): string {
  return Buffer.from(value, 'utf8').toString('base64url');
}

describe('issueAccessToken', () => {
  it('issues an HS256 token that round-trips the user and session', async () => {
    const { accessToken } = await issueAccessToken({
      userId: USER_ID,
      sessionId: SESSION_ID,
      secret: SECRET,
      lifetimeMs: LIFETIME_MS,
      now: NOW,
    });

    const [header, payload] = accessToken.split('.');
    expect(
      JSON.parse(Buffer.from(header, 'base64url').toString('utf8')),
    ).toEqual({
      alg: ACCESS_TOKEN_ALGORITHM,
      typ: 'JWT',
    });
    expect(
      JSON.parse(Buffer.from(payload, 'base64url').toString('utf8')),
    ).toMatchObject({
      sub: USER_ID,
      sid: SESSION_ID,
      iat: Math.floor(NOW.getTime() / 1000),
      exp: Math.floor(NOW.getTime() / 1000) + LIFETIME_MS / 1000,
    });

    await expect(verifyAccessToken(accessToken, SECRET)).resolves.toEqual({
      userId: USER_ID,
      sessionId: SESSION_ID,
    });
  });

  it('derives the expiry from the configured access-token lifetime', async () => {
    const { expiresAt } = await issueAccessToken({
      userId: USER_ID,
      sessionId: SESSION_ID,
      secret: SECRET,
      lifetimeMs: LIFETIME_MS,
      now: NOW,
    });

    expect(expiresAt.toISOString()).toBe(
      new Date(NOW.getTime() + LIFETIME_MS).toISOString(),
    );
  });

  it('rejects a token whose expiry has passed', async () => {
    const { accessToken } = await issueAccessToken({
      userId: USER_ID,
      sessionId: SESSION_ID,
      secret: SECRET,
      lifetimeMs: -1_000,
      now: NOW,
    });

    await expect(verifyAccessToken(accessToken, SECRET)).rejects.toBeInstanceOf(
      InvalidAccessTokenError,
    );
  });
});

describe('verifyAccessToken', () => {
  it('rejects a token signed with a different secret', async () => {
    const { accessToken } = await issueAccessToken({
      userId: USER_ID,
      sessionId: SESSION_ID,
      secret: SECRET,
      lifetimeMs: LIFETIME_MS,
      now: NOW,
    });

    await expect(
      verifyAccessToken(accessToken, 'another-secret-of-sufficient-length'),
    ).rejects.toBeInstanceOf(InvalidAccessTokenError);
  });

  it('rejects a token whose session claim is missing', async () => {
    const accessToken = await new SignJWT({})
      .setProtectedHeader({ alg: ACCESS_TOKEN_ALGORITHM, typ: 'JWT' })
      .setSubject(USER_ID)
      .setExpirationTime(Math.floor(NOW.getTime() / 1000) + 600)
      .sign(new TextEncoder().encode(SECRET));

    await expect(verifyAccessToken(accessToken, SECRET)).rejects.toBeInstanceOf(
      InvalidAccessTokenError,
    );
  });

  it('rejects a token whose subject is missing', async () => {
    const accessToken = await new SignJWT({ sid: SESSION_ID })
      .setProtectedHeader({ alg: ACCESS_TOKEN_ALGORITHM, typ: 'JWT' })
      .setExpirationTime(Math.floor(NOW.getTime() / 1000) + 600)
      .sign(new TextEncoder().encode(SECRET));

    await expect(verifyAccessToken(accessToken, SECRET)).rejects.toBeInstanceOf(
      InvalidAccessTokenError,
    );
  });

  it('rejects an unsecured token', async () => {
    const header = base64Url(JSON.stringify({ alg: 'none', typ: 'JWT' }));
    const payload = base64Url(
      JSON.stringify({ sub: USER_ID, sid: SESSION_ID, exp: 4_000_000_000 }),
    );

    await expect(
      verifyAccessToken(`${header}.${payload}.`, SECRET),
    ).rejects.toBeInstanceOf(InvalidAccessTokenError);
  });

  it('rejects a token that is not a JWT at all', async () => {
    await expect(
      verifyAccessToken('not-a-token', SECRET),
    ).rejects.toBeInstanceOf(InvalidAccessTokenError);
    await expect(verifyAccessToken('', SECRET)).rejects.toBeInstanceOf(
      InvalidAccessTokenError,
    );
  });
});
