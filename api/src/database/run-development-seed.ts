import { and, eq } from 'drizzle-orm';
import { drizzle, type PostgresJsDatabase } from 'drizzle-orm/postgres-js';
import postgres from 'postgres';
import { addressSnapshotOf } from '../address/address-snapshot.js';
import { loadConfig } from '../config/configuration.js';
import type { SystemRoleCode } from '../members/organization-member.types.js';
import { hashPassword } from '../users/password-hasher.js';
import {
  generateSeedPassword,
  resolveDevelopmentSeed,
  type DevelopmentSeed,
  type SeedAccount,
} from './development-seed.js';
import {
  JOB_STATUS_PATHS,
  SEED_CUSTOMERS,
  SEED_TECHNICIAN_MEMBER_TEMPLATES,
  VISIT_STATUS_PATHS,
  addHours,
  assertSeedScenariosAreCoherent,
  orderedPastEventTimes,
  pastInstant,
  resolveSeedJob,
  type ResolvedSeedJob,
  type ResolvedSeedVisit,
  type SeedCustomerPlan,
  type SeedJobPlan,
  type SeedPropertyPlan,
  type SeedTechnicianKey,
} from './development-seed-scenarios.js';
import {
  customerAddresses,
  customerCompanies,
  customerContacts,
  customerIndividuals,
  customers,
  jobStatusHistory,
  jobs,
  organizationJobNumberCounters,
  organizationRoles,
  organizationMembers,
  organizations,
  permissions,
  properties,
  propertyCustomerRelationships,
  rolePermissions,
  userProfiles,
  users,
  visitNotes,
  visitOutcomeHistory,
  visitStatusHistory,
  visits,
  visitTechnicianHistory,
  visitTechnicians,
} from './schema.js';

// Compiled to dist/ by `nest build` and executed by `npm run db:seed`
// (`make seed`). Development tooling only: it never runs inside the API process
// and refuses to run when NODE_ENV=production.
type SeedDatabase = PostgresJsDatabase<Record<string, never>>;

/** The first Job number the demo uses; `BR-052` numbers Jobs per organization from there. */
const FIRST_DEMO_JOB_NUMBER = 1001;


