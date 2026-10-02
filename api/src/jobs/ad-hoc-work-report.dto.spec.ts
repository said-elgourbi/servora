import { describe, expect, it } from 'vitest';
import { DomainValidationError } from '../validation/domain-validation.js';
import {
  parseRejectAdHocWorkReportDto,
  parseSubmitAdHocWorkReportDto,
  toAdHocWorkReportDto,
} from './ad-hoc-work-report.dto.js';

const CUSTOMER_ID = '00000000-0000-4000-8000-000000000001';
const PROPERTY_ID = '00000000-0000-4000-8000-000000000002';
const JOB_ID = '00000000-0000-4000-8000-000000000003';

describe('ad-hoc work report DTOs', () => {
  it('parses a minimal report and defaults provenance to null', () => {
    const result = parseSubmitAdHocWorkReportDto({
      workStartedAt: '2026-10-01T13:00:00.000Z',
      workEndedAt: '2026-10-01T15:00:00.000Z',
      outcomeCode: 'RESOLVED',
      summary: 'Replaced the leaking valve.',
    });

    expect(result).toEqual({
      customerId: null,
      propertyId: null,
      knownJobId: null,
      workStartedAt: new Date('2026-10-01T13:00:00.000Z'),
      workEndedAt: new Date('2026-10-01T15:00:00.000Z'),
      outcomeCode: 'RESOLVED',
      summary: 'Replaced the leaking valve.',
      notes: null,
      reportedCustomerName: null,
      reportedCustomerPhone: null,
      reportedCustomerAddress: null,
      clientOperationId: null,
    });
  });

  it('parses unknown-customer provenance fields', () => {
    const result = parseSubmitAdHocWorkReportDto({
      workStartedAt: '2026-10-01T13:00:00.000Z',
      workEndedAt: '2026-10-01T15:00:00.000Z',
      outcomeCode: 'NEEDS_FOLLOW_UP',
      summary: 'Inspected the rooftop unit.',
      reportedCustomerName: 'Maple Leaf Bakery',
      reportedCustomerPhone: '+16135550123',
      reportedCustomerAddress: '210 Sparks Street, Ottawa',
    });

    expect(result.reportedCustomerName).toBe('Maple Leaf Bakery');
    expect(result.reportedCustomerPhone).toBe('+16135550123');
    expect(result.reportedCustomerAddress).toBe('210 Sparks Street, Ottawa');
  });

  it('parses a selected customer, property and related job', () => {
    const result = parseSubmitAdHocWorkReportDto({
      customerId: CUSTOMER_ID,
      propertyId: PROPERTY_ID,
      knownJobId: JOB_ID,
      workStartedAt: '2026-10-01T13:00:00.000Z',
      workEndedAt: '2026-10-01T15:00:00.000Z',
      outcomeCode: 'RESOLVED',
      summary: 'Finished the maintenance call.',
      clientOperationId: '10000000-0000-4000-8000-000000000001',
    });

    expect(result.customerId).toBe(CUSTOMER_ID);
    expect(result.propertyId).toBe(PROPERTY_ID);
    expect(result.knownJobId).toBe(JOB_ID);
  });

  it('rejects a work interval that does not end after it starts', () => {
    expect(() =>
      parseSubmitAdHocWorkReportDto({
        workStartedAt: '2026-10-01T15:00:00.000Z',
        workEndedAt: '2026-10-01T15:00:00.000Z',
        outcomeCode: 'RESOLVED',
        summary: 'Finished the maintenance call.',
      }),
    ).toThrow(DomainValidationError);
  });

  it('rejects a blank summary', () => {
    expect(() =>
      parseSubmitAdHocWorkReportDto({
        workStartedAt: '2026-10-01T13:00:00.000Z',
        workEndedAt: '2026-10-01T15:00:00.000Z',
        outcomeCode: 'RESOLVED',
        summary: '   ',
      }),
    ).toThrow(DomainValidationError);
  });

  it('parses a reject review with an optional note and optimistic guards', () => {
    const result = parseRejectAdHocWorkReportDto({
      note: 'Not legitimate Servora work.',
      expectedStatus: 'PENDING',
      expectedVersion: 3,
    });

    expect(result).toEqual({
      note: 'Not legitimate Servora work.',
      expectedStatus: 'PENDING',
      expectedVersion: 3,
    });
  });

  it('allows a reject with no note', () => {
    expect(parseRejectAdHocWorkReportDto({})).toEqual({
      note: null,
      expectedStatus: null,
      expectedVersion: null,
    });
  });

  it('maps provenance and the REJECTED status onto the wire DTO', () => {
    const dto = toAdHocWorkReportDto(
      {
        id: '20000000-0000-4000-8000-000000000001',
        reportingTechnicianMembershipId: '30000000-0000-4000-8000-000000000001',
        customerId: null,
        propertyId: null,
        knownJobId: null,
        workStartedAt: new Date('2026-10-01T13:00:00.000Z'),
        workEndedAt: new Date('2026-10-01T15:00:00.000Z'),
        outcomeCode: 'NEEDS_PARTS',
        summary: 'Pump needs a replacement seal.',
        notes: null,
        reportedCustomerName: 'Riverside Foods',
        reportedCustomerPhone: null,
        reportedCustomerAddress: '88 Elgin Street, Ottawa',
        status: 'REJECTED',
        reviewerMembershipId: '40000000-0000-4000-8000-000000000001',
        reviewedAt: new Date('2026-10-02T09:00:00.000Z'),
        reviewNote: 'Not legitimate Servora work.',
        createdJobId: null,
        createdVisitId: null,
        version: 2,
        createdAt: new Date('2026-10-01T13:00:00.000Z'),
        updatedAt: new Date('2026-10-02T09:00:00.000Z'),
      },
      {
        customerName: null,
        propertyAddress: null,
        knownJobNumber: null,
        knownJobTitle: null,
        createdJobNumber: null,
        createdJobTitle: null,
      },
    );

    expect(dto.status).toBe('REJECTED');
    expect(dto.reportedCustomerName).toBe('Riverside Foods');
    expect(dto.reportedCustomerPhone).toBeNull();
    expect(dto.reportedCustomerAddress).toBe('88 Elgin Street, Ottawa');
  });
});
