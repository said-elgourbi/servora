import { and, eq, inArray, sql } from 'drizzle-orm';
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
  customerAddresses,
  customerCompanies,
  customerContacts,
  customerIndividuals,
  customers,
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
  visits,
  visitTechnicianHistory,
  visitTechnicians,
} from './schema.js';

// Compiled to dist/ by `nest build` and executed by `npm run db:seed`
// (`make seed`). Development tooling only: it never runs inside the API process
// and refuses to run when NODE_ENV=production.
type SeedDatabase = PostgresJsDatabase<Record<string, never>>;

const OPERATIONAL_SEED_TAG = '[dev-seed-operational]';

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
    descriptionEn: 'Permanently delete a Property that has never been referenced.',
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

type CustomerSeedKind = 'INDIVIDUAL' | 'COMPANY';

interface CustomerSeedTemplate {
  readonly kind: CustomerSeedKind;
  readonly displayName: string;
  readonly email: string;
  readonly phone: string;
  readonly preferredContactMethod: 'EMAIL' | 'PHONE' | 'SMS' | 'NONE';
  readonly language: 'en-CA' | 'fr-CA';
  readonly status: 'ACTIVE' | 'INACTIVE';
  readonly city: string;
  readonly province: string;
  readonly postalCode: string;
  readonly propertyCount: number;
  readonly situation: string;
}

