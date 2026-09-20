import {
  DomainValidationError,
  optionalInstant,
  optionalText,
  optionalUuid,
  requireEnum,
  requireText,
} from '../validation/domain-validation.js';
import type { AudioContentType } from '../storage/object-storage.js';
import { EVIDENCE_PHASES } from './evidence-phase.js';
import type { EvidencePhase } from './evidence-phase.js';
import { readMp4AudioDurationSeconds, sniffAudioContentType } from './mp4-audio.js';

/*
 * The Job audio note upload request (`BR-027`, `BR-091`, `ADR-018`).
 *
 * An audio note is field evidence a technician records on a Job while working, exactly as a photo is. This
 * module validates the untrusted part of that request — the multipart text fields and the bytes
 * themselves — with the shared, dependency-free validation helpers, so the rules are unit-testable
 * without a Nest application and without an object store.
 *
 * Three rules the API deliberately keeps (`ADR-018` A2–A4):
 *
 * - The **content type is decided from the bytes**, not from what the client says the file is
 *   (`BR-015`): a recording that is not an audio-only MP4 is refused before it becomes evidence.
 * - The **length is read from the container** and the limits are applied to it. A duration a client
 *   declares is not accepted, and is not stored.
 * - The **phase** is the shared, closed vocabulary photos and audio both use (`BR-041`): a code this API
 *   does not know is refused rather than stored, because a client must not be able to invent a phase
 *   Servora does not have (`BR-042`).
 */

/**
 * The largest recording the API accepts, in bytes.
 *
 * A constant rather than a deployment setting, as the photo limit is: a limit that needs to differ per
 * environment is a product decision and none has been taken. It is a safety bound rather than the limit
 * a technician meets first — 300 s of mono AAC audio is roughly 2.4 MB at 64 kbps (`ADR-018` A3). The
 * multipart parser enforces it while reading, and this module enforces it on the buffered part, so an
 * oversized body is refused either way.
 */
export const MAX_JOB_AUDIO_BYTES = 10 * 1024 * 1024;

/** The longest recording the API accepts, in seconds (`ADR-018` A3). */
export const MAX_JOB_AUDIO_DURATION_SECONDS = 300;

/** The shortest recording the API accepts, in seconds — a recording that carries no work is not evidence. */
export const MIN_JOB_AUDIO_DURATION_SECONDS = 1;

/**
 * The longest note an audio note may carry, in characters.
 *
 * The same bound a photo's note has: the note is the technician's own text about the evidence, not a
 * report, and a bound keeps it from becoming an unbounded text field on an upload.
 */
export const MAX_JOB_AUDIO_NOTE_LENGTH = 2000;

/** The longest reason a removal may carry, in characters (`BR-089`). */
export const MAX_JOB_AUDIO_REMOVAL_REASON_LENGTH = 2000;

/** The file part as the multipart parser hands it over, without a framework type. */
export interface JobAudioNoteUpload {
  readonly buffer: Buffer;
  readonly mimetype: string;
  readonly size: number;
}

/** Fails the request with the same envelope every other parser produces. */
function fail(field: string, rule: string): never {
  throw new DomainValidationError([`${field} ${rule}`]);
}

/** The fields a caller supplies alongside the recording's bytes. */
export interface CreateJobAudioNoteDto {
  /**
   * The idempotency key the device generated once, before its first attempt (`BR-031`, standard §5).
   *
   * It is also the audio note's own identifier: the same value names the row and the object key, so a
   * retry after a timeout stores the same evidence under the same key rather than a second copy.
   */
  readonly clientOperationId: string;
  readonly phase: EvidencePhase;
  readonly note: string | null;
  /** The device instant the recording was made; display and provenance only (`BR-031`). */
  readonly capturedAt: Date | null;
}