const PERMISSION_TEMPLATES = [
  {
    code: 'customers.create',
    nameEn: 'Create customers',
    nameFr: 'Creer des clients',
    descriptionEn: 'Create customer records.',
    descriptionFr: 'Creer des dossiers client.',
  },
  {
    code: 'customers.view',
    nameEn: 'View customers',
    nameFr: 'Voir les clients',
    descriptionEn: 'View customer records.',
    descriptionFr: 'Voir les dossiers client.',
  },
  {
    code: 'customers.edit',
    nameEn: 'Edit customers',
    nameFr: 'Modifier les clients',
    descriptionEn: 'Edit customer records.',
    descriptionFr: 'Modifier les dossiers client.',
  },
  {
    code: 'customers.archive',
    nameEn: 'Archive customers',
    nameFr: 'Archiver des clients',
    descriptionEn:
      'Archive customer records without physically deleting historical data.',
    descriptionFr:
      'Archiver les dossiers client sans supprimer physiquement les donnees historiques.',
  },
  {
    // The technician's read of the customer behind an assigned Job (`BR-092`; `ADR-021` D1). It is a
    // capability of its own rather than an inference from the Visit capabilities, so a company can
    // withdraw a member's customer visibility without withdrawing their field work. The Technician
    // template below lists its capabilities explicitly and holds it; the Manager template grants the
    // whole catalogue, as it already does for the field codes, and needs nothing extra — a manager
    // reads the same customer through `customers.view`.
    code: 'customers.view_assigned',
    nameEn: 'View assigned customers',
    nameFr: 'Voir les clients assignes',
    descriptionEn: 'View the customer of a job the member is assigned to.',
    descriptionFr:
      "Voir le client d'un travail auquel le membre est assigne.",
  },
  {
    // Contact-person writes are a capability set of their own rather than `customers.edit`
    // (`BR-095`; `ADR-022` D4): `BR-085`'s Property position applied to a different asset, because a
    // role may legitimately maintain a customer without being trusted to change or delete the people
    // the organization calls. The Manager template below grants the whole catalogue; the Technician
    // template lists its capabilities explicitly and holds none of these.
    code: 'customers.contacts.create',
    nameEn: 'Create customer contacts',
    nameFr: 'Creer des contacts client',
    descriptionEn: 'Add a contact person to a Customer.',
    descriptionFr: 'Ajouter un contact au client.',
  },
  {
    code: 'customers.contacts.edit',
    nameEn: 'Edit customer contacts',
    nameFr: 'Modifier des contacts client',
    descriptionEn: "Edit a Customer's contact person, its primary flag included.",
    descriptionFr:
      'Modifier un contact du client, y compris son indicateur principal.',
  },
  {
    code: 'customers.contacts.remove',
    nameEn: 'Remove customer contacts',
    nameFr: 'Retirer des contacts client',
    descriptionEn: "Remove a Customer's contact person.",
    descriptionFr: 'Retirer un contact du client.',
  },
  {
    code: 'properties.view',
    nameEn: 'View properties',
    nameFr: 'Voir les proprietes',
    descriptionEn:
      'View Properties and resolve a Property where a location is displayed or selected.',
    descriptionFr:
      'Voir les proprietes et resoudre une propriete lorsqu un lieu est affiche ou selectionne.',
  },
  {
    code: 'properties.create',
    nameEn: 'Create properties',
    nameFr: 'Creer des proprietes',
    descriptionEn: 'Create Properties and relate them to a Customer.',
    descriptionFr: 'Creer des proprietes et les associer a un client.',
  },
  {
    code: 'properties.edit',
    nameEn: 'Edit properties',
    nameFr: 'Modifier les proprietes',
    descriptionEn: 'Edit a Property.',
    descriptionFr: 'Modifier les donnees d une propriete.',
  },
  {
    code: 'properties.archive',
    nameEn: 'Archive properties',
    nameFr: 'Archiver des proprietes',
    descriptionEn: 'Archive and restore a Property.',
    descriptionFr: 'Archiver et restaurer une propriete.',
  },
  {
    code: 'properties.delete',
    nameEn: 'Delete properties',
    nameFr: 'Supprimer des proprietes',
    descriptionEn:
      'Permanently delete a Property that has never been referenced.',
    descriptionFr: 'Supprimer definitivement une propriete jamais referencee.',
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
    code: 'schedule.view_org',
    nameEn: 'View organization schedule',
    nameFr: 'Voir l horaire de l organisation',
    descriptionEn: 'View the organization dispatch schedule.',
    descriptionFr: 'Voir l horaire de repartition de l organisation.',
  },
  {
    code: 'visits.create_schedule',
    nameEn: 'Create and schedule visits',
    nameFr: 'Creer et planifier des visites',
    descriptionEn: 'Create and schedule Visits directly.',
    descriptionFr: 'Creer et planifier des visites directement.',
  },
  {
    code: 'visits.update_schedule',
    nameEn: 'Update visit schedules',
    nameFr: 'Modifier les horaires de visite',
    descriptionEn: 'Schedule or reschedule existing Visits.',
    descriptionFr: 'Planifier ou replanifier des visites existantes.',
  },
  {
    code: 'visits.assign_technicians',
    nameEn: 'Assign visit technicians',
    nameFr: 'Assigner des techniciens aux visites',
    descriptionEn: 'Assign technicians to Visits.',
    descriptionFr: 'Assigner des techniciens aux visites.',
  },
  {
    code: 'visits.request_follow_up',
    nameEn: 'Request follow-up visits',
    nameFr: 'Demander des visites de suivi',
    descriptionEn: 'Submit follow-up Visit requests.',
    descriptionFr: 'Soumettre des demandes de visite de suivi.',
  },
  {
    code: 'visits.review_requests',
    nameEn: 'Review follow-up visit requests',
    nameFr: 'Examiner les demandes de visite de suivi',
    descriptionEn: 'Approve, clarify, or reject follow-up Visit requests.',
    descriptionFr:
      'Approuver, clarifier ou refuser les demandes de visite de suivi.',
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
  {
    code: 'evidence.view',
    nameEn: 'View evidence',
    nameFr: 'Voir les preuves',
    descriptionEn:
      'View evidence attached to a Job and read its stored content.',
    descriptionFr:
      'Voir les preuves jointes a un travail et lire leur contenu enregistre.',
  },
  {
    code: 'evidence.photo.add',
    nameEn: 'Add photo evidence',
    nameFr: 'Ajouter des preuves photo',
    descriptionEn: 'Attach a photo to a Job as field evidence.',
    descriptionFr: 'Joindre une photo a un travail comme preuve terrain.',
  },
  {
    // Removing accepted evidence is a Manager capability, not a field one (`BR-089`; tracker 029
    // Phase 6b). It is in this template so the default Manager role holds it — the role template
    // below grants the whole catalogue — while the Technician template lists its capabilities
    // explicitly and does not include it.
    code: 'evidence.photo.remove',
    nameEn: 'Remove photo evidence',
    nameFr: 'Retirer des preuves photo',
    descriptionEn: 'Remove accepted photo evidence from ordinary views.',
    descriptionFr: 'Retirer des preuves photo acceptees des vues courantes.',
  },
  {
    // Recording an audio note is a kind of its own (`BR-091`; tracker 035, `ADR-018` A7). The code was
    // reserved by `ADR-015` D2 and is created with the rules that accept a recording.
    code: 'evidence.audio.add',
    nameEn: 'Add audio evidence',
    nameFr: 'Ajouter des preuves audio',
    descriptionEn: 'Record an audio note on a Job as field evidence.',
    descriptionFr: 'Enregistrer une note audio sur un travail comme preuve terrain.',
  },
  {
    // The audio kind's removal, Manager-only exactly as the photo kind's is (`BR-089`, `ADR-018` A7):
    // the Technician template below lists its capabilities explicitly and does not include it.
    code: 'evidence.audio.remove',
    nameEn: 'Remove audio evidence',
    nameFr: 'Retirer des preuves audio',
    descriptionEn: 'Remove accepted audio evidence from ordinary views.',
    descriptionFr: 'Retirer des preuves audio acceptees des vues courantes.',
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
      'visits.request_follow_up',
      // The customer of a Job the technician is assigned to, view-only (`BR-092`; `ADR-021` D1). It is
      // the default field role that holds it, which is what "by default" means here: a technician in
      // the field has to be able to reach the customer they are working for.
      'customers.view_assigned',
      // A technician records the evidence of the work performed, so the evidence capabilities are
      // part of the default field role rather than a Manager grant (`BR-009`, `BR-015`, tracker 029).
      // `evidence.photo.remove` and `evidence.audio.remove` are deliberately **not** here: taking
      // accepted evidence out of ordinary use is a Manager capability (`BR-089`, `ADR-018` A7), and the
      // technician's own discard of an unsubmitted draft needs no capability at all (`BR-088`).
      'evidence.view',
      'evidence.photo.add',
      'evidence.audio.add',
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
    const membershipIds = await getSeedMembershipIds(db, organizationId, seed);
    const technicians = await seedTechnicians(db, organizationId, roleIds, {
      manager: membershipIds.MANAGER,
      technician: membershipIds.TECHNICIAN,
    });
    // The demo is written from a clean slate: every Customer, Property, Job and Visit of this
    // organization is replaced, so `make seed` always produces the same known dataset.
    const removed = await clearDemoData(db, organizationId);
    const seeded = await seedDemoData(db, organizationId, technicians);

    report(seed, seeded, removed);
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

/**
 * Resolves the seeded accounts' own memberships.
 *
 * The accounts are addressed by the credential the seed just upserted, not by role: an organization
 * now has many members holding the Technician role (`BR-024`), so resolving "the technician" by role
 * would be ambiguous and could hand back a different member than the documented QA account — which
 * would then be seeded twice into a crew. Addressing the account by its email is exact.
 */
async function getSeedMembershipIds(
  db: SeedDatabase,
  organizationId: string,
  seed: DevelopmentSeed,
): Promise<Record<SystemRoleCode, string>> {
  const ids = {} as Record<SystemRoleCode, string>;

  for (const account of seed.accounts) {
    const [membership] = await db
      .select({ id: organizationMembers.id })
      .from(organizationMembers)
      .innerJoin(users, eq(users.id, organizationMembers.userId))
      .where(
        and(
          eq(organizationMembers.organizationId, organizationId),
          eq(users.email, account.email),
        ),
      )
      .limit(1);
    if (!membership) {
      throw new Error(
        `Failed to resolve the membership of the seeded ${account.role} account.`,
      );
    }
    ids[account.role] = membership.id;
  }

  return ids;
}

/** The people the demo data assigns work to, addressed by the dataset's own technician keys. */
interface SeedTechnicians {
  readonly managerMembershipId: string;
  readonly byKey: Readonly<Record<SeedTechnicianKey, string>>;
}

/**
 * Creates the extra Technician members the demo assigns work to and resolves every key the dataset
 * names to a membership id.
 *
 * Servora assigns technicians to a **Visit** (`BR-068`), so a demo that only ever assigned the single
 * seeded technician account could not show a crew. The documented QA accounts keep their credentials;
 * the extra members exist only so a Visit can carry a real crew with one Lead and several technicians.
 */
async function seedTechnicians(
  db: SeedDatabase,
  organizationId: string,
  roleIds: Record<keyof typeof ROLE_TEMPLATES, string>,
  membershipIds: { manager: string; technician: string },
): Promise<SeedTechnicians> {
  const byKey = {
    SEEDED: membershipIds.technician,
  } as Record<SeedTechnicianKey, string>;
  await ensureTechnicianMembers(db, organizationId, roleIds.TECHNICIAN, byKey);
  return { managerMembershipId: membershipIds.manager, byKey };
}

/**
 * Creates the extra members the dataset names, each an ordinary `ACTIVE` member holding the
 * organization's Technician role (`BR-024`).
 *
 * They are **not** sign-in accounts: no password is generated for them, and the two documented QA
 * credentials stay the only seeded logins.
 */
async function ensureTechnicianMembers(
  db: SeedDatabase,
  organizationId: string,
  technicianRoleId: string,
  byKey: Record<SeedTechnicianKey, string>,
): Promise<void> {
  for (const template of SEED_TECHNICIAN_MEMBER_TEMPLATES) {
    const passwordHash = await hashPassword(generateSeedPassword());
    await db
      .insert(users)
      .values({ email: template.email, passwordHash })
      .onConflictDoUpdate({
        target: users.email,
        set: { passwordHash, status: 'ACTIVE' },
      });
    const [user] = await db
      .select({ id: users.id })
      .from(users)
      .where(eq(users.email, template.email))
      .limit(1);
    if (!user) {
      throw new Error(`Failed to seed technician "${template.email}".`);
    }

    const displayName = `${template.firstName} ${template.lastName}`;
    await db
      .insert(userProfiles)
      .values({
        userId: user.id,
        firstName: template.firstName,
        lastName: template.lastName,
        displayName,
      })
      .onConflictDoUpdate({
        target: userProfiles.userId,
        set: {
          firstName: template.firstName,
          lastName: template.lastName,
          displayName,
        },
      });

    const [member] = await db
      .insert(organizationMembers)
      .values({
        organizationId,
        userId: user.id,
        roleId: technicianRoleId,
        status: 'ACTIVE',
      })
      .onConflictDoUpdate({
        target: [
          organizationMembers.organizationId,
          organizationMembers.userId,
        ],
        set: { roleId: technicianRoleId, status: 'ACTIVE' },
      })
      .returning({ id: organizationMembers.id });
    if (!member) {
      throw new Error(`Failed to seed technician member "${displayName}".`);
    }

    byKey[template.key] = member.id;
  }
}

/**
 * The demo data itself: a clean slate, then the dataset `development-seed-scenarios.ts` describes.
 *
 * Everything here is development tooling: it writes business rows directly instead of going through the
 * API's own operations, because a demo has to present histories that would otherwise take weeks to
 * accumulate (`docs/development/setup.md` §3). The dataset is asserted against the same rules the API
 * enforces (`assertSeedScenariosAreCoherent`) before a single row is inserted.
 */

/** How many rows of each kind the demo holds after a run. */
interface DemoDataCounts {
  readonly customers: number;
  readonly properties: number;
  readonly jobs: number;
  readonly visits: number;
}

/** What replacing the demo removed first, so the command can report what it did. */
type ClearedRows = DemoDataCounts;

/**
 * Removes every Customer, Property, Job and Visit of the organization, in the order the foreign keys
 * allow: the Visits and Jobs first, then the Property ↔ Customer relationships, then the Properties and
 * the Customers themselves.
 *
 * Re-running `make seed` therefore replaces the previous demo instead of appending to it, and the
 * organization's Job numbering restarts at the number the dataset expects.
 */
async function clearDemoData(
  db: SeedDatabase,
  organizationId: string,
): Promise<ClearedRows> {
  const removedVisits = await db
    .delete(visits)
    .where(eq(visits.organizationId, organizationId))
    .returning({ id: visits.id });
  const removedJobs = await db
    .delete(jobs)
    .where(eq(jobs.organizationId, organizationId))
    .returning({ id: jobs.id });
  await db
    .delete(propertyCustomerRelationships)
    .where(eq(propertyCustomerRelationships.organizationId, organizationId));
  const removedProperties = await db
    .delete(properties)
    .where(eq(properties.organizationId, organizationId))
    .returning({ id: properties.id });
  const removedCustomers = await db
    .delete(customers)
    .where(eq(customers.organizationId, organizationId))
    .returning({ id: customers.id });

  await db
    .insert(organizationJobNumberCounters)
    .values({ organizationId, lastJobNumber: 0 })
    .onConflictDoUpdate({
      target: organizationJobNumberCounters.organizationId,
      set: { lastJobNumber: 0, updatedAt: new Date() },
    });

  return {
    customers: removedCustomers.length,
    properties: removedProperties.length,
    jobs: removedJobs.length,
    visits: removedVisits.length,
  };
}

/** Writes the whole demo dataset, and reports what it holds. */
async function seedDemoData(
  db: SeedDatabase,
  organizationId: string,
  technicians: SeedTechnicians,
): Promise<DemoDataCounts> {
  const now = new Date();
  assertSeedScenariosAreCoherent(SEED_CUSTOMERS, now);

  let nextJobNumber = FIRST_DEMO_JOB_NUMBER;
  let propertyCount = 0;
  let visitCount = 0;

  for (const customer of SEED_CUSTOMERS) {
    const customerId = await insertCustomer(db, organizationId, customer, now);
    const seededProperties: SeededProperty[] = [];

    for (const property of customer.properties) {
      seededProperties.push(
        await insertProperty(
          db,
          organizationId,
          customerId,
          property,
          technicians.managerMembershipId,
          now,
        ),
      );
      propertyCount += 1;
    }

    for (const job of customer.jobs) {
      const property =
        job.propertyIndex === null
          ? null
          : (seededProperties[job.propertyIndex] ?? null);
      visitCount += await insertJob(db, {
        organizationId,
        customerId,
        jobNumber: nextJobNumber,
        job,
        property,
        technicians,
        now,
      });
      nextJobNumber += 1;
    }
  }

  // The counter holds the last number issued (`BR-052`), so a Job created through the API after a seed
  // continues from the demo's last number instead of colliding with it.
  const lastJobNumber = nextJobNumber - 1;
  await db
    .insert(organizationJobNumberCounters)
    .values({ organizationId, lastJobNumber })
    .onConflictDoUpdate({
      target: organizationJobNumberCounters.organizationId,
      set: { lastJobNumber, updatedAt: new Date() },
    });

  return {
    customers: SEED_CUSTOMERS.length,
    properties: propertyCount,
    jobs: lastJobNumber - FIRST_DEMO_JOB_NUMBER + 1,
    visits: visitCount,
  };
}

/** A Property row as the demo needs it back: its id, and the address a snapshot is written from. */
interface SeededProperty {
  readonly id: string;
  readonly name: string | null;
  readonly addressLine1: string;
  readonly addressLine2: string | null;
  readonly city: string;
  readonly province: string;
  readonly postalCode: string;
  readonly country: string;
}

/** Writes one Customer with its subtype record, contacts and billing address (`BR-023`, `BR-095`). */
async function insertCustomer(
  db: SeedDatabase,
  organizationId: string,
  customer: SeedCustomerPlan,
  now: Date,
): Promise<string> {
  const since = addHours(now, -customer.customerSinceDaysAgo * 24);
  const [row] = await db
    .insert(customers)
    .values({
      organizationId,
      type: customer.kind,
      displayName: customer.displayName,
      email: customer.email,
      phone: customer.phone,
      billingEmail: customer.billingEmail ?? null,
      billingPhone: customer.billingPhone ?? null,
      notes: customer.notes,
      preferredContactMethod: customer.preferredContactMethod,
      language: customer.language,
      status: customer.status,
      createdAt: since,
      updatedAt: since,
    })
    .returning({ id: customers.id });
  if (!row) {
    throw new Error(`Failed to seed customer "${customer.displayName}".`);
  }

  // Exactly one subtype record matches `customers.type` (`BR-023`). An individual *is* the customer,
  // which is also why an individual holds no contact person row (`BR-095`).
  if (customer.kind === 'INDIVIDUAL') {
    await db.insert(customerIndividuals).values({
      customerId: row.id,
      firstName: customer.firstName ?? customer.displayName,
      lastName: customer.lastName ?? '',
    });
  } else {
    await db.insert(customerCompanies).values({
      customerId: row.id,
      legalName: customer.legalName ?? customer.displayName,
      businessName: customer.businessName ?? null,
      taxNumber: customer.taxNumber ?? null,
    });
  }

  for (const contact of customer.contacts) {
    await db.insert(customerContacts).values({
      customerId: row.id,
      firstName: contact.firstName,
      lastName: contact.lastName,
      role: contact.role ?? null,
      email: contact.email ?? null,
      phone: contact.phone ?? null,
      isPrimary: contact.isPrimary ?? false,
      isBillingContact: contact.isBillingContact ?? false,
      isJobContact: contact.isJobContact ?? false,
    });
  }

  await db.insert(customerAddresses).values({
    customerId: row.id,
    type: 'BILLING',
    addressLine1: customer.billingAddress.addressLine1,
    addressLine2: customer.billingAddress.addressLine2 ?? null,
    city: customer.billingAddress.city,
    province: customer.billingAddress.province,
    postalCode: customer.billingAddress.postalCode,
    country: 'Canada',
    isDefault: true,
  });

  return row.id;
}

/** Writes one Property and its active Property ↔ Customer relationship (`BR-049`, `BR-050`). */
async function insertProperty(
  db: SeedDatabase,
  organizationId: string,
  customerId: string,
  property: SeedPropertyPlan,
  actorMembershipId: string,
  now: Date,
): Promise<SeededProperty> {
  const [row] = await db
    .insert(properties)
    .values({
      organizationId,
      name: property.name,
      addressLine1: property.addressLine1,
      addressLine2: property.addressLine2 ?? null,
      city: property.city,
      province: property.province,
      postalCode: property.postalCode,
      country: 'Canada',
      notes: property.notes ?? null,
      createdAt: now,
      updatedAt: now,
    })
    .returning({
      id: properties.id,
      name: properties.name,
      addressLine1: properties.addressLine1,
      addressLine2: properties.addressLine2,
      city: properties.city,
      province: properties.province,
      postalCode: properties.postalCode,
      country: properties.country,
    });
  if (!row) {
    throw new Error(`Failed to seed property "${property.name}".`);
  }

  await db.insert(propertyCustomerRelationships).values({
    organizationId,
    propertyId: row.id,
    customerId,
    actorMembershipId,
  });

  return row;
}

/** Writes one Job with its status history and every Visit it holds. Returns the Visit count. */
async function insertJob(
  db: SeedDatabase,
  input: {
    organizationId: string;
    customerId: string;
    jobNumber: number;
    job: SeedJobPlan;
    property: SeededProperty | null;
    technicians: SeedTechnicians;
    now: Date;
  },
): Promise<number> {
  const { job, technicians, now } = input;
  const resolved = resolveSeedJob(job, now);

  const [row] = await db
    .insert(jobs)
    .values({
      organizationId: input.organizationId,
      jobNumber: input.jobNumber,
      customerId: input.customerId,
      propertyId: input.property?.id ?? null,
      propertyAddressSnapshot: input.property
        ? addressSnapshotOf(input.property)
        : null,
      title: job.title,
      description: job.description,
      typeCode: job.typeCode,
      status: job.status,
      ownerMembershipId: job.owner ? technicians.managerMembershipId : null,
      // `final_outcome` is optional and its catalogue is an open question (`BR-062`), so the demo
      // does not invent a value for it.
      finalOutcomeCode: null,
      version: 1,
      createdAt: resolved.createdAt,
      updatedAt: resolved.statusEventTimes.at(-1) ?? resolved.createdAt,
    })
    .returning({ id: jobs.id });
  if (!row) {
    throw new Error(`Failed to seed job ${input.jobNumber} "${job.title}".`);
  }

  await insertJobStatusHistory(db, {
    organizationId: input.organizationId,
    jobId: row.id,
    job,
    resolved,
    actorMembershipId: technicians.managerMembershipId,
  });

  for (const visit of resolved.visits) {
    await insertVisit(db, {
      organizationId: input.organizationId,
      jobId: row.id,
      visit,
      property: input.property,
      technicians,
      now,
    });
  }

  return resolved.visits.length;
}

/** Records how the Job reached its status, one permitted transition at a time (`BR-058`). */
async function insertJobStatusHistory(
  db: SeedDatabase,
  input: {
    organizationId: string;
    jobId: string;
    job: SeedJobPlan;
    resolved: ResolvedSeedJob;
    actorMembershipId: string;
  },
): Promise<void> {
  let previous: string | null = null;

  for (const [step, to] of JOB_STATUS_PATHS[input.job.status].entries()) {
    await db.insert(jobStatusHistory).values({
      organizationId: input.organizationId,
      jobId: input.jobId,
      fromStatus: previous,
      toStatus: to,
      // A cancellation needs an explanation (`BR-064`). The structured reason catalogue is an open
      // question, so the explanation is carried as the note and no reason code is invented.
      note: to === 'CANCELED' ? (input.job.cancellationNote ?? null) : null,
      actorMembershipId: input.actorMembershipId,
      recordedAt: input.resolved.statusEventTimes[step],
    });
    previous = to;
  }
}

/** Writes one Visit with its crew, its status history, its outcome and its notes. */
async function insertVisit(
  db: SeedDatabase,
  input: {
    organizationId: string;
    jobId: string;
    visit: ResolvedSeedVisit;
    property: SeededProperty | null;
    technicians: SeedTechnicians;
    now: Date;
  },
): Promise<void> {
  const { visit, technicians, now } = input;
  const { plan, window } = visit;
  const crew = plan.crew
    .map((key) => technicians.byKey[key])
    .filter((id): id is string => id !== undefined);
  const leadMembershipId = crew[0] ?? technicians.managerMembershipId;
  const outcome = plan.status === 'COMPLETED' ? plan.outcome : undefined;
  const created =
    window === null
      ? now
      : pastInstant(addHours(window.scheduledStart, -72), now, 1);

  const [row] = await db
    .insert(visits)
    .values({
      organizationId: input.organizationId,
      jobId: input.jobId,
      propertyId: input.property?.id ?? null,
      locationAddressSnapshot: input.property
        ? addressSnapshotOf(input.property)
        : null,
      status: plan.status,
      scheduledStart: window?.scheduledStart ?? null,
      scheduledEnd: window?.scheduledEnd ?? null,
      // The customer-facing arrival window is derived from the internal schedule `BR-072` keeps
      // authoritative: the crew arrives within ninety minutes of the scheduled start.
      arrivalWindowStart: window ? addHours(window.scheduledStart, -0.5) : null,
      arrivalWindowEnd: window ? addHours(window.scheduledStart, 1.5) : null,
      outcomeCode: outcome?.code ?? null,
      outcomeSummary: outcome?.summary ?? null,
      outcomeRecordedAt: outcome ? visit.outcomeRecordedAt : null,
      outcomeRecordedByMembershipId: outcome ? leadMembershipId : null,
      version: 1,
      createdAt: created,
      updatedAt: visit.statusEventTimes.at(-1) ?? created,
    })
    .returning({ id: visits.id });
  if (!row) {
    throw new Error(`Failed to seed a ${plan.status} visit.`);
  }

  await insertVisitAssignment(db, {
    organizationId: input.organizationId,
    visitId: row.id,
    visit,
    crew,
    technicians,
    now,
  });
  await insertVisitStatusHistory(db, {
    organizationId: input.organizationId,
    visitId: row.id,
    visit,
    leadMembershipId,
    managerMembershipId: technicians.managerMembershipId,
  });
  await insertVisitNotes(db, {
    organizationId: input.organizationId,
    visitId: row.id,
    visit,
    technicians,
  });

  if (outcome !== undefined && visit.outcomeRecordedAt !== null) {
    // The outcome history is what Job Activity projects as `VISIT_OUTCOME_RECORDED`, so a completed
    // Visit carries it exactly as an API completion does (`BR-079`, `BR-080`).
    await db.insert(visitOutcomeHistory).values({
      organizationId: input.organizationId,
      visitId: row.id,
      outcomeCode: outcome.code,
      outcomeSummary: outcome.summary,
      previousOutcomeCode: null,
      previousOutcomeSummary: null,
      actorMembershipId: leadMembershipId,
      recordedAt: visit.outcomeRecordedAt,
    });
  }
}

/**
 * Writes the crew a Visit carries and the append-only assignment history `BR-069` describes.
 *
 * The current assignment holds only who is on the Visit now; a technician who had been on it and was
 * removed again, and a Lead who joined as an ordinary technician and was promoted later, are recorded as
 * history rather than as a rewritten assignment.
 */
async function insertVisitAssignment(
  db: SeedDatabase,
  input: {
    organizationId: string;
    visitId: string;
    visit: ResolvedSeedVisit;
    crew: readonly string[];
    technicians: SeedTechnicians;
    now: Date;
  },
): Promise<void> {
  const { plan, window } = input.visit;
  if (input.crew.length === 0) {
    // A draft field attempt may exist without technicians (`BR-068`, `BR-072`).
    return;
  }
  const start = window?.scheduledStart ?? input.now;

  const insertEvent = async (event: {
    technicianMembershipId: string;
    event: 'ASSIGNED' | 'REMOVED' | 'ROLE_CHANGED';
    roleCode: 'LEAD' | 'TECHNICIAN' | null;
    previousRoleCode: 'LEAD' | 'TECHNICIAN' | null;
    recordedAt: Date;
  }): Promise<void> => {
    await db.insert(visitTechnicianHistory).values({
      organizationId: input.organizationId,
      visitId: input.visitId,
      technicianMembershipId: event.technicianMembershipId,
      event: event.event,
      roleCode: event.roleCode,
      previousRoleCode: event.previousRoleCode,
      actorMembershipId: input.technicians.managerMembershipId,
      recordedAt: event.recordedAt,
    });
  };

  const removedMembershipId =
    plan.removedTechnician === undefined
      ? undefined
      : input.technicians.byKey[plan.removedTechnician];

  // The history is assembled in the order it happened and then ordered backwards from "now": a Visit
  // scheduled weeks ahead still records a crew that was assigned in the past, and the instants stay
  // strictly increasing whatever the plan asked for.
  const events: {
    technicianMembershipId: string;
    event: 'ASSIGNED' | 'REMOVED' | 'ROLE_CHANGED';
    roleCode: 'LEAD' | 'TECHNICIAN' | null;
    previousRoleCode: 'LEAD' | 'TECHNICIAN' | null;
    candidate: Date;
  }[] = [];

  if (removedMembershipId !== undefined) {
    events.push(
      {
        technicianMembershipId: removedMembershipId,
        event: 'ASSIGNED',
        roleCode: 'TECHNICIAN',
        previousRoleCode: null,
        candidate: addHours(start, -30),
      },
      {
        technicianMembershipId: removedMembershipId,
        event: 'REMOVED',
        roleCode: null,
        previousRoleCode: 'TECHNICIAN',
        candidate: addHours(start, -26),
      },
    );
  }

  input.crew.forEach((technicianMembershipId, position) => {
    events.push({
      technicianMembershipId,
      event: 'ASSIGNED',
      roleCode: 'TECHNICIAN',
      previousRoleCode: null,
      candidate: addHours(start, -24 + position / 60),
    });
    if (position === 0) {
      // The Lead joined as an ordinary technician and was promoted later — the change `BR-069`
      // illustrates: two recorded facts, never a rewritten assignment.
      events.push({
        technicianMembershipId,
        event: 'ROLE_CHANGED',
        roleCode: 'LEAD',
        previousRoleCode: 'TECHNICIAN',
        candidate: addHours(start, -12),
      });
    }
  });

  const recordedAt = orderedPastEventTimes(
    events.map((event) => event.candidate),
    input.now,
  );
  for (const [index, event] of events.entries()) {
    await insertEvent({
      technicianMembershipId: event.technicianMembershipId,
      event: event.event,
      roleCode: event.roleCode,
      previousRoleCode: event.previousRoleCode,
      recordedAt: recordedAt[index],
    });
  }

  for (const [position, technicianMembershipId] of input.crew.entries()) {
    await db.insert(visitTechnicians).values({
      organizationId: input.organizationId,
      visitId: input.visitId,
      technicianMembershipId,
      roleCode: position === 0 ? 'LEAD' : 'TECHNICIAN',
    });
  }
}

/** Records how the Visit reached its status, one permitted transition at a time (`BR-074`). */
async function insertVisitStatusHistory(
  db: SeedDatabase,
  input: {
    organizationId: string;
    visitId: string;
    visit: ResolvedSeedVisit;
    leadMembershipId: string;
    managerMembershipId: string;
  },
): Promise<void> {
  const transition = VISIT_STATUS_PATHS[input.visit.plan.status];
  let previous: string | null = null;

  for (const [step, to] of transition.path.entries()) {
    const isCancellation = to === 'CANCELED';
    // Scheduling and the dispatch pair are office actions (`BR-066`, `BR-074`); the field statuses are
    // recorded by the crew's Lead, so the history names the member who really acted (`BR-033`, `BR-093`).
    const isOfficeAction =
      to === 'SCHEDULED' || to === 'CANCELED' || to === 'NO_SHOW';
    await db.insert(visitStatusHistory).values({
      organizationId: input.organizationId,
      visitId: input.visitId,
      fromStatus: previous,
      toStatus: to,
      cancellationSource: isCancellation ? 'MANUAL' : null,
      reasonCode: isCancellation
        ? (input.visit.plan.cancellation?.reasonCode ??
          transition.cancellationReasonCode ??
          null)
        : null,
      note: isCancellation
        ? (input.visit.plan.cancellation?.note ?? null)
        : null,
      actorMembershipId: isOfficeAction
        ? input.managerMembershipId
        : input.leadMembershipId,
      recordedAt: input.visit.statusEventTimes[step],
    });
    previous = to;
  }
}

/** Writes the Visit's notes, each by the member who really wrote it (`BR-027`). */
async function insertVisitNotes(
  db: SeedDatabase,
  input: {
    organizationId: string;
    visitId: string;
    visit: ResolvedSeedVisit;
    technicians: SeedTechnicians;
  },
): Promise<void> {
  for (const note of input.visit.notes) {
    const authorMembershipId =
      note.plan.author === 'MANAGER'
        ? input.technicians.managerMembershipId
        : input.technicians.byKey[note.plan.author];
    if (authorMembershipId === undefined) {
      throw new Error(`Unknown note author "${note.plan.author}".`);
    }
    await db.insert(visitNotes).values({
      organizationId: input.organizationId,
      visitId: input.visitId,
      authorMembershipId,
      body: note.plan.body,
      recordedAt: note.recordedAt,
    });
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

function report(
  seed: DevelopmentSeed,
  seeded: DemoDataCounts,
  removed: ClearedRows,
): void {
  console.log(
    `Seeded development data in the organization "${seed.organizationName}":`,
  );
  for (const account of seed.accounts) {
    const credential = account.generatedPassword
      ? `password (generated now): ${account.password}`
      : `password: the value of ${account.passwordVariable}`;
    console.log(`  ${account.email} — ${account.role} — ${credential}`);
  }
  console.log(
    `  demo data: ${seeded.customers} customers, ${seeded.properties} properties, ` +
      `${seeded.jobs} jobs and ${seeded.visits} visits written`,
  );
  console.log(
    `  replaced first: ${removed.customers} customers, ${removed.properties} properties, ` +
      `${removed.jobs} jobs and ${removed.visits} visits`,
  );
  console.log('These accounts sign in through POST /auth/sign-in.');
}

main().catch((error: unknown) => {
  const message = error instanceof Error ? error.message : String(error);
  console.error(`Development seeding failed: ${message}`);
  process.exitCode = 1;
});
