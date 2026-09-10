import {
  DEFAULT_ACCESS_TOKEN_LIFETIME,
  DEFAULT_PASSWORD_RESET_TOKEN_LIFETIME,
  DEFAULT_SESSION_LIFETIME,
  MIN_JWT_SECRET_LENGTH,
  loadAuthConfig,
} from './auth-config.js';

const JWT_SECRET = 'unit-test-signing-secret-long-enough-for-hs256';

describe('loadAuthConfig lifetimes', () => {
  it('falls back to the documented defaults when the environment omits them', () => {
    const withDefaults = loadAuthConfig({ JWT_SECRET });
    const explicit = loadAuthConfig({
      JWT_SECRET,
      ACCESS_TOKEN_LIFETIME: DEFAULT_ACCESS_TOKEN_LIFETIME,
      SESSION_LIFETIME: DEFAULT_SESSION_LIFETIME,
      PASSWORD_RESET_TOKEN_LIFETIME: DEFAULT_PASSWORD_RESET_TOKEN_LIFETIME,
    });

    expect(withDefaults).toEqual(explicit);
  });

  it('reads each configured lifetime from the environment', () => {
    const config = loadAuthConfig({
      JWT_SECRET,
      ACCESS_TOKEN_LIFETIME: '30m',
      SESSION_LIFETIME: '7d',
      PASSWORD_RESET_TOKEN_LIFETIME: '1h',
    });

    expect(config.accessTokenLifetimeMs).toBe(30 * 60_000);
    expect(config.sessionLifetimeMs).toBe(7 * 86_400_000);
    expect(config.passwordResetTokenLifetimeMs).toBe(60 * 60_000);
  });

  it('rejects a lifetime that is not a duration', () => {
    expect(() =>
      loadAuthConfig({ JWT_SECRET, ACCESS_TOKEN_LIFETIME: 'soon' }),
    ).toThrow();
  });
});

describe('loadAuthConfig JWT secret', () => {
  it('reads the signing secret from the environment', () => {
    expect(loadAuthConfig({ JWT_SECRET }).jwtSecret).toBe(JWT_SECRET);
  });

  it('trims surrounding whitespace from the secret', () => {
    expect(loadAuthConfig({ JWT_SECRET: `  ${JWT_SECRET}  ` }).jwtSecret).toBe(
      JWT_SECRET,
    );
  });

  it('requires a signing secret', () => {
    expect(() => loadAuthConfig({})).toThrow(/JWT_SECRET is required/);
  });

  it('treats a blank secret as missing', () => {
    expect(() => loadAuthConfig({ JWT_SECRET: '   ' })).toThrow(
      /JWT_SECRET is required/,
    );
  });

  it('rejects a secret shorter than the minimum length', () => {
    expect(() => loadAuthConfig({ JWT_SECRET: 'too-short' })).toThrow(
      new RegExp(`at least ${MIN_JWT_SECRET_LENGTH}`),
    );
  });
});
