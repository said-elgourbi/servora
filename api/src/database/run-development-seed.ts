import { and, eq } from 'drizzle-orm';
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
  organizationRoles,
  organizationMembers,
  organizations,
  permissions,
  rolePermissions,
  userProfiles,
  users,
} from './schema.js';

// Compiled to dist/ by `nest build` and executed by `npm run db:seed`
// (`make seed`). Development tooling only: it never runs inside the API process
// and refuses to run when NODE_ENV=production.
type SeedDatabase = PostgresJsDatabase<Record<string, never>>;

const PERMISSION_TEMPLATES = [
  {
    code: 'CUSTOMER_CREATE',
    nameEn: 'Create customers',
    nameFr: 'Creer des clients',
    descriptionEn: 'Create customer records.',
    descriptionFr: 'Creer des dossiers client.',
  },
  {
    code: 'CUSTOMER_VIEW',
    nameEn: 'View customers',
    nameFr: 'Voir les clients',
    descriptionEn: 'View customer records.',
    descriptionFr: 'Voir les dossiers client.',
  },
  {
    code: 'CUSTOMER_UPDATE',
    nameEn: 'Update customers',
    nameFr: 'Modifier les clients',
    descriptionEn: 'Update customer records.',
    descriptionFr: 'Modifier les dossiers client.',
  },
  {
    code: 'CUSTOMER_DELETE',
    nameEn: 'Delete customers',
    nameFr: 'Supprimer des clients',
    descriptionEn: 'Soft-delete customer records.',
    descriptionFr: 'Supprimer logiquement des dossiers client.',
  },
  {
    code: 'CUSTOMER_VIEW_DELETED',
    nameEn: 'View deleted customers',
    nameFr: 'Voir les clients supprimes',
    descriptionEn: 'Include soft-deleted customers in filtered views.',
    descriptionFr: 'Inclure les clients supprimes dans les vues filtrees.',
  },
  {
    code: 'TECHNICIAN_CREATE',
    nameEn: 'Create technicians',
    nameFr: 'Creer des techniciens',
    descriptionEn: 'Create technician users and memberships.',
    descriptionFr: 'Creer des utilisateurs et adhesions technicien.',
  },
  {
    code: 'TECHNICIAN_VIEW',
    nameEn: 'View technicians',
    nameFr: 'Voir les techniciens',
    descriptionEn: 'View technician records.',
    descriptionFr: 'Voir les dossiers technicien.',
  },
  {
    code: 'TECHNICIAN_UPDATE',
    nameEn: 'Update technicians',
    nameFr: 'Modifier les techniciens',
    descriptionEn: 'Update technician records.',
    descriptionFr: 'Modifier les dossiers technicien.',
  },
  {
    code: 'TECHNICIAN_DELETE',
    nameEn: 'Delete technicians',
    nameFr: 'Supprimer des techniciens',
    descriptionEn:
      'Delete or deactivate technician access according to domain rules.',
    descriptionFr:
      'Supprimer ou desactiver l acces technicien selon les regles du domaine.',
  },
  {
    code: 'JOB_CREATE',
    nameEn: 'Create jobs',
    nameFr: 'Creer des travaux',
    descriptionEn: 'Create job records.',
    descriptionFr: 'Creer des dossiers de travail.',
  },
  {
    code: 'JOB_VIEW',
    nameEn: 'View jobs',
    nameFr: 'Voir les travaux',
    descriptionEn: 'View job records.',
    descriptionFr: 'Voir les dossiers de travail.',
  },
  {
    code: 'JOB_UPDATE',
    nameEn: 'Update jobs',
    nameFr: 'Modifier les travaux',
    descriptionEn: 'Update job records.',
    descriptionFr: 'Modifier les dossiers de travail.',
  },
  {
    code: 'JOB_DELETE',
    nameEn: 'Delete jobs',
    nameFr: 'Supprimer des travaux',
    descriptionEn: 'Delete jobs according to domain rules.',
    descriptionFr: 'Supprimer des travaux selon les regles du domaine.',
  },
  {
    code: 'VISIT_VIEW_ASSIGNED',
    nameEn: 'View assigned visits',
    nameFr: 'Voir les visites assignees',
    descriptionEn: 'View visits assigned to the technician.',
    descriptionFr: 'Voir les visites assignees au technicien.',
  },
  {
    code: 'VISIT_UPDATE_ASSIGNED_STATUS',
    nameEn: 'Update assigned visit status',
    nameFr: 'Modifier le statut des visites assignees',
    descriptionEn: 'Advance the field status of assigned visits.',
    descriptionFr: 'Faire avancer le statut terrain des visites assignees.',
  },
  {
    code: 'VISIT_ADD_NOTE',
    nameEn: 'Add visit notes',
    nameFr: 'Ajouter des notes de visite',
    descriptionEn: 'Add notes to assigned visits.',
    descriptionFr: 'Ajouter des notes aux visites assignees.',
  },
  {
    code: 'VISIT_RECORD_OUTCOME',
    nameEn: 'Record visit outcomes',
    nameFr: 'Enregistrer les resultats de visite',
    descriptionEn: 'Record the outcome of assigned visits.',
    descriptionFr: 'Enregistrer le resultat des visites assignees.',
  },
] as const;

