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
  Query,
  Req,
  UseGuards,
} from '@nestjs/common';
import { AuthApiError } from '../auth/auth-error.js';
import { AuthGuard } from '../auth/auth.guard.js';
import {
  CUSTOMER_CONTACT_PERMISSIONS,
  CUSTOMER_PERMISSIONS,
  PROPERTY_PERMISSIONS,
} from '../auth/permissions.js';
import { RequirePermissions } from '../auth/permissions.decorator.js';
import { PermissionsGuard } from '../auth/permissions.guard.js';
import type { PermissionedRequest } from '../auth/permissions.guard.js';
import {
  parseArchiveCustomerDto,
  parseCreateCustomerDto,
  parseUpdateCustomerDto,
  toCompanyCustomerDto,
  toCustomerDto,
  toCustomerSummaryDto,
  toIndividualCustomerDto,
} from './customer.dto.js';
import type { CustomerDto, CustomerSummaryDto } from './customer.dto.js';
import {
  toCustomerDetailDto,
  toCustomerJobDto,
  toCustomerPropertyDto,
} from './customer-detail.dto.js';
import type {
  CustomerDetailDto,
  CustomerJobDto,
  CustomerPropertyDto,
} from './customer-detail.dto.js';
import {
  parseCreateCustomerContactDto,
  parseRemoveCustomerContactDto,
  parseUpdateCustomerContactDto,
  toCustomerContactDto,
} from './customer-contact.dto.js';
import type { CustomerContactDto } from './customer-contact.dto.js';
import { parseCustomerListFilters } from './customer-list-filter.dto.js';
import {
  parseCreatePropertyDto,
  parsePropertyListFilters,
} from './property.dto.js';
import {
  ContactNotFoundError,
  ContactVersionConflictError,
  CustomerNotFoundError,
  CustomersService,
} from './customers.service.js';
import { DomainValidationError } from '../validation/domain-validation.js';

@Controller('customers')
@UseGuards(AuthGuard, PermissionsGuard)
export class CustomersController {
  constructor(private readonly customers: CustomersService) {}

  @Get()
  @RequirePermissions(CUSTOMER_PERMISSIONS.VIEW)
  async list(
    @Req() request: PermissionedRequest,
    @Query() query: unknown,
  ): Promise<CustomerSummaryDto[]> {
    const scope = requireAuthorization(request);
    const filters = parseInput(() => parseCustomerListFilters(query));
    const rows = await this.customers.listCustomersInOrganization(scope, {
      filters,
    });
    return rows.map((row) => toCustomerSummaryDto(row.customer, row));
  }

  @Get(':id')
  @RequirePermissions(CUSTOMER_PERMISSIONS.VIEW)
  async findOne(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
  ): Promise<CustomerDetailDto> {
    const scope = requireAuthorization(request);
    const customer = await this.customers.findCustomerDetailInOrganization(
      scope,
      id,
    );
    if (customer === null) {
      throw notFound();
    }
    return toCustomerDetailDto(customer);
  }

  @Get(':id/properties')
  @RequirePermissions(PROPERTY_PERMISSIONS.VIEW)
  async properties(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Query() query: unknown,
  ): Promise<CustomerPropertyDto[]> {
    const scope = requireAuthorization(request);
    // Archived Properties are excluded by default; a client asks for them explicitly with
    // `?status=ARCHIVED` or `?status=ALL` (`BR-082`, `BR-083`).
    const filters = parseInput(() => parsePropertyListFilters(query));
    try {
      const summaries =
        await this.customers.findCustomerPropertiesInOrganization(
          scope,
          id,
          filters,
        );
      return summaries.map(toCustomerPropertyDto);
    } catch (error) {
      if (error instanceof CustomerNotFoundError) {
        throw notFound();
      }
      throw error;
    }
  }

