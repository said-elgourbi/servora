/**
 * Configuration for transactional email delivery (`BR-043`, `ADR-008`).
 *
 * `EMAIL_PROVIDER` selects the implementation bound to the `EMAIL_PROVIDER` injection token.
 * `noop` stays the default so a machine without provider credentials still boots and still
 * refuses to pretend it delivered a message.
 *
 * There is deliberately no `file` kind here, unlike `SMS_PROVIDER`: the password-reset flow
 * already has a development sink one level up (`PASSWORD_RESET_DELIVERY=file`), so a second one
 * would only duplicate it (`dev.md` §1, KISS).
 */

export const EMAIL_PROVIDER_KINDS = ['noop', 'resend'] as const;
export type EmailProviderKind = (typeof EMAIL_PROVIDER_KINDS)[number];

export const DEFAULT_EMAIL_PROVIDER_KIND: EmailProviderKind = 'noop';

/** Resend credentials, present only when the Resend provider is selected. */
export interface ResendEmailConfig {
  readonly apiKey: string;
  /**
   * Sender address, for example `Servora <no-reply@example.com>`. It must be an address on a
   * domain verified in Resend, which makes it deployment configuration rather than a constant.
   */
  readonly from: string;
}

export interface EmailConfig {
  readonly provider: EmailProviderKind;
  readonly resend: ResendEmailConfig | null;
}

function parseProviderKind(raw: string | undefined): EmailProviderKind {
  const value =
    (raw ?? DEFAULT_EMAIL_PROVIDER_KIND).trim() || DEFAULT_EMAIL_PROVIDER_KIND;
  if (!(EMAIL_PROVIDER_KINDS as readonly string[]).includes(value)) {
    throw new Error(
      `Invalid EMAIL_PROVIDER "${value}". Allowed values: ${EMAIL_PROVIDER_KINDS.join(', ')}.`,
    );
  }
  return value as EmailProviderKind;
}

function requireValue(
  env: NodeJS.ProcessEnv,
  name: string,
  provider: string,
): string {
  const value = env[name]?.trim();
  if (!value) {
    throw new Error(
      `${name} is required when EMAIL_PROVIDER=${provider}: the provider cannot be used without it.`,
    );
  }
  return value;
}

/**
 * Loads and validates the transactional-email configuration.
 *
 * Selecting a provider without its credentials fails at startup instead of at the first
 * password reset, so a deployment never runs with a delivery path that silently cannot deliver
 * (`dev.md` §5). Credentials are read from the environment and never logged (`Project.md` §16).
 */
export function loadEmailConfig(
  env: NodeJS.ProcessEnv = process.env,
): EmailConfig {
  const provider = parseProviderKind(env.EMAIL_PROVIDER);

  if (provider !== 'resend') {
    return { provider, resend: null };
  }

  return {
    provider,
    resend: {
      apiKey: requireValue(env, 'RESEND_API_KEY', provider),
      from: requireValue(env, 'EMAIL_FROM', provider),
    },
  };
}