const CUSTOMER_TEMPLATES: readonly CustomerSeedTemplate[] = [
  {
    kind: 'COMPANY',
    displayName: 'Northstar Bakery',
    email: 'ops+northstar@servora.test',
    phone: '+1 416 555 0101',
    preferredContactMethod: 'EMAIL',
    language: 'en-CA',
    status: 'ACTIVE',
    city: 'Toronto',
    province: 'Ontario',
    postalCode: 'M5V 2T6',
    propertyCount: 3,
    situation: 'multi-site commercial customer',
  },
  {
    kind: 'INDIVIDUAL',
    displayName: 'Amelie Tremblay',
    email: 'amelie.tremblay@servora.test',
    phone: '+1 514 555 0102',
    preferredContactMethod: 'SMS',
    language: 'fr-CA',
    status: 'ACTIVE',
    city: 'Montreal',
    province: 'Quebec',
    postalCode: 'H2X 1Y4',
    propertyCount: 1,
    situation: 'single residential property',
  },
  {
    kind: 'COMPANY',
    displayName: 'Harbour Clinic Group',
    email: 'facilities+harbour@servora.test',
    phone: '+1 604 555 0103',
    preferredContactMethod: 'PHONE',
    language: 'en-CA',
    status: 'ACTIVE',
    city: 'Vancouver',
    province: 'British Columbia',
    postalCode: 'V6B 3K9',
    propertyCount: 2,
    situation: 'healthcare locations with scheduled maintenance',
  },
  {
    kind: 'COMPANY',
    displayName: 'Prairie Retail Co.',
    email: 'maintenance+prairie@servora.test',
    phone: '+1 403 555 0104',
    preferredContactMethod: 'EMAIL',
    language: 'en-CA',
    status: 'ACTIVE',
    city: 'Calgary',
    province: 'Alberta',
    postalCode: 'T2P 1J9',
    propertyCount: 2,
    situation: 'retail customer with an active follow-up',
  },
  {
    kind: 'INDIVIDUAL',
    displayName: 'Noah Singh',
    email: 'noah.singh@servora.test',
    phone: '+1 905 555 0105',
    preferredContactMethod: 'SMS',
    language: 'en-CA',
    status: 'ACTIVE',
    city: 'Mississauga',
    province: 'Ontario',
    postalCode: 'L5B 4M7',
    propertyCount: 1,
    situation: 'new request waiting on property details',
  },
  {
    kind: 'COMPANY',
    displayName: 'Lakeside Condos',
    email: 'board+lakeside@servora.test',
    phone: '+1 613 555 0106',
    preferredContactMethod: 'EMAIL',
    language: 'en-CA',
    status: 'ACTIVE',
    city: 'Ottawa',
    province: 'Ontario',
    postalCode: 'K1P 5G4',
    propertyCount: 3,
    situation: 'property manager with several open visits',
  },
  {
    kind: 'INDIVIDUAL',
    displayName: 'Sofia Rossi',
    email: 'sofia.rossi@servora.test',
    phone: '+1 289 555 0107',
    preferredContactMethod: 'PHONE',
    language: 'en-CA',
    status: 'ACTIVE',
    city: 'Hamilton',
    province: 'Ontario',
    postalCode: 'L8P 1A1',
    propertyCount: 1,
    situation: 'completed work awaiting review history',
  },
  {
    kind: 'COMPANY',
    displayName: 'Maple Office Partners',
    email: 'service+mapleoffice@servora.test',
    phone: '+1 647 555 0108',
    preferredContactMethod: 'EMAIL',
    language: 'en-CA',
    status: 'ACTIVE',
    city: 'Toronto',
    province: 'Ontario',
    postalCode: 'M4W 1A8',
    propertyCount: 2,
    situation: 'downtown offices with canceled work',
  },
  {
    kind: 'INDIVIDUAL',
    displayName: 'Ethan Chen',
    email: 'ethan.chen@servora.test',
    phone: '+1 778 555 0109',
    preferredContactMethod: 'NONE',
    language: 'en-CA',
    status: 'INACTIVE',
    city: 'Burnaby',
    province: 'British Columbia',
    postalCode: 'V5C 2K1',
    propertyCount: 1,
    situation: 'inactive customer retained for archive filters',
  },
  {
    kind: 'COMPANY',
    displayName: 'Summit Schools',
    email: 'facilities+summit@servora.test',
    phone: '+1 780 555 0110',
    preferredContactMethod: 'EMAIL',
    language: 'en-CA',
    status: 'ACTIVE',
    city: 'Edmonton',
    province: 'Alberta',
    postalCode: 'T5J 2N3',
    propertyCount: 4,
    situation: 'large customer for scrolling long property lists',
  },
  {
    kind: 'INDIVIDUAL',
    displayName: 'Maya Okafor',
    email: 'maya.okafor@servora.test',
    phone: '+1 519 555 0111',
    preferredContactMethod: 'SMS',
    language: 'en-CA',
    status: 'ACTIVE',
    city: 'London',
    province: 'Ontario',
    postalCode: 'N6A 3K7',
    propertyCount: 1,
    situation: 'no-show visit scenario',
  },
  {
    kind: 'COMPANY',
    displayName: 'Cedar Hotel',
    email: 'engineering+cedar@servora.test',
    phone: '+1 902 555 0112',
    preferredContactMethod: 'PHONE',
    language: 'en-CA',
    status: 'ACTIVE',
    city: 'Halifax',
    province: 'Nova Scotia',
    postalCode: 'B3J 3K5',
    propertyCount: 2,
    situation: 'hospitality customer with urgent in-progress work',
  },
  {
    kind: 'INDIVIDUAL',
    displayName: 'Lucas Martin',
    email: 'lucas.martin@servora.test',
    phone: '+1 418 555 0113',
    preferredContactMethod: 'EMAIL',
    language: 'fr-CA',
    status: 'ACTIVE',
    city: 'Quebec City',
    province: 'Quebec',
    postalCode: 'G1R 4P5',
    propertyCount: 1,
    situation: 'French-language customer',
  },
  {
    kind: 'COMPANY',
    displayName: 'Riverbend Warehouse',
    email: 'dispatch+riverbend@servora.test',
    phone: '+1 204 555 0114',
    preferredContactMethod: 'EMAIL',
    language: 'en-CA',
    status: 'ACTIVE',
    city: 'Winnipeg',
    province: 'Manitoba',
    postalCode: 'R3C 0V8',
    propertyCount: 2,
    situation: 'industrial customer needing parts',
  },
  {
    kind: 'INDIVIDUAL',
    displayName: 'Ava Williams',
    email: 'ava.williams@servora.test',
    phone: '+1 705 555 0115',
    preferredContactMethod: 'PHONE',
    language: 'en-CA',
    status: 'ACTIVE',
    city: 'Barrie',
    province: 'Ontario',
    postalCode: 'L4M 4S5',
    propertyCount: 1,
    situation: 'quote approval pending',
  },
  {
    kind: 'COMPANY',
    displayName: 'Evergreen Dental',
    email: 'admin+evergreen@servora.test',
    phone: '+1 236 555 0116',
    preferredContactMethod: 'EMAIL',
    language: 'en-CA',
    status: 'INACTIVE',
    city: 'Victoria',
    province: 'British Columbia',
    postalCode: 'V8W 1P6',
    propertyCount: 1,
    situation: 'inactive business customer',
  },
  {
    kind: 'INDIVIDUAL',
    displayName: 'Grace Murphy',
    email: 'grace.murphy@servora.test',
    phone: '+1 506 555 0117',
    preferredContactMethod: 'SMS',
    language: 'en-CA',
    status: 'ACTIVE',
    city: 'Fredericton',
    province: 'New Brunswick',
    postalCode: 'E3B 1B5',
    propertyCount: 1,
    situation: 'draft visit not scheduled yet',
  },
  {
    kind: 'COMPANY',
    displayName: 'Bluebird Grocers',
    email: 'repairs+bluebird@servora.test',
    phone: '+1 306 555 0118',
    preferredContactMethod: 'PHONE',
    language: 'en-CA',
    status: 'ACTIVE',
    city: 'Regina',
    province: 'Saskatchewan',
    postalCode: 'S4P 3Y2',
    propertyCount: 2,
    situation: 'grocery customer with recurring service',
  },
  {
    kind: 'INDIVIDUAL',
    displayName: 'Olivier Gagnon',
    email: 'olivier.gagnon@servora.test',
    phone: '+1 450 555 0119',
    preferredContactMethod: 'EMAIL',
    language: 'fr-CA',
    status: 'INACTIVE',
    city: 'Laval',
    province: 'Quebec',
    postalCode: 'H7N 5K2',
    propertyCount: 1,
    situation: 'inactive archived residential customer',
  },
  {
    kind: 'COMPANY',
    displayName: 'Atlas Fitness',
    email: 'maintenance+atlas@servora.test',
    phone: '+1 437 555 0120',
    preferredContactMethod: 'EMAIL',
    language: 'en-CA',
    status: 'ACTIVE',
    city: 'Toronto',
    province: 'Ontario',
    postalCode: 'M6K 3C3',
    propertyCount: 3,
    situation: 'multi-branch customer for customer search',
  },
] as const;