  @Get(':id/jobs')
  @RequirePermissions(CUSTOMER_PERMISSIONS.VIEW)
  async jobs(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
  ): Promise<CustomerJobDto[]> {
    const scope = requireAuthorization(request);
    try {
      const summaries = await this.customers.findCustomerJobsInOrganization(
        scope,
        id,
      );
      return summaries.map(toCustomerJobDto);
    } catch (error) {
      if (error instanceof CustomerNotFoundError) {
        throw notFound();
      }
      throw error;
    }
  }

  @Post(':id/properties')
  @RequirePermissions(PROPERTY_PERMISSIONS.CREATE)
  async createProperty(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Body() body: unknown,
  ): Promise<CustomerPropertyDto> {
    const scope = requireAuthorization(request);
    const input = parseInput(() => parseCreatePropertyDto(body));
    try {
      const summary = await this.customers.createPropertyForCustomer(
        scope,
        id,
        input,
      );
      return toCustomerPropertyDto(summary);
    } catch (error) {
      if (error instanceof CustomerNotFoundError) {
        throw notFound();
      }
      throw error;
    }
  }

  @Post()
  @RequirePermissions(CUSTOMER_PERMISSIONS.CREATE)
  async create(@Req() request: PermissionedRequest, @Body() body: unknown) {
    const scope = requireAuthorization(request);
    const input = parseInput(() => parseCreateCustomerDto(body));
    const created =
      input.type === 'INDIVIDUAL'
        ? await this.customers.createIndividualCustomer(
            scope.organizationId,
            input,
          )
        : await this.customers.createCompanyCustomer(
            scope.organizationId,
            input,
          );
    return toCustomerDetailsDto(created);
  }

  @Patch(':id')
  @RequirePermissions(CUSTOMER_PERMISSIONS.EDIT)
  async patch(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Body() body: unknown,
  ) {
    return this.update(request, id, body);
  }

  @Put(':id')
  @RequirePermissions(CUSTOMER_PERMISSIONS.EDIT)
  async put(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Body() body: unknown,
  ) {
    return this.update(request, id, body);
  }

  @Post(':id/archive')
  @RequirePermissions(CUSTOMER_PERMISSIONS.ARCHIVE)
  async archive(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Body() body: unknown,
  ): Promise<CustomerDto> {
    const scope = requireAuthorization(request);
    const input = parseInput(() => parseArchiveCustomerDto(body));
    try {
      return toCustomerDto(
        await this.customers.archiveCustomer(scope, id, input),
      );
    } catch (error) {
      if (error instanceof CustomerNotFoundError) {
        throw notFound();
      }
      throw error;
    }
  }

  /**
   * Adds a contact person to a customer the caller's organization owns (`BR-023`, `BR-095`).
   *
   * Authorization is `customers.contacts.create`, the contact capability set's own code
   * (`ADR-022` D4). `customers.edit` deliberately no longer authorizes a contact write: a role may
   * maintain a customer without being trusted to change the people the organization calls
   * (`BR-006`, `BR-085`'s Property position).
   *
   * A create that states `isPrimary` clears the customer's previous primary in the same transaction,
   * so the customer never holds two primary contacts (`BR-095`).
   *
   * A customer the caller's organization does not own is `404 CUSTOMER_NOT_FOUND`, never `403`
   * (`BR-001`).
   */
  @Post(':id/contacts')
  @RequirePermissions(CUSTOMER_CONTACT_PERMISSIONS.CREATE)
  async addContact(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Body() body: unknown,
  ): Promise<CustomerContactDto> {
    const scope = requireAuthorization(request);
    const input = parseInput(() => parseCreateCustomerContactDto(body));
    try {
      return toCustomerContactDto(
        await this.customers.addContact(scope, id, input),
      );
    } catch (error) {
      throw mapContactError(error);
    }
  }

