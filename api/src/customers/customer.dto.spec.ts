import { DomainValidationError } from '../validation/domain-validation.js';
import { parseUpdateCustomerDto } from './customer.dto.js';

describe('parseUpdateCustomerDto', () => {
  it('accepts a partial edit that states no type', () => {
    expect(parseUpdateCustomerDto({ displayName: 'Edited Customer' })).toEqual({
      displayName: 'Edited Customer',
    });
  });

  it('keeps an ordinary edit free of the subtype group', () => {
    const parsed = parseUpdateCustomerDto({
      notes: 'Prefers mornings.',
      status: 'INACTIVE',
      individual: { firstName: 'John' },
    });

    expect(parsed).toEqual({
      notes: 'Prefers mornings.',
      status: 'INACTIVE',
      individual: { firstName: 'John' },
    });
  });

  it('clears an optional header field from an explicit null', () => {
    expect(parseUpdateCustomerDto({ email: null, phone: '   ' })).toEqual({
      email: null,
      phone: null,
    });
  });

  it('requires the display name when the edit states the type', () => {
    expect(() =>
      parseUpdateCustomerDto({
        type: 'COMPANY',
        company: { legalName: 'ABC Property Management Ltd.' },
      }),
    ).toThrow(DomainValidationError);
  });

  it('requires the required fields of the stated type', () => {
    expect(() =>
      parseUpdateCustomerDto({ type: 'COMPANY', displayName: 'ABC' }),
    ).toThrow(DomainValidationError);
    expect(() =>
      parseUpdateCustomerDto({
        type: 'INDIVIDUAL',
        displayName: 'John Smith',
        individual: { firstName: 'John' },
      }),
    ).toThrow(DomainValidationError);
  });

  it('parses a conversion to a company, keeping the optional subtype fields', () => {
    expect(
      parseUpdateCustomerDto({
        type: 'COMPANY',
        displayName: ' ABC Property Management ',
        email: ' hello@abc.example ',
        company: {
          legalName: ' ABC Property Management Ltd. ',
          businessName: 'ABC PM',
          taxNumber: null,
        },
      }),
    ).toEqual({
      type: 'COMPANY',
      displayName: 'ABC Property Management',
      email: 'hello@abc.example',
      company: {
        legalName: 'ABC Property Management Ltd.',
        businessName: 'ABC PM',
        taxNumber: null,
      },
    });
  });

  it('parses a conversion to an individual', () => {
    expect(
      parseUpdateCustomerDto({
        type: 'INDIVIDUAL',
        displayName: 'John Smith',
        individual: {
          firstName: ' John ',
          lastName: ' Smith ',
          dateOfBirth: '1980-01-31',
        },
      }),
    ).toEqual({
      type: 'INDIVIDUAL',
      displayName: 'John Smith',
      individual: {
        firstName: 'John',
        lastName: 'Smith',
        dateOfBirth: '1980-01-31',
      },
    });
  });

  it('rejects the subtype the customer is leaving', () => {
    // A contradictory body fails loudly rather than having a payload silently dropped
    // (`BR-042`, `BR-087`).
    expect(() =>
      parseUpdateCustomerDto({
        type: 'COMPANY',
        displayName: 'ABC Property Management',
        company: { legalName: 'ABC Property Management Ltd.' },
        individual: { firstName: 'John', lastName: 'Smith' },
      }),
    ).toThrow(DomainValidationError);
  });

  it('rejects a type that is not a customer type', () => {
    expect(() =>
      parseUpdateCustomerDto({
        type: 'BUSINESS',
        displayName: 'ABC Property Management',
        company: { legalName: 'ABC Property Management Ltd.' },
      }),
    ).toThrow(DomainValidationError);
  });
});
