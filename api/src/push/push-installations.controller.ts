import {
  Body,
  Controller,
  Delete,
  Get,
  HttpCode,
  Param,
  Put,
  Req,
  UseGuards,
} from '@nestjs/common';
import { AuthGuard, type AuthenticatedRequest } from '../auth/auth.guard.js';
import { AuthApiError } from '../auth/auth-error.js';
import {
  parseInstallationId,
  parseInstallationSecret,
  parsePushRegistration,
} from './push-registration.dto.js';
import { PushInstallationsService } from './push-installations.service.js';

@Controller('push/installations')
@UseGuards(AuthGuard)
export class PushInstallationsController {
  constructor(private readonly installations: PushInstallationsService) {}

  @Put(':installationId')
  register(
    @Param('installationId') id: string,
    @Req() request: AuthenticatedRequest,
    @Body() body: unknown,
  ) {
    return this.installations.register(
      requireAuth(request),
      parseInstallationId(id),
      parseInstallationSecret(request.headers['x-push-installation-secret']),
      parsePushRegistration(body),
    );
  }

  @Get(':installationId')
  get(
    @Param('installationId') id: string,
    @Req() request: AuthenticatedRequest,
  ) {
    return this.installations.get(
      requireAuth(request),
      parseInstallationId(id),
      parseInstallationSecret(request.headers['x-push-installation-secret']),
    );
  }

  @Delete(':installationId')
  @HttpCode(204)
  unregister(
    @Param('installationId') id: string,
    @Req() request: AuthenticatedRequest,
  ) {
    return this.installations.unregister(
      requireAuth(request),
      parseInstallationId(id),
      parseInstallationSecret(request.headers['x-push-installation-secret']),
    );
  }
}
function requireAuth(request: AuthenticatedRequest) {
  if (!request.auth) throw AuthApiError.unauthenticated();
  return request.auth;
}