  /**
   * Edits a contact person of a customer the caller's organization owns (`BR-095`).
   *
   * The edit is **partial** — a field the caller leaves out keeps the value the contact holds — and
   * it requires `expectedVersion`, the version the caller read. A mutation naming a version the
   * contact has left is `409 CONTACT_VERSION_CONFLICT` rather than an applied write (`BR-032`).
   * Making the contact primary clears the previous primary in the same transaction, so two contacts
   * are never primary and the customer is never briefly without one.
   */
  @Patch(':id/contacts/:contactId')
  @RequirePermissions(CUSTOMER_CONTACT_PERMISSIONS.EDIT)
  async updateContact(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Param('contactId') contactId: string,
    @Body() body: unknown,
  ): Promise<CustomerContactDto> {
    const scope = requireAuthorization(request);
    const input = parseInput(() => parseUpdateCustomerContactDto(body));
    try {
      return toCustomerContactDto(
        await this.customers.updateContact(scope, id, contactId, input),
      );
    } catch (error) {
      throw mapContactError(error);
    }
  }

  /**
   * Removes a contact person from ordinary use (`BR-095`).
   *
   * The removal is **soft**: the record, the acting member and the moment survive so the question
   * "who removed this person, and when" stays answerable (`BR-033`; `ADR-022` D8). No reason is
   * required. The caller states the version it read, exactly as an edit does, and a version the
   * contact has left is `409 CONTACT_VERSION_CONFLICT`.
   */
  @Delete(':id/contacts/:contactId')
  @HttpCode(HttpStatus.NO_CONTENT)
  @RequirePermissions(CUSTOMER_CONTACT_PERMISSIONS.REMOVE)
  async removeContact(
    @Req() request: PermissionedRequest,
    @Param('id') id: string,
    @Param('contactId') contactId: string,
    @Body() body: unknown,
  ): Promise<void> {
    const scope = requireAuthorization(request);
    const input = parseInput(() => parseRemoveCustomerContactDto(body));
    try {
      await this.customers.removeContact(scope, id, contactId, input);
    } catch (error) {
      throw mapContactError(error);
    }
  }

  private async update(
    request: PermissionedRequest,
    id: string,
    body: unknown,
  ) {
    const scope = requireAuthorization(request);
    const input = parseInput(() => parseUpdateCustomerDto(body));
    try {
      return toCustomerDetailsDto(
        await this.customers.updateCustomer(scope, id, input),
      );
    } catch (error) {
      if (error instanceof CustomerNotFoundError) {
        throw notFound();
      }
      // The service rejects an edit the request shape alone cannot judge — a subtype payload that
      // contradicts the customer's stored type (`BR-042`, `BR-087`) — as a domain validation failure.
      if (error instanceof DomainValidationError) {
        throw AuthApiError.validationFailed(error.issues.join('; '));
      }
      throw error;
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

function toCustomerDetailsDto(
  customer:
    | Parameters<typeof toIndividualCustomerDto>[0]
    | Parameters<typeof toCompanyCustomerDto>[0],
) {
  return 'individual' in customer
    ? toIndividualCustomerDto(customer)
    : toCompanyCustomerDto(customer);
}

function notFound(): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.NOT_FOUND,
      code: 'CUSTOMER_NOT_FOUND',
      message: 'Customer was not found.',
    },
    HttpStatus.NOT_FOUND,
  );
}

/** Maps a contact write's domain failure onto the HTTP contract (`dev.md` §7). */
function mapContactError(error: unknown): unknown {
  if (error instanceof CustomerNotFoundError) {
    return notFound();
  }
  if (error instanceof ContactNotFoundError) {
    return contactNotFound();
  }
  if (error instanceof ContactVersionConflictError) {
    return contactVersionConflict(error.currentVersion);
  }
  return error;
}

function contactNotFound(): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.NOT_FOUND,
      code: 'CONTACT_NOT_FOUND',
      message: 'Contact was not found for this customer.',
    },
    HttpStatus.NOT_FOUND,
  );
}

function contactVersionConflict(currentVersion: number): HttpException {
  return new HttpException(
    {
      statusCode: HttpStatus.CONFLICT,
      code: 'CONTACT_VERSION_CONFLICT',
      message:
        'The contact changed since it was read. Re-read it and try again.',
      details: { currentVersion },
    },
    HttpStatus.CONFLICT,
  );
}
