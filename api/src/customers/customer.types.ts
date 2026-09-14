import type { InferInsertModel, InferSelectModel } from 'drizzle-orm';
import {
  customerAddresses,
  customerCompanies,
  customerContacts,
  customerIndividuals,
  customerLifecycleHistory,
  customers,
  properties,
} from '../database/schema.js';

// Stable, machine-readable codes. Never persist localized display labels.
export const CUSTOMER_TYPES = ['INDIVIDUAL', 'COMPANY'] as const;
export type CustomerType = (typeof CUSTOMER_TYPES)[number];

/** The Customer lifecycle events the backend records (`BR-087`). */
export const CUSTOMER_LIFECYCLE_ACTIONS = ['TYPE_CONVERTED'] as const;
export type CustomerLifecycleAction =
  (typeof CUSTOMER_LIFECYCLE_ACTIONS)[number];

export const CUSTOMER_STATUSES = ['ACTIVE', 'INACTIVE'] as const;
export type CustomerStatus = (typeof CUSTOMER_STATUSES)[number];

export const CUSTOMER_PREFERRED_CONTACT_METHODS = [
  'EMAIL',
  'PHONE',
  'SMS',
  'NONE',
] as const;
export type CustomerPreferredContactMethod =
  (typeof CUSTOMER_PREFERRED_CONTACT_METHODS)[number];

export const CUSTOMER_LANGUAGES = ['en-CA', 'fr-CA'] as const;
export type CustomerLanguage = (typeof CUSTOMER_LANGUAGES)[number];

export const CUSTOMER_ADDRESS_TYPES = ['SERVICE', 'BILLING', 'OTHER'] as const;
export type CustomerAddressType = (typeof CUSTOMER_ADDRESS_TYPES)[number];

/**
 * A customer is a customer of exactly one organization. `type` selects which
 * 1–0..1 subtype table (`customer_individuals` or `customer_companies`) must
 * contain the matching record.
 */
export type Customer = InferSelectModel<typeof customers>;
export type NewCustomer = InferInsertModel<typeof customers>;

export type CustomerIndividual = InferSelectModel<typeof customerIndividuals>;
export type NewCustomerIndividual = InferInsertModel<
  typeof customerIndividuals
>;

export type CustomerCompany = InferSelectModel<typeof customerCompanies>;
export type NewCustomerCompany = InferInsertModel<typeof customerCompanies>;

export type CustomerContact = InferSelectModel<typeof customerContacts>;
export type NewCustomerContact = InferInsertModel<typeof customerContacts>;

export type CustomerAddress = InferSelectModel<typeof customerAddresses>;
export type NewCustomerAddress = InferInsertModel<typeof customerAddresses>;

/** One append-only Customer lifecycle event (`BR-087`). */
export type CustomerLifecycleEvent = InferSelectModel<
  typeof customerLifecycleHistory
>;
export type NewCustomerLifecycleEvent = InferInsertModel<
  typeof customerLifecycleHistory
>;

/** A customer (type `INDIVIDUAL`) together with its individual subtype record. */
export interface IndividualCustomer {
  readonly customer: Customer;
  readonly individual: CustomerIndividual;
}

/** A customer (type `COMPANY`) together with its company subtype record. */
export interface CompanyCustomer {
  readonly customer: Customer;
  readonly company: CustomerCompany;
}

/**
 * The Property and Job rows the customer detail projections read (`BR-081`).
 *
 * The Job & Visit domain owns these entities. `Job` is declared by `jobs/job.types.ts` and re-exported
 * here so the customer projections keep one definition of a Job row; there is no Properties module
 * yet, so `Property` is still declared here.
 */
export type Property = InferSelectModel<typeof properties>;
export type { Job } from '../jobs/job.types.js';
