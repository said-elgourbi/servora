import { AuthApiError } from '../auth/auth-error.js';

export interface PushRegistrationRequest {
  readonly expectedRevision: number;
  readonly applicationId: string;
  readonly registration: { readonly kind: 'FCM_TOKEN'; readonly value: string };
  readonly locale: 'en-CA' | 'fr-CA';
  readonly alertsEnabled: boolean;
  readonly notificationPermission: 'GRANTED' | 'DENIED' | 'NOT_REQUESTED';
  readonly channelEnabled: boolean;
}

export interface PushInstallationDto {
  readonly installationId: string;
  readonly organizationId: string;
  readonly bindingId: string;
  readonly revision: number;
  readonly alertsEnabled: boolean;
  readonly deliveryEnabled: boolean;
  readonly registeredAt: string;
}

export function parseInstallationId(value: string): string {
  if (
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
      value,
    )
  )
    fail();
  return value.toLowerCase();
}

export function parseInstallationSecret(value: unknown): string {
  if (
    typeof value !== 'string' ||
    !/^[A-Za-z0-9_-]{43}$/.test(value) ||
    Buffer.from(value, 'base64url').toString('base64url') !== value
  )
    fail();
  return value;
}

export function parsePushRegistration(body: unknown): PushRegistrationRequest {
  const source = record(body, [
    'expectedRevision',
    'applicationId',
    'registration',
    'locale',
    'alertsEnabled',
    'notificationPermission',
    'channelEnabled',
  ]);
  const registration = record(source.registration, ['kind', 'value']);
  if (
    !Number.isSafeInteger(source.expectedRevision) ||
    (source.expectedRevision as number) < 0 ||
    (source.expectedRevision as number) >= 2147483647 ||
    typeof source.applicationId !== 'string' ||
    source.applicationId.length > 200 ||
    !/^[a-zA-Z][\w]*(\.[a-zA-Z][\w]*)+$/.test(source.applicationId) ||
    registration.kind !== 'FCM_TOKEN' ||
    typeof registration.value !== 'string' ||
    Buffer.byteLength(registration.value, 'utf8') > 4096 ||
    !registration.value.length ||
    /[\s\p{Cc}]/u.test(registration.value) ||
    !['en-CA', 'fr-CA'].includes(source.locale as string) ||
    typeof source.alertsEnabled !== 'boolean' ||
    typeof source.channelEnabled !== 'boolean' ||
    !['GRANTED', 'DENIED', 'NOT_REQUESTED'].includes(
      source.notificationPermission as string,
    )
  )
    fail();
  return source as unknown as PushRegistrationRequest;
}

function record(
  value: unknown,
  keys: readonly string[],
): Record<string, unknown> {
  if (
    !value ||
    typeof value !== 'object' ||
    Array.isArray(value) ||
    Object.keys(value).some((key) => !keys.includes(key)) ||
    keys.some((key) => !Object.hasOwn(value, key))
  )
    fail();
  return value as Record<string, unknown>;
}
function fail(): never {
  throw AuthApiError.validationFailed(
    'The push registration request is invalid.',
  );
}