const ROLE_TEMPLATES = {
  MANAGER: {
    nameEn: 'Manager',
    nameFr: 'Gestionnaire',
    descriptionEn: 'Default management role with core CRUD permissions.',
    descriptionFr:
      'Role de gestion par defaut avec les permissions CRUD principales.',
    permissionCodes: PERMISSION_TEMPLATES.map((permission) => permission.code),
  },
  TECHNICIAN: {
    nameEn: 'Technician',
    nameFr: 'Technicien',
    descriptionEn: 'Default field role for assigned visit work.',
    descriptionFr: 'Role terrain par defaut pour les visites assignees.',
    permissionCodes: [
      'VISIT_VIEW_ASSIGNED',
      'VISIT_UPDATE_ASSIGNED_STATUS',
      'VISIT_ADD_NOTE',
      'VISIT_RECORD_OUTCOME',
    ],
  },
} as const;

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
    const permissionIds = await ensurePermissions(db);
    const roleIds = await ensureDefaultRoles(db, organizationId, permissionIds);
    for (const account of seed.accounts) {
      await upsertAccount(db, organizationId, roleIds[account.role], account);
    }

    report(seed);
  } finally {
    await client.end();
  }
}

async function ensurePermissions(
  db: SeedDatabase,
): Promise<Record<string, string>> {
  const ids: Record<string, string> = {};
  for (const template of PERMISSION_TEMPLATES) {
    const [permission] = await db
      .insert(permissions)
      .values(template)
      .onConflictDoUpdate({
        target: permissions.code,
        set: {
          nameEn: template.nameEn,
          nameFr: template.nameFr,
          descriptionEn: template.descriptionEn,
          descriptionFr: template.descriptionFr,
        },
      })
      .returning({ id: permissions.id, code: permissions.code });
    if (!permission) {
      throw new Error(`Failed to seed permission "${template.code}".`);
    }
    ids[permission.code] = permission.id;
  }
  return ids;
}

async function ensureDefaultRoles(
  db: SeedDatabase,
  organizationId: string,
  permissionIds: Record<string, string>,
): Promise<Record<keyof typeof ROLE_TEMPLATES, string>> {
  const roleIds = {} as Record<keyof typeof ROLE_TEMPLATES, string>;

  for (const systemCode of Object.keys(ROLE_TEMPLATES) as Array<
    keyof typeof ROLE_TEMPLATES
  >) {
    const template = ROLE_TEMPLATES[systemCode];
    const [existingRole] = await db
      .select({ id: organizationRoles.id })
      .from(organizationRoles)
      .where(
        and(
          eq(organizationRoles.organizationId, organizationId),
          eq(organizationRoles.systemCode, systemCode),
        ),
      )
      .limit(1);

    const role =
      existingRole ??
      (
        await db
          .insert(organizationRoles)
          .values({
            organizationId,
            systemCode,
            nameEn: template.nameEn,
            nameFr: template.nameFr,
            descriptionEn: template.descriptionEn,
            descriptionFr: template.descriptionFr,
            status: 'ACTIVE',
          })
          .returning({ id: organizationRoles.id })
      )[0];
    if (existingRole) {
      await db
        .update(organizationRoles)
        .set({
          nameEn: template.nameEn,
          nameFr: template.nameFr,
          descriptionEn: template.descriptionEn,
          descriptionFr: template.descriptionFr,
          status: 'ACTIVE',
        })
        .where(eq(organizationRoles.id, existingRole.id));
    }
    if (!role) {
      throw new Error(`Failed to seed the ${systemCode} role.`);
    }
    roleIds[systemCode] = role.id;

    for (const permissionCode of template.permissionCodes) {
      const permissionId = permissionIds[permissionCode];
      if (permissionId === undefined) {
        throw new Error(`Missing seeded permission "${permissionCode}".`);
      }
      await db
        .insert(rolePermissions)
        .values({ organizationId, roleId: role.id, permissionId })
        .onConflictDoNothing();
    }
  }

  return roleIds;
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
  roleId: string,
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
      roleId,
      status: 'ACTIVE',
    })
    .onConflictDoUpdate({
      target: [organizationMembers.organizationId, organizationMembers.userId],
      set: { roleId, status: 'ACTIVE' },
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
