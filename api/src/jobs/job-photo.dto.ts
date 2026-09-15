import {
  DomainValidationError,
  optionalInstant,
  optionalText,
  optionalUuid,
  requireEnum,
} from '../validation/domain-validation.js';
import type { PhotoContentType } from '../storage/object-storage.js';

/*
 * The Job photo upload request (`BR-015`, `BR-027`).
 *
 * A photo is field evidence a technician attaches to a Job while working. This module validates the
 * untrusted part of that request — the multipart text fields and the bytes themselves — with the
 * shared, dependency-free validation helpers, so the rules are unit-testable without a Nest
 * application and without an object store.
 *
 * Two rules the API deliberately keeps:
 *
 * - The **field-work phase** is a closed, stable vocabulary (`BR-028`, `BR-041`): a code this API
 *   does not know is refused rather than stored, because a client must not be able to invent a phase
 *   Servora does not have (`BR-042`).
 * - The **content type is decided from the bytes**, not from what the client says the file is
 *   (`BR-015`, `ADR-013` D7): bytes are validated before they become evidence, instead of trusting a
 *   client's declaration.
 */

/** The field-work phase a photo was taken in (`BR-027`). */
export const JOB_PHOTO_PHASES = ['BEFORE_WORK', 'DURING_WORK', 'AFTER_WORK'] as const;

export type JobPhotoPhase = (typeof JOB_PHOTO_PHASES)[number];

/**
 * The largest photo the API accepts, in bytes.
 *
 * It is a constant rather than a deployment setting: a limit that needs to differ per environment is
 * a product decision and none has been taken. The multipart parser enforces it while reading, and
 * this module enforces it on the buffered part, so an oversized body is refused either way.
 */
export const MAX_JOB_PHOTO_BYTES = 15 * 1024 * 1024;

/** The file part as the multipart parser hands it over, without a framework type. */
export interface JobPhotoUpload {
  readonly buffer: Buffer;
  readonly mimetype: string;
  readonly size: number;
}

/** Fails the request with the same envelope every other parser produces. */
function fail(field: string, rule: string): never {
  throw new DomainValidationError([`${field} ${rule}`]);
}

/** The fields a caller supplies alongside the photo bytes. */
export interface CreateJobPhotoDto {
  /**
   * The idempotency key the device generated once, before its first attempt (`BR-031`, standard §5).
   *
   * It is also the photo's own identifier: the same value names the row and the object key, so a
   * retry after a timeout stores the same evidence under the same key rather than a second copy.
   */
  readonly clientOperationId: string;
  readonly phase: JobPhotoPhase;
  readonly note: string | null;
  /** The device instant the photo was taken; display and provenance only (`BR-031`). */
  readonly capturedAt: Date | null;
}

/** Validates the multipart text fields into a `CreateJobPhotoDto`. */
export function parseCreateJobPhotoDto(input: unknown): CreateJobPhotoDto {
  const source = (input ?? {}) as Record<string, unknown>;
  const clientOperationId = optionalUuid(
    source.clientOperationId,
    'clientOperationId',
  );
  if (clientOperationId === null) {
    fail('clientOperationId', 'is required');
  }
  const capturedAt = optionalInstant(source.capturedAt, 'capturedAt');

  return {
    clientOperationId,
    phase: requireEnum(source.phase, JOB_PHOTO_PHASES, 'phase'),
    note: optionalText(source.note, 'note', 2000),
    capturedAt: capturedAt === null ? null : new Date(capturedAt),
  };
}

/** The photo's bytes, with the type the bytes themselves declare. */
export interface ValidatedJobPhoto {
  readonly body: Buffer;
  readonly contentType: PhotoContentType;
}

/**
 * Validates the uploaded bytes and decides their content type.
 *
 * The bytes are sniffed rather than believed: a file that is not a JPEG, PNG or WebP is refused, and
 * a declared content type that disagrees with the bytes is refused too, so the stored object never
 * claims a type it does not have.
 */
export function validateJobPhotoUpload(
  upload: JobPhotoUpload | undefined,
): ValidatedJobPhoto {
  if (upload === undefined || upload.buffer.length === 0) {
    fail('file', 'is required');
  }
  if (upload.size > MAX_JOB_PHOTO_BYTES || upload.buffer.length > MAX_JOB_PHOTO_BYTES) {
    fail('file', `must be at most ${MAX_JOB_PHOTO_BYTES} bytes`);
  }

  const contentType = sniffPhotoContentType(upload.buffer);
  if (contentType === null) {
    fail('file', 'must be a JPEG, PNG or WebP image');
  }
  const declared = upload.mimetype.trim().toLowerCase();
  if (declared.length > 0 && declared !== contentType) {
    fail('file', `content type must be ${contentType}`);
  }

  return { body: upload.buffer, contentType };
}

/**
 * The image type the bytes declare, or `null` when they are not one Servora accepts.
 *
 * A magic-number check is used rather than a decoding library: it is dependency-free, it cannot be
 * fooled by a file name, and it is exactly the guarantee this boundary needs — the stored object is
 * an image of a type a client is allowed to send.
 */
export function sniffPhotoContentType(bytes: Buffer): PhotoContentType | null {
  if (
    bytes.length >= 3 &&
    bytes[0] === 0xff &&
    bytes[1] === 0xd8 &&
    bytes[2] === 0xff
  ) {
    return 'image/jpeg';
  }
  const png = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  if (bytes.length >= png.length && png.every((byte, index) => bytes[index] === byte)) {
    return 'image/png';
  }
  if (
    bytes.length >= 12 &&
    bytes.toString('ascii', 0, 4) === 'RIFF' &&
    bytes.toString('ascii', 8, 12) === 'WEBP'
  ) {
    return 'image/webp';
  }
  return null;
}

