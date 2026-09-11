CREATE TABLE "organization_member_permissions" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"member_id" uuid NOT NULL,
	"permission_id" uuid NOT NULL,
	"granted_by_membership_id" uuid,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "organization_member_permissions_member_permission_unique" UNIQUE("member_id","permission_id")
);
--> statement-breakpoint
CREATE TABLE "organization_roles" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"system_code" varchar(20),
	"name_en" varchar(150) NOT NULL,
	"name_fr" varchar(150) NOT NULL,
	"description_en" text NOT NULL,
	"description_fr" text NOT NULL,
	"status" varchar(20) DEFAULT 'ACTIVE' NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "organization_roles_system_code_check" CHECK ("organization_roles"."system_code" is null or "organization_roles"."system_code" in ('MANAGER', 'TECHNICIAN')),
	CONSTRAINT "organization_roles_status_check" CHECK ("organization_roles"."status" in ('ACTIVE', 'INACTIVE')),
	CONSTRAINT "organization_roles_name_en_not_blank_check" CHECK (btrim("organization_roles"."name_en") <> ''),
	CONSTRAINT "organization_roles_name_fr_not_blank_check" CHECK (btrim("organization_roles"."name_fr") <> '')
);
--> statement-breakpoint
CREATE TABLE "permissions" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"code" varchar(100) NOT NULL,
	"name_en" varchar(150) NOT NULL,
	"name_fr" varchar(150) NOT NULL,
	"description_en" text NOT NULL,
	"description_fr" text NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "permissions_code_unique" UNIQUE("code"),
	CONSTRAINT "permissions_code_not_blank_check" CHECK (btrim("permissions"."code") <> ''),
	CONSTRAINT "permissions_name_en_not_blank_check" CHECK (btrim("permissions"."name_en") <> ''),
	CONSTRAINT "permissions_name_fr_not_blank_check" CHECK (btrim("permissions"."name_fr") <> '')
);
--> statement-breakpoint
CREATE TABLE "role_permissions" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"role_id" uuid NOT NULL,
	"permission_id" uuid NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "role_permissions_role_permission_unique" UNIQUE("role_id","permission_id")
);
--> statement-breakpoint
ALTER TABLE "organization_members" DROP CONSTRAINT "organization_members_role_check";--> statement-breakpoint
DROP INDEX "organization_members_role_idx";--> statement-breakpoint
ALTER TABLE "customers" ADD COLUMN "preferred_contact_method" varchar(20) DEFAULT 'NONE' NOT NULL;--> statement-breakpoint
ALTER TABLE "customers" ADD COLUMN "language" varchar(10) DEFAULT 'en-CA' NOT NULL;--> statement-breakpoint
ALTER TABLE "customers" ADD COLUMN "deleted_at" timestamp with time zone;--> statement-breakpoint
ALTER TABLE "customers" ADD COLUMN "deleted_by_membership_id" uuid;--> statement-breakpoint
ALTER TABLE "customers" ADD COLUMN "delete_reason" text;--> statement-breakpoint
ALTER TABLE "organization_members" ADD COLUMN "role_id" uuid;--> statement-breakpoint
INSERT INTO "permissions" ("code", "name_en", "name_fr", "description_en", "description_fr")
VALUES
	('CUSTOMER_CREATE', 'Create customers', 'Creer des clients', 'Create customer records.', 'Creer des dossiers client.'),
	('CUSTOMER_VIEW', 'View customers', 'Voir les clients', 'View customer records.', 'Voir les dossiers client.'),
	('CUSTOMER_UPDATE', 'Update customers', 'Modifier les clients', 'Update customer records.', 'Modifier les dossiers client.'),
	('CUSTOMER_DELETE', 'Delete customers', 'Supprimer des clients', 'Soft-delete customer records.', 'Supprimer logiquement des dossiers client.'),
	('CUSTOMER_VIEW_DELETED', 'View deleted customers', 'Voir les clients supprimes', 'Include soft-deleted customers in filtered views.', 'Inclure les clients supprimes dans les vues filtrees.'),
	('TECHNICIAN_CREATE', 'Create technicians', 'Creer des techniciens', 'Create technician users and memberships.', 'Creer des utilisateurs et adhesions technicien.'),
	('TECHNICIAN_VIEW', 'View technicians', 'Voir les techniciens', 'View technician records.', 'Voir les dossiers technicien.'),
	('TECHNICIAN_UPDATE', 'Update technicians', 'Modifier les techniciens', 'Update technician records.', 'Modifier les dossiers technicien.'),
	('TECHNICIAN_DELETE', 'Delete technicians', 'Supprimer des techniciens', 'Delete or deactivate technician access according to domain rules.', 'Supprimer ou desactiver l acces technicien selon les regles du domaine.'),
	('JOB_CREATE', 'Create jobs', 'Creer des travaux', 'Create job records.', 'Creer des dossiers de travail.'),
	('JOB_VIEW', 'View jobs', 'Voir les travaux', 'View job records.', 'Voir les dossiers de travail.'),
	('JOB_UPDATE', 'Update jobs', 'Modifier les travaux', 'Update job records.', 'Modifier les dossiers de travail.'),
	('JOB_DELETE', 'Delete jobs', 'Supprimer des travaux', 'Delete jobs according to domain rules.', 'Supprimer des travaux selon les regles du domaine.'),
	('VISIT_VIEW_ASSIGNED', 'View assigned visits', 'Voir les visites assignees', 'View visits assigned to the technician.', 'Voir les visites assignees au technicien.'),
	('VISIT_UPDATE_ASSIGNED_STATUS', 'Update assigned visit status', 'Modifier le statut des visites assignees', 'Advance the field status of assigned visits.', 'Faire avancer le statut terrain des visites assignees.'),
	('VISIT_ADD_NOTE', 'Add visit notes', 'Ajouter des notes de visite', 'Add notes to assigned visits.', 'Ajouter des notes aux visites assignees.'),
	('VISIT_RECORD_OUTCOME', 'Record visit outcomes', 'Enregistrer les resultats de visite', 'Record the outcome of assigned visits.', 'Enregistrer le resultat des visites assignees.');--> statement-breakpoint
