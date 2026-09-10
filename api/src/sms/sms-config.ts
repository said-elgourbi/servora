/**
 * Configuration for SMS delivery (`BR-019`, `ADR-006` D4).
 *
 * `SMS_PROVIDER` selects the implementation bound to the `SMS_PROVIDER` injection token.
 * `noop` stays the default so a machine without provider credentials still boots and still
 * refuses to pretend it delivered an OTP.
 */

export const SMS_PROVIDER_KINDS = ['noop', 'sinch', 'file'] as const;
export type SmsProviderKind = (typeof SMS_PROVIDER_KINDS)[number];

export const DEFAULT_SMS_PROVIDER_KIND: SmsProviderKind = 'noop';

/**
 * Where the `file` provider writes messages. It is a development sink: the directory is
 * git-ignored because it contains one-time codes.
 */
export const DEFAULT_SMS_FILE_DIRECTORY = 'var/dev-notifications';

/** Sinch credentials, present only when the Sinch provider is selected. */
export interface SinchSmsConfig {
  readonly servicePlanId: string;
  readonly apiToken: string;
  readonly from: string;
}

export interface SmsConfig {
  readonly provider: SmsProviderKind;
  readonly sinch: SinchSmsConfig | null;
  readonly directory: string;
}

function parseProviderKind(raw: string | undefined): SmsProviderKind {
  const value =
    (raw ?? DEFAULT_SMS_PROVIDER_KIND).trim() || DEFAULT_SMS_PROVIDER_KIND;
  if (!(SMS_PROVIDER_KINDS as readonly string[]).includes(value)) {
    throw new Error(
      `Invalid SMS_PROVIDER "${value}". Allowed values: ${SMS_PROVIDER_KINDS.join(', ')}.`,
    );
  }
  return value as SmsProviderKind;
}

function requireValue(env: NodeJS.ProcessEnv, name: string): string {
  const value = env[name]?.trim();
  if (!value) {
    throw new Error(
      `${name} is required when SMS_PROVIDER=sinch: the provider cannot be used without it.`,
    );
  }
  return value;
}

/**
 * Loads and validates the SMS configuration.
 *
 * Selecting a provider without its credentials fails at startup instead of at the first OTP,
 * so a deployment never runs with a delivery path that silently cannot deliver (`dev.md` §5).
 * Credentials are read from the environment and never logged (`dev.md` §5, `Project.md` §16).
 */
export function loadSmsConfig(env: NodeJS.ProcessEnv = process.env): SmsConfig {
  const provider = parseProviderKind(env.SMS_PROVIDER);
  const directory =
    (env.SMS_FILE_DIRECTORY ?? '').trim() || DEFAULT_SMS_FILE_DIRECTORY;

  if (provider === 'file' && (env.NODE_ENV ?? '').trim() === 'production') {
    // The file provider writes a one-time code to disk, so it is a development affordance that
    // must not be reachable in a deployment (`dev.md` §11).
    throw new Error(
      'SMS_PROVIDER=file writes one-time passwords to disk and must not be used with NODE_ENV=production. Configure a real provider instead.',
    );
  }

  if (provider !== 'sinch') {
    return { provider, sinch: null, directory };
  }

  return {
    provider,
    directory,
    sinch: {
      servicePlanId: requireValue(env, 'SINCH_SERVICE_PLAN_ID'),
      apiToken: requireValue(env, 'SINCH_API_TOKEN'),
      from: requireValue(env, 'SINCH_FROM'),
    },
  };
}
