import {
  optionalInstant,
  optionalPositiveInteger,
  optionalText,
  optionalUuid,
  requireEnum,
  requireText,
} from '../validation/domain-validation.js';
import type { Property } from './customer.types.js';

/**
 * The Canadian province/territory codes the API exchanges (`BR-049`).
 *
 * A Property address is structured data (`BR-049`), and the province is exchanged as a stable,
 * machine-readable code rather than as a localized display label (`BR-028`, `BR-041`, `dev.md` §8).
 * The vocabulary is the one the approved design and the customer detail contract already use; a
 * client presents its own label for a code and never sends one.
 */
export const CANADIAN_PROVINCE_CODES = [
  'AB',
  'BC',
  'MB',
  'NB',
  'NL',
  'NS',
  'NT',
  'NU',
  'ON',
  'PE',
  'QC',
  'SK',
  'YT',
] as const;

export type CanadianProvinceCode = (typeof CANADIAN_PROVINCE_CODES)[number];

/**
 * The country a Property is stored with.
 *
 * Servora currently operates in Canada only, so the country is not a client field: it is the
 * existing value the address model already carries (`properties.country`, default `Canada`) and is
 * written here on behalf of every Property. It is never asked for and never accepted from the
 * client.
 */
export const PROPERTY_COUNTRY = 'Canada';

/** The fields a caller supplies to create an organization-owned Property (`BR-049`). */
export interface CreatePropertyDto {
  name?: string | null;
  addressLine1: string;
  addressLine2?: string | null;
  city: string;
  province: CanadianProvinceCode;
  postalCode: string;
  notes?: string | null;
}

/** Validates untrusted input into a `CreatePropertyDto`. */
export function parseCreatePropertyDto(input: unknown): CreatePropertyDto {
  const source = (input ?? {}) as Record<string, unknown>;
  return {
    name: optionalText(source.name, 'name', 255),
    addressLine1: requireText(source.addressLine1, 'addressLine1', 255),
    addressLine2: optionalText(source.addressLine2, 'addressLine2', 255),
    city: requireText(source.city, 'city', 100),
    province: requireEnum(source.province, CANADIAN_PROVINCE_CODES, 'province'),
    postalCode: requireText(source.postalCode, 'postalCode', 20),
    notes: optionalText(source.notes, 'notes', 4000),
  };
}

/**
 * The Property lifecycle state (`BR-082`).
 *
 * `ACTIVE` is available for new Jobs and shown in the normal lists and selectors; `ARCHIVED` is out
 * of active use but stays a retrievable record. The vocabulary is closed and shared (`BR-041`).
 */
export const PROPERTY_STATUSES = ['ACTIVE', 'ARCHIVED'] as const;
export type PropertyStatus = (typeof PROPERTY_STATUSES)[number];

/**
 * The Property-list lifecycle filter a client may send.
 *
 * `ALL` means "no constraint on this dimension"; it is a query value, not a stored Property state.
 * The default is `ACTIVE`, because archiving is how a Property leaves active use and an archived
 * Property must be requested explicitly (`BR-082`, `BR-083`).
 */
export const PROPERTY_STATUS_FILTERS = ['ALL', ...PROPERTY_STATUSES] as const;
export type PropertyStatusFilter = (typeof PROPERTY_STATUS_FILTERS)[number];

export interface PropertyListFilters {
  readonly status: PropertyStatusFilter;
}

/** The filter applied when the request names no Property lifecycle state. */
export const DEFAULT_PROPERTY_LIST_FILTERS: PropertyListFilters = {
  status: 'ACTIVE',
};

/**
 * Reads a single query value, so a repeated parameter still validates as a scalar.
 *
 * Mirrors the customer-list filter parser: an unknown value fails validation rather than being
 * ignored, so a client cannot silently receive a different set than it asked for.
 */
function firstQueryValue(value: unknown): unknown {
  return Array.isArray(value) ? value[0] : value;
}

/** Validates untrusted query input into a Property-list filter. */
export function parsePropertyListFilters(input: unknown): PropertyListFilters {
  const source = (input ?? {}) as Record<string, unknown>;
  const status = firstQueryValue(source.status);
  return {
    status:
      status === undefined
        ? DEFAULT_PROPERTY_LIST_FILTERS.status
        : requireEnum(status, PROPERTY_STATUS_FILTERS, 'status'),
  };
}

/**
 * The fields a caller supplies to edit a Property (`BR-084`).
 *
 * An edit replaces the same authoritative fields a create supplies, so the Add Property form is
 * reused unchanged. `expectedVersion` is the version the client last saw: when it is supplied and
 * the Property has moved on, the mutation is rejected rather than applied (`BR-086`).
 */
export interface UpdatePropertyDto extends CreatePropertyDto {
  expectedVersion?: number;
}

