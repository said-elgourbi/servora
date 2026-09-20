import { DomainValidationError } from '../validation/domain-validation.js';
import {
  MAX_JOB_DESCRIPTION_LENGTH,
  parseCreateJobDto,
} from './job-create.dto.js';

/*
 * What `POST /jobs` accepts (`BR-053`, `BR-056`).
 *
 * The request carries the Customer, the Property and the title, and nothing a client must not
 * decide. These tests assert the boundary's own rules: a required field is required, a UUID is a
 * UUID, and a value the backend owns is never read from the body.
 */
describe('parseCreateJobDto', () => {
  const customerId = '0f0a6c2e-9d4b-4e6a-8f1c-3a2b1c4d5e6f';
  const propertyId = 'b7c1e2f3-4d5a-4b6c-8d9e-0f1a2b3c4d5e';
  const valid = { customerId, propertyId, title: 'Furnace repair' };

  it('accepts the minimum a Job needs, with no description', () => {
    expect(parseCreateJobDto(valid)).toEqual({
      customerId,
      propertyId,
      title: 'Furnace repair',
      description: null,
    });
  });

  it('trims the supplied values and keeps a description', () => {
    expect(
      parseCreateJobDto({
        customerId: ` ${customerId} `,
        propertyId: ` ${propertyId} `,
        title: '  Furnace repair ',
        description: ' Customer reports the furnace is not producing heat. ',
      }),
    ).toEqual({
      customerId,
      propertyId,
      title: 'Furnace repair',
      description: 'Customer reports the furnace is not producing heat.',
    });
  });

  it('treats a blank description as absent', () => {
    expect(
      parseCreateJobDto({ ...valid, description: '   ' }).description,
    ).toBeNull();
    expect(parseCreateJobDto({ ...valid, description: '' }).description).toBeNull();
    expect(
      parseCreateJobDto({ ...valid, description: null }).description,
    ).toBeNull();
  });

  it('requires each required field', () => {
    for (const field of ['customerId', 'propertyId', 'title'] as const) {
      const withoutField: Record<string, unknown> = { ...valid };
      delete withoutField[field];
      expect(() => parseCreateJobDto(withoutField)).toThrow(DomainValidationError);
    }
  });

  it('rejects a whitespace-only title', () => {
    expect(() => parseCreateJobDto({ ...valid, title: '   ' })).toThrow(
      DomainValidationError,
    );
  });

  it('requires canonical UUID identifiers', () => {
    expect(() =>
      parseCreateJobDto({ ...valid, customerId: 'customer-1' }),
    ).toThrow(DomainValidationError);
    expect(() =>
      parseCreateJobDto({ ...valid, propertyId: 'not-a-uuid' }),
    ).toThrow(DomainValidationError);
    expect(() => parseCreateJobDto({ ...valid, customerId: 42 })).toThrow(
      DomainValidationError,
    );
  });

  it('rejects a value longer than the column or the description cap', () => {
    expect(() =>
      parseCreateJobDto({ ...valid, title: 'a'.repeat(256) }),
    ).toThrow(DomainValidationError);
    expect(() =>
      parseCreateJobDto({
        ...valid,
        description: 'a'.repeat(MAX_JOB_DESCRIPTION_LENGTH + 1),
      }),
    ).toThrow(DomainValidationError);
  });

  it('never reads a value the backend owns', () => {
    const parsed = parseCreateJobDto({
      ...valid,
      jobNumber: 1042,
      status: 'COMPLETED',
      version: 9,
      propertyAddressSnapshot: { city: 'Havana' },
      organizationId: 'another-organization',
      typeCode: 'REPAIR',
      ownerMembershipId: 'someone',
    });

    // The parser exposes exactly the four fields a caller supplies, so a client cannot name the
    // number, the status, the version, the snapshot or the organization (`BR-001`, `BR-052`).
    expect(Object.keys(parsed).sort()).toEqual([
      'customerId',
      'description',
      'propertyId',
      'title',
    ]);
  });
});
