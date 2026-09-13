import { DomainValidationError } from '../validation/domain-validation.js';
import {
  CANADIAN_PROVINCE_CODES,
  parseCreatePropertyDto,
  PROPERTY_COUNTRY,
} from './property.dto.js';

describe('parseCreatePropertyDto', () => {
  const valid = {
    addressLine1: '987 Cedar Lane',
    city: 'Montreal',
    province: 'QC',
    postalCode: 'H3A 2T6',
  };

  it('accepts the minimum a Property needs, leaving the optional fields absent', () => {
    expect(parseCreatePropertyDto(valid)).toEqual({
      name: null,
      addressLine1: '987 Cedar Lane',
      addressLine2: null,
      city: 'Montreal',
      province: 'QC',
      postalCode: 'H3A 2T6',
      notes: null,
    });
  });

  it('trims the supplied values and keeps the optional ones', () => {
    expect(
      parseCreatePropertyDto({
        name: '  Cedar Lane Building ',
        addressLine1: ' 987 Cedar Lane ',
        addressLine2: ' Suite 200 ',
        city: ' Montreal ',
        province: 'QC',
        postalCode: ' H3A 2T6 ',
        notes: ' Mechanical room in basement B1. ',
      }),
    ).toEqual({
      name: 'Cedar Lane Building',
      addressLine1: '987 Cedar Lane',
      addressLine2: 'Suite 200',
      city: 'Montreal',
      province: 'QC',
      postalCode: 'H3A 2T6',
      notes: 'Mechanical room in basement B1.',
    });
  });

  it('treats a blank optional field as absent', () => {
    const parsed = parseCreatePropertyDto({
      ...valid,
      name: '   ',
      addressLine2: '',
      notes: '',
    });

    expect(parsed.name).toBeNull();
    expect(parsed.addressLine2).toBeNull();
    expect(parsed.notes).toBeNull();
  });

  it('accepts every Canadian province and territory code', () => {
    for (const province of CANADIAN_PROVINCE_CODES) {
      expect(parseCreatePropertyDto({ ...valid, province }).province).toBe(
        province,
      );
    }
  });

  it('rejects a province outside the Canadian vocabulary', () => {
    // A full name or a foreign code must not be accepted as a Property province: the vocabulary is
    // the stable Canadian code set (`BR-028`, `BR-041`).
    expect(() =>
      parseCreatePropertyDto({ ...valid, province: 'Quebec' }),
    ).toThrow(DomainValidationError);
    expect(() =>
      parseCreatePropertyDto({ ...valid, province: 'CA-QC' }),
    ).toThrow(DomainValidationError);
    expect(() => parseCreatePropertyDto({ ...valid, province: 'NY' })).toThrow(
      DomainValidationError,
    );
  });

  it('requires the street, city, province and postal code', () => {
    for (const field of [
      'addressLine1',
      'city',
      'province',
      'postalCode',
    ] as const) {
      const withoutField: Record<string, unknown> = { ...valid };
      delete withoutField[field];
      expect(() => parseCreatePropertyDto(withoutField)).toThrow(
        DomainValidationError,
      );
    }
  });

  it('rejects a blank required field', () => {
    expect(() =>
      parseCreatePropertyDto({ ...valid, addressLine1: '   ' }),
    ).toThrow(DomainValidationError);
    expect(() => parseCreatePropertyDto({ ...valid, city: '' })).toThrow(
      DomainValidationError,
    );
    expect(() => parseCreatePropertyDto({ ...valid, postalCode: '' })).toThrow(
      DomainValidationError,
    );
  });

  it('rejects a value longer than its column', () => {
    expect(() =>
      parseCreatePropertyDto({ ...valid, addressLine1: 'a'.repeat(256) }),
    ).toThrow(DomainValidationError);
    expect(() =>
      parseCreatePropertyDto({ ...valid, city: 'a'.repeat(101) }),
    ).toThrow(DomainValidationError);
    expect(() =>
      parseCreatePropertyDto({ ...valid, postalCode: 'a'.repeat(21) }),
    ).toThrow(DomainValidationError);
    expect(() =>
      parseCreatePropertyDto({ ...valid, name: 'a'.repeat(256) }),
    ).toThrow(DomainValidationError);
  });

  it('does not accept a client-supplied country', () => {
    // The country is not a client field: it is the existing value the address model carries
    // (`PROPERTY_COUNTRY`) and is written on behalf of every Property.
    const parsed = parseCreatePropertyDto({ ...valid, country: 'United States' });

    expect(parsed).not.toHaveProperty('country');
    expect(PROPERTY_COUNTRY).toBe('Canada');
  });
});
