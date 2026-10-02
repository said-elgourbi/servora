import { describe, expect, it } from 'vitest';
import { DomainValidationError } from '../validation/domain-validation.js';
import {
  parseDirectCreateVisitDto,
  parseFollowUpVisitApprovalDto,
  parseFollowUpVisitReviewDto,
  parseReplyFollowUpVisitRequestDto,
  parseSubmitFollowUpVisitRequestDto,
  toFollowUpVisitRequestDto,
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

  it('requires an answer to say something', () => {
    expect(() =>
      parseReplyFollowUpVisitRequestDto({
        body: '   ',
        expectedStatus: 'NEEDS_CLARIFICATION',
        expectedVersion: 2,
      }),
    ).toThrow(DomainValidationError);
  });

  it('parses an answer with the state it answers', () => {
    expect(
      parseReplyFollowUpVisitRequestDto({
        body: '  The part number is PN-4471.  ',
        expectedStatus: 'NEEDS_CLARIFICATION',
        expectedVersion: 2,
      }),
    ).toEqual({
      body: 'The part number is PN-4471.',
      expectedStatus: 'NEEDS_CLARIFICATION',
      expectedVersion: 2,
    });
  });

  it('derives which side of the conversation wrote each message', () => {
    const dto = toFollowUpVisitRequestDto(REQUEST_ROW, REQUEST_CONTEXT, [
      {
        id: 'message-from-office',
        authorMembershipId: ASSISTANT_ID,
        body: 'Which part number?',
        recordedAt: new Date('2026-09-21T10:00:00.000Z'),
      },
      {
        id: 'message-from-requester',
        authorMembershipId: LEAD_ID,
        body: 'PN-4471.',
        recordedAt: new Date('2026-09-21T11:00:00.000Z'),
      },
    ]);

    expect(dto.messages).toEqual([
      {
        id: 'message-from-office',
        authorMembershipId: ASSISTANT_ID,
        authorKind: 'OFFICE',
        body: 'Which part number?',
        recordedAt: '2026-09-21T10:00:00.000Z',
      },
      {
        id: 'message-from-requester',
        authorMembershipId: LEAD_ID,
        authorKind: 'REQUESTER',
        body: 'PN-4471.',
        recordedAt: '2026-09-21T11:00:00.000Z',
      },
    ]);
  });

  it('answers a request that carries no conversation with an empty list', () => {
    expect(toFollowUpVisitRequestDto(REQUEST_ROW, REQUEST_CONTEXT)).toMatchObject({
      jobNumber: 42,
      jobTitle: 'Replace rooftop unit',
      customerName: 'Northwind Foods',
      address: { addressLine1: '412 Visit Request Road' },
      sourceVisitScheduledStart: null,
      sourceVisitStatus: null,
      sourceVisitOutcomeCode: null,
      messages: [],
    });
  });
});

const REQUEST_CONTEXT = {
  jobNumber: 42,
  jobTitle: 'Replace rooftop unit',
  customerName: 'Northwind Foods',
  address: {
    propertyName: null,
    addressLine1: '412 Visit Request Road',
    addressLine2: null,
    city: 'Ottawa',
    province: 'ON',
    postalCode: 'K1A 0B1',
    country: 'Canada',
  },
  sourceVisitScheduledStart: null,
  sourceVisitStatus: null,
  sourceVisitOutcomeCode: null,
};

const REQUEST_ROW = {
  id: 'request-id',
  jobId: 'job-id',
  sourceVisitId: null,
  requestingTechnicianMembershipId: LEAD_ID,
  proposedStart: new Date('2026-09-21T14:00:00.000Z'),
  proposedEnd: new Date('2026-09-21T16:00:00.000Z'),
  reason: 'Return with a replacement part.',
  sameTechnicianPreferred: false,
  status: 'NEEDS_CLARIFICATION',
  reviewerMembershipId: ASSISTANT_ID,
  reviewedAt: new Date('2026-09-21T10:00:00.000Z'),
  reviewNote: 'Which part number?',
  createdVisitId: null,
  version: 2,
  createdAt: new Date('2026-09-21T09:00:00.000Z'),
  updatedAt: new Date('2026-09-21T10:00:00.000Z'),
};
