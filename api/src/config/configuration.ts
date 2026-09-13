const ALLOWED_NODE_ENVS = ['development', 'test', 'production'] as const;
export type NodeEnv = (typeof ALLOWED_NODE_ENVS)[number];

const ALLOWED_LOG_LEVELS = [
  'fatal',
  'error',
  'warn',
  'info',
  'debug',
  'trace',
  'silent',
] as const;
export type LogLevel = (typeof ALLOWED_LOG_LEVELS)[number];

export interface AppConfig {
  readonly nodeEnv: NodeEnv;
  readonly port: number;
  readonly databaseUrl: string;
  readonly logLevel: LogLevel;
}

const DEFAULT_PORT = 3000;
const DEFAULT_LOG_LEVEL: LogLevel = 'info';
const DEFAULT_DATABASE_URL =
  'postgres://servora:servora@localhost:5432/servora';

function isOneOf<T extends readonly string[]>(
  value: string,
  allowed: T,
): value is (typeof allowed)[number] {
  return allowed.includes(value as (typeof allowed)[number]);
}

function parseNodeEnv(raw: string | undefined): NodeEnv {
  const value = (raw ?? 'development').trim() || 'development';
  if (!isOneOf(value, ALLOWED_NODE_ENVS)) {
    throw new Error(
      `Invalid NODE_ENV "${value}". Allowed values: ${ALLOWED_NODE_ENVS.join(', ')}.`,
    );
  }
  return value;
}

function parseLogLevel(raw: string | undefined): LogLevel {
  const value = (raw ?? DEFAULT_LOG_LEVEL).trim() || DEFAULT_LOG_LEVEL;
  if (!isOneOf(value, ALLOWED_LOG_LEVELS)) {
    throw new Error(
      `Invalid LOG_LEVEL "${value}". Allowed values: ${ALLOWED_LOG_LEVELS.join(', ')}.`,
    );
  }
  return value;
}

function parsePort(raw: string | undefined): number {
  const value = (raw ?? String(DEFAULT_PORT)).trim() || String(DEFAULT_PORT);
  const port = Number(value);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error(
      `Invalid PORT "${value}". Expected an integer between 1 and 65535.`,
    );
  }
  return port;
}

function parseDatabaseUrl(raw: string | undefined): string {
  const value = (raw ?? DEFAULT_DATABASE_URL).trim() || DEFAULT_DATABASE_URL;
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error(`Invalid DATABASE_URL "${value}". It must be a valid URL.`);
  }
  if (parsed.protocol !== 'postgres:' && parsed.protocol !== 'postgresql:') {
    throw new Error(
      `Invalid DATABASE_URL "${value}". Expected a postgres:// or postgresql:// URL.`,
    );
  }
  if (!parsed.hostname) {
    throw new Error(`Invalid DATABASE_URL "${value}". It must include a host.`);
  }
  return value;
}

/**
 * Loads and validates the API configuration from the environment.
 *
 * Fails fast with a descriptive error when a required value is malformed so a
 * misconfigured process never starts half-configured.
 */
export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  return {
    nodeEnv: parseNodeEnv(env.NODE_ENV),
    port: parsePort(env.PORT),
    databaseUrl: parseDatabaseUrl(env.DATABASE_URL),
    logLevel: parseLogLevel(env.LOG_LEVEL),
  };
}
