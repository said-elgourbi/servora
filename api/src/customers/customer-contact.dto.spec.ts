import { DomainValidationError } from '../validation/domain-validation.js';
import {
  parseCreateCustomerContactDto,
  parseRemoveCustomerContactDto,
  parseUpdateCustomerContactDto,
  toCustomerContactDto,
} from './customer-contact.dto.js';
import type { CustomerContact } from './customer.types.js';

describe('parseCreateCustomerContactDto', () => {
  const valid = { firstName: 'John', lastName: 'Smith' };

  it('accepts the minimum a contact needs and defaults its flags', () => {
    expect(parseCreateCustomerContactDto(valid)).toEqual({
      firstName: 'John',
      lastName: 'Smith',
      email: null,
      phone: null,
      role: null,
      isPrimary: false,
      isBillingContact: false,
      isJobContact: false,
    });
  });

  it('trims the supplied values and keeps the optional ones', () => {
    expect(
      parseCreateCustomerContactDto({
        firstName: '  John ',
        lastName: ' Smith  ',
        email: ' john@example.com ',
        phone: ' +15551234567 ',
        role: ' Site manager ',
        isPrimary: true,
        isJobContact: true,
      }),
    ).toEqual({
      firstName: 'John',
      lastName: 'Smith',
      email: 'john@example.com',
      phone: '+15551234567',
      role: 'Site manager',
      isPrimary: true,
      isBillingContact: false,
      isJobContact: true,
    });
  });

  it('treats a blank optional field as absent', () => {
    const parsed = parseCreateCustomerContactDto({
      ...valid,
      email: '   ',
      phone: '',
      role: '  ',
    });

    expect(parsed.email).toBeNull();
    expect(parsed.phone).toBeNull();
    expect(parsed.role).toBeNull();
  });

  it('requires a first and last name', () => {
    expect(() => parseCreateCustomerContactDto({ lastName: 'Smith' })).toThrow(
      DomainValidationError,
    );
    expect(() => parseCreateCustomerContactDto({ firstName: 'John' })).toThrow(
      DomainValidationError,
    );
  });

  it('rejects an email that is not an address', () => {
    expect(() =>
      parseCreateCustomerContactDto({ ...valid, email: 'not-an-address' }),
    ).toThrow(DomainValidationError);
  });

  it('rejects a flag that is not a boolean', () => {
    expect(() =>
      parseCreateCustomerContactDto({ ...valid, isPrimary: 'yes' }),
    ).toThrow(DomainValidationError);
  });
});

describe('toCustomerContactDto', () => {
  it('serialises the row with ISO-8601 timestamps', () => {
    const createdAt = new Date('2026-09-13T14:00:00.000Z');
    const updatedAt = new Date('2026-09-13T15:30:00.000Z');
    const contact: CustomerContact = {
      id: '6a1a1a2e-0f83-4a4c-9c0e-2a1f0f4a5b6c',
      customerId: '1f1a1a2e-0f83-4a4c-9c0e-2a1f0f4a5b6c',
      firstName: 'John',
      lastName: 'Smith',
      email: 'john@example.com',
      phone: null,
      role: null,
      isPrimary: true,
      isBillingContact: false,
      isJobContact: false,
      removedAt: null,
      removedByMembershipId: null,
      version: 1,
      createdAt,
      updatedAt,
    };

    expect(toCustomerContactDto(contact)).toEqual({
      id: contact.id,
      customerId: contact.customerId,
      firstName: 'John',
      lastName: 'Smith',
      email: 'john@example.com',
      phone: null,
      role: null,
      isPrimary: true,
      isBillingContact: false,
      isJobContact: false,
      version: 1,
      createdAt: '2026-09-13T14:00:00.000Z',
      updatedAt: '2026-09-13T15:30:00.000Z',
    });
  });
});

describe('parseUpdateCustomerContactDto', () => {
  it('requires the version the caller read', () => {
    expect(() => parseUpdateCustomerContactDto({ firstName: 'John' })).toThrow(
      DomainValidationError,
    );
    expect(() => parseUpdateCustomerContactDto({ expectedVersion: 0 })).toThrow(
      DomainValidationError,
    );
  });

  it('leaves an absent field out, so a partial edit changes only what it states', () => {
    expect(
      parseUpdateCustomerContactDto({
        phone: ' +15551234567 ',
        expectedVersion: 3,
      }),
    ).toEqual({ phone: '+15551234567', expectedVersion: 3 });
  });

  it('keeps an explicit null as a cleared optional value', () => {
    const parsed = parseUpdateCustomerContactDto({
      email: null,
      role: '   ',
      expectedVersion: 1,
    });

    expect(parsed.email).toBeNull();
    expect(parsed.role).toBeNull();
  });

  it('rejects a blank name, because a supplied name must be a name', () => {
    expect(() =>
      parseUpdateCustomerContactDto({ firstName: '  ', expectedVersion: 1 }),
    ).toThrow(DomainValidationError);
  });

  it('rejects a flag that is not a boolean', () => {
    expect(() =>
      parseUpdateCustomerContactDto({ isPrimary: 'yes', expectedVersion: 1 }),
    ).toThrow(DomainValidationError);
  });

  it('does not accept the billing or job-contact flags', () => {
    // No rule defines what makes a billing or a job contact, so no client edits one (`BR-042`).
    expect(
      parseUpdateCustomerContactDto({
        isBillingContact: true,
        isJobContact: true,
        expectedVersion: 1,
      }),
    ).toEqual({ expectedVersion: 1 });
  });
});

describe('parseRemoveCustomerContactDto', () => {
  it('reads the version the caller states', () => {
    expect(parseRemoveCustomerContactDto({ expectedVersion: 4 })).toEqual({
      expectedVersion: 4,
    });
  });

  it('requires the version, because a removal that names none would overwrite blindly', () => {
    expect(() => parseRemoveCustomerContactDto({})).toThrow(
      DomainValidationError,
    );
  });
});
