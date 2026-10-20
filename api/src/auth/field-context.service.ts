import { Injectable } from '@nestjs/common';
import type { FieldContextDto } from '@servora/shared-types';
import {
  AuthorizationService,
  type AuthorizedMembership,
} from './authorization.service.js';
import { fieldError } from './field-scope.js';
import {
  CUSTOMER_PERMISSIONS,
  JOB_PERMISSIONS,
  VISIT_PERMISSIONS,
} from './permissions.js';

@Injectable()
export class FieldContextService {
  constructor(private readonly authorization: AuthorizationService) {}
  async resolve(
    userId: string,
    expected?: FieldContextDto,
  ): Promise<AuthorizedMembership> {
    const memberships =
      await this.authorization.resolveMembershipsForUser(userId);
    if (memberships.length > 1)
      throw fieldError(409, 'FIELD_CONTEXT_AMBIGUOUS');
    const member = memberships[0];
    if (
      !member ||
      !member.permissions.includes(VISIT_PERMISSIONS.VIEW_ASSIGNED)
    ) {
      throw fieldError(403, 'FIELD_CONTEXT_UNAVAILABLE');
    }
    if (
      expected &&
      (expected.userId !== userId ||
        expected.organizationId !== member.organizationId ||
        expected.membershipId !== member.membershipId)
    ) {
      throw fieldError(403, 'FIELD_SCOPE_CHANGED');
    }
    // Scoped browser requests deliberately select field reach, including for mixed office/field members.
    return {
      ...member,
      permissions: member.permissions.filter(
        (code) =>
          code !== CUSTOMER_PERMISSIONS.VIEW && code !== JOB_PERMISSIONS.UPDATE,
      ),
      fieldScoped: true,
    };
  }
}
