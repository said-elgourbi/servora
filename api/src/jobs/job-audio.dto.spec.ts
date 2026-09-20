import { DomainValidationError } from '../validation/domain-validation.js';
import {
  MAX_JOB_AUDIO_BYTES,
  MAX_JOB_AUDIO_DURATION_SECONDS,
  MAX_JOB_AUDIO_NOTE_LENGTH,
  MAX_JOB_AUDIO_REMOVAL_REASON_LENGTH,
  parseCreateJobAudioNoteDto,
  parseRemoveJobAudioNoteDto,
  validateJobAudioNoteUpload,
} from './job-audio.dto.js';

/*
 * The Job audio note upload request (`BR-091`, `ADR-018`).
 *
 * Validation is the API boundary's job (`dev.md` §7): a phase Servora does not have, a missing
 * idempotency key, an oversized part, a file that is not an audio-only MP4, or a recording whose length
 * the API cannot read are all refused here — before a single byte reaches the object store, so nothing
 * that is not evidence is ever stored.
 */

/** One ISO-BMFF box, so a fixture is a real container rather than a stub. */
function box(type: string, payload: Buffer): Buffer {
  const header = Buffer.alloc(8);
  header.writeUInt32BE(payload.length + 8, 0);
  header.write(type, 4, 'latin1');
  return Buffer.concat([header, payload]);
}

/** An audio-only MP4 of [seconds], which is what the Android recorder produces (`ADR-018` A2/A3). */
function audioMp4(seconds = 18, handlers: readonly string[] = ['soun']): Buffer {
  const mvhd = Buffer.alloc(100);
  mvhd.writeUInt32BE(1000, 12);
  mvhd.writeUInt32BE(Math.round(seconds * 1000), 16);
  const traks = handlers.map((handler) => {
    const hdlr = Buffer.alloc(24);
    hdlr.write(handler, 8, 'latin1');
    return box('trak', box('mdia', box('hdlr', hdlr)));
  });
  return Buffer.concat([
    box(
      'ftyp',
      Buffer.concat([
        Buffer.from('isom', 'latin1'),
        Buffer.from([0x00, 0x00, 0x02, 0x00]),
      ]),
    ),
    box('moov', Buffer.concat([box('mvhd', mvhd), ...traks])),
  ]);
}

const OPERATION_ID = '6f1a8d1e-4b26-4f8f-9a34-2b7c9e0d5a11';

