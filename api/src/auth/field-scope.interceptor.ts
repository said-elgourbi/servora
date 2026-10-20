import {
  Injectable,
  type NestInterceptor,
  type ExecutionContext,
  type CallHandler,
} from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { defer, lastValueFrom } from 'rxjs';
import { and, eq } from 'drizzle-orm';
import { DatabaseService } from '../database/database.service.js';
import { organizationMembers, visits } from '../database/schema.js';
import { AuthApiError } from './auth-error.js';
import { FieldContextService } from './field-context.service.js';
import { FIELD_SCOPED, fieldError, readFieldScope } from './field-scope.js';
import type { PermissionedRequest } from './permissions.guard.js';
import {
  REQUIRED_ANY_PERMISSIONS_METADATA,
  REQUIRED_PERMISSIONS_METADATA,
} from './permissions.decorator.js';
import { AuthorizationService } from './authorization.service.js';
import type { PermissionCode } from './permissions.js';

@Injectable()
export class FieldScopeInterceptor implements NestInterceptor {
  constructor(
    private readonly database: DatabaseService,
    private readonly fields: FieldContextService,
    private readonly reflector: Reflector,
    private readonly authorization: AuthorizationService,
  ) {}
  intercept(context: ExecutionContext, next: CallHandler) {
    const request = context.switchToHttp().getRequest<PermissionedRequest>();
    const scope = readFieldScope(request.headers);
    if (!scope) return next.handle();
    context
      .switchToHttp()
      .getResponse<{ setHeader(name: string, value: string): void }>()
      .setHeader('Cache-Control', 'no-store');
    const handlers = [context.getHandler(), context.getClass()];
    if (!this.reflector.getAllAndOverride<boolean>(FIELD_SCOPED, handlers))
      throw fieldError(400, 'FIELD_SCOPE_UNSUPPORTED');
    const params = context
      .switchToHttp()
      .getRequest<{ params?: Record<string, string>; method?: string }>();
    const routeParams = params.params;
    const lockMode = params.method === 'GET' ? 'share' : 'update';
    const auth = request.auth;
    if (!auth) throw AuthApiError.unauthenticated();
    return defer(() =>
      this.database.withFieldTransaction(async () => {
        // A membership change cannot commit between verification and this handler's mutation.
        await this.database.db
          .select({ id: organizationMembers.id })
          .from(organizationMembers)
          .where(eq(organizationMembers.userId, auth.userId))
          .for('share');
        const member = await this.fields.resolve(auth.userId, scope);
        const required =
          this.reflector.getAllAndOverride<readonly PermissionCode[]>(
            REQUIRED_PERMISSIONS_METADATA,
            handlers,
          ) ?? [];
        const any =
          this.reflector.getAllAndOverride<readonly PermissionCode[]>(
            REQUIRED_ANY_PERMISSIONS_METADATA,
            handlers,
          ) ?? [];
        if (
          !this.authorization.hasAllPermissions(member, required) ||
          (any.length > 0 && !this.authorization.hasAnyPermission(member, any))
        )
          throw AuthApiError.forbidden();
        if (routeParams?.['id']) {
          // Assignment changes take the Visit row lock; hold it across field verification and execution.
          await this.database.db
            .select({ id: visits.id })
            .from(visits)
            .where(
              and(
                eq(visits.organizationId, member.organizationId),
                eq(visits.jobId, routeParams['id']),
              ),
            )
            .orderBy(visits.id)
            .for(lockMode);
        }
        request.authorization = member;
        return lastValueFrom(next.handle());
      }),
    );
  }
}
