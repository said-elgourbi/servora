import {
  optionalText,
  requireEnum,
  requireText,
} from '../validation/domain-validation.js';

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
