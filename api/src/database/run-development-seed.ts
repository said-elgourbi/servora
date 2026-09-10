import { eq } from 'drizzle-orm';
import { drizzle, type PostgresJsDatabase } from 'drizzle-orm/postgres-js';
import postgres from 'postgres';
import { loadConfig } from '../config/configuration.js';
import { hashPassword } from '../users/password-hasher.js';
import {
  resolveDevelopmentSeed,
  type DevelopmentSeed,
  type SeedAccount,
} from './development-seed.js';
import {
  organizationMembers,
  organizations,
  userProfiles,
  users,
} from './schema.js';

// Compiled to dist/ by `nest build` and executed by `npm run db:seed`
// (`make seed`). Development tooling only: it never runs inside the API process
// and refuses to run when NODE_ENV=production.
type SeedDatabase = PostgresJsDatabase<Record<string, never>>;

async function main(): Promise<void> {
  const { databaseUrl, nodeEnv } = loadConfig();
  if (nodeEnv === 'production') {
    throw new Error(
      'Refusing to seed development data while NODE_ENV=production.',
    );
  }

  const seed = resolveDevelopmentSeed(process.env);
  const client = postgres(databaseUrl, { max: 1, connect_timeout: 10 });

  try {
    // Verify connectivity before writing anything.
    await client`select 1`;
    const db = drizzle(client);

    const organizationId = await ensureOrganization(db, seed.organizationName);
    for (const account of seed.accounts) {
      await upsertAccount(db, organizationId, account);
    }

    report(seed);
  } finally {
    await client.end();
  }
}

/** Creates the organization on first use; re-running the seed never duplicates it. */
async function ensureOrganization(
  db: SeedDatabase,
  name: string,
): Promise<string> {
  const [existing] = await db
    .select({ id: organizations.id })
    .from(organizations)
    .where(eq(organizations.name, name))
    .limit(1);
  if (existing) {
    return existing.id;
  }

  const [created] = await db
    .insert(organizations)
    .values({ name })
    .returning({ id: organizations.id });
  if (!created) {
    throw new Error(`Failed to create the organization "${name}".`);
  }
  return created.id;
}

/**
 * Upserts one account: credential, profile and organization membership.
 *
 * Re-running the seed restores the documented credential of an existing account
 * (the password is fully hashed again, so the stored hash is never reused) and
 * reactivates it rather than creating a second account.
 */
async function upsertAccount(
  db: SeedDatabase,
  organizationId: string,
  account: SeedAccount,
): Promise<void> {
  const passwordHash = await hashPassword(account.password);

  const [user] = await db
    .insert(users)
    .values({ email: account.email, passwordHash })
    .onConflictDoUpdate({
      target: users.email,
      set: { passwordHash, status: 'ACTIVE' },
    })
    .returning({ id: users.id });
  if (!user) {
    throw new Error(`Failed to seed the account "${account.email}".`);
  }

  const displayName = `${account.firstName} ${account.lastName}`;
  await db
    .insert(userProfiles)
    .values({
      userId: user.id,
      firstName: account.firstName,
      lastName: account.lastName,
      displayName,
    })
    .onConflictDoUpdate({
      target: userProfiles.userId,
      set: {
        firstName: account.firstName,
        lastName: account.lastName,
        displayName,
      },
    });

  await db
    .insert(organizationMembers)
    .values({
      organizationId,
      userId: user.id,
      role: account.role,
      status: 'ACTIVE',
    })
    .onConflictDoUpdate({
      target: [organizationMembers.organizationId, organizationMembers.userId],
      set: { role: account.role, status: 'ACTIVE' },
    });
}

function report(seed: DevelopmentSeed): void {
  console.log(
    `Seeded development data in the organization "${seed.organizationName}":`,
  );
  for (const account of seed.accounts) {
    const credential = account.generatedPassword
      ? `password (generated now): ${account.password}`
      : `password: the value of ${account.passwordVariable}`;
    console.log(`  ${account.email} — ${account.role} — ${credential}`);
  }
  console.log('These accounts sign in through POST /auth/sign-in.');
}

main().catch((error: unknown) => {
  const message = error instanceof Error ? error.message : String(error);
  console.error(`Development seeding failed: ${message}`);
  process.exitCode = 1;
});
