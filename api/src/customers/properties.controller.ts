import {
  Body,
  Controller,
  Delete,
  Get,
  HttpCode,
  HttpException,
  HttpStatus,
  Param,
  Patch,
  Post,
  Put,
  Req,
  UseGuards,
} from '@nestjs/common';
import { AuthApiError } from '../auth/auth-error.js';
import { AuthGuard } from '../auth/auth.guard.js';
import { PROPERTY_PERMISSIONS } from '../auth/permissions.js';
import { RequirePermissions } from '../auth/permissions.decorator.js';
import { PermissionsGuard } from '../auth/permissions.guard.js';
import type { PermissionedRequest } from '../auth/permissions.guard.js';
import { DomainValidationError } from '../validation/domain-validation.js';
import {
  parsePropertyLifecycleDto,
  parseUpdatePropertyDto,
  toPropertyDetailDto,
} from './property.dto.js';
import type { PropertyDetail, PropertyDetailDto } from './property.dto.js';
import {
  PropertiesService,
  PropertyHasReferencesError,
  PropertyNotFoundError,
  PropertyVersionConflictError,
} from './properties.service.js';

/**
 * The Property lifecycle routes (`BR-082` – `BR-086`).
 *
 * They address one Property under the Customer the client is working with, matching the existing
 * `POST /customers/:id/properties` create route. Every route requires its own Property capability
 * (`BR-085`), and the backend resolves the tenant, the Customer context and the lifecycle state
 * (`BR-001`, `BR-007`).
 */
@Controller('customers/:customerId/properties')
@UseGuards(AuthGuard, PermissionsGuard)
export class PropertiesController {
  constructor(private readonly properties: PropertiesService) {}

  /** One Property, `ACTIVE` or `ARCHIVED`, with its derived values (`properties.view`). */
  @Get(':propertyId')
  @RequirePermissions(PROPERTY_PERMISSIONS.VIEW)
  async findOne(
    @Req() request: PermissionedRequest,
    @Param('customerId') customerId: string,
    @Param('propertyId') propertyId: string,
  ): Promise<PropertyDetailDto> {
    const scope = requireAuthorization(request);
    const detail = await this.properties.findPropertyDetailInOrganization(
      scope,
      customerId,
      propertyId,
    );
    if (detail === null) {
      throw propertyNotFound();
    }
    return toPropertyDetailDto(detail);
  }

  @Patch(':propertyId')
  @RequirePermissions(PROPERTY_PERMISSIONS.EDIT)
  async patch(
    @Req() request: PermissionedRequest,
    @Param('customerId') customerId: string,
    @Param('propertyId') propertyId: string,
    @Body() body: unknown,
  ): Promise<PropertyDetailDto> {
    return this.update(request, customerId, propertyId, body);
  }

  @Put(':propertyId')
  @RequirePermissions(PROPERTY_PERMISSIONS.EDIT)
  async put(
    @Req() request: PermissionedRequest,
    @Param('customerId') customerId: string,
    @Param('propertyId') propertyId: string,
    @Body() body: unknown,
  ): Promise<PropertyDetailDto> {
    return this.update(request, customerId, propertyId, body);
  }

  /** Archives a Property; idempotent when it is already archived (`properties.archive`). */
  @Post(':propertyId/archive')
  @HttpCode(HttpStatus.OK)
  @RequirePermissions(PROPERTY_PERMISSIONS.ARCHIVE)
  async archive(
    @Req() request: PermissionedRequest,
    @Param('customerId') customerId: string,
    @Param('propertyId') propertyId: string,
    @Body() body: unknown,
  ): Promise<PropertyDetailDto> {
    const scope = requireAuthorization(request);
    const input = parseInput(() => parsePropertyLifecycleDto(body));
    return this.lifecycle(() =>
      this.properties.archiveProperty(scope, customerId, propertyId, input),
    );
  }

