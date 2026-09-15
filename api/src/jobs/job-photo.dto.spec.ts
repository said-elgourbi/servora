import { DomainValidationError } from '../validation/domain-validation.js';
import {
  MAX_JOB_PHOTO_BYTES,
  parseCreateJobPhotoDto,
  sniffPhotoContentType,
  validateJobPhotoUpload,
} from './job-photo.dto.js';

/**
 * The Job photo upload request (`BR-015`, `BR-027`).
 *
 * Validation is the API boundary's job (`dev.md` §7): a phase Servora does not have, a missing
 * idempotency key, an oversized part, or bytes that are not an image are all refused here — before a
 * single byte reaches the object store, so nothing that is not evidence is ever stored.
 */

/** A minimal but real JPEG header, which is what the sniffing rule checks. */
const JPEG_BYTES = Buffer.concat([
  Buffer.from([0xff, 0xd8, 0xff, 0xe0]),
  Buffer.alloc(16, 0x00),
]);
const PNG_BYTES = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  Buffer.alloc(16, 0x00),
]);
const WEBP_BYTES = Buffer.concat([
  Buffer.from('RIFF', 'ascii'),
  Buffer.from([0x20, 0x00, 0x00, 0x00]),
  Buffer.from('WEBP', 'ascii'),
  Buffer.alloc(16, 0x00),
]);

const OPERATION_ID = '6f1a8d1e-4b26-4f8f-9a34-2b7c9e0d5a11';

describe('job photo requests', () => {
  describe('fields', () => {
    it('accepts a known phase, an optional note and the device instant', () => {
      expect(
        parseCreateJobPhotoDto({
          clientOperationId: OPERATION_ID,
          phase: 'BEFORE_WORK',
          note: '  Panel before the repair  ',
          capturedAt: '2026-09-15T13:04:05.000Z',
        }),
      ).toEqual({
        clientOperationId: OPERATION_ID,
        phase: 'BEFORE_WORK',
        note: 'Panel before the repair',
        capturedAt: new Date('2026-09-15T13:04:05.000Z'),
      });
    });

    it('treats a blank note as no note, because the note is optional', () => {
      const dto = parseCreateJobPhotoDto({
        clientOperationId: OPERATION_ID,
        phase: 'DURING_WORK',
        note: '   ',
      });

      expect(dto.note).toBeNull();
      expect(dto.capturedAt).toBeNull();
    });

    it('refuses a phase Servora does not have', () => {
      expect(() =>
        parseCreateJobPhotoDto({
          clientOperationId: OPERATION_ID,
          phase: 'BEFORE_AFTER',
        }),
      ).toThrow(DomainValidationError);
      expect(() =>
        parseCreateJobPhotoDto({ clientOperationId: OPERATION_ID }),
      ).toThrow(DomainValidationError);
    });

    it('requires the idempotency key, because a photo may be uploaded more than once', () => {
      expect(() =>
        parseCreateJobPhotoDto({ phase: 'DURING_WORK' }),
      ).toThrow(DomainValidationError);
      expect(() =>
        parseCreateJobPhotoDto({ phase: 'DURING_WORK', clientOperationId: 'not-a-uuid' }),
      ).toThrow(DomainValidationError);
    });

    it('refuses a note longer than the shared limit', () => {
      expect(() =>
        parseCreateJobPhotoDto({
          clientOperationId: OPERATION_ID,
          phase: 'AFTER_WORK',
          note: 'x'.repeat(2001),
        }),
      ).toThrow(DomainValidationError);
    });
  });

  describe('bytes', () => {
    it('accepts a JPEG, a PNG and a WebP, and decides the type from the bytes', () => {
      expect(sniffPhotoContentType(JPEG_BYTES)).toBe('image/jpeg');
      expect(sniffPhotoContentType(PNG_BYTES)).toBe('image/png');
      expect(sniffPhotoContentType(WEBP_BYTES)).toBe('image/webp');

      expect(
        validateJobPhotoUpload({
          buffer: JPEG_BYTES,
          mimetype: 'image/jpeg',
          size: JPEG_BYTES.length,
        }),
      ).toEqual({ body: JPEG_BYTES, contentType: 'image/jpeg' });
    });

    it('refuses bytes that are not one of the image types Servora accepts', () => {
      expect(sniffPhotoContentType(Buffer.from('not an image'))).toBeNull();
      expect(() =>
        validateJobPhotoUpload({
          buffer: Buffer.from('not an image'),
          mimetype: 'image/jpeg',
          size: 12,
        }),
      ).toThrow(DomainValidationError);
    });

    it('refuses a declaration that disagrees with the bytes', () => {
      expect(() =>
        validateJobPhotoUpload({
          buffer: PNG_BYTES,
          mimetype: 'image/jpeg',
          size: PNG_BYTES.length,
        }),
      ).toThrow(DomainValidationError);
    });

    it('refuses a missing or empty part', () => {
      expect(() => validateJobPhotoUpload(undefined)).toThrow(
        DomainValidationError,
      );
      expect(() =>
        validateJobPhotoUpload({
          buffer: Buffer.alloc(0),
          mimetype: 'image/jpeg',
          size: 0,
        }),
      ).toThrow(DomainValidationError);
    });

    it('refuses a part larger than the accepted limit', () => {
      expect(() =>
        validateJobPhotoUpload({
          buffer: JPEG_BYTES,
          mimetype: 'image/jpeg',
          size: MAX_JOB_PHOTO_BYTES + 1,
        }),
      ).toThrow(DomainValidationError);
    });
  });
});
