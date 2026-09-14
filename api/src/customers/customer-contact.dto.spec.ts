import { DomainValidationError } from '../validation/domain-validation.js';
import {
  parseCreateCustomerContactDto,
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
      createdAt: '2026-09-13T14:00:00.000Z',
      updatedAt: '2026-09-13T15:30:00.000Z',
    });
  });
});
