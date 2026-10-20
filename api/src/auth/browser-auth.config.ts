import type { Provider } from '@nestjs/common';

export interface BrowserAuthConfig {
  readonly origin: string | null;
  readonly secure: boolean;
}
export const BROWSER_AUTH_CONFIG = Symbol('BROWSER_AUTH_CONFIG');
export function loadBrowserAuthConfig(
  env: NodeJS.ProcessEnv = process.env,
): BrowserAuthConfig {
  const secure = env.NODE_ENV?.trim() === 'production';
  const raw = env.WEB_ORIGIN ?? (secure ? '' : 'http://localhost:4200');
  if (!raw) return { origin: null, secure };
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new Error('WEB_ORIGIN must be an exact HTTP(S) origin.');
  }
  if (
    url.origin !== raw ||
    !['http:', 'https:'].includes(url.protocol) ||
    url.username ||
    url.password ||
    (secure && url.protocol !== 'https:')
  ) {
    throw new Error(
      'WEB_ORIGIN must be an exact origin, using HTTPS in production.',
    );
  }
  return { origin: url.origin, secure };
}
export const browserAuthConfigProvider: Provider = {
  provide: BROWSER_AUTH_CONFIG,
  useFactory: loadBrowserAuthConfig,
};
