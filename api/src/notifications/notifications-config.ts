/**
 * Configuration for password-reset delivery (`ADR-006` D3, `ADR-008`).
 *
 * The transport is selected by `PASSWORD_RESET_DELIVERY`: `noop` delivers nothing, `file` writes
 * messages to disk (development only), and `email` delivers through the transactional-email port,
 * whose own selection (`EMAIL_PROVIDER`) decides which service sends. `noop` stays the default,
 * and the default is the safe one: it delivers nothing rather than pretending to deliver.
 */
export const PASSWORD_RESET_DELIVERIES = ['noop', 'file', 'email'] as const;
export type PasswordResetDelivery = (typeof PASSWORD_RESET_DELIVERIES)[number];

export const DEFAULT_PASSWORD_RESET_DELIVERY: PasswordResetDelivery = 'noop';

/**
 * Where the `file` transport writes messages. Relative paths resolve against the process
 * working directory, and the directory is git-ignored because it contains one-time codes.
 */
export const DEFAULT_PASSWORD_RESET_DIRECTORY = 'var/dev-notifications';

export interface NotificationsConfig {
  readonly delivery: PasswordResetDelivery;
  readonly directory: string;
}

function parseDelivery(raw: string | undefined): PasswordResetDelivery {
  const value =
    (raw ?? DEFAULT_PASSWORD_RESET_DELIVERY).trim() ||
    DEFAULT_PASSWORD_RESET_DELIVERY;
  if (!(PASSWORD_RESET_DELIVERIES as readonly string[]).includes(value)) {
    throw new Error(
      `Invalid PASSWORD_RESET_DELIVERY "${value}". Allowed values: ${PASSWORD_RESET_DELIVERIES.join(', ')}.`,
    );
  }
  return value as PasswordResetDelivery;
}

/**
 * Loads and validates the notification configuration.
 *
 * Refusing `file` under `NODE_ENV=production` is the point of this loader: it writes a
 * one-time code to disk, so it is a development affordance that must not be reachable in a
 * deployment (`dev.md` §11). The check is here, at startup, rather than in the transport, so
 * a misconfiguration fails loudly instead of silently persisting credentials.
 */
export function loadNotificationsConfig(
  env: NodeJS.ProcessEnv = process.env,
): NotificationsConfig {
  const delivery = parseDelivery(env.PASSWORD_RESET_DELIVERY);

  if (delivery === 'file' && (env.NODE_ENV ?? '').trim() === 'production') {
    throw new Error(
      'PASSWORD_RESET_DELIVERY=file writes password-reset codes to disk and must not be used with NODE_ENV=production. Configure a real delivery transport instead.',
    );
  }

  const directory =
    (env.PASSWORD_RESET_DELIVERY_DIRECTORY ?? '').trim() ||
    DEFAULT_PASSWORD_RESET_DIRECTORY;

  return { delivery, directory };
}