  /** Restores an archived Property; idempotent when it is already active (`properties.archive`). */
  @Post(':propertyId/restore')
  @HttpCode(HttpStatus.OK)
  @RequirePermissions(PROPERTY_PERMISSIONS.ARCHIVE)
  async restore(
    @Req() request: PermissionedRequest,
    @Param('customerId') customerId: string,
    @Param('propertyId') propertyId: string,
    @Body() body: unknown,
  ): Promise<PropertyDetailDto> {
    const scope = requireAuthorization(request);
    const input = parseInput(() => parsePropertyLifecycleDto(body));
    return this.lifecycle(() =>
      this.properties.restoreProperty(scope, customerId, propertyId, input),
    );
  }

  /**
   * Permanently deletes an unreferenced Property (`properties.delete`).
   *
   * Answers `409 PROPERTY_HAS_REFERENCES` when any business record references the Property, so a
   * client can explain the impact and offer archiving instead (`BR-082`). Nothing is deleted by
   * cascade.
   */
  @Delete(':propertyId')
  @HttpCode(HttpStatus.NO_CONTENT)
  @RequirePermissions(PROPERTY_PERMISSIONS.DELETE)
  async remove(
    @Req() request: PermissionedRequest,
    @Param('customerId') customerId: string,
    @Param('propertyId') propertyId: string,
  ): Promise<void> {
    const scope = requireAuthorization(request);
    try {
      await this.properties.deleteProperty(scope, customerId, propertyId);
    } catch (error) {
      throw mapLifecycleError(error);
    }
  }

  private async update(
    request: PermissionedRequest,
    customerId: string,
    propertyId: string,
    body: unknown,
  ): Promise<PropertyDetailDto> {
    const scope = requireAuthorization(request);
    const input = parseInput(() => parseUpdatePropertyDto(body));
    return this.lifecycle(() =>
      this.properties.updateProperty(scope, customerId, propertyId, input),
    );
  }

  /** Runs one lifecycle mutation and maps its domain outcome onto the HTTP contract. */
  private async lifecycle(
    action: () => Promise<PropertyDetail>,
  ): Promise<PropertyDetailDto> {
    try {
      return toPropertyDetailDto(await action());
    } catch (error) {
      throw mapLifecycleError(error);
    }
  }
}

function requireAuthorization(request: PermissionedRequest) {
  if (request.authorization === undefined) {
    throw AuthApiError.unauthenticated();
  }
  return request.authorization;
}

function parseInput<T>(parser: () => T): T {
  try {
    return parser();
  } catch (error) {
    if (error instanceof DomainValidationError) {
      throw AuthApiError.validationFailed(error.issues.join('; '));
    }
    throw error;
  }
}

/** Maps a lifecycle domain failure onto the HTTP contract (`dev.md` §7). */
function mapLifecycleError(error: unknown): unknown {
  if (error instanceof PropertyNotFoundError) {
    return propertyNotFound();
  }
  if (error instanceof PropertyVersionConflictError) {
    return versionConflict(error.currentVersion);
  }
  if (error instanceof PropertyHasReferencesError) {
    return referencesConflict(error.referenceKinds);
  }
  return error;
}

function propertyNotFound(): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.NOT_FOUND,
      code: 'PROPERTY_NOT_FOUND',
      message: 'Property was not found.',
    },
    HttpStatus.NOT_FOUND,
  );
}

function versionConflict(currentVersion: number): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.CONFLICT,
      code: 'PROPERTY_VERSION_CONFLICT',
      message:
        'The property changed since it was read. Re-read it and try again.',
      details: { currentVersion },
    },
    HttpStatus.CONFLICT,
  );
}

function referencesConflict(referenceKinds: readonly string[]): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.CONFLICT,
      code: 'PROPERTY_HAS_REFERENCES',
      message:
        'The property has history and cannot be deleted. Archive it instead.',
      details: { referenceKinds: [...referenceKinds] },
    },
    HttpStatus.CONFLICT,
  );
}
