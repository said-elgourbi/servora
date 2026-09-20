-- Customer contact persons (`BR-095`; `ADR-022` D1, D4, D8, D9), generated from
-- `api/src/database/schema.ts` by `npm run db:generate` and extended with the capability rows the
-- rule requires.
--
-- Three things the model could not do before this migration:
--
-- 1. **Hold the invariant.** `is_primary` was a bare flag, so two rows for one customer could be
--    primary or none could. The partial unique index enforces at most one primary per customer in
--    the database rather than in whichever code path remembers it (`BR-001`, `Project.md` §17),
--    following `visit_technicians_lead_unique` (`0003`) and
--    `customer_addresses_default_service_unique` (`0004`). Zero primaries stays legal: contacts are
--    optional, a company that has never recorded one must not be in an invalid state, and nothing
--    promotes a contact to primary on its own (`BR-069`'s position for a removed Lead applied to the
--    same shape of problem).
-- 2. **Remove softly.** `removed_at` plus `removed_by_membership_id` take a contact out of ordinary
--    views while the record survives, so "who removed this person, and when" stays answerable
--    (`BR-033`, `BR-067`), the position `BR-089` already accepted for evidence. The paired check
--    keeps the two columns consistent: either both are set or neither is.
-- 3. **Refuse a stale write.** `version` gives the edit and removal routes the caller's
--    `expectedVersion`, so a mutation naming state the contact has left is refused instead of
--    overwriting it (`BR-032`, `BR-086`; `ADR-022` D9).
--
-- The capability rows are appended below, as `0007`'s are: contact writes are authorized by their
-- own set rather than by `customers.edit` (`BR-085`'s Property position), and `customers.edit` no
-- longer authorizes any contact write (`ADR-022` D4). The default Manager role receives all three;
-- the default Technician role receives none of them, and no default-Technician grant changes
-- (`BR-009`, `BR-092`).
ALTER TABLE "customer_contacts" ADD COLUMN "removed_at" timestamp with time zone;--> statement-breakpoint
ALTER TABLE "customer_contacts" ADD COLUMN "removed_by_membership_id" uuid;--> statement-breakpoint
ALTER TABLE "customer_contacts" ADD COLUMN "version" integer DEFAULT 1 NOT NULL;--> statement-breakpoint
ALTER TABLE "customer_contacts" ADD CONSTRAINT "customer_contacts_removed_by_membership_id_organization_members_id_fk" FOREIGN KEY ("removed_by_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
CREATE UNIQUE INDEX "customer_contacts_primary_unique" ON "customer_contacts" USING btree ("customer_id") WHERE "customer_contacts"."is_primary";--> statement-breakpoint
ALTER TABLE "customer_contacts" ADD CONSTRAINT "customer_contacts_version_check" CHECK ("customer_contacts"."version" > 0);--> statement-breakpoint
ALTER TABLE "customer_contacts" ADD CONSTRAINT "customer_contacts_removal_state_check" CHECK (("customer_contacts"."removed_at" is not null) = ("customer_contacts"."removed_by_membership_id" is not null));--> statement-breakpoint
INSERT INTO "permissions" ("code", "name_en", "name_fr", "description_en", "description_fr")
VALUES
	('customers.contacts.create', 'Create customer contacts', 'Creer des contacts client', 'Add a contact person to a Customer.', 'Ajouter un contact au client.'),
	('customers.contacts.edit', 'Edit customer contacts', 'Modifier des contacts client', 'Edit a Customer''s contact person, its primary flag included.', 'Modifier un contact du client, y compris son indicateur principal.'),
	('customers.contacts.remove', 'Remove customer contacts', 'Retirer des contacts client', 'Remove a Customer''s contact person.', 'Retirer un contact du client.');
--> statement-breakpoint
INSERT INTO "role_permissions" ("organization_id", "role_id", "permission_id")
SELECT "organization_roles"."organization_id", "organization_roles"."id", "permissions"."id"
FROM "organization_roles"
CROSS JOIN "permissions"
WHERE "organization_roles"."system_code" = 'MANAGER'
	AND "permissions"."code" IN (
		'customers.contacts.create',
		'customers.contacts.edit',
		'customers.contacts.remove'
	);
