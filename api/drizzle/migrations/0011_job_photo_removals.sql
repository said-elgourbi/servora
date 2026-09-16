CREATE TABLE "job_photo_removals" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"job_photo_id" uuid NOT NULL,
	"actor_membership_id" uuid NOT NULL,
	"reason" text NOT NULL,
	"recorded_at" timestamp with time zone DEFAULT now() NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "job_photo_removals_reason_check" CHECK (length(btrim("job_photo_removals"."reason")) > 0)
);
--> statement-breakpoint
ALTER TABLE "job_photo_removals" ADD CONSTRAINT "job_photo_removals_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_photo_removals" ADD CONSTRAINT "job_photo_removals_job_photo_id_job_photos_id_fk" FOREIGN KEY ("job_photo_id") REFERENCES "public"."job_photos"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_photo_removals" ADD CONSTRAINT "job_photo_removals_actor_membership_id_organization_members_id_fk" FOREIGN KEY ("actor_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
CREATE UNIQUE INDEX "job_photo_removals_photo_unique" ON "job_photo_removals" USING btree ("organization_id","job_photo_id");
--> statement-breakpoint
-- The capability that takes accepted photo evidence out of ordinary use (`BR-089`; tracker 029 Phase
-- 6b, decisions D6a/D6b).
--
-- Evidence is immutable once this API has accepted it (`BR-088`), so the only operation that reaches
-- it is an explicit, audited removal. That is its own capability rather than a reuse of
-- `evidence.photo.add` or `evidence.view`: a technician records and reads evidence on site and must
-- keep doing so, while removing recorded evidence is a Manager decision. The default **Technician**
-- role therefore does **not** receive it — the grant below names the MANAGER system role alone.
--
-- Hand-written, like `0005`-`0010`: this part adds catalogue and grant **rows**, not schema. The
-- snapshot beside it was regenerated from the current schema, so a later migration still diffs
-- against the truth. An applied migration is never modified (`dev.md` §6).
INSERT INTO "permissions" ("code", "name_en", "name_fr", "description_en", "description_fr")
VALUES
	('evidence.photo.remove', 'Remove photo evidence', 'Retirer des preuves photo', 'Remove accepted photo evidence from ordinary views.', 'Retirer des preuves photo acceptees des vues courantes.');
--> statement-breakpoint
INSERT INTO "role_permissions" ("organization_id", "role_id", "permission_id")
SELECT "organization_roles"."organization_id", "organization_roles"."id", "permissions"."id"
FROM "organization_roles"
CROSS JOIN "permissions"
WHERE "organization_roles"."system_code" = 'MANAGER'
	AND "permissions"."code" = 'evidence.photo.remove';
