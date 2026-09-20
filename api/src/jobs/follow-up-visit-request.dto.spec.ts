import { describe, expect, it } from 'vitest';
import { DomainValidationError } from '../validation/domain-validation.js';
import {
  parseDirectCreateVisitDto,
  parseFollowUpVisitApprovalDto,
  parseFollowUpVisitReviewDto,
  parseSubmitFollowUpVisitRequestDto,
} from './follow-up-visit-request.dto.js';

const LEAD_ID = '00000000-0000-4000-8000-000000000001';
const ASSISTANT_ID = '00000000-0000-4000-8000-000000000002';

describe('follow-up Visit request DTOs', () => {
  it('parses a request and defaults same-technician preference to false', () => {
    const result = parseSubmitFollowUpVisitRequestDto({
      proposedStart: '2026-09-21T14:00:00.000Z',
      proposedEnd: '2026-09-21T16:00:00.000Z',
      reason: 'Return with a replacement part.',
    });

    expect(result).toEqual({
      sourceVisitId: null,
      proposedStart: new Date('2026-09-21T14:00:00.000Z'),
      proposedEnd: new Date('2026-09-21T16:00:00.000Z'),
      reason: 'Return with a replacement part.',
      sameTechnicianPreferred: false,
    });
  });

  it('rejects a proposed interval that does not end after it starts', () => {
    expect(() =>
      parseSubmitFollowUpVisitRequestDto({
        proposedStart: '2026-09-21T16:00:00.000Z',
        proposedEnd: '2026-09-21T16:00:00.000Z',
        reason: 'Return visit.',
      }),
    ).toThrow(DomainValidationError);
  });

  it('parses optimistic review fields', () => {
    expect(
      parseFollowUpVisitReviewDto({
        note: 'Please identify the required part.',
        expectedStatus: 'PENDING',
        expectedVersion: 2,
      }),
    ).toEqual({
      note: 'Please identify the required part.',
      expectedStatus: 'PENDING',
      expectedVersion: 2,
    });
  });

  it('allows approval to inherit the proposed interval', () => {
    const result = parseFollowUpVisitApprovalDto({
      technicians: [{ membershipId: LEAD_ID, roleCode: 'LEAD' }],
      expectedStatus: 'NEEDS_CLARIFICATION',
      expectedVersion: 3,
    });

    expect(result.scheduledStart).toBeNull();
    expect(result.scheduledEnd).toBeNull();
    expect(result.technicians).toEqual([
      { membershipId: LEAD_ID, roleCode: 'LEAD' },
    ]);
  });

  it('requires approval schedule overrides to be supplied as a complete interval', () => {
    expect(() =>
      parseFollowUpVisitApprovalDto({
        scheduledStart: '2026-09-21T14:00:00.000Z',
        technicians: [{ membershipId: LEAD_ID, roleCode: 'LEAD' }],
      }),
    ).toThrow(DomainValidationError);
  });

  it('requires exactly one lead and unique technicians for a scheduled Visit', () => {
    expect(() =>
      parseDirectCreateVisitDto({
        scheduledStart: '2026-09-21T14:00:00.000Z',
        scheduledEnd: '2026-09-21T16:00:00.000Z',
        technicians: [
          { membershipId: LEAD_ID, roleCode: 'LEAD' },
          { membershipId: LEAD_ID, roleCode: 'ASSISTANT' },
        ],
      }),
    ).toThrow(DomainValidationError);

    expect(() =>
      parseDirectCreateVisitDto({
        scheduledStart: '2026-09-21T14:00:00.000Z',
        scheduledEnd: '2026-09-21T16:00:00.000Z',
        technicians: [
          { membershipId: LEAD_ID, roleCode: 'ASSISTANT' },
          { membershipId: ASSISTANT_ID, roleCode: 'ASSISTANT' },
        ],
      }),
    ).toThrow(DomainValidationError);
  });
});