const JOB_STATUS_SEQUENCE = [
  'NEW',
  'SCHEDULED',
  'IN_PROGRESS',
  'PENDING_REVIEW',
  'COMPLETED',
  'CANCELED',
] as const;

const VISIT_STATUS_SEQUENCE = [
  'DRAFT',
  'SCHEDULED',
  'EN_ROUTE',
  'ON_SITE',
  'IN_PROGRESS',
  'COMPLETED',
  'CANCELED',
  'NO_SHOW',
] as const;

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
    const membershipIds = await getSeedMembershipIds(
      db,
      organizationId,
      roleIds,
    );
    await seedOperationalDemoData(db, organizationId, membershipIds);

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

async function getSeedMembershipIds(
  db: SeedDatabase,
  organizationId: string,
  roleIds: Record<keyof typeof ROLE_TEMPLATES, string>,
): Promise<{ manager: string; technician: string }> {
  const [manager] = await db
    .select({ id: organizationMembers.id })
    .from(organizationMembers)
    .where(
      and(
        eq(organizationMembers.organizationId, organizationId),
        eq(organizationMembers.roleId, roleIds.MANAGER),
      ),
    )
    .limit(1);
  const [technician] = await db
    .select({ id: organizationMembers.id })
    .from(organizationMembers)
    .where(
      and(
        eq(organizationMembers.organizationId, organizationId),
        eq(organizationMembers.roleId, roleIds.TECHNICIAN),
      ),
    )
    .limit(1);

  if (!manager || !technician) {
    throw new Error(
      'Failed to resolve seeded manager and technician memberships.',
    );
  }

  return { manager: manager.id, technician: technician.id };
}