describe('job audio note requests', () => {
  describe('fields', () => {
    it('accepts a known phase, an optional note and the device instant', () => {
      expect(
        parseCreateJobAudioNoteDto({
          clientOperationId: OPERATION_ID,
          phase: 'AFTER_WORK',
          note: '  Customer agreed to the return visit  ',
          capturedAt: '2026-09-16T09:12:00.000Z',
        }),
      ).toEqual({
        clientOperationId: OPERATION_ID,
        phase: 'AFTER_WORK',
        note: 'Customer agreed to the return visit',
        capturedAt: new Date('2026-09-16T09:12:00.000Z'),
      });
    });

    it('treats a blank note as no note, because the recording is the evidence', () => {
      const dto = parseCreateJobAudioNoteDto({
        clientOperationId: OPERATION_ID,
        phase: 'DURING_WORK',
        note: '   ',
      });

      expect(dto.note).toBeNull();
      expect(dto.capturedAt).toBeNull();
    });

    it('refuses a phase Servora does not have', () => {
      expect(() =>
        parseCreateJobAudioNoteDto({
          clientOperationId: OPERATION_ID,
          phase: 'BEFORE_AFTER',
        }),
      ).toThrow(DomainValidationError);
      expect(() =>
        parseCreateJobAudioNoteDto({ clientOperationId: OPERATION_ID }),
      ).toThrow(DomainValidationError);
    });

    it('requires the idempotency key, because a recording may be uploaded more than once', () => {
      expect(() => parseCreateJobAudioNoteDto({ phase: 'DURING_WORK' })).toThrow(
        DomainValidationError,
      );
      expect(() =>
        parseCreateJobAudioNoteDto({
          phase: 'DURING_WORK',
          clientOperationId: 'not-a-uuid',
        }),
      ).toThrow(DomainValidationError);
    });

    it('refuses a note longer than the accepted limit', () => {
      expect(() =>
        parseCreateJobAudioNoteDto({
          clientOperationId: OPERATION_ID,
          phase: 'AFTER_WORK',
          note: 'x'.repeat(MAX_JOB_AUDIO_NOTE_LENGTH + 1),
        }),
      ).toThrow(DomainValidationError);
    });
  });

  describe('bytes', () => {
    it('accepts an audio-only MP4 and answers with the length its container declares', () => {
      const bytes = audioMp4(18);

      expect(
        validateJobAudioNoteUpload({
          buffer: bytes,
          mimetype: 'audio/mp4',
          size: bytes.length,
        }),
      ).toEqual({ body: bytes, contentType: 'audio/mp4', durationSeconds: 18 });
    });

    it('refuses bytes that are not a recording', () => {
      const jpeg = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(32)]);
      expect(() =>
        validateJobAudioNoteUpload({
          buffer: jpeg,
          mimetype: 'image/jpeg',
          size: jpeg.length,
        }),
      ).toThrow(DomainValidationError);
    });

    it('refuses a declaration that disagrees with the bytes', () => {
      const bytes = audioMp4();
      expect(() =>
        validateJobAudioNoteUpload({
          buffer: bytes,
          mimetype: 'audio/3gpp',
          size: bytes.length,
        }),
      ).toThrow(DomainValidationError);
    });

    it('refuses a recording longer than the accepted limit, and one that is too short', () => {
      const tooLong = audioMp4(MAX_JOB_AUDIO_DURATION_SECONDS + 1);
      expect(() =>
        validateJobAudioNoteUpload({
          buffer: tooLong,
          mimetype: 'audio/mp4',
          size: tooLong.length,
        }),
      ).toThrow(DomainValidationError);

      const tooShort = audioMp4(0.4);
      expect(() =>
        validateJobAudioNoteUpload({
          buffer: tooShort,
          mimetype: 'audio/mp4',
          size: tooShort.length,
        }),
      ).toThrow(DomainValidationError);
    });

    it('refuses a missing or empty part', () => {
      expect(() => validateJobAudioNoteUpload(undefined)).toThrow(DomainValidationError);
      expect(() =>
        validateJobAudioNoteUpload({
          buffer: Buffer.alloc(0),
          mimetype: 'audio/mp4',
          size: 0,
        }),
      ).toThrow(DomainValidationError);
    });

    it('refuses a part larger than the accepted limit', () => {
      const bytes = audioMp4();
      expect(() =>
        validateJobAudioNoteUpload({
          buffer: bytes,
          mimetype: 'audio/mp4',
          size: MAX_JOB_AUDIO_BYTES + 1,
        }),
      ).toThrow(DomainValidationError);
    });
  });

  /**
   * Taking accepted evidence out of ordinary use (`BR-089`, `ADR-018` A7).
   *
   * The reason is required, because the rule states that a removal records the actor, the instant and the
   * reason; it is bounded, because an audited action records a reason rather than arbitrary text.
   */
  describe('removal reason', () => {
    it('accepts a reason and trims it', () => {
      expect(parseRemoveJobAudioNoteDto({ reason: '  Wrong job  ' })).toEqual({
        reason: 'Wrong job',
      });
    });

    it('refuses a missing, blank or non-text reason', () => {
      expect(() => parseRemoveJobAudioNoteDto({})).toThrow(DomainValidationError);
      expect(() => parseRemoveJobAudioNoteDto({ reason: '   ' })).toThrow(
        DomainValidationError,
      );
      expect(() => parseRemoveJobAudioNoteDto({ reason: 42 })).toThrow(
        DomainValidationError,
      );
    });

    it('refuses a reason longer than the accepted limit', () => {
      expect(() =>
        parseRemoveJobAudioNoteDto({
          reason: 'x'.repeat(MAX_JOB_AUDIO_REMOVAL_REASON_LENGTH + 1),
        }),
      ).toThrow(DomainValidationError);
    });
  });
});
