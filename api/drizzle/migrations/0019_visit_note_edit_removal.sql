-- Editable and removable Visit notes.
--
-- Notes remain ordinary activity, but mistakes can now be corrected. Edits update the note row while
-- recording who edited it and when; removals are soft deletes so ordinary reads hide the note and audit
-- reads can still show what was removed and why.
ALTER TABLE "visit_notes" ADD COLUMN "edited_at" timestamp with time zone;--> statement-breakpoint
ALTER TABLE "visit_notes" ADD COLUMN "edited_by_membership_id" uuid;--> statement-breakpoint
ALTER TABLE "visit_notes" ADD COLUMN "removed_at" timestamp with time zone;--> statement-breakpoint
ALTER TABLE "visit_notes" ADD COLUMN "removed_by_membership_id" uuid;--> statement-breakpoint
ALTER TABLE "visit_notes" ADD COLUMN "removal_reason" text;--> statement-breakpoint
ALTER TABLE "visit_notes" ADD COLUMN "updated_at" timestamp with time zone DEFAULT now() NOT NULL;--> statement-breakpoint
ALTER TABLE "visit_notes" ADD CONSTRAINT "visit_notes_edited_by_membership_id_organization_members_id_fk" FOREIGN KEY ("edited_by_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_notes" ADD CONSTRAINT "visit_notes_removed_by_membership_id_organization_members_id_fk" FOREIGN KEY ("removed_by_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_notes" ADD CONSTRAINT "visit_notes_edit_pair_check" CHECK (("visit_notes"."edited_at" is null) = ("visit_notes"."edited_by_membership_id" is null));--> statement-breakpoint
ALTER TABLE "visit_notes" ADD CONSTRAINT "visit_notes_removed_pair_check" CHECK (("visit_notes"."removed_at" is null) = ("visit_notes"."removed_by_membership_id" is null) and ("visit_notes"."removed_at" is null) = ("visit_notes"."removal_reason" is null));--> statement-breakpoint
ALTER TABLE "visit_notes" ADD CONSTRAINT "visit_notes_removal_reason_check" CHECK ("visit_notes"."removal_reason" is null or length(btrim("visit_notes"."removal_reason")) > 0);
