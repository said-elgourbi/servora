import {
  Inject,
  Injectable,
  type CanActivate,
  type ExecutionContext,
} from '@nestjs/common';
import type { Request } from 'express';
import { AuthApiError } from './auth-error.js';
import {
  BROWSER_AUTH_CONFIG,
  type BrowserAuthConfig,
} from './browser-auth.config.js';

/** Exact configured Origin plus a non-simple header protects every cookie mutation, including login. */
@Injectable()
export class BrowserOriginGuard implements CanActivate {
  constructor(
    @Inject(BROWSER_AUTH_CONFIG) private readonly config: BrowserAuthConfig,
  ) {}
  canActivate(context: ExecutionContext): boolean {
    const request = context.switchToHttp().getRequest<Request>();
    if (
      !this.config.origin ||
      request.headers.origin !== this.config.origin ||
      request.headers['x-servora-web'] !== '1'
    )
      throw AuthApiError.forbidden();
    return true;
  }
}
