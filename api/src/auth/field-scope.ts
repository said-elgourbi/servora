import { HttpException, SetMetadata } from '@nestjs/common';
import type {
  FieldContextDto,
  FieldScopeErrorCode,
} from '@servora/shared-types';
import type { IncomingHttpHeaders } from 'node:http';

export const FIELD_SCOPED = 'servora:field-scoped';
export const FieldScoped = () => SetMetadata(FIELD_SCOPED, true);
export function fieldError(
  status: number,
  code: FieldScopeErrorCode,
): HttpException {
  return new HttpException({ statusCode: status, code, message: code }, status);
}
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export function readFieldScope(
  headers: IncomingHttpHeaders,
): FieldContextDto | null {
  const values = ['user', 'organization', 'membership'].map(
    (key) => headers[`x-servora-field-${key}`],
  );
  if (values.every((value) => value === undefined)) return null;
  if (
    !values.every(
      (value): value is string => typeof value === 'string' && uuid.test(value),
    )
  ) {
    throw fieldError(400, 'FIELD_SCOPE_INVALID');
  }
  return {
    userId: values[0]!.toLowerCase(),
    organizationId: values[1]!.toLowerCase(),
    membershipId: values[2]!.toLowerCase(),
  };
}