function addHours(value: Date, hours: number): Date {
  return new Date(value.getTime() + hours * 60 * 60 * 1000);
}

function propertySnapshot(property: {
  name: string | null;
  addressLine1: string;
  addressLine2?: string | null;
  city: string;
  province: string;
  postalCode: string;
  country: string;
}): Record<string, string | null> {
  return {
    propertyName: property.name,
    addressLine1: property.addressLine1,
    addressLine2: property.addressLine2 ?? null,
    city: property.city,
    province: property.province,
    postalCode: property.postalCode,
    country: property.country,
  };
}

async function resetOperationalDemoData(
  db: SeedDatabase,
  organizationId: string,
): Promise<void> {
  const demoCustomers = await db
    .select({ id: customers.id })
    .from(customers)
    .where(
      and(
        eq(customers.organizationId, organizationId),
        sql`${customers.notes} like ${`%${OPERATIONAL_SEED_TAG}%`}`,
      ),
    );
  const customerIds = demoCustomers.map((customer) => customer.id);

  if (customerIds.length > 0) {
    await db
      .delete(jobs)
      .where(
        and(
          eq(jobs.organizationId, organizationId),
          inArray(jobs.customerId, customerIds),
        ),
      );
    await db
      .delete(propertyCustomerRelationships)
      .where(
        and(
          eq(propertyCustomerRelationships.organizationId, organizationId),
          inArray(propertyCustomerRelationships.customerId, customerIds),
        ),
      );
    await db.delete(customers).where(inArray(customers.id, customerIds));
  }

  await db
    .delete(properties)
    .where(
      and(
        eq(properties.organizationId, organizationId),
        sql`${properties.notes} like ${`%${OPERATIONAL_SEED_TAG}%`}`,
      ),
    );
}

