/**
 * Client context recorded on a new session
 * (`docs/domain/authentication-domain-model.md` §2).
 *
 * These values are observed by the server, never supplied by the client. A value
 * the server cannot represent is stored as `null` rather than rejected: observation
 * metadata must not prevent a technician from signing in (`BR-012`).
 */
export interface ClientContext {
  readonly ipAddress: string | null;
  readonly userAgent: string | null;
}

/** Matches the `user_agent varchar(500)` column. */
const MAX_USER_AGENT_LENGTH = 500;

// Guards the PostgreSQL `inet` column against any value that is not an address.
const IP_ADDRESS_PATTERN = /^[0-9a-fA-F.:]+$/;
const MAX_IP_ADDRESS_LENGTH = 45;

/** The subset of an HTTP request this module reads; kept minimal for testability. */
export interface ObservedRequest {
  readonly ip: string | undefined;
  readonly headers: Record<string, string | string[] | undefined>;
}

export function readClientContext(request: ObservedRequest): ClientContext {
  return {
    ipAddress: normalizeIpAddress(request.ip),
    userAgent: normalizeUserAgent(request.headers['user-agent']),
  };
}

function normalizeIpAddress(value: string | undefined): string | null {
  if (typeof value !== 'string') {
    return null;
  }
  const trimmed = value.trim();
  if (
    trimmed.length === 0 ||
    trimmed.length > MAX_IP_ADDRESS_LENGTH ||
    !IP_ADDRESS_PATTERN.test(trimmed)
  ) {
    return null;
  }
  return trimmed;
}

function normalizeUserAgent(
  value: string | string[] | undefined,
): string | null {
  const single = Array.isArray(value) ? value[0] : value;
  if (typeof single !== 'string') {
    return null;
  }
  const trimmed = single.trim();
  if (trimmed.length === 0) {
    return null;
  }
  return trimmed.slice(0, MAX_USER_AGENT_LENGTH);
}
