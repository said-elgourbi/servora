import { Controller, Get, Header, Req, UseGuards } from '@nestjs/common';
import type { FieldContextDto } from '@servora/shared-types';
import { AuthGuard, type AuthenticatedRequest } from './auth.guard.js';
import { AuthApiError } from './auth-error.js';
import { FieldContextService } from './field-context.service.js';
@Controller('auth')
@UseGuards(AuthGuard)
export class FieldContextController {
  constructor(private readonly fields: FieldContextService) {}
  @Get('field-context')
  @Header('Cache-Control', 'no-store')
  async read(@Req() request: AuthenticatedRequest): Promise<FieldContextDto> {
    if (!request.auth) throw AuthApiError.unauthenticated();
    const member = await this.fields.resolve(request.auth.userId);
    return {
      userId: request.auth.userId,
      organizationId: member.organizationId,
      membershipId: member.membershipId,
    };
  }
}
