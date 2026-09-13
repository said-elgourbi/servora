import { CanActivate, ExecutionContext, Injectable } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { AuthApiError } from './auth-error.js';
import type { AuthenticatedRequest } from './auth.guard.js';
import { REQUIRED_PERMISSIONS_METADATA } from './permissions.decorator.js';
import type { PermissionCode } from './permissions.js';
import { AuthorizationService } from './authorization.service.js';

export interface PermissionedRequest extends AuthenticatedRequest {
  authorization?: {
    readonly organizationId: string;
    readonly membershipId: string;
    readonly permissions: readonly PermissionCode[];
  };
}

@Injectable()
export class PermissionsGuard implements CanActivate {
  constructor(
    private readonly reflector: Reflector,
    private readonly authorization: AuthorizationService,
  ) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const required =
      this.reflector.getAllAndOverride<readonly PermissionCode[]>(
        REQUIRED_PERMISSIONS_METADATA,
        [context.getHandler(), context.getClass()],
      ) ?? [];

    if (required.length === 0) {
      return true;
    }

    const request = context.switchToHttp().getRequest<PermissionedRequest>();
    if (request.auth === undefined) {
      throw AuthApiError.unauthenticated();
    }

    const membership =
      await this.authorization.resolveMembershipForUserWithPermissions(
        request.auth.userId,
        required,
      );
    if (membership === null) {
      throw AuthApiError.forbidden();
    }

    request.authorization = membership;
    return true;
  }
}
