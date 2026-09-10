import { DomainValidationError } from '../validation/domain-validation.js';
import { assertCustomerSubtypeIntegrity } from './customer-subtype.js';

describe('assertCustomerSubtypeIntegrity', () => {
  it('accepts an individual customer with exactly an individual record', () => {
    expect(() =>
      assertCustomerSubtypeIntegrity({
        type: 'INDIVIDUAL',
        hasIndividual: true,
        hasCompany: false,
      }),
    ).not.toThrow();
  });

  it('accepts a company customer with exactly a company record', () => {
    expect(() =>
      assertCustomerSubtypeIntegrity({
        type: 'COMPANY',
        hasIndividual: false,
        hasCompany: true,
      }),
    ).not.toThrow();
  });

  it('rejects an INDIVIDUAL customer without an individual record', () => {
    expect(() =>
      assertCustomerSubtypeIntegrity({
        type: 'INDIVIDUAL',
        hasIndividual: false,
        hasCompany: false,
      }),
    ).toThrow(/INDIVIDUAL requires an individual record/);
  });

  it('rejects a COMPANY customer without a company record', () => {
    expect(() =>
      assertCustomerSubtypeIntegrity({
        type: 'COMPANY',
        hasIndividual: false,
        hasCompany: false,
      }),
    ).toThrow(/COMPANY requires a company record/);
  });

  it('rejects an INDIVIDUAL customer that also has a company record', () => {
    expect(() =>
      assertCustomerSubtypeIntegrity({
        type: 'INDIVIDUAL',
        hasIndividual: true,
        hasCompany: true,
      }),
    ).toThrow(/INDIVIDUAL must not have a company record/);
  });

  it('rejects a COMPANY customer that also has an individual record', () => {
    expect(() =>
      assertCustomerSubtypeIntegrity({
        type: 'COMPANY',
        hasIndividual: true,
        hasCompany: true,
      }),
    ).toThrow(/COMPANY must not have an individual record/);
  });

  it('collects every violated rule into one error', () => {
    expect(() =>
      assertCustomerSubtypeIntegrity({
        type: 'INDIVIDUAL',
        hasIndividual: false,
        hasCompany: true,
      }),
    ).toThrow(DomainValidationError);
  });
});
