import { DomainValidationError } from '../validation/domain-validation.js';
import {
  parseAssignVisitTechniciansDto,
  parseChangeJobStatusDto,
  parseRescheduleVisitDto,
} from './job-action.dto.js';

/**
 * The action request parsers (`BR-058`, `BR-068`, `BR-072`, `BR-073`).
 *
 * Validation is the API boundary's job (`dev.md` §7): a client that sends a status Servora does not
 * have, a schedule the API cannot store, or a crew without exactly one Lead is refused here rather
 * than reaching the database.
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
