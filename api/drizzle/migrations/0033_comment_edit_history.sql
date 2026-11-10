CREATE TABLE "job_activity_edits" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"job_id" uuid NOT NULL,
	"activity_kind" varchar(40) NOT NULL,
	"activity_id" uuid NOT NULL,
	"editor_membership_id" uuid NOT NULL,
	"previous_value" jsonb NOT NULL,
	"recorded_at" timestamp with time zone DEFAULT now() NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
ALTER TABLE "job_audio_notes" ADD COLUMN "edited_at" timestamp with time zone;--> statement-breakpoint
ALTER TABLE "job_audio_notes" ADD COLUMN "edited_by_membership_id" uuid;--> statement-breakpoint
ALTER TABLE "job_documents" ADD COLUMN "edited_at" timestamp with time zone;--> statement-breakpoint
ALTER TABLE "job_documents" ADD COLUMN "edited_by_membership_id" uuid;--> statement-breakpoint
ALTER TABLE "job_documents" ADD COLUMN "removed_at" timestamp with time zone;--> statement-breakpoint
ALTER TABLE "job_documents" ADD COLUMN "removed_by_membership_id" uuid;--> statement-breakpoint
ALTER TABLE "job_notes" ADD COLUMN "edited_at" timestamp with time zone;--> statement-breakpoint
ALTER TABLE "job_notes" ADD COLUMN "edited_by_membership_id" uuid;--> statement-breakpoint
ALTER TABLE "job_notes" ADD COLUMN "removed_at" timestamp with time zone;--> statement-breakpoint
ALTER TABLE "job_notes" ADD COLUMN "removed_by_membership_id" uuid;--> statement-breakpoint
ALTER TABLE "job_photos" ADD COLUMN "edited_at" timestamp with time zone;--> statement-breakpoint
ALTER TABLE "job_photos" ADD COLUMN "edited_by_membership_id" uuid;--> statement-breakpoint
ALTER TABLE "job_activity_edits" ADD CONSTRAINT "job_activity_edits_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_activity_edits" ADD CONSTRAINT "job_activity_edits_job_id_jobs_id_fk" FOREIGN KEY ("job_id") REFERENCES "public"."jobs"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_activity_edits" ADD CONSTRAINT "job_activity_edits_editor_membership_id_organization_members_id_fk" FOREIGN KEY ("editor_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "job_activity_edits_target_recorded_idx" ON "job_activity_edits" USING btree ("organization_id","job_id","activity_kind","activity_id","recorded_at");--> statement-breakpoint
ALTER TABLE "job_audio_notes" ADD CONSTRAINT "job_audio_notes_edited_by_membership_id_organization_members_id_fk" FOREIGN KEY ("edited_by_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_documents" ADD CONSTRAINT "job_documents_edited_by_membership_id_organization_members_id_fk" FOREIGN KEY ("edited_by_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_documents" ADD CONSTRAINT "job_documents_removed_by_membership_id_organization_members_id_fk" FOREIGN KEY ("removed_by_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_notes" ADD CONSTRAINT "job_notes_edited_by_membership_id_organization_members_id_fk" FOREIGN KEY ("edited_by_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_notes" ADD CONSTRAINT "job_notes_removed_by_membership_id_organization_members_id_fk" FOREIGN KEY ("removed_by_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_photos" ADD CONSTRAINT "job_photos_edited_by_membership_id_organization_members_id_fk" FOREIGN KEY ("edited_by_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_audio_notes" ADD CONSTRAINT "job_audio_notes_edit_pair_check" CHECK (("job_audio_notes"."edited_at" is null) = ("job_audio_notes"."edited_by_membership_id" is null));--> statement-breakpoint
ALTER TABLE "job_documents" ADD CONSTRAINT "job_documents_edit_pair_check" CHECK (("job_documents"."edited_at" is null) = ("job_documents"."edited_by_membership_id" is null));--> statement-breakpoint
ALTER TABLE "job_documents" ADD CONSTRAINT "job_documents_remove_pair_check" CHECK (("job_documents"."removed_at" is null) = ("job_documents"."removed_by_membership_id" is null));--> statement-breakpoint
ALTER TABLE "job_notes" ADD CONSTRAINT "job_notes_edit_pair_check" CHECK (("job_notes"."edited_at" is null) = ("job_notes"."edited_by_membership_id" is null));--> statement-breakpoint
ALTER TABLE "job_notes" ADD CONSTRAINT "job_notes_remove_pair_check" CHECK (("job_notes"."removed_at" is null) = ("job_notes"."removed_by_membership_id" is null));--> statement-breakpoint
ALTER TABLE "job_photos" ADD CONSTRAINT "job_photos_edit_pair_check" CHECK (("job_photos"."edited_at" is null) = ("job_photos"."edited_by_membership_id" is null));