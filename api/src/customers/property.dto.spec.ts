import { DomainValidationError } from '../validation/domain-validation.js';
import {
  CANADIAN_PROVINCE_CODES,
  DEFAULT_PROPERTY_LIST_FILTERS,
  parseCreatePropertyDto,
  parsePropertyLifecycleDto,
  parsePropertyListFilters,
  parseUpdatePropertyDto,
  PROPERTY_COUNTRY,
  PROPERTY_STATUS_FILTERS,
  toPropertyDetailDto,
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
    const parsed = parseCreatePropertyDto({
      ...valid,
      country: 'United States',
    });

    expect(parsed).not.toHaveProperty('country');
    expect(PROPERTY_COUNTRY).toBe('Canada');
  });
});

describe('parseUpdatePropertyDto', () => {
  const valid = {
    addressLine1: '987 Cedar Lane',
    city: 'Montreal',
    province: 'QC',
    postalCode: 'H3A 2T6',
  };

  it('parses the same authoritative fields a create does', () => {
    expect(parseUpdatePropertyDto(valid)).toEqual({
      name: null,
      addressLine1: '987 Cedar Lane',
      addressLine2: null,
      city: 'Montreal',
      province: 'QC',
      postalCode: 'H3A 2T6',
      notes: null,
    });
  });

  it('keeps the version the client last saw when one is supplied', () => {
    expect(
      parseUpdatePropertyDto({ ...valid, expectedVersion: 4 }),
    ).toMatchObject({ expectedVersion: 4 });
  });

  it.each([0, -1, 1.5, '2'])(
    'rejects an unusable expected version (%s)',
    (expectedVersion) => {
      expect(() =>
        parseUpdatePropertyDto({ ...valid, expectedVersion }),
      ).toThrow(DomainValidationError);
    },
  );

  it('rejects a value longer than its column', () => {
    expect(() =>
      parseUpdatePropertyDto({ ...valid, city: 'a'.repeat(101) }),
    ).toThrow(DomainValidationError);
  });
});

describe('parsePropertyLifecycleDto', () => {
  it('accepts an empty body, because no rule requires a reason', () => {
    expect(parsePropertyLifecycleDto({})).toEqual({
      note: null,
      clientOperationId: null,
      capturedAt: null,
      expectedVersion: undefined,
    });
  });

  it('keeps the offline replay fields an archive or restore may carry', () => {
    const clientOperationId = '3f1a1a2e-0f83-4a4c-9c0e-2a1f0f4a5b6c';
    expect(
      parsePropertyLifecycleDto({
        note: '  No longer serviced.  ',
        clientOperationId,
        capturedAt: '2026-09-13T10:15:00.000Z',
        expectedVersion: 2,
      }),
    ).toEqual({
      note: 'No longer serviced.',
      clientOperationId,
      capturedAt: '2026-09-13T10:15:00.000Z',
      expectedVersion: 2,
    });
  });

  it('rejects an idempotency key that is not a UUID', () => {
    expect(() =>
      parsePropertyLifecycleDto({ clientOperationId: 'op-1' }),
    ).toThrow(DomainValidationError);
  });

  it('rejects a device time that is not an ISO-8601 instant', () => {
    expect(() =>
      parsePropertyLifecycleDto({ capturedAt: '2026-09-13 10:15' }),
    ).toThrow(DomainValidationError);
  });

  it('accepts the device time precision an Android client sends', () => {
    // Kotlin's `Instant.toString()` reports microseconds, which must not fail validation: doing so
    // surfaced as the generic validation message on the Property archive screen.
    const clientOperationId = '3f1a1a2e-0f83-4a4c-9c0e-2a1f0f4a5b6c';
    expect(
      parsePropertyLifecycleDto({
        clientOperationId,
        capturedAt: '2026-09-13T14:39:21.123456Z',
        expectedVersion: 1,
      }),
    ).toEqual({
      note: null,
      clientOperationId,
      capturedAt: '2026-09-13T14:39:21.123456Z',
      expectedVersion: 1,
    });
  });
});

describe('parsePropertyListFilters', () => {
  it('defaults to the active-only projection', () => {
    expect(parsePropertyListFilters(undefined)).toEqual({ status: 'ACTIVE' });
    expect(DEFAULT_PROPERTY_LIST_FILTERS).toEqual({ status: 'ACTIVE' });
  });

  it('accepts every lifecycle filter a client may send', () => {
    for (const status of PROPERTY_STATUS_FILTERS) {
      expect(parsePropertyListFilters({ status }).status).toBe(status);
    }
  });

  it('reads the first value of a repeated parameter', () => {
    expect(parsePropertyListFilters({ status: ['ARCHIVED', 'ALL'] })).toEqual({
      status: 'ARCHIVED',
    });
  });

  it('rejects an unknown lifecycle filter', () => {
    expect(() => parsePropertyListFilters({ status: 'DELETED' })).toThrow(
      DomainValidationError,
    );
  });
});

describe('toPropertyDetailDto', () => {
  it('carries the lifecycle state, the derived counts and the deletion eligibility', () => {
    const archivedAt = new Date('2026-09-13T10:15:00.000Z');
    const dto = toPropertyDetailDto({
      property: {
        id: 'p1',
        organizationId: 'o1',
        name: 'Cedar Lane Building',
        addressLine1: '987 Cedar Lane',
        addressLine2: 'Suite 200',
        city: 'Montreal',
        province: 'QC',
        postalCode: 'H3A 2T6',
        country: 'Canada',
        notes: 'Mechanical room in basement B1.',
        status: 'ARCHIVED',
        archivedAt,
        archivedByMembershipId: 'm1',
        version: 3,
        createdAt: archivedAt,
        updatedAt: archivedAt,
      },
      jobCount: 4,
      lastServiceAt: archivedAt,
      activeJobCount: 2,
      activeVisitCount: 1,
      canBePermanentlyDeleted: false,
    });

    expect(dto).toMatchObject({
      id: 'p1',
      status: 'ARCHIVED',
      version: 3,
      archivedAt: '2026-09-13T10:15:00.000Z',
      jobCount: 4,
      lastServiceAt: '2026-09-13T10:15:00.000Z',
      archiveImpact: { activeJobCount: 2, activeVisitCount: 1 },
      canBePermanentlyDeleted: false,
    });
  });

  it('reports absent derived values as null', () => {
    const now = new Date('2026-09-13T10:15:00.000Z');
    const dto = toPropertyDetailDto({
      property: {
        id: 'p2',
        organizationId: 'o1',
        name: null,
        addressLine1: '1 Main Street',
        addressLine2: null,
        city: 'Ottawa',
        province: 'ON',
        postalCode: 'K1A 0B1',
        country: 'Canada',
        notes: null,
        status: 'ACTIVE',
        archivedAt: null,
        archivedByMembershipId: null,
        version: 1,
        createdAt: now,
        updatedAt: now,
      },
      jobCount: 0,
      lastServiceAt: null,
      activeJobCount: 0,
      activeVisitCount: 0,
      canBePermanentlyDeleted: true,
    });

    expect(dto.archivedAt).toBeNull();
    expect(dto.lastServiceAt).toBeNull();
    expect(dto.canBePermanentlyDeleted).toBe(true);
  });
});
