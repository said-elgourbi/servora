import { DomainValidationError } from '../validation/domain-validation.js';
import {
  parseAddVisitNoteDto,
  parseAssignVisitTechniciansDto,
  parseChangeJobStatusDto,
  parseChangeVisitStatusDto,
  parseRescheduleVisitDto,
} from './job-action.dto.js';

/**
 * The action request parsers (`BR-058`, `BR-068`, `BR-072`, `BR-073`, `BR-074`, `BR-077`, `BR-078`).
 *
 * Validation is the API boundary's job (`dev.md` §7): a client that sends a status Servora does not
 * have, a schedule the API cannot store, a crew without exactly one Lead, or a completion without the
 * outcome `BR-077` requires is refused here rather than reaching the database.
 */
describe('job action requests', () => {
  describe('job status change', () => {
    it('accepts a known status with an optional note', () => {
      expect(
        parseChangeJobStatusDto({
          status: 'PENDING_REVIEW',
          note: ' Work done ',
        }),
      ).toEqual({
        status: 'PENDING_REVIEW',
        note: 'Work done',
        expectedVersion: null,
      });
    });

    it('refuses a status Servora does not have', () => {
      expect(() => parseChangeJobStatusDto({ status: 'EN_ROUTE' })).toThrow(
        DomainValidationError,
      );
      expect(() => parseChangeJobStatusDto({})).toThrow(DomainValidationError);
    });
  });

  describe('visit reschedule', () => {
    const valid = {
      scheduledStart: '2026-09-14T13:00:00.000Z',
      scheduledEnd: '2026-09-14T15:00:00.000Z',
    };

    it('accepts a valid window and defaults the rest', () => {
      const parsed = parseRescheduleVisitDto(valid);
      expect(parsed.scheduledStart.toISOString()).toBe(valid.scheduledStart);
      expect(parsed.scheduledEnd.toISOString()).toBe(valid.scheduledEnd);
      expect(parsed.arrivalWindowStart).toBeNull();
      expect(parsed.arrivalWindowEnd).toBeNull();
      expect(parsed.reason).toBeNull();
      expect(parsed.confirmConflicts).toBe(false);
    });

    it('requires an instant with an explicit zone', () => {
      // A local wall-clock string would have to be guessed at, so it is not accepted
      // (`Project.md` §15).
      expect(() =>
        parseRescheduleVisitDto({
          ...valid,
          scheduledStart: '2026-09-14 13:00',
        }),
      ).toThrow(DomainValidationError);
      expect(() =>
        parseRescheduleVisitDto({ scheduledEnd: valid.scheduledEnd }),
      ).toThrow(DomainValidationError);
    });

    it('requires the window to end after it starts', () => {
      expect(() =>
        parseRescheduleVisitDto({
          scheduledStart: valid.scheduledEnd,
          scheduledEnd: valid.scheduledStart,
        }),
      ).toThrow(DomainValidationError);
    });

    it('requires the arrival window to be a pair, in order', () => {
      expect(() =>
        parseRescheduleVisitDto({
          ...valid,
          arrivalWindowStart: '2026-09-14T12:00:00.000Z',
        }),
      ).toThrow(DomainValidationError);
      expect(() =>
        parseRescheduleVisitDto({
          ...valid,
          arrivalWindowStart: '2026-09-14T14:00:00.000Z',
          arrivalWindowEnd: '2026-09-14T12:00:00.000Z',
        }),
      ).toThrow(DomainValidationError);
      expect(
        parseRescheduleVisitDto({
          ...valid,
          arrivalWindowStart: '2026-09-14T12:00:00.000Z',
          arrivalWindowEnd: '2026-09-14T14:00:00.000Z',
          confirmConflicts: true,
        }),
      ).toMatchObject({
        confirmConflicts: true,
        arrivalWindowStart: new Date('2026-09-14T12:00:00.000Z'),
        arrivalWindowEnd: new Date('2026-09-14T14:00:00.000Z'),
      });
    });
  });

  describe('visit status change', () => {
    it('accepts a lifecycle destination with the device provenance and version', () => {
      expect(
        parseChangeVisitStatusDto({
          status: 'EN_ROUTE',
          clientOperationId: '33333333-3333-4333-8333-333333333333',
          capturedAt: '2026-09-17T12:00:00.000Z',
          expectedVersion: 3,
        }),
      ).toEqual({
        status: 'EN_ROUTE',
        outcomeCode: null,
        outcomeSummary: null,
        clientOperationId: '33333333-3333-4333-8333-333333333333',
        capturedAt: new Date('2026-09-17T12:00:00.000Z'),
        expectedVersion: 3,
        confirmConflicts: false,
      });
    });

    it('requires the outcome a completion records (`BR-077`, `BR-078`)', () => {
      expect(() => parseChangeVisitStatusDto({ status: 'COMPLETED' })).toThrow(
        DomainValidationError,
      );
      // A code is not enough: `BR-077` requires the outcome type *and* its summary.
      expect(() =>
        parseChangeVisitStatusDto({
          status: 'COMPLETED',
          outcomeCode: 'RESOLVED',
        }),
      ).toThrow(DomainValidationError);
      expect(() =>
        parseChangeVisitStatusDto({
          status: 'COMPLETED',
          outcomeSummary: 'Replaced the igniter.',
        }),
      ).toThrow(DomainValidationError);
      expect(
        parseChangeVisitStatusDto({
          status: 'COMPLETED',
          outcomeCode: 'NEEDS_PARTS',
          outcomeSummary: ' Ordered the igniter. ',
        }),
      ).toMatchObject({
        outcomeCode: 'NEEDS_PARTS',
        outcomeSummary: 'Ordered the igniter.',
      });
    });

    it('refuses an outcome on a destination that stores none', () => {
      // `docs/domain/job-visit-domain-model.md` §11.2: a DRAFT outcome is not modelled, so accepting one
      // for another destination would report a record the API did not make (`BR-042`).
      expect(() =>
        parseChangeVisitStatusDto({
          status: 'ON_SITE',
          outcomeCode: 'RESOLVED',
          outcomeSummary: 'Done.',
        }),
      ).toThrow(DomainValidationError);
    });

    it('refuses a Visit status Servora does not have, and the two no route applies', () => {
      expect(() => parseChangeVisitStatusDto({ status: 'PAUSED' })).toThrow(
        DomainValidationError,
      );
      // A Job status is not a Visit status: the vocabularies are separate and closed (`BR-041`).
      expect(() => parseChangeVisitStatusDto({ status: 'PENDING_REVIEW' })).toThrow(
        DomainValidationError,
      );
      // `CANCELED` and `NO_SHOW` are refused by the lifecycle table, not by the parser, so the API can
      // answer with the destinations the Visit really has (`BR-074`, `BR-066`).
      expect(parseChangeVisitStatusDto({ status: 'CANCELED' }).status).toBe(
        'CANCELED',
      );
      expect(parseChangeVisitStatusDto({ status: 'NO_SHOW' }).status).toBe(
        'NO_SHOW',
      );
    });

    it('refuses an outcome code Servora does not have', () => {
      expect(() =>
        parseChangeVisitStatusDto({
          status: 'COMPLETED',
          outcomeCode: 'FIXED',
          outcomeSummary: 'Done.',
        }),
      ).toThrow(DomainValidationError);
    });

    it('refuses a malformed operation id, instant or version', () => {
      expect(() =>
        parseChangeVisitStatusDto({
          status: 'EN_ROUTE',
          clientOperationId: 'not-a-uuid',
        }),
      ).toThrow(DomainValidationError);
      expect(() =>
        parseChangeVisitStatusDto({
          status: 'EN_ROUTE',
          capturedAt: '2026-09-17 12:00',
        }),
      ).toThrow(DomainValidationError);
      expect(() =>
        parseChangeVisitStatusDto({ status: 'EN_ROUTE', expectedVersion: 0 }),
      ).toThrow(DomainValidationError);
    });
  });

  describe('visit note', () => {
    it('accepts a body with the optional device provenance', () => {
      expect(parseAddVisitNoteDto({ body: ' Filter replaced. ' })).toEqual({
        body: 'Filter replaced.',
        clientOperationId: null,
        capturedAt: null,
      });
      expect(
        parseAddVisitNoteDto({
          body: 'Filter replaced.',
          clientOperationId: '44444444-4444-4444-8444-444444444444',
          capturedAt: '2026-09-17T12:30:00.000Z',
        }),
      ).toMatchObject({
        clientOperationId: '44444444-4444-4444-8444-444444444444',
        capturedAt: new Date('2026-09-17T12:30:00.000Z'),
      });
      expect(() => parseAddVisitNoteDto({})).toThrow(DomainValidationError);
      expect(() => parseAddVisitNoteDto({ body: '   ' })).toThrow(
        DomainValidationError,
      );
    });
  });

  describe('visit assignment', () => {
    const membershipId = '11111111-1111-4111-8111-111111111111';
    const secondId = '22222222-2222-4222-8222-222222222222';

    it('accepts a crew with exactly one Lead', () => {
      expect(
        parseAssignVisitTechniciansDto({
          technicians: [
            { membershipId, roleCode: 'LEAD' },
            { membershipId: secondId, roleCode: 'TECHNICIAN' },
          ],
        }),
      ).toEqual({
        technicians: [
          { membershipId, roleCode: 'LEAD' },
          { membershipId: secondId, roleCode: 'TECHNICIAN' },
        ],
        confirmConflicts: false,
        expectedVersion: null,
      });
    });

    it('refuses a crew with no Lead or more than one', () => {
      // A Visit with technicians has exactly one Lead (`BR-068`), so the API never has to choose.
      expect(() =>
        parseAssignVisitTechniciansDto({
          technicians: [{ membershipId, roleCode: 'TECHNICIAN' }],
        }),
      ).toThrow(DomainValidationError);
      expect(() =>
        parseAssignVisitTechniciansDto({
          technicians: [
            { membershipId, roleCode: 'LEAD' },
            { membershipId: secondId, roleCode: 'LEAD' },
          ],
        }),
      ).toThrow(DomainValidationError);
    });

    it('refuses an empty crew, a repeated technician and an unknown role', () => {
      expect(() => parseAssignVisitTechniciansDto({ technicians: [] })).toThrow(
        DomainValidationError,
      );
      expect(() => parseAssignVisitTechniciansDto({})).toThrow(
        DomainValidationError,
      );
      expect(() =>
        parseAssignVisitTechniciansDto({
          technicians: [
            { membershipId, roleCode: 'LEAD' },
            { membershipId, roleCode: 'TECHNICIAN' },
          ],
        }),
      ).toThrow(DomainValidationError);
      expect(() =>
        parseAssignVisitTechniciansDto({
          technicians: [{ membershipId, roleCode: 'HELPER' }],
        }),
      ).toThrow(DomainValidationError);
      expect(() =>
        parseAssignVisitTechniciansDto({
          technicians: [{ membershipId: 'not-a-uuid', roleCode: 'LEAD' }],
        }),
      ).toThrow(DomainValidationError);
    });
  });
});
