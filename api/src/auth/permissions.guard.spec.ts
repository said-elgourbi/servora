import { ExecutionContext, HttpStatus } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { describe, expect, it, vi } from 'vitest';
import { AuthorizationService } from './authorization.service.js';
import { AuthApiError } from './auth-error.js';
import {
  REQUIRED_ANY_PERMISSIONS_METADATA,
  REQUIRED_PERMISSIONS_METADATA,
} from './permissions.decorator.js';
import { PermissionsGuard } from './permissions.guard.js';
import type { PermissionedRequest } from './permissions.guard.js';
import { CUSTOMER_PERMISSIONS, VISIT_PERMISSIONS } from './permissions.js';

/**
 * The guard's two shapes (`ADR-019` D1).
 *
 * `@RequirePermissions` is all-of and keeps its meaning: the caller must hold every listed code. The
 * new `@RequireAnyPermission` admits a caller holding one of them, which is how one route serves the
 * office audience and the field audience without a role check (`BR-006`, `BR-009`).
 *
 * These are the guard's own decisions — which resolution it asks, what it writes back and what it
 * refuses — asserted without HTTP. The route-level behaviour (the status codes a caller sees) is
 * asserted against PostgreSQL in `test/job-details.e2e-spec.ts` and `test/job-activity.e2e-spec.ts`.
 */
describe('PermissionsGuard', () => {
  const membership = {
    organizationId: 'org-1',
    membershipId: 'member-1',
    permissions: [VISIT_PERMISSIONS.VIEW_ASSIGNED],
  };

  function build(options: {
    required?: readonly string[];
    anyOf?: readonly string[];
    resolved?: unknown;
  }) {
    const reflector = {
      getAllAndOverride: (key: string) => {
        if (key === REQUIRED_PERMISSIONS_METADATA) {
          return options.required ?? [];
        }
        if (key === REQUIRED_ANY_PERMISSIONS_METADATA) {
          return options.anyOf ?? [];
        }
        return undefined;
      },
    } as unknown as Reflector;
    const authorization = {
      resolveMembershipForUserWithPermissions: vi.fn(
        async () => options.resolved ?? null,
      ),
      resolveMembershipForUserWithAnyPermission: vi.fn(
        async () => options.resolved ?? null,
      ),
    } as unknown as AuthorizationService;
    return {
      guard: new PermissionsGuard(reflector, authorization),
      authorization,
    };
  }

  function contextFor(request: PermissionedRequest): ExecutionContext {
    return {
      getHandler: () => 'handler',
      getClass: () => 'class',
      switchToHttp: () => ({ getRequest: () => request }),
    } as unknown as ExecutionContext;
  }

  function contextWithAuth(userId = 'user-1'): PermissionedRequest {
    return { auth: { userId } } as unknown as PermissionedRequest;
  }

  it('lets a route with no requirement through without asking for a membership', async () => {
    const { guard, authorization } = build({});
    const request = contextWithAuth();

    await expect(guard.canActivate(contextFor(request))).resolves.toBe(true);
    expect(
      authorization.resolveMembershipForUserWithAnyPermission,
    ).not.toHaveBeenCalled();
    expect(request.authorization).toBeUndefined();
  });

  it('admits a caller holding one of the capabilities an any-of route lists', async () => {
    const { guard, authorization } = build({
      anyOf: [
        CUSTOMER_PERMISSIONS.VIEW,
        VISIT_PERMISSIONS.VIEW_ASSIGNED,
      ],
      resolved: membership,
    });
    const request = contextWithAuth();

    await expect(guard.canActivate(contextFor(request))).resolves.toBe(true);
    expect(
      authorization.resolveMembershipForUserWithAnyPermission,
    ).toHaveBeenCalledWith('user-1', [
      CUSTOMER_PERMISSIONS.VIEW,
      VISIT_PERMISSIONS.VIEW_ASSIGNED,
    ]);
    // The resolved membership is what the controller reads the caller's scope from (`ADR-019` D2).
    expect(request.authorization).toEqual(membership);
  });

  it('keeps an all-of route asking for every code', async () => {
    const { guard, authorization } = build({
      required: [CUSTOMER_PERMISSIONS.VIEW],
      resolved: membership,
    });

    await expect(guard.canActivate(contextFor(contextWithAuth()))).resolves.toBe(
      true,
    );
    expect(
      authorization.resolveMembershipForUserWithPermissions,
    ).toHaveBeenCalledWith('user-1', [CUSTOMER_PERMISSIONS.VIEW]);
    expect(
      authorization.resolveMembershipForUserWithAnyPermission,
    ).not.toHaveBeenCalled();
  });

  it('refuses a caller no membership satisfies', async () => {
    const { guard } = build({
      anyOf: [VISIT_PERMISSIONS.VIEW_ASSIGNED],
      resolved: null,
    });

    await expect(
      guard.canActivate(contextFor(contextWithAuth())),
    ).rejects.toMatchObject({ status: HttpStatus.FORBIDDEN });
  });

  it('refuses an unauthenticated request before resolving any membership', async () => {
    const { guard, authorization } = build({
      anyOf: [VISIT_PERMISSIONS.VIEW_ASSIGNED],
      resolved: membership,
    });

    await expect(
      guard.canActivate(contextFor({} as PermissionedRequest)),
    ).rejects.toBeInstanceOf(AuthApiError);
    expect(
      authorization.resolveMembershipForUserWithAnyPermission,
    ).not.toHaveBeenCalled();
  });

  it('refuses a route that declares both shapes', async () => {
    // Two authorization questions on one route is a programming mistake, not a client error: the
    // second question belongs in the service (`ADR-019` D1), so the guard fails loudly rather than
    // picking a precedence no product decision defines (`BR-042`).
    const { guard, authorization } = build({
      required: [CUSTOMER_PERMISSIONS.VIEW],
      anyOf: [VISIT_PERMISSIONS.VIEW_ASSIGNED],
      resolved: membership,
    });

    await expect(
      guard.canActivate(contextFor(contextWithAuth())),
    ).rejects.toThrow(/never both/);
    expect(
      authorization.resolveMembershipForUserWithPermissions,
    ).not.toHaveBeenCalled();
    expect(
      authorization.resolveMembershipForUserWithAnyPermission,
    ).not.toHaveBeenCalled();
  });
});
