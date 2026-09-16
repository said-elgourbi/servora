CREATE TABLE "job_audio_note_removals" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"job_audio_note_id" uuid NOT NULL,
	"actor_membership_id" uuid NOT NULL,
	"reason" text NOT NULL,
	"recorded_at" timestamp with time zone DEFAULT now() NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "job_audio_note_removals_reason_check" CHECK (length(btrim("job_audio_note_removals"."reason")) > 0)
);
--> statement-breakpoint
CREATE TABLE "job_audio_notes" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"job_id" uuid NOT NULL,
	"uploader_membership_id" uuid NOT NULL,
	"phase" varchar(20) NOT NULL,
	"note" text,
	"object_key" varchar(512) NOT NULL,
	"content_type" varchar(100) NOT NULL,
	"byte_size" integer NOT NULL,
	"duration_seconds" integer NOT NULL,
	"captured_at" timestamp with time zone,
	"recorded_at" timestamp with time zone DEFAULT now() NOT NULL,
	"client_operation_id" uuid,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "job_audio_notes_phase_check" CHECK ("job_audio_notes"."phase" in ('BEFORE_WORK', 'DURING_WORK', 'AFTER_WORK')),
	CONSTRAINT "job_audio_notes_content_type_check" CHECK ("job_audio_notes"."content_type" in ('audio/mp4')),
	CONSTRAINT "job_audio_notes_byte_size_check" CHECK ("job_audio_notes"."byte_size" > 0),
	CONSTRAINT "job_audio_notes_duration_check" CHECK ("job_audio_notes"."duration_seconds" > 0)
);
--> statement-breakpoint
ALTER TABLE "job_audio_note_removals" ADD CONSTRAINT "job_audio_note_removals_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_audio_note_removals" ADD CONSTRAINT "job_audio_note_removals_job_audio_note_id_job_audio_notes_id_fk" FOREIGN KEY ("job_audio_note_id") REFERENCES "public"."job_audio_notes"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_audio_note_removals" ADD CONSTRAINT "job_audio_note_removals_actor_membership_id_organization_members_id_fk" FOREIGN KEY ("actor_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_audio_notes" ADD CONSTRAINT "job_audio_notes_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_audio_notes" ADD CONSTRAINT "job_audio_notes_job_id_jobs_id_fk" FOREIGN KEY ("job_id") REFERENCES "public"."jobs"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_audio_notes" ADD CONSTRAINT "job_audio_notes_uploader_membership_id_organization_members_id_fk" FOREIGN KEY ("uploader_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
CREATE UNIQUE INDEX "job_audio_note_removals_note_unique" ON "job_audio_note_removals" USING btree ("organization_id","job_audio_note_id");--> statement-breakpoint
CREATE INDEX "job_audio_notes_organization_job_recorded_idx" ON "job_audio_notes" USING btree ("organization_id","job_id","recorded_at");--> statement-breakpoint
CREATE UNIQUE INDEX "job_audio_notes_client_operation_unique" ON "job_audio_notes" USING btree ("organization_id","client_operation_id") WHERE "job_audio_notes"."client_operation_id" is not null;--> statement-breakpoint
-- The audio evidence capability set (`BR-091`, `BR-089`; tracker 035, decisions `ADR-018` A7).
--
-- Audio is evidence of its own kind, so it is authorized by capabilities of its own, in the same
-- per-kind shape `ADR-015` D2 defined for photos: `evidence.audio.add` is the code that ADR reserved
-- and deliberately did not create until a product rule accepted a recording, which the audio slice now
-- does. `evidence.audio.remove` is separate from `evidence.photo.remove` because the catalogue is per
-- kind — a company may let a member remove one kind of evidence and not the other — and because the
-- bilingual name a member sees should say which kind it governs (`BR-005`, `BR-006`).
--
-- Hand-written, like `0010`/`0011`: this part adds catalogue and grant **rows**, not schema, so the
-- snapshot beside it is unaffected. An applied migration is never modified (`dev.md` §6).
INSERT INTO "permissions" ("code", "name_en", "name_fr", "description_en", "description_fr")
VALUES
	('evidence.audio.add', 'Add audio evidence', 'Ajouter des preuves audio', 'Record an audio note on a Job as field evidence.', 'Enregistrer une note audio sur un travail comme preuve terrain.'),
	('evidence.audio.remove', 'Remove audio evidence', 'Retirer des preuves audio', 'Remove accepted audio evidence from ordinary views.', 'Retirer des preuves audio acceptees des vues courantes.');
--> statement-breakpoint
INSERT INTO "role_permissions" ("organization_id", "role_id", "permission_id")
SELECT "organization_roles"."organization_id", "organization_roles"."id", "permissions"."id"
FROM "organization_roles"
CROSS JOIN "permissions"
WHERE "organization_roles"."system_code" = 'MANAGER'
	AND "permissions"."code" IN ('evidence.audio.add', 'evidence.audio.remove');
--> statement-breakpoint
INSERT INTO "role_permissions" ("organization_id", "role_id", "permission_id")
SELECT "organization_roles"."organization_id", "organization_roles"."id", "permissions"."id"
FROM "organization_roles"
CROSS JOIN "permissions"
WHERE "organization_roles"."system_code" = 'TECHNICIAN'
	AND "permissions"."code" = 'evidence.audio.add';
