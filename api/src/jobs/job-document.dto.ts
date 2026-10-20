import {
  DomainValidationError,
  optionalText,
  requireEnum,
} from '../validation/domain-validation.js';
import type { JobDocumentContentType } from '../storage/object-storage.js';
import type { JobQuoteWorkflowState } from './job.types.js';

/*
 * The Job document upload request.
 *
 * Job documents are office/commercial artifacts attached directly to a Job. The first supported type
 * is a quote PDF for Assessment Visits that conclude with `QUOTE_REQUIRED`; it is deliberately not
 * modeled as Visit evidence, so this boundary has no evidence phase or Visit id.
 */

export const JOB_DOCUMENT_TYPES = ['QUOTE'] as const;

export type JobDocumentType = (typeof JOB_DOCUMENT_TYPES)[number];

/** The largest Job document the API accepts, in bytes. */
export const MAX_JOB_DOCUMENT_BYTES = 20 * 1024 * 1024;

/** The longest optional display name a Job document may carry, in characters. */
export const MAX_JOB_DOCUMENT_DISPLAY_NAME_LENGTH = 255;

/** The file part as the multipart parser hands it over, without a framework type. */
export interface JobDocumentUpload {
  readonly buffer: Buffer;
  readonly mimetype: string;
  readonly size: number;
}

/** The fields a caller supplies alongside the document bytes. */
export interface CreateJobDocumentDto {
  readonly documentType: JobDocumentType;
  readonly displayName: string | null;
}

/** The metadata the API returns after a Job document write. */
export interface JobDocumentDto {
  readonly id: string;
  readonly jobId: string;
  readonly documentType: JobDocumentType;
  readonly displayName: string | null;
  readonly documentVersion: number;
  readonly isCurrent: boolean;
  readonly contentType: JobDocumentContentType;
  readonly byteSize: number;
  readonly uploadedByMembershipId: string;
  readonly supersededAt: string | null;
  readonly supersededByJobDocumentId: string | null;
  readonly recordedAt: string;
}

/** The uploaded document's bytes, with the type the bytes themselves declare. */
export interface ValidatedJobDocument {
  readonly body: Buffer;
  readonly contentType: JobDocumentContentType;
}

/** Fails the request with the same envelope every other parser produces. */
function fail(field: string, rule: string): never {
  throw new DomainValidationError([`${field} ${rule}`]);
}

/** Validates the multipart text fields into a `CreateJobDocumentDto`. */
export function parseCreateJobDocumentDto(
  input: unknown,
): CreateJobDocumentDto {
  const source = (input ?? {}) as Record<string, unknown>;

  return {
    documentType: requireEnum(
      source.documentType,
      JOB_DOCUMENT_TYPES,
      'documentType',
    ),
    displayName: optionalText(
      source.displayName,
      'displayName',
      MAX_JOB_DOCUMENT_DISPLAY_NAME_LENGTH,
    ),
  };
}

/**
 * Validates the uploaded bytes and decides their content type.
 *
 * Quote documents are PDF-only. The API sniffs the magic header and refuses a declared content type
 * that disagrees with the bytes, so stored metadata cannot claim a non-PDF is a quote document.
 */
export function validateJobDocumentUpload(
  upload: JobDocumentUpload | undefined,
): ValidatedJobDocument {
  if (upload === undefined || upload.buffer.length === 0) {
    fail('file', 'is required');
  }
  if (
    upload.size > MAX_JOB_DOCUMENT_BYTES ||
    upload.buffer.length > MAX_JOB_DOCUMENT_BYTES
  ) {
    fail('file', `must be at most ${MAX_JOB_DOCUMENT_BYTES} bytes`);
  }

  const contentType = sniffJobDocumentContentType(upload.buffer);
  if (contentType === null) {
    fail('file', 'must be a PDF document');
  }
  const declared = upload.mimetype.trim().toLowerCase();
  if (declared.length > 0 && declared !== contentType) {
    fail('file', `content type must be ${contentType}`);
  }

  return { body: upload.buffer, contentType };
}

/** The document type the bytes declare, or `null` when they are not one Servora accepts. */
export function sniffJobDocumentContentType(
  bytes: Buffer,
): JobDocumentContentType | null {
  if (bytes.length >= 5 && bytes.toString('ascii', 0, 5) === '%PDF-') {
    return 'application/pdf';
  }
  return null;
}

/**
 * A replacement quote is a new commercial artifact. If the previous quote had already been sent,
 * the replacement must be explicitly sent again instead of inheriting customer-delivery state.
 */
export function quoteWorkflowStateAfterQuoteDocumentReplacement(
  state: JobQuoteWorkflowState,
): JobQuoteWorkflowState {
  return state === 'SENT' ? 'REQUIRED' : state;
}