/** Validates untrusted input into an `UpdatePropertyDto`. */
export function parseUpdatePropertyDto(input: unknown): UpdatePropertyDto {
  const source = (input ?? {}) as Record<string, unknown>;
  const update: UpdatePropertyDto = parseCreatePropertyDto(source);
  const expectedVersion = optionalPositiveInteger(
    source.expectedVersion,
    'expectedVersion',
  );
  if (expectedVersion !== null) {
    update.expectedVersion = expectedVersion;
  }
  return update;
}

/**
 * The fields an archive or restore carries (`BR-086`).
 *
 * A reason is not required by any rule, so `note` is optional. `clientOperationId` is the offline
 * replay's idempotency key and `capturedAt` is the device time, kept for display only; both exist so
 * a queued offline archive/restore can be replayed without applying it twice (`BR-031`).
 * `expectedVersion` is the optimistic-concurrency guard (`BR-086`).
 */
export interface PropertyLifecycleDto {
  note?: string | null;
  clientOperationId?: string | null;
  capturedAt?: string | null;
  expectedVersion?: number;
}

/** Validates untrusted input into a `PropertyLifecycleDto`; archive and restore share its shape. */
export function parsePropertyLifecycleDto(
  input: unknown,
): PropertyLifecycleDto {
  const source = (input ?? {}) as Record<string, unknown>;
  return {
    note: optionalText(source.note, 'note', 1000),
    clientOperationId: optionalUuid(
      source.clientOperationId,
      'clientOperationId',
    ),
    capturedAt: optionalInstant(source.capturedAt, 'capturedAt'),
    expectedVersion:
      optionalPositiveInteger(source.expectedVersion, 'expectedVersion') ??
      undefined,
  };
}

/**
 * The authoritative open-work counts the archive confirmation displays (`BR-083`).
 *
 * `activeJobCount` counts the Property's Jobs whose status is not terminal (`BR-058`);
 * `activeVisitCount` counts the Visits of those Jobs whose status is not `COMPLETED`, `CANCELED` or
 * `NO_SHOW` (`BR-074`). This is the `archiveWarningOpenWork` classification, and it is deliberately
 * not the derived `needsSchedulingActiveVisit` set of `BR-060`.
 */
export interface PropertyArchiveImpactDto {
  activeJobCount: number;
  activeVisitCount: number;
}

/**
 * One Property addressed by its own identifier, with the derived values the lifecycle screens use.
 *
 * `jobCount` and `lastServiceAt` are the same derived projections a customer-detail row carries
 * (`BR-081`). `canBePermanentlyDeleted` is the API's answer to whether `BR-082`'s precondition holds
 * right now; a client must not compute it locally (`BR-001`, `BR-007`). Nothing here is stored: every
 * derived value comes from the authoritative tables (`BR-080`).
 */
export interface PropertyDetailDto {
  id: string;
  name: string | null;
  addressLine1: string;
  addressLine2: string | null;
  city: string;
  province: string;
  postalCode: string;
  country: string;
  notes: string | null;
  status: PropertyStatus;
  version: number;
  archivedAt: string | null;
  jobCount: number;
  lastServiceAt: string | null;
  archiveImpact: PropertyArchiveImpactDto;
  canBePermanentlyDeleted: boolean;
}

/** A Property plus the derived values a lifecycle screen renders. */
export interface PropertyDetail {
  readonly property: Property;
  /** Jobs associated with the Property, whatever their status (`BR-081`). */
  readonly jobCount: number;
  /** Scheduled start of the most recent `COMPLETED` Visit, or `null` (`BR-081`). */
  readonly lastServiceAt: Date | null;
  /** Jobs of the Property that are not terminal (`BR-083`). */
  readonly activeJobCount: number;
  /** Visits of those Jobs that are not terminal (`BR-083`). */
  readonly activeVisitCount: number;
  /** Whether `BR-082`'s permanent-deletion precondition currently holds. */
  readonly canBePermanentlyDeleted: boolean;
}

export function toPropertyDetailDto(detail: PropertyDetail): PropertyDetailDto {
  const property = detail.property;
  return {
    id: property.id,
    name: property.name,
    addressLine1: property.addressLine1,
    addressLine2: property.addressLine2,
    city: property.city,
    province: property.province,
    postalCode: property.postalCode,
    country: property.country,
    notes: property.notes,
    status: property.status as PropertyStatus,
    version: property.version,
    archivedAt: property.archivedAt?.toISOString() ?? null,
    jobCount: detail.jobCount,
    lastServiceAt: detail.lastServiceAt?.toISOString() ?? null,
    archiveImpact: {
      activeJobCount: detail.activeJobCount,
      activeVisitCount: detail.activeVisitCount,
    },
    canBePermanentlyDeleted: detail.canBePermanentlyDeleted,
  };
}
