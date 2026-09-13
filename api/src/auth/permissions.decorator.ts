import { SetMetadata } from '@nestjs/common';
import type { PermissionCode } from './permissions.js';

export const REQUIRED_PERMISSIONS_METADATA = 'servora:requiredPermissions';

export const RequirePermissions = (...permissions: PermissionCode[]) =>
  SetMetadata(REQUIRED_PERMISSIONS_METADATA, permissions);
