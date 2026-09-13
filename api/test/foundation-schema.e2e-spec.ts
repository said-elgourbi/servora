import crypto from 'node:crypto';
import { eq, inArray } from 'drizzle-orm';
import {
  customerAddresses,
  customerContacts,
  customerIndividuals,
  customers,
  organizationMemberPermissions,
  organizationMembers,
  organizationRoles,
  organizations,
  permissions,
  rolePermissions,
  userProfiles,
  users,
} from '../src/database/schema.js';
import {
  createFoundationTestDatabase,
  createTestOrganization,
  createTestOrganizationRole,
  createTestUser,
  expectPostgresError,
  uniqueEmail,
  type FoundationTestDatabase,
} from './support/foundation-database.js';

// Requires PostgreSQL at DATABASE_URL with migrations applied (global setup).
describe('foundation domain schema (e2e)', () => {
  let database: FoundationTestDatabase;

  beforeAll(async () => {
    database = await createFoundationTestDatabase();
  });

  afterAll(async () => {
    await database.dispose();
  });

  describe('organizations', () => {
    it('persists an organization and defaults its status to ACTIVE', async () => {
      const organization = await createTestOrganization(database.db, {
        name: 'Northwind',
      });
      database.cleanup.trackOrganization(organization.id);

      expect(organization.status).toBe('ACTIVE');
      expect(organization.id).toMatch(/^[0-9a-f-]{36}$/);
      expect(organization.createdAt).toBeInstanceOf(Date);
    });

    it('does not require email uniqueness across organizations', async () => {
      const email = uniqueEmail('org');
      const first = await createTestOrganization(database.db, { email });
      const second = await createTestOrganization(database.db, { email });
      database.cleanup.trackOrganization(first.id);
      database.cleanup.trackOrganization(second.id);

      expect(first.email).toBe(second.email);
    });

    it('rejects an unknown status through the check constraint', async () => {
      await expectPostgresError(
        () =>
          database.db
            .insert(organizations)
            .values({ name: 'Bad status', status: 'DELETED' }),
        '23514',
      );
    });
  });

  describe('users', () => {
    it('stores a password hash and never a plaintext password column', async () => {
      const user = await createTestUser(database.db);
      database.cleanup.trackUser(user.id);

      expect(user.passwordHash.startsWith('$argon2id$')).toBe(true);

      const columns = (
        await database.client`select column_name from information_schema.columns
          where table_schema = 'public' and table_name = 'users'`
      ).map((row) => row.column_name as string);

      expect(columns).toContain('password_hash');
      expect(columns).not.toContain('password');
    });

    it('enforces a globally unique email', async () => {
      const email = uniqueEmail('user');
      const user = await createTestUser(database.db, { email });
      database.cleanup.trackUser(user.id);

      await expectPostgresError(
        () => database.db.insert(users).values({ email, passwordHash: 'x' }),
        '23505',
      );
    });

    it('rejects an unknown status through the check constraint', async () => {
      await expectPostgresError(
        () =>
          database.db
            .insert(users)
            .values({
              email: uniqueEmail(),
              passwordHash: 'x',
              status: 'BANNED',
            }),
        '23514',
      );
    });
  });

  describe('user_profiles', () => {
    it('stores a single profile keyed by user_id and cascades with the user', async () => {
      const user = await createTestUser(database.db);
      await database.db
        .insert(userProfiles)
        .values({ userId: user.id, firstName: 'Ada', lastName: 'Lovelace' });

      const [profile] = await database.db
        .select()
        .from(userProfiles)
        .where(eq(userProfiles.userId, user.id));
      expect(profile.firstName).toBe('Ada');
      expect(profile.locale).toBe('en-CA');

      await database.db.delete(users).where(eq(users.id, user.id));

      const remaining = await database.db
        .select()
        .from(userProfiles)
        .where(eq(userProfiles.userId, user.id));
      expect(remaining).toHaveLength(0);
    });

    it('rejects a second profile for the same user (primary key)', async () => {
      const user = await createTestUser(database.db);
      await database.cleanup.trackUser(user.id);
      await database.db
        .insert(userProfiles)
        .values({ userId: user.id, firstName: 'Grace', lastName: 'Hopper' });

      await expectPostgresError(
        () =>
          database.db
            .insert(userProfiles)
            .values({
              userId: user.id,
              firstName: 'Duplicate',
              lastName: 'Profile',
            }),
        '23505',
      );
    });
  });

  describe('organization_members', () => {
    it('stores one organization role per member and grants permissions through joins', async () => {
      const organization = await createTestOrganization(database.db);
      database.cleanup.trackOrganization(organization.id);
      const manager = await createTestUser(database.db);
      const technician = await createTestUser(database.db);
      database.cleanup.trackUser(manager.id);
      database.cleanup.trackUser(technician.id);
      const managerRole = await createTestOrganizationRole(
        database.db,
        organization.id,
        {
          systemCode: 'MANAGER',
          nameEn: 'Supervisor',
          nameFr: 'Superviseur',
        },
      );
      const technicianRole = await createTestOrganizationRole(
        database.db,
        organization.id,
        {
          systemCode: 'TECHNICIAN',
          nameEn: 'Field Tech',
          nameFr: 'Technicien terrain',
        },
      );
      const [permission] = await database.db
        .insert(permissions)
        .values({
          code: `CUSTOMER_VIEW_${crypto.randomUUID()}`,
          nameEn: 'View customers',
          nameFr: 'Voir les clients',
          descriptionEn: 'View customer records.',
          descriptionFr: 'Voir les dossiers client.',
        })
        .returning();
      await database.db.insert(rolePermissions).values({
        organizationId: organization.id,
        roleId: managerRole.id,
        permissionId: permission.id,
      });

      const rows = await database.db
        .insert(organizationMembers)
        .values([
          {
            organizationId: organization.id,
            userId: manager.id,
            roleId: managerRole.id,
          },
          {
            organizationId: organization.id,
            userId: technician.id,
            roleId: technicianRole.id,
          },
        ])
        .returning();

      expect(rows.map((row) => row.roleId).sort()).toEqual(
        [managerRole.id, technicianRole.id].sort(),
      );
      expect(rows.every((row) => row.status === 'ACTIVE')).toBe(true);

      await database.db.insert(organizationMemberPermissions).values({
        organizationId: organization.id,
        memberId: rows[1].id,
        permissionId: permission.id,
        grantedByMembershipId: rows[0].id,
      });
    });

    it('rejects an unknown default role setup code through the check constraint', async () => {
      const organization = await createTestOrganization(database.db);
      database.cleanup.trackOrganization(organization.id);

      await expectPostgresError(
        () =>
          database.db.insert(organizationRoles).values({
            organizationId: organization.id,
            systemCode: 'DISPATCHER',
            nameEn: 'Dispatcher',
            nameFr: 'Repartiteur',
            descriptionEn: 'Dispatch role.',
            descriptionFr: 'Role de repartition.',
          }),
        '23514',
      );
    });

    it('rejects a duplicate (organization_id, user_id) membership', async () => {
      const organization = await createTestOrganization(database.db);
      database.cleanup.trackOrganization(organization.id);
      const user = await createTestUser(database.db);
      database.cleanup.trackUser(user.id);
      const role = await createTestOrganizationRole(
        database.db,
        organization.id,
      );
      await database.db
        .insert(organizationMembers)
        .values({
          organizationId: organization.id,
          userId: user.id,
          roleId: role.id,
        });

      await expectPostgresError(
        () =>
          database.db
            .insert(organizationMembers)
            .values({
              organizationId: organization.id,
              userId: user.id,
              roleId: role.id,
            }),
        '23505',
      );
    });
  });

  describe('customers and subtypes', () => {
    it('rejects an unknown customer type through the check constraint', async () => {
      const organization = await createTestOrganization(database.db);
      database.cleanup.trackOrganization(organization.id);

      await expectPostgresError(
        () =>
          database.db.insert(customers).values({
            organizationId: organization.id,
            type: 'GOVERNMENT',
            displayName: 'Not allowed',
          }),
        '23514',
      );
    });

    it('defaults customer language/contact settings and supports soft deletion', async () => {
      const organization = await createTestOrganization(database.db);
      database.cleanup.trackOrganization(organization.id);
      const user = await createTestUser(database.db);
      database.cleanup.trackUser(user.id);
      const role = await createTestOrganizationRole(
        database.db,
        organization.id,
      );
      const [member] = await database.db
        .insert(organizationMembers)
        .values({
          organizationId: organization.id,
          userId: user.id,
          roleId: role.id,
        })
        .returning();

      const [customer] = await database.db
        .insert(customers)
        .values({
          organizationId: organization.id,
          type: 'COMPANY',
          displayName: 'Soft Delete Co',
        })
        .returning();

      expect(customer.preferredContactMethod).toBe('NONE');
      expect(customer.language).toBe('en-CA');

      const [deleted] = await database.db
        .update(customers)
        .set({
          deletedAt: new Date(),
          deletedByMembershipId: member.id,
          deleteReason: 'Duplicate account.',
        })
        .where(eq(customers.id, customer.id))
        .returning();

      expect(deleted.deletedAt).toBeInstanceOf(Date);
      expect(deleted.deletedByMembershipId).toBe(member.id);
    });

    it('keeps an individual subtype keyed by customer_id and cascades on customer delete', async () => {
      const organization = await createTestOrganization(database.db);
      database.cleanup.trackOrganization(organization.id);
      const [customer] = await database.db
        .insert(customers)
        .values({
          organizationId: organization.id,
          type: 'INDIVIDUAL',
          displayName: 'Jane Doe',
        })
        .returning();
      await database.db
        .insert(customerIndividuals)
        .values({
          customerId: customer.id,
          firstName: 'Jane',
          lastName: 'Doe',
        });

      await database.db.delete(customers).where(eq(customers.id, customer.id));

      const individuals = await database.db
        .select()
        .from(customerIndividuals)
        .where(eq(customerIndividuals.customerId, customer.id));
      expect(individuals).toHaveLength(0);
    });

    it('allows many contacts and addresses per customer and cascades with the customer', async () => {
      const organization = await createTestOrganization(database.db);
      database.cleanup.trackOrganization(organization.id);
      const [customer] = await database.db
        .insert(customers)
        .values({
          organizationId: organization.id,
          type: 'COMPANY',
          displayName: 'Acme',
        })
        .returning();

      await database.db.insert(customerContacts).values([
        { customerId: customer.id, firstName: 'First', lastName: 'Contact' },
        {
          customerId: customer.id,
          firstName: 'Second',
          lastName: 'Contact',
          isBillingContact: true,
        },
      ]);
      await database.db.insert(customerAddresses).values([
        {
          customerId: customer.id,
          type: 'SERVICE',
          addressLine1: '1 Main St',
          city: 'Ottawa',
          province: 'ON',
          postalCode: 'K1A 0B1',
        },
        {
          customerId: customer.id,
          type: 'BILLING',
          addressLine1: '2 Side St',
          city: 'Ottawa',
          province: 'ON',
          postalCode: 'K1A 0B2',
          isDefault: true,
        },
      ]);

      expect(
        await database.db
          .select()
          .from(customerContacts)
          .where(eq(customerContacts.customerId, customer.id)),
      ).toHaveLength(2);
      expect(
        await database.db
          .select()
          .from(customerAddresses)
          .where(eq(customerAddresses.customerId, customer.id)),
      ).toHaveLength(2);

      await database.db.delete(customers).where(eq(customers.id, customer.id));

      expect(
        await database.db
          .select()
          .from(customerContacts)
          .where(eq(customerContacts.customerId, customer.id)),
      ).toHaveLength(0);
      expect(
        await database.db
          .select()
          .from(customerAddresses)
          .where(eq(customerAddresses.customerId, customer.id)),
      ).toHaveLength(0);
    });
  });

  describe('customer addresses and tenant ownership', () => {
    it('rejects an unknown address type through the check constraint', async () => {
      const organization = await createTestOrganization(database.db);
      database.cleanup.trackOrganization(organization.id);
      const [customer] = await database.db
        .insert(customers)
        .values({
          organizationId: organization.id,
          type: 'INDIVIDUAL',
          displayName: 'Jane',
        })
        .returning();

      await expectPostgresError(
        () =>
          database.db.insert(customerAddresses).values({
            customerId: customer.id,
            type: 'WAREHOUSE',
            addressLine1: 'x',
            city: 'y',
            province: 'z',
            postalCode: 'p',
          }),
        '23514',
      );
    });

    it('cascades members and customers when the organization is deleted', async () => {
      const organization = await createTestOrganization(database.db);
      const user = await createTestUser(database.db);
      database.cleanup.trackUser(user.id);
      const role = await createTestOrganizationRole(
        database.db,
        organization.id,
      );
      await database.db
        .insert(organizationMembers)
        .values({
          organizationId: organization.id,
          userId: user.id,
          roleId: role.id,
        });
      const [customer] = await database.db
        .insert(customers)
        .values({
          organizationId: organization.id,
          type: 'COMPANY',
          displayName: 'Cascade Co',
        })
        .returning();

      await database.db
        .delete(organizations)
        .where(eq(organizations.id, organization.id));

      expect(
        await database.db
          .select()
          .from(organizationMembers)
          .where(eq(organizationMembers.organizationId, organization.id)),
      ).toHaveLength(0);
      expect(
        await database.db
          .select()
          .from(customers)
          .where(inArray(customers.id, [customer.id])),
      ).toHaveLength(0);
    });
  });

  describe('timestamps', () => {
    it('stores timezone-aware created_at and updated_at columns', async () => {
      const columns = await database.client`select column_name, data_type
        from information_schema.columns
        where table_schema = 'public' and table_name = 'organizations'
          and column_name in ('created_at', 'updated_at')`;

      expect(columns).toHaveLength(2);
      expect(
        columns.every((row) => row.data_type === 'timestamp with time zone'),
      ).toBe(true);
    });

    it('refreshes updated_at on update', async () => {
      const organization = await createTestOrganization(database.db);
      database.cleanup.trackOrganization(organization.id);
      await new Promise((resolve) => setTimeout(resolve, 5));

      const [updated] = await database.db
        .update(organizations)
        .set({ name: 'Renamed Organization' })
        .where(eq(organizations.id, organization.id))
        .returning();

      expect(updated.updatedAt.getTime()).toBeGreaterThan(
        organization.updatedAt.getTime(),
      );
    });
  });
});
