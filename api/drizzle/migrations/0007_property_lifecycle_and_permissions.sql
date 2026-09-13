ALTER TABLE "properties" ADD COLUMN "status" varchar(16) DEFAULT 'ACTIVE' NOT NULL;--> statement-breakpoint
ALTER TABLE "properties" ADD COLUMN "archived_at" timestamp with time zone;--> statement-breakpoint
ALTER TABLE "properties" ADD COLUMN "archived_by_membership_id" uuid;--> statement-breakpoint
ALTER TABLE "properties" ADD COLUMN "version" integer DEFAULT 1 NOT NULL;--> statement-breakpoint
CREATE TABLE "property_lifecycle_history" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"property_id" uuid NOT NULL,
	"action" varchar(16) NOT NULL,
	"actor_membership_id" uuid NOT NULL,
	"note" text,
	"recorded_at" timestamp with time zone DEFAULT now() NOT NULL,
	"captured_at" timestamp with time zone,
	"client_operation_id" uuid,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "property_lifecycle_history_action_check" CHECK ("property_lifecycle_history"."action" in ('ARCHIVED', 'RESTORED'))
);
--> statement-breakpoint
ALTER TABLE "properties" ADD CONSTRAINT "properties_archived_by_membership_id_organization_members_id_fk" FOREIGN KEY ("archived_by_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "property_lifecycle_history" ADD CONSTRAINT "property_lifecycle_history_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "property_lifecycle_history" ADD CONSTRAINT "property_lifecycle_history_property_id_properties_id_fk" FOREIGN KEY ("property_id") REFERENCES "public"."properties"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "property_lifecycle_history" ADD CONSTRAINT "property_lifecycle_history_actor_membership_id_organization_members_id_fk" FOREIGN KEY ("actor_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "properties_organization_status_idx" ON "properties" USING btree ("organization_id","status");--> statement-breakpoint
CREATE INDEX "property_lifecycle_history_organization_property_idx" ON "property_lifecycle_history" USING btree ("organization_id","property_id","recorded_at");--> statement-breakpoint
CREATE UNIQUE INDEX "property_lifecycle_history_client_operation_unique" ON "property_lifecycle_history" USING btree ("organization_id","client_operation_id") WHERE "property_lifecycle_history"."client_operation_id" is not null;--> statement-breakpoint
ALTER TABLE "properties" ADD CONSTRAINT "properties_status_check" CHECK ("properties"."status" in ('ACTIVE', 'ARCHIVED'));--> statement-breakpoint
ALTER TABLE "properties" ADD CONSTRAINT "properties_archived_state_check" CHECK (("properties"."status" = 'ARCHIVED') = ("properties"."archived_at" is not null));--> statement-breakpoint
ALTER TABLE "properties" ADD CONSTRAINT "properties_archived_actor_check" CHECK (("properties"."status" = 'ARCHIVED') = ("properties"."archived_by_membership_id" is not null));--> statement-breakpoint
ALTER TABLE "properties" ADD CONSTRAINT "properties_version_check" CHECK ("properties"."version" > 0);
--> statement-breakpoint
INSERT INTO "permissions" ("code", "name_en", "name_fr", "description_en", "description_fr")
VALUES
	('properties.view', 'View properties', 'Voir les proprietes', 'View Properties and resolve a Property where a location is displayed or selected.', 'Voir les proprietes et resoudre une propriete lorsqu un lieu est affiche ou selectionne.'),
	('properties.create', 'Create properties', 'Creer des proprietes', 'Create Properties and relate them to a Customer.', 'Creer des proprietes et les associer a un client.'),
	('properties.edit', 'Edit properties', 'Modifier les proprietes', 'Edit a Property.', 'Modifier les donnees d une propriete.'),
	('properties.archive', 'Archive properties', 'Archiver des proprietes', 'Archive and restore a Property.', 'Archiver et restaurer une propriete.'),
	('properties.delete', 'Delete properties', 'Supprimer des proprietes', 'Permanently delete a Property that has never been referenced.', 'Supprimer definitivement une propriete jamais referencee.');
--> statement-breakpoint
INSERT INTO "role_permissions" ("organization_id", "role_id", "permission_id")
SELECT "organization_roles"."organization_id", "organization_roles"."id", "permissions"."id"
FROM "organization_roles"
CROSS JOIN "permissions"
WHERE "organization_roles"."system_code" = 'MANAGER'
	AND "permissions"."code" IN (
		'properties.view',
		'properties.create',
		'properties.edit',
		'properties.archive',
		'properties.delete'
	);
