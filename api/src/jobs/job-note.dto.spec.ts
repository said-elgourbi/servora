import { describe, expect, it } from 'vitest';
import { DomainValidationError } from '../validation/domain-validation.js';
import { parseAddJobNoteDto } from './job-note.dto.js';

describe('Job update requests', () => {
  it('trims a bounded update and accepts an idempotency key', () => {
    const note = parseAddJobNoteDto({
      body: '  Access confirmed.  ',
      clientOperationId: '6f1a8d1e-4b26-4f8f-9a34-2b7c9e0d5a11',
    });
    expect(note.body).toBe('Access confirmed.');
    expect(note.clientOperationId).toBe('6f1a8d1e-4b26-4f8f-9a34-2b7c9e0d5a11');
  });

  it('rejects empty text, oversized content and malformed retry identifiers', () => {
    expect(() => parseAddJobNoteDto({ body: '  ' })).toThrow(DomainValidationError);
    expect(() => parseAddJobNoteDto({ body: 'x'.repeat(4001) })).toThrow(DomainValidationError);
    expect(() => parseAddJobNoteDto({ body: 'Update', clientOperationId: 'bad' })).toThrow(DomainValidationError);
  });
});
