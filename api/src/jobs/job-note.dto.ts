import { DomainValidationError, optionalUuid, requireText } from '../validation/domain-validation.js';

export interface AddJobNoteDto {
  body: string;
  clientOperationId: string | null;
}

export function parseAddJobNoteDto(input: unknown): AddJobNoteDto {
  const source = (input ?? {}) as Record<string, unknown>;
  const body = requireText(source.body, 'body', 4000);
  if (!body.trim()) throw new DomainValidationError(['body must contain text']);
  return { body, clientOperationId: optionalUuid(source.clientOperationId, 'clientOperationId') };
}