async function seedOperationalDemoData(
  db: SeedDatabase,
  organizationId: string,
  membershipIds: { manager: string; technician: string },
): Promise<void> {
  await resetOperationalDemoData(db, organizationId);

  let jobNumber = 1000;
  const baseSchedule = new Date('2026-09-14T13:00:00.000Z');

  for (const [customerIndex, template] of CUSTOMER_TEMPLATES.entries()) {
    const [customer] = await db
      .insert(customers)
      .values({
        organizationId,
        type: template.kind,
        displayName: template.displayName,
        email: template.email,
        phone: template.phone,
        billingEmail:
          template.kind === 'COMPANY'
            ? `billing+${customerIndex + 1}@servora.test`
            : template.email,
        billingPhone: template.phone,
        notes: `${OPERATIONAL_SEED_TAG} ${template.situation}.`,
        preferredContactMethod: template.preferredContactMethod,
        language: template.language,
        status: template.status,
      })
      .returning({ id: customers.id });
    if (!customer) {
      throw new Error(`Failed to seed customer "${template.displayName}".`);
    }

    if (template.kind === 'INDIVIDUAL') {
      const [firstName, ...lastNameParts] = template.displayName.split(' ');
      await db.insert(customerIndividuals).values({
        customerId: customer.id,
        firstName,
        lastName: lastNameParts.join(' '),
      });
    } else {
      await db.insert(customerCompanies).values({
        customerId: customer.id,
        legalName: `${template.displayName} Ltd.`,
        businessName: template.displayName,
        taxNumber: `GST-${String(customerIndex + 1).padStart(4, '0')}`,
      });
    }

    await db.insert(customerContacts).values({
      customerId: customer.id,
      firstName: template.kind === 'COMPANY' ? 'Morgan' : 'Primary',
      lastName: template.kind === 'COMPANY' ? 'Contact' : 'Contact',
      email: template.email,
      phone: template.phone,
      role: template.kind === 'COMPANY' ? 'Operations' : 'Owner',
      isPrimary: true,
      isBillingContact: template.kind === 'COMPANY',
      isJobContact: true,
    });

    await db.insert(customerAddresses).values({
      customerId: customer.id,
      type: 'BILLING',
      addressLine1: `${100 + customerIndex} Billing Street`,
      city: template.city,
      province: template.province,
      postalCode: template.postalCode,
      country: 'Canada',
      isDefault: true,
    });

    const seededProperties = [];
    for (
      let propertyIndex = 0;
      propertyIndex < template.propertyCount;
      propertyIndex += 1
    ) {
      const [property] = await db
        .insert(properties)
        .values({
          organizationId,
          name:
            template.propertyCount === 1
              ? `${template.displayName} Main Property`
              : `${template.displayName} Site ${propertyIndex + 1}`,
          addressLine1: `${200 + customerIndex * 10 + propertyIndex} Service Avenue`,
          addressLine2:
            propertyIndex % 3 === 2 ? `Unit ${propertyIndex + 1}` : null,
          city: template.city,
          province: template.province,
          postalCode: template.postalCode,
          country: 'Canada',
          notes: `${OPERATIONAL_SEED_TAG} Property ${propertyIndex + 1} for ${template.displayName}.`,
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
      if (!property) {
        throw new Error(
          `Failed to seed property for "${template.displayName}".`,
        );
      }
      seededProperties.push(property);

      await db.insert(propertyCustomerRelationships).values({
        organizationId,
        propertyId: property.id,
        customerId: customer.id,
        actorMembershipId: membershipIds.manager,
      });
    }

    const jobCount = customerIndex < 8 ? 3 : customerIndex < 16 ? 2 : 1;
    for (let localJobIndex = 0; localJobIndex < jobCount; localJobIndex += 1) {
      jobNumber += 1;
      const status =
        JOB_STATUS_SEQUENCE[
          (customerIndex + localJobIndex) % JOB_STATUS_SEQUENCE.length
        ];
      const property =
        status === 'NEW' && localJobIndex === 0
          ? undefined
          : seededProperties[
              (localJobIndex + customerIndex) % seededProperties.length
            ];
      const snapshot = property ? propertySnapshot(property) : null;
      const [job] = await db
        .insert(jobs)
        .values({
          organizationId,
          jobNumber,
          customerId: customer.id,
          propertyId: property?.id ?? null,
          propertyAddressSnapshot: snapshot,
          title: `${status.replace('_', ' ').toLowerCase()} service ${jobNumber}`,
          description: `${OPERATIONAL_SEED_TAG} ${template.situation}; customer ${customerIndex + 1}, job ${localJobIndex + 1}.`,
          typeCode: ['REPAIR', 'MAINTENANCE', 'INSPECTION', 'INSTALLATION'][
            (customerIndex + localJobIndex) % 4
          ],
          status,
          ownerMembershipId: membershipIds.manager,
          finalOutcomeCode: status === 'COMPLETED' ? 'RESOLVED' : null,
        })
        .returning({ id: jobs.id });
      if (!job) {
        throw new Error(`Failed to seed job ${jobNumber}.`);
      }

      await seedVisitsForJob(db, {
        organizationId,
        jobId: job.id,
        property,
        snapshot,
        jobStatus: status,
        seedIndex: customerIndex * 3 + localJobIndex,
        membershipIds,
        baseSchedule,
      });
    }
  }

  await db
    .insert(organizationJobNumberCounters)
    .values({ organizationId, lastJobNumber: jobNumber })
    .onConflictDoUpdate({
      target: organizationJobNumberCounters.organizationId,
      set: {
        lastJobNumber: sql`greatest(${organizationJobNumberCounters.lastJobNumber}, ${jobNumber})`,
      },
    });
}

async function seedVisitsForJob(
  db: SeedDatabase,
  input: {
    organizationId: string;
    jobId: string;
    property:
      | {
          id: string;
          name: string | null;
          addressLine1: string;
          addressLine2: string | null;
          city: string;
          province: string;
          postalCode: string;
          country: string;
        }
      | undefined;
    snapshot: Record<string, string | null> | null;
    jobStatus: (typeof JOB_STATUS_SEQUENCE)[number];
    seedIndex: number;
    membershipIds: { manager: string; technician: string };
    baseSchedule: Date;
  },
): Promise<void> {
  if (!input.property || input.jobStatus === 'NEW') {
    return;
  }

  const visitCount = input.jobStatus === 'IN_PROGRESS' ? 2 : 1;
  for (let index = 0; index < visitCount; index += 1) {
    const visitStatus =
      input.jobStatus === 'SCHEDULED'
        ? 'SCHEDULED'
        : input.jobStatus === 'IN_PROGRESS'
          ? VISIT_STATUS_SEQUENCE[(input.seedIndex + index + 2) % 5]
          : input.jobStatus === 'PENDING_REVIEW' ||
              input.jobStatus === 'COMPLETED'
            ? 'COMPLETED'
            : 'CANCELED';
    const scheduledStart = addHours(
      input.baseSchedule,
      input.seedIndex * 5 + index * 3,
    );
    const scheduledEnd = addHours(scheduledStart, 2);
    const isCompleted = visitStatus === 'COMPLETED';

    const [visit] = await db
      .insert(visits)
      .values({
        organizationId: input.organizationId,
        jobId: input.jobId,
        propertyId: input.property.id,
        locationAddressSnapshot: input.snapshot,
        status: visitStatus,
        scheduledStart,
        scheduledEnd,
        arrivalWindowStart: addHours(scheduledStart, -1),
        arrivalWindowEnd: addHours(scheduledStart, 1),
        outcomeCode: isCompleted ? 'RESOLVED' : null,
        outcomeSummary: isCompleted
          ? 'Development seed completed visit outcome.'
          : null,
        outcomeRecordedAt: isCompleted ? scheduledEnd : null,
        outcomeRecordedByMembershipId: isCompleted
          ? input.membershipIds.technician
          : null,
      })
      .returning({ id: visits.id });
    if (!visit) {
      throw new Error('Failed to seed visit.');
    }

    await db.insert(visitTechnicians).values({
      organizationId: input.organizationId,
      visitId: visit.id,
      technicianMembershipId: input.membershipIds.technician,
      roleCode: 'LEAD',
    });
    await db.insert(visitTechnicianHistory).values({
      organizationId: input.organizationId,
      visitId: visit.id,
      technicianMembershipId: input.membershipIds.technician,
      event: 'ASSIGNED',
      roleCode: 'LEAD',
      actorMembershipId: input.membershipIds.manager,
    });
    await db.insert(visitNotes).values({
      organizationId: input.organizationId,
      visitId: visit.id,
      authorMembershipId:
        index % 2 === 0
          ? input.membershipIds.manager
          : input.membershipIds.technician,
      body: `${OPERATIONAL_SEED_TAG} ${visitStatus.toLowerCase()} visit note for filtering.`,
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
  console.log(
    `  operational demo: ${CUSTOMER_TEMPLATES.length} customers, properties, jobs, visits and assignments refreshed`,
  );
  console.log('These accounts sign in through POST /auth/sign-in.');
}

main().catch((error: unknown) => {
  const message = error instanceof Error ? error.message : String(error);
  console.error(`Development seeding failed: ${message}`);
  process.exitCode = 1;
});
