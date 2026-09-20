/**
 * Configuration for object storage (`ADR-013` D6).
 *
 * `STORAGE_PROVIDER` selects the implementation bound to the `OBJECT_STORAGE` token. `noop` stays
 * the default so a machine with no storage credentials still boots — and still refuses to pretend it
 * stored evidence, which is why the no-op implementation fails every write rather than accepting
 * one.
 *
 * The application's contract is **S3** (`ADR-013` D1, D6): MinIO is the local stand-in, and a
 * production deployment replaces it with any S3-compatible provider by configuration. Nothing here
 * names a provider.
 */

export const STORAGE_PROVIDER_KINDS = ['noop', 's3'] as const;
export type StorageProviderKind = (typeof STORAGE_PROVIDER_KINDS)[number];

export const DEFAULT_STORAGE_PROVIDER_KIND: StorageProviderKind = 'noop';

/** The S3 contract the adapter reads, present only when the `s3` provider is selected. */
export interface S3StorageConfig {
  /** Endpoint the API talks to (`http://minio:9000` inside compose). */
  readonly endpoint: string;
  /** Signing region; `us-east-1` is MinIO's default. */
  readonly region: string;
  readonly accessKey: string;
  readonly secretKey: string;
  /** The bucket the deployment provisioned; the API never creates one (`ADR-013` D4). */
  readonly bucket: string;
  /**
   * Path-style addressing. Required for MinIO, because neither `bucket.minio:9000` nor
   * `bucket.127.0.0.1:9000` resolves, and supported by the providers (`ADR-013` D6.3).
   */
  readonly forcePathStyle: boolean;
}

export interface StorageConfig {
  readonly provider: StorageProviderKind;
  readonly s3: S3StorageConfig | null;
}

function parseProviderKind(raw: string | undefined): StorageProviderKind {
  const value =
    (raw ?? DEFAULT_STORAGE_PROVIDER_KIND).trim() || DEFAULT_STORAGE_PROVIDER_KIND;
  if (!(STORAGE_PROVIDER_KINDS as readonly string[]).includes(value)) {
    throw new Error(
      `Invalid STORAGE_PROVIDER "${value}". Allowed values: ${STORAGE_PROVIDER_KINDS.join(', ')}.`,
    );
  }
  return value as StorageProviderKind;
}

function requireValue(
  env: NodeJS.ProcessEnv,
  name: string,
  provider: StorageProviderKind,
): string {
  const value = env[name]?.trim();
  if (!value) {
    throw new Error(
      `${name} is required when STORAGE_PROVIDER=${provider}: the provider cannot be used without it.`,
    );
  }
  return value;
}

/** An endpoint that is not a URL is rejected at startup rather than on the first upload. */
function parseEndpoint(endpoint: string): string {
  let parsed: URL;
  try {
    parsed = new URL(endpoint);
  } catch {
    throw new Error(
      `Invalid S3_ENDPOINT "${endpoint}". It must be a valid URL, for example http://minio:9000.`,
    );
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new Error(
      `Invalid S3_ENDPOINT "${endpoint}". Expected an http:// or https:// URL.`,
    );
  }
  return endpoint;
}

function parseBoolean(raw: string | undefined, name: string, fallback: boolean): boolean {
  const value = raw?.trim().toLowerCase();
  if (value === undefined || value === '') {
    return fallback;
  }
  if (['true', '1', 'yes'].includes(value)) {
    return true;
  }
  if (['false', '0', 'no'].includes(value)) {
    return false;
  }
  throw new Error(`Invalid ${name} "${raw}". Expected true or false.`);
}

/**
 * Loads and validates the storage configuration.
 *
 * Selecting the `s3` provider without usable configuration fails at startup instead of at the first
 * evidence upload, in the shape `loadSmsConfig` already established (`ADR-013` D6.2,
 * `dev.md` §5). Credentials are read from the environment and never logged.
 */
export function loadStorageConfig(env: NodeJS.ProcessEnv = process.env): StorageConfig {
  const provider = parseProviderKind(env.STORAGE_PROVIDER);

  if (provider === 'noop') {
    return { provider, s3: null };
  }

  return {
    provider,
    s3: {
      endpoint: parseEndpoint(requireValue(env, 'S3_ENDPOINT', provider)),
      region: requireValue(env, 'S3_REGION', provider),
      accessKey: requireValue(env, 'S3_ACCESS_KEY', provider),
      secretKey: requireValue(env, 'S3_SECRET_KEY', provider),
      bucket: requireValue(env, 'S3_BUCKET', provider),
      // Path style is the local MinIO default and remains a supported provider setting.
      forcePathStyle: parseBoolean(env.S3_FORCE_PATH_STYLE, 'S3_FORCE_PATH_STYLE', true),
    },
  };
}
