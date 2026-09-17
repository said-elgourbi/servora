import { SetMetadata } from '@nestjs/common';
import type { PermissionCode } from './permissions.js';

export const REQUIRED_PERMISSIONS_METADATA = 'servora:requiredPermissions';

/**
 * Declares that a route requires **every** listed capability. This is the default shape.
 */
export const RequirePermissions = (...permissions: PermissionCode[]) =>
  SetMetadata(REQUIRED_PERMISSIONS_METADATA, permissions);

export const REQUIRED_ANY_PERMISSIONS_METADATA =
  'servora:requiredAnyPermissions';

/**
 * Declares that a route accepts **any one** of the listed capabilities.
 *
 * `BR-009` gives a technician a read of their assigned visits while `BR-008` gives the office a read of
 * every Job, and `BR-006` authorizes actions by capability rather than by role name. A route both
 * audiences reach therefore has to say "either of these two", which an all-of list cannot express —
 * this decorator is that statement and is answered by the same guard (`ADR-019` D1).
 *
 * A route declares either this or `@RequirePermissions`, **never both**: a route with two authorization
 * questions asks the second one in its own service, as the activity read's `includeRemovedEvidence`
 * flag does. Declaring both is refused rather than resolved by a precedence rule no product decision
 * defines (`BR-042`).
 */
export const RequireAnyPermission = (...permissions: PermissionCode[]) =>
  SetMetadata(REQUIRED_ANY_PERMISSIONS_METADATA, permissions);