INSERT INTO "organization_roles" ("organization_id", "system_code", "name_en", "name_fr", "description_en", "description_fr")
SELECT "id", 'MANAGER', 'Manager', 'Gestionnaire', 'Default management role with core CRUD permissions.', 'Role de gestion par defaut avec les permissions CRUD principales.'
FROM "organizations";--> statement-breakpoint
INSERT INTO "organization_roles" ("organization_id", "system_code", "name_en", "name_fr", "description_en", "description_fr")
SELECT "id", 'TECHNICIAN', 'Technician', 'Technicien', 'Default field role for assigned visit work.', 'Role terrain par defaut pour les visites assignees.'
FROM "organizations";--> statement-breakpoint
INSERT INTO "role_permissions" ("organization_id", "role_id", "permission_id")
SELECT "organization_roles"."organization_id", "organization_roles"."id", "permissions"."id"
FROM "organization_roles"
CROSS JOIN "permissions"
WHERE "organization_roles"."system_code" = 'MANAGER'
	AND "permissions"."code" IN (
		'CUSTOMER_CREATE',
		'CUSTOMER_VIEW',
		'CUSTOMER_UPDATE',
		'CUSTOMER_DELETE',
		'CUSTOMER_VIEW_DELETED',
		'TECHNICIAN_CREATE',
		'TECHNICIAN_VIEW',
		'TECHNICIAN_UPDATE',
		'TECHNICIAN_DELETE',
		'JOB_CREATE',
		'JOB_VIEW',
		'JOB_UPDATE',
		'JOB_DELETE',
		'VISIT_VIEW_ASSIGNED',
		'VISIT_UPDATE_ASSIGNED_STATUS',
		'VISIT_ADD_NOTE',
		'VISIT_RECORD_OUTCOME'
	);--> statement-breakpoint
INSERT INTO "role_permissions" ("organization_id", "role_id", "permission_id")
SELECT "organization_roles"."organization_id", "organization_roles"."id", "permissions"."id"
FROM "organization_roles"
CROSS JOIN "permissions"
WHERE "organization_roles"."system_code" = 'TECHNICIAN'
	AND "permissions"."code" IN (
		'VISIT_VIEW_ASSIGNED',
		'VISIT_UPDATE_ASSIGNED_STATUS',
		'VISIT_ADD_NOTE',
		'VISIT_RECORD_OUTCOME'
	);--> statement-breakpoint
