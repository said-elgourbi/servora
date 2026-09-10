import { randomUUID } from 'node:crypto';
import { inArray } from 'drizzle-orm';
import { drizzle, type PostgresJsDatabase } from 'drizzle-orm/postgres-js';
import postgres from 'postgres';
import { loadConfig } from '../../src/config/configuration.js';
import { organizations, users } from '../../src/database/schema.js';

// A real Argon2id hash used by tests that only need a valid value.
export const TEST_PASSWORD_HASH = '$argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaGhhc2g';

export type TestDb = PostgresJsDatabase<Record<string, never>>;

/**
 * Records rows created by a test so they can be removed afterwards by deleting
 * only what the test created. Deleting an organization cascades to its members
 * and customers; tests never truncate shared tables.
 */
export class CleanupTracker {
  readonly organizationIds: string[] = [];
  readonly userIds: string[] = [];

  trackOrganization(id: string): string {
    this.organizationIds.push(id);
    return id;
  }

  trackUser(id: string): string {
    this.userIds.push(id);
    return id;
  }
}

export interface FoundationTestDatabase {
  readonly client: postgres.Sql;
  readonly db: TestDb;
  readonly cleanup: CleanupTracker;
  dispose(): Promise<void>;
}

/** Opens a pool against the configured DATABASE_URL (migrations run once in global setup). */
export async function createFoundationTestDatabase(): Promise<FoundationTestDatabase> {
  const { databaseUrl } = loadConfig();
  const client = postgres(databaseUrl, {
    max: 5,
    connect_timeout: 10,
    onnotice: () => undefined,
  });
  const db = drizzle(client);
  await client`select 1`;

  const cleanup = new CleanupTracker();

  return {
    client,
    db,
    cleanup,
    async dispose() {
      if (cleanup.organizationIds.length > 0) {
        await db
          .delete(organizations)
          .where(inArray(organizations.id, [...cleanup.organizationIds]));
      }
      if (cleanup.userIds.length > 0) {
        await db.delete(users).where(inArray(users.id, [...cleanup.userIds]));
      }
      await client.end();
    },
  };
}

export function uniqueEmail(prefix = 'test'): string {
  return `${prefix}-${randomUUID()}@servora.test`;
}

export async function createTestOrganization(
  db: TestDb,
  values: { name?: string; email?: string | null; phone?: string | null; status?: string } = {},
) {
  const [row] = await db
    .insert(organizations)
    .values({
      name: values.name ?? 'Test Organization',
      email: values.email ?? null,
      phone: values.phone ?? null,
      ...(values.status === undefined ? {} : { status: values.status }),
    })
    .returning();
  return row;
}

export async function createTestUser(
  db: TestDb,
  values: { email?: string; passwordHash?: string; phone?: string | null; status?: string } = {},
) {
  const [row] = await db
    .insert(users)
    .values({
      email: values.email ?? uniqueEmail(),
      passwordHash: values.passwordHash ?? TEST_PASSWORD_HASH,
      phone: values.phone ?? null,
      ...(values.status === undefined ? {} : { status: values.status }),
    })
    .returning();
  return row;
}

/** Extracts a PostgreSQL error code, unwrapping Drizzle's error wrapper if present. */
function postgresErrorCode(error: unknown): string | undefined {
  if (typeof error !== 'object' || error === null) {
    return undefined;
  }
  const direct = (error as { code?: unknown }).code;
  if (typeof direct === 'string') {
    return direct;
  }
  const cause = (error as { cause?: unknown }).cause;
  if (typeof cause === 'object' && cause !== null) {
    const nested = (cause as { code?: unknown }).code;
    if (typeof nested === 'string') {
      return nested;
    }
  }
  return undefined;
}

/** Asserts that an operation fails with a specific PostgreSQL error code. */
export async function expectPostgresError(
  action: () => Promise<unknown>,
  code: string,
): Promise<void> {
  try {
    await action();
  } catch (error) {
    expect(postgresErrorCode(error)).toBe(code);
    return;
  }
  throw new Error(`Expected PostgreSQL error ${code}, but the operation succeeded.`);
}
