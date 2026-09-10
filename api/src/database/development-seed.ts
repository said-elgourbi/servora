import { randomBytes } from 'node:crypto';
import type { MemberRole } from '../members/organization-member.types.js';
import {
  requireEmail,
  requirePassword,
} from '../validation/domain-validation.js';

/**
 * Development seed data - the smallest dataset that lets a developer sign in and
 * exercise both foundation audiences (`BR-003`) against a local database.
 *
 * This is local developer tooling, never product behaviour: it is applied by
 * `make seed` (`api/src/database/run-development-seed.ts`), refuses to run with
 * `NODE_ENV=production`, and is documented in `docs/development/setup.md`.
 *
 * Resolving the dataset from the environment is pure so the seeding rules
 * (default addresses, password sourcing, domain validation) are unit-testable
 * without a database.
 */

/** Organization the seeded accounts belong to. Override with `SEED_ORGANIZATION_NAME`. */
export const DEFAULT_SEED_ORGANIZATION_NAME = 'Servora Development';

// Reserved `.test` addresses (RFC 2606) so a seeded account can never reach a
// real inbox even if a notification path is added later.
export const DEFAULT_SEED_MANAGER_EMAIL = 'manager@servora.test';
export const DEFAULT_SEED_TECHNICIAN_EMAIL = 'technician@servora.test';

/** 18 random bytes encode to 24 URL-safe characters, comfortably above `MIN_PASSWORD_LENGTH`. */
const GENERATED_PASSWORD_BYTES = 18;

/** One account the seed guarantees, including where its credential came from. */
export interface SeedAccount {
  /** Foundation role code (never a display label). */
  readonly role: MemberRole;
  /** Stored lower case: sign-in compares against the lowercased address. */
  readonly email: string;
  readonly password: string;
  /** `true` when the password was generated here rather than configured in the environment. */
  readonly generatedPassword: boolean;
  /** Environment variable the password is read from when it is not generated. */
  readonly passwordVariable: string;
  readonly firstName: string;
  readonly lastName: string;
}

/** The complete local dataset applied by one `make seed` run. */
export interface DevelopmentSeed {
  readonly organizationName: string;
  readonly accounts: readonly SeedAccount[];
}

interface AccountTemplate {
  readonly role: MemberRole;
  readonly emailVariable: string;
  readonly passwordVariable: string;
  readonly defaultEmail: string;
  readonly firstName: string;
  readonly lastName: string;
}

// One entry per foundation role: `MEMBER_ROLES` and the
// `organization_members_role_check` constraint define exactly these two codes,
// so adding a third one here would be a product decision, not a convenience.
const ACCOUNT_TEMPLATES: readonly AccountTemplate[] = [
  {
    role: 'MANAGER',
    emailVariable: 'SEED_MANAGER_EMAIL',
    passwordVariable: 'SEED_MANAGER_PASSWORD',
    defaultEmail: DEFAULT_SEED_MANAGER_EMAIL,
    firstName: 'Dev',
    lastName: 'Manager',
  },
  {
    role: 'TECHNICIAN',
    emailVariable: 'SEED_TECHNICIAN_EMAIL',
    passwordVariable: 'SEED_TECHNICIAN_PASSWORD',
    defaultEmail: DEFAULT_SEED_TECHNICIAN_EMAIL,
    firstName: 'Dev',
    lastName: 'Technician',
  },
];

/**
 * Generates a password nobody chose. It is printed once by the seeding command
 * and stored only as an Argon2id hash, exactly like any other credential.
 */
export function generateSeedPassword(): string {
  return randomBytes(GENERATED_PASSWORD_BYTES).toString('base64url');
}

/** Treats an empty or whitespace-only variable as absent (`SEED_*=` means "generate one"). */
function configuredValue(raw: string | undefined): string | undefined {
  if (raw === undefined) {
    return undefined;
  }
  const value = raw.trim();
  return value.length === 0 ? undefined : value;
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function resolveEmail(
  env: NodeJS.ProcessEnv,
  template: AccountTemplate,
): string {
  const raw =
    configuredValue(env[template.emailVariable]) ?? template.defaultEmail;
  try {
    return requireEmail(raw, template.emailVariable).toLowerCase();
  } catch (error) {
    throw new Error(`Invalid ${template.emailVariable}: ${describe(error)}.`);
  }
}

function resolvePassword(
  env: NodeJS.ProcessEnv,
  template: AccountTemplate,
): { password: string; generatedPassword: boolean } {
  const raw = configuredValue(env[template.passwordVariable]);
  if (raw === undefined) {
    return { password: generateSeedPassword(), generatedPassword: true };
  }
  try {
    return { password: requirePassword(raw), generatedPassword: false };
  } catch (error) {
    throw new Error(
      `Invalid ${template.passwordVariable}: ${describe(error)}.`,
    );
  }
}

/**
 * Resolves the development dataset from the environment.
 *
 * Addresses default to reserved addresses and the organization to
 * `Servora Development`. Passwords are read from `SEED_*_PASSWORD`; when that is
 * unset one is generated and returned for the caller to print. No credential is
 * ever committed to the repository.
 */
export function resolveDevelopmentSeed(
  env: NodeJS.ProcessEnv = process.env,
): DevelopmentSeed {
  return {
    organizationName:
      configuredValue(env.SEED_ORGANIZATION_NAME) ??
      DEFAULT_SEED_ORGANIZATION_NAME,
    accounts: ACCOUNT_TEMPLATES.map((template) => ({
      role: template.role,
      email: resolveEmail(env, template),
      passwordVariable: template.passwordVariable,
      firstName: template.firstName,
      lastName: template.lastName,
      ...resolvePassword(env, template),
    })),
  };
}