/** Validates the multipart text fields into a `CreateJobAudioNoteDto`. */
export function parseCreateJobAudioNoteDto(input: unknown): CreateJobAudioNoteDto {
  const source = (input ?? {}) as Record<string, unknown>;
  const clientOperationId = optionalUuid(source.clientOperationId, 'clientOperationId');
  if (clientOperationId === null) {
    fail('clientOperationId', 'is required');
  }
  const capturedAt = optionalInstant(source.capturedAt, 'capturedAt');

  return {
    clientOperationId,
    phase: requireEnum(source.phase, EVIDENCE_PHASES, 'phase'),
    note: optionalText(source.note, 'note', MAX_JOB_AUDIO_NOTE_LENGTH),
    capturedAt: capturedAt === null ? null : new Date(capturedAt),
  };
}

/** The fields a caller supplies to take accepted evidence out of ordinary use (`BR-089`). */
export interface RemoveJobAudioNoteDto {
  /**
   * Why the evidence is being removed (`BR-089`). Required, as it is for a photo: the rule states that a
   * removal records the actor, the timestamp and the reason. The catalogue of reasons is free text until
   * product ownership defines one (`BR-042`).
   */
  readonly reason: string;
}

/** Validates the removal request into a `RemoveJobAudioNoteDto`. */
export function parseRemoveJobAudioNoteDto(input: unknown): RemoveJobAudioNoteDto {
  const source = (input ?? {}) as Record<string, unknown>;
  return {
    reason: requireText(source.reason, 'reason', MAX_JOB_AUDIO_REMOVAL_REASON_LENGTH),
  };
}

/** The recording's bytes, with the type and the length the bytes themselves declare. */
export interface ValidatedJobAudioNote {
  readonly body: Buffer;
  readonly contentType: AudioContentType;
  /**
   * The recording's length in whole seconds, read from the container (`ADR-018` A3).
   *
   * It is derived rather than declared, and it is what the API stores and what the limits were applied
   * to. [MAX_JOB_AUDIO_DURATION_SECONDS] and [MIN_JOB_AUDIO_DURATION_SECONDS] bound it, so the stored
   * value is always within them.
   */
  readonly durationSeconds: number;
}

/**
 * Validates the uploaded bytes and answers with what they declare.
 *
 * The bytes are inspected rather than believed: a file that is not an audio-only MP4 is refused, a
 * declared content type that disagrees with the bytes is refused, and a recording whose length cannot be
 * read from its container is refused rather than accepted with no length to bound. Nothing is stored for
 * a refusal (`BR-015`, `ADR-018` A2/A3).
 */
export function validateJobAudioNoteUpload(
  upload: JobAudioNoteUpload | undefined,
): ValidatedJobAudioNote {
  if (upload === undefined || upload.buffer.length === 0) {
    fail('file', 'is required');
  }
  if (upload.size > MAX_JOB_AUDIO_BYTES || upload.buffer.length > MAX_JOB_AUDIO_BYTES) {
    fail('file', `must be at most ${MAX_JOB_AUDIO_BYTES} bytes`);
  }

  const contentType = sniffAudioContentType(upload.buffer);
  if (contentType === null) {
    fail('file', 'must be an audio MP4 recording');
  }
  const declared = upload.mimetype.trim().toLowerCase();
  if (declared.length > 0 && declared !== contentType) {
    fail('file', `content type must be ${contentType}`);
  }

  const duration = readMp4AudioDurationSeconds(upload.buffer);
  if (duration === null) {
    fail('file', 'must declare its length');
  }
  if (duration > MAX_JOB_AUDIO_DURATION_SECONDS) {
    fail('file', `must be at most ${MAX_JOB_AUDIO_DURATION_SECONDS} seconds long`);
  }
  if (duration < MIN_JOB_AUDIO_DURATION_SECONDS) {
    fail('file', `must be at least ${MIN_JOB_AUDIO_DURATION_SECONDS} second long`);
  }

  return { body: upload.buffer, contentType, durationSeconds: Math.round(duration) };
}
