import { FieldContextService } from './field-context.service.js';
import { FIELD_SCOPED, fieldError, readFieldScope } from './field-scope.js';
import { CanActivate, ExecutionContext, Injectable } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { AuthApiError } from './auth-error.js';
import type { AuthenticatedRequest } from './auth.guard.js';
import {
  REQUIRED_ANY_PERMISSIONS_METADATA,
  REQUIRED_PERMISSIONS_METADATA,
} from './permissions.decorator.js';
import type { PermissionCode } from './permissions.js';
import { AuthorizationService } from './authorization.service.js';

export interface PermissionedRequest extends AuthenticatedRequest {
  authorization?: {
    readonly organizationId: string;
    readonly membershipId: string;
    readonly fieldScoped?: boolean;
    readonly permissions: readonly PermissionCode[];
  };
}

@Injectable()
export class PermissionsGuard implements CanActivate {
  constructor(
    private readonly reflector: Reflector,
    private readonly authorization: AuthorizationService,
    private readonly fieldContext: FieldContextService,
  ) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const handlers = [context.getHandler(), context.getClass()];
    const required =
      this.reflector.getAllAndOverride<readonly PermissionCode[]>(
        REQUIRED_PERMISSIONS_METADATA,
        handlers,
      ) ?? [];
    const anyOf =
      this.reflector.getAllAndOverride<readonly PermissionCode[]>(
        REQUIRED_ANY_PERMISSIONS_METADATA,
        handlers,
      ) ?? [];

    const request = context.switchToHttp().getRequest<PermissionedRequest>();
    const fieldScope = readFieldScope(request.headers ?? {});
    if (
      fieldScope &&
      !this.reflector.getAllAndOverride<boolean>(FIELD_SCOPED, handlers)
    ) {
      throw fieldError(400, 'FIELD_SCOPE_UNSUPPORTED');
    }
    if (!fieldScope && required.length === 0 && anyOf.length === 0) {
      return true;
    }

    if (request.auth === undefined) {
      throw AuthApiError.unauthenticated();
    }

    const membership = fieldScope
      ? await this.fieldContext.resolve(request.auth.userId, fieldScope)
      : await this.resolveMembership(request.auth.userId, required, anyOf);
    if (
      membership === null ||
      (fieldScope &&
        (!this.authorization.hasAllPermissions(membership, required) ||
          (anyOf.length > 0 &&
            !this.authorization.hasAnyPermission(membership, anyOf))))
    ) {
      throw AuthApiError.forbidden();
    }

    request.authorization = membership;
    return true;
  }

  /**
   * The membership the route is reached with, or `null` when there is none.
   *
   * All-of (`@RequirePermissions`) is the default and keeps its meaning: the caller must hold every
   * listed code. Any-of (`@RequireAnyPermission`) admits a caller holding one of them, which is how a
   * route reaches the office audience and the field audience without a role check (`BR-006`, `BR-009`,
   * `ADR-019` D1).
   *
   * Declaring both on one route is refused: the two questions are answered in different places by
   * design, and picking a precedence here would invent a rule no product decision defines (`BR-042`).
   */
  private async resolveMembership(
    userId: string,
    required: readonly PermissionCode[],
    anyOf: readonly PermissionCode[],
  ) {
    if (required.length > 0 && anyOf.length > 0) {
      throw new Error(
        'A route declares either @RequirePermissions or @RequireAnyPermission, never both (ADR-019 D1).',
      );
    }

    return required.length > 0
      ? this.authorization.resolveMembershipForUserWithPermissions(
          userId,
          required,
        )
      : this.authorization.resolveMembershipForUserWithAnyPermission(
          userId,
          anyOf,
        );
  }
}
