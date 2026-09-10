import { DomainValidationError } from '../validation/domain-validation.js';
import type { CustomerType } from './customer.types.js';

/**
 * Describes which subtype records exist for a customer (`customer_individuals`
 * and/or `customer_companies`).
 *
 * A customer has exactly one subtype record, and it must match the customer's
 * `type`: `INDIVIDUAL` ⇒ individual record only; `COMPANY` ⇒ company record
 * only. This invariant is enforced in the service layer (and covered by tests)
 * rather than by a database trigger, per the foundation design.
 */
export interface CustomerSubtypePresence {
  readonly type: CustomerType;
  readonly hasIndividual: boolean;
  readonly hasCompany: boolean;
}

/** Throws a `DomainValidationError` when the subtype/type combination is invalid. */
export function assertCustomerSubtypeIntegrity(presence: CustomerSubtypePresence): void {
  const issues: string[] = [];

  if (presence.type === 'INDIVIDUAL') {
    if (!presence.hasIndividual) {
      issues.push('customer type INDIVIDUAL requires an individual record');
    }
    if (presence.hasCompany) {
      issues.push('customer type INDIVIDUAL must not have a company record');
    }
  } else {
    if (!presence.hasCompany) {
      issues.push('customer type COMPANY requires a company record');
    }
    if (presence.hasIndividual) {
      issues.push('customer type COMPANY must not have an individual record');
    }
  }

  if (issues.length > 0) {
    throw new DomainValidationError(issues);
  }
}
