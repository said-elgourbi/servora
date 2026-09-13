import {
  optionalBoolean,
  optionalText,
  requireEnum,
  requireText,
} from '../validation/domain-validation.js';
import {
  CUSTOMER_ADDRESS_TYPES,
  type CustomerAddress,
  type CustomerAddressType,
} from './customer.types.js';

export interface CustomerAddressDto {
  id: string;
  customerId: string;
  type: CustomerAddressType;
  addressLine1: string;
  addressLine2: string | null;
  city: string;
  province: string;
  postalCode: string;
  country: string;
  isDefault: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateCustomerAddressDto {
  type: CustomerAddressType;
  addressLine1: string;
  addressLine2?: string | null;
  city: string;
  province: string;
  postalCode: string;
  country: string;
  isDefault: boolean;
}

export function toCustomerAddressDto(
  address: CustomerAddress,
): CustomerAddressDto {
  return {
    id: address.id,
    customerId: address.customerId,
    type: address.type as CustomerAddressType,
    addressLine1: address.addressLine1,
    addressLine2: address.addressLine2,
    city: address.city,
    province: address.province,
    postalCode: address.postalCode,
    country: address.country,
    isDefault: address.isDefault,
    createdAt: address.createdAt.toISOString(),
    updatedAt: address.updatedAt.toISOString(),
  };
}

/** Validates untrusted input into a `CreateCustomerAddressDto`. */
export function parseCreateCustomerAddressDto(
  input: unknown,
): CreateCustomerAddressDto {
  const source = (input ?? {}) as Record<string, unknown>;
  return {
    type: requireEnum(source.type, CUSTOMER_ADDRESS_TYPES, 'type'),
    addressLine1: requireText(source.addressLine1, 'addressLine1', 255),
    addressLine2: optionalText(source.addressLine2, 'addressLine2', 255),
    city: requireText(source.city, 'city', 100),
    province: requireText(source.province, 'province', 100),
    postalCode: requireText(source.postalCode, 'postalCode', 20),
    country: requireText(source.country ?? 'Canada', 'country', 100),
    isDefault: optionalBoolean(source.isDefault, 'isDefault'),
  };
}
