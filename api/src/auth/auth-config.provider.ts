import type { Provider } from '@nestjs/common';
import { loadAuthConfig } from './auth-config.js';

/**
 * DI token for the validated authentication configuration.
 *
 * The configuration is provided rather than read inside each consumer so that a
 * misconfigured deployment fails during application bootstrap: `loadAuthConfig`
 * throws on a missing or weak `JWT_SECRET` instead of falling back to a default
 * that would silently become a production secret (`dev.md` §5).
 */
export const AUTH_CONFIG = Symbol('AUTH_CONFIG');

export const authConfigProvider: Provider = {
  provide: AUTH_CONFIG,
  useFactory: loadAuthConfig,
};