UPDATE "organization_members"
SET "role_id" = "organization_roles"."id"
FROM "organization_roles"
WHERE "organization_roles"."organization_id" = "organization_members"."organization_id"
	AND "organization_roles"."system_code" = "organization_members"."role";--> statement-breakpoint
ALTER TABLE "organization_members" ALTER COLUMN "role_id" SET NOT NULL;--> statement-breakpoint
ALTER TABLE "organization_member_permissions" ADD CONSTRAINT "organization_member_permissions_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "organization_member_permissions" ADD CONSTRAINT "organization_member_permissions_member_id_organization_members_id_fk" FOREIGN KEY ("member_id") REFERENCES "public"."organization_members"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "organization_member_permissions" ADD CONSTRAINT "organization_member_permissions_permission_id_permissions_id_fk" FOREIGN KEY ("permission_id") REFERENCES "public"."permissions"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "organization_member_permissions" ADD CONSTRAINT "organization_member_permissions_granted_by_membership_id_organization_members_id_fk" FOREIGN KEY ("granted_by_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "organization_roles" ADD CONSTRAINT "organization_roles_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "role_permissions" ADD CONSTRAINT "role_permissions_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "role_permissions" ADD CONSTRAINT "role_permissions_role_id_organization_roles_id_fk" FOREIGN KEY ("role_id") REFERENCES "public"."organization_roles"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "role_permissions" ADD CONSTRAINT "role_permissions_permission_id_permissions_id_fk" FOREIGN KEY ("permission_id") REFERENCES "public"."permissions"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "organization_member_permissions_organization_member_idx" ON "organization_member_permissions" USING btree ("organization_id","member_id");--> statement-breakpoint
CREATE INDEX "organization_member_permissions_permission_id_idx" ON "organization_member_permissions" USING btree ("permission_id");--> statement-breakpoint
CREATE UNIQUE INDEX "organization_roles_system_code_unique" ON "organization_roles" USING btree ("organization_id","system_code") WHERE "organization_roles"."system_code" is not null;--> statement-breakpoint
CREATE INDEX "organization_roles_organization_id_idx" ON "organization_roles" USING btree ("organization_id");--> statement-breakpoint
CREATE INDEX "role_permissions_organization_role_idx" ON "role_permissions" USING btree ("organization_id","role_id");--> statement-breakpoint
CREATE INDEX "role_permissions_permission_id_idx" ON "role_permissions" USING btree ("permission_id");--> statement-breakpoint
ALTER TABLE "customers" ADD CONSTRAINT "customers_deleted_by_membership_id_organization_members_id_fk" FOREIGN KEY ("deleted_by_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "organization_members" ADD CONSTRAINT "organization_members_role_id_organization_roles_id_fk" FOREIGN KEY ("role_id") REFERENCES "public"."organization_roles"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
CREATE UNIQUE INDEX "customer_addresses_default_service_unique" ON "customer_addresses" USING btree ("customer_id") WHERE "customer_addresses"."type" = 'SERVICE' and "customer_addresses"."is_default" = true;--> statement-breakpoint
CREATE UNIQUE INDEX "customer_addresses_default_billing_unique" ON "customer_addresses" USING btree ("customer_id") WHERE "customer_addresses"."type" = 'BILLING' and "customer_addresses"."is_default" = true;--> statement-breakpoint
CREATE INDEX "customers_organization_not_deleted_idx" ON "customers" USING btree ("organization_id") WHERE "customers"."deleted_at" is null;--> statement-breakpoint
CREATE INDEX "customers_organization_deleted_idx" ON "customers" USING btree ("organization_id","deleted_at");--> statement-breakpoint
CREATE INDEX "organization_members_role_id_idx" ON "organization_members" USING btree ("role_id");--> statement-breakpoint
ALTER TABLE "organization_members" DROP COLUMN "role";--> statement-breakpoint
ALTER TABLE "customers" ADD CONSTRAINT "customers_preferred_contact_method_check" CHECK ("customers"."preferred_contact_method" in ('EMAIL', 'PHONE', 'SMS', 'NONE'));--> statement-breakpoint
ALTER TABLE "customers" ADD CONSTRAINT "customers_language_check" CHECK ("customers"."language" in ('en-CA', 'fr-CA'));--> statement-breakpoint
ALTER TABLE "customers" ADD CONSTRAINT "customers_deleted_actor_check" CHECK (("customers"."deleted_at" is null) = ("customers"."deleted_by_membership_id" is null));
