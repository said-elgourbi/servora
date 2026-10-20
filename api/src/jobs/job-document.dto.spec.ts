import { DomainValidationError } from '../validation/domain-validation.js';
import { jobDocumentObjectKey } from '../storage/object-storage.js';
import {
  MAX_JOB_DOCUMENT_BYTES,
  MAX_JOB_DOCUMENT_DISPLAY_NAME_LENGTH,
  parseCreateJobDocumentDto,
  quoteWorkflowStateAfterQuoteDocumentReplacement,
  sniffJobDocumentContentType,
  validateJobDocumentUpload,
} from './job-document.dto.js';

const PDF_BYTES = Buffer.concat([
  Buffer.from('%PDF-1.7\n', 'ascii'),
  Buffer.from('1 0 obj\n<<>>\nendobj\n%%EOF\n', 'ascii'),
]);

describe('job document requests', () => {
  describe('fields', () => {
    it('accepts a quote document with an optional display name', () => {
      expect(
        parseCreateJobDocumentDto({
          documentType: 'QUOTE',
          displayName: '  Assessment quote.pdf  ',
        }),
      ).toEqual({
        documentType: 'QUOTE',
        displayName: 'Assessment quote.pdf',
      });
    });

    it('treats a blank display name as no display name', () => {
      expect(
        parseCreateJobDocumentDto({
          documentType: 'QUOTE',
          displayName: '   ',
        }).displayName,
      ).toBeNull();
    });

    it('refuses an unknown document type', () => {
      expect(() =>
        parseCreateJobDocumentDto({ documentType: 'INVOICE' }),
      ).toThrow(DomainValidationError);
    });

    it('refuses a display name longer than the accepted limit', () => {
      expect(() =>
        parseCreateJobDocumentDto({
          documentType: 'QUOTE',
          displayName: 'x'.repeat(MAX_JOB_DOCUMENT_DISPLAY_NAME_LENGTH + 1),
        }),
      ).toThrow(DomainValidationError);
    });
  });

  describe('bytes', () => {
    it('accepts a PDF and decides the type from the bytes', () => {
      expect(sniffJobDocumentContentType(PDF_BYTES)).toBe('application/pdf');

      expect(
        validateJobDocumentUpload({
          buffer: PDF_BYTES,
          mimetype: 'application/pdf',
          size: PDF_BYTES.length,
        }),
      ).toEqual({ body: PDF_BYTES, contentType: 'application/pdf' });
    });

    it('refuses bytes that are not a PDF', () => {
      expect(sniffJobDocumentContentType(Buffer.from('not a pdf'))).toBeNull();
      expect(() =>
        validateJobDocumentUpload({
          buffer: Buffer.from('not a pdf'),
          mimetype: 'application/pdf',
          size: 9,
        }),
      ).toThrow(DomainValidationError);
    });

    it('refuses a declaration that disagrees with the bytes', () => {
      expect(() =>
        validateJobDocumentUpload({
          buffer: PDF_BYTES,
          mimetype: 'image/png',
          size: PDF_BYTES.length,
        }),
      ).toThrow(DomainValidationError);
    });

    it('refuses a missing, empty, or oversized part', () => {
      expect(() => validateJobDocumentUpload(undefined)).toThrow(
        DomainValidationError,
      );
      expect(() =>
        validateJobDocumentUpload({
          buffer: Buffer.alloc(0),
          mimetype: 'application/pdf',
          size: 0,
        }),
      ).toThrow(DomainValidationError);
      expect(() =>
        validateJobDocumentUpload({
          buffer: PDF_BYTES,
          mimetype: 'application/pdf',
          size: MAX_JOB_DOCUMENT_BYTES + 1,
        }),
      ).toThrow(DomainValidationError);
    });
  });

  it('requires a replacement sent quote to be sent again', () => {
    expect(quoteWorkflowStateAfterQuoteDocumentReplacement('SENT')).toBe(
      'REQUIRED',
    );
    expect(quoteWorkflowStateAfterQuoteDocumentReplacement('REQUIRED')).toBe(
      'REQUIRED',
    );
    expect(quoteWorkflowStateAfterQuoteDocumentReplacement('APPROVED')).toBe(
      'APPROVED',
    );
    expect(quoteWorkflowStateAfterQuoteDocumentReplacement('REJECTED')).toBe(
      'REJECTED',
    );
  });

  it('stores documents outside the evidence keyspace', () => {
    expect(
      jobDocumentObjectKey({
        organizationId: 'org-1',
        jobId: 'job-1',
        documentId: 'doc-1',
        contentType: 'application/pdf',
      }),
    ).toBe('jobs/documents/org-1/job-1/doc-1.pdf');
  });
});
