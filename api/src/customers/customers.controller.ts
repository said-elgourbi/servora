import {
  Body,
  Controller,
  Get,
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
import { CUSTOMER_PERMISSIONS, PROPERTY_PERMISSIONS } from '../auth/permissions.js';
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
import { parseCustomerListFilters } from './customer-list-filter.dto.js';
import { parseCreatePropertyDto } from './property.dto.js';
import {
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
  ): Promise<CustomerPropertyDto[]> {
    const scope = requireAuthorization(request);
    try {
      const summaries =
        await this.customers.findCustomerPropertiesInOrganization(scope, id);
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
