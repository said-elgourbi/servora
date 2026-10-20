import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Test } from '@nestjs/testing';
import type { INestApplication } from '@nestjs/common';
import { BrowserAuthController } from './browser-auth.controller.js';
import { BrowserOriginGuard } from './browser-origin.guard.js';
import { BROWSER_AUTH_CONFIG } from './browser-auth.config.js';
import { AUTH_CONFIG } from './auth-config.provider.js';
import { AuthService } from './auth.service.js';
import { SmsOtpService } from './sms-otp.service.js';
import { AuthApiError } from './auth-error.js';

// HTTP-level adapter tests, without a database or external SMS transport.
const device = { platform: 'WEB', deviceId: 'browser-installation' };
const tokens = {
  accessToken: 'access-secret',
  accessTokenExpiresAt: '2026-10-18T18:00:00.000Z',
  refreshToken: 'refresh-secret',
  sessionId: 'session-id',
  permissions: ['customers.view'],
};
const service = {
  signIn: vi.fn(),
  refresh: vi.fn(),
  signOutByRefreshToken: vi.fn(),
};
const sms = { request: vi.fn(), verify: vi.fn() };
let app: INestApplication;
let origin: string;

async function post(
  path: string,
  body: unknown = {},
  headers: Record<string, string> = {},
) {
  return fetch(`${origin}/auth/web/${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Origin: 'https://office.example',
      'X-Servora-Web': '1',
      ...headers,
    },
    body: JSON.stringify(body),
  });
}
beforeEach(async () => {
  vi.resetAllMocks();
  service.signIn.mockResolvedValue(tokens);
  service.refresh.mockResolvedValue(tokens);
  service.signOutByRefreshToken.mockResolvedValue(undefined);
  sms.verify.mockResolvedValue(tokens);
  const module = await Test.createTestingModule({
    controllers: [BrowserAuthController],
    providers: [
      BrowserOriginGuard,
      { provide: AuthService, useValue: service },
      { provide: SmsOtpService, useValue: sms },
      {
        provide: BROWSER_AUTH_CONFIG,
        useValue: { origin: 'https://office.example', secure: true },
      },
      { provide: AUTH_CONFIG, useValue: { sessionLifetimeMs: 86400000 } },
    ],
  }).compile();
  app = module.createNestApplication();
  await app.listen(0, '127.0.0.1');
  origin = await app.getUrl();
});
afterEach(async () => {
  await app.close();
});

describe('browser authentication HTTP adapter', () => {
  it('sets a secure HttpOnly scoped cookie and excludes refresh credentials from JSON', async () => {
    const res = await post('sign-in', {
      email: 'office@example.com',
      password: ' secret ',
      device,
    });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({
      accessToken: tokens.accessToken,
      accessTokenExpiresAt: tokens.accessTokenExpiresAt,
      sessionId: tokens.sessionId,
    });
    expect(res.headers.get('cache-control')).toBe('no-store');
    const cookie = res.headers.get('set-cookie');
    expect(cookie).toContain('__Secure-servora-refresh=refresh-secret');
    for (const attribute of [
      'HttpOnly',
      'Secure',
      'SameSite=Strict',
      'Path=/auth/web',
    ])
      expect(cookie).toContain(attribute);
    expect(service.signIn.mock.calls[0][0].password).toBe(' secret ');
  });
  it.each(['sign-in', 'sms/request', 'sms/verify', 'refresh', 'sign-out'])(
    'rejects foreign origins and missing custom headers on %s',
    async (path) => {
      expect(
        (await post(path, {}, { Origin: 'https://evil.example' })).status,
      ).toBe(403);
      expect((await post(path, {}, { 'X-Servora-Web': '' })).status).toBe(403);
      expect((await post(path, {}, { Origin: '' })).status).toBe(403);
      expect(service.refresh).not.toHaveBeenCalled();
    },
  );
  it('refuses a non-WEB platform', async () => {
    expect(
      (
        await post('sign-in', {
          email: 'office@example.com',
          password: 'secret',
          device: { ...device, platform: 'ANDROID' },
        })
      ).status,
    ).toBe(400);
    expect(service.signIn).not.toHaveBeenCalled();
  });
  it('refreshes from the cookie, ignoring body credentials', async () => {
    const res = await post(
      'refresh',
      { refreshToken: 'body-token' },
      { Cookie: '__Secure-servora-refresh=cookie-token' },
    );
    expect(res.status).toBe(200);
    expect(service.refresh).toHaveBeenCalledWith('cookie-token');
    expect(await res.json()).not.toHaveProperty('refreshToken');
  });
  it('clears rejected cookies but retains them on transient errors', async () => {
    service.refresh.mockRejectedValueOnce(AuthApiError.refreshTokenInvalid());
    const res = await post(
      'refresh',
      {},
      { Cookie: '__Secure-servora-refresh=old-token' },
    );
    expect(res.status).toBe(401);
    expect(res.headers.get('set-cookie')).toContain('Expires=Thu, 01 Jan 1970');
    service.refresh.mockRejectedValueOnce(AuthApiError.tooManyRequests());
    expect(
      (
        await post(
          'refresh',
          {},
          { Cookie: '__Secure-servora-refresh=old-token' },
        )
      ).headers.get('set-cookie'),
    ).toBeNull();
  });
  it('rejects missing or ambiguous cookies', async () => {
    expect((await post('refresh')).status).toBe(401);
    expect(
      (
        await post(
          'refresh',
          {},
          { Cookie: '__Secure-servora-refresh=a; __Secure-servora-refresh=b' },
        )
      ).status,
    ).toBe(401);
    expect(service.refresh).not.toHaveBeenCalled();
  });
  it('revokes using the refresh cookie and clears it without an access token', async () => {
    const res = await post(
      'sign-out',
      {},
      { Cookie: '__Secure-servora-refresh=old-token' },
    );
    expect(res.status).toBe(204);
    expect(service.signOutByRefreshToken).toHaveBeenCalledWith('old-token');
    expect(res.headers.get('set-cookie')).toContain('Expires=Thu, 01 Jan 1970');
  });
  it('preserves generic SMS request and code semantics', async () => {
    expect((await post('sms/request', { phone: '+15145551234' })).status).toBe(
      202,
    );
    expect(
      (
        await post('sms/verify', {
          phone: '+15145551234',
          code: '001234',
          device,
        })
      ).status,
    ).toBe(200);
    expect(sms.verify.mock.calls[0][0].code).toBe('001234');
  });
});
