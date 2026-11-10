CREATE TABLE "job_notes" (
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
  "organization_id" uuid NOT NULL,
  "job_id" uuid NOT NULL,
  "author_membership_id" uuid NOT NULL,
  "body" text NOT NULL,
  "recorded_at" timestamp with time zone DEFAULT now() NOT NULL,
  "client_operation_id" uuid,
  "created_at" timestamp with time zone DEFAULT now() NOT NULL,
  CONSTRAINT "job_notes_body_check" CHECK (length(btrim("body")) between 1 and 4000)
);
--> statement-breakpoint
ALTER TABLE "job_notes" ADD CONSTRAINT "job_notes_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "job_notes" ADD CONSTRAINT "job_notes_job_id_jobs_id_fk" FOREIGN KEY ("job_id") REFERENCES "public"."jobs"("id") ON DELETE cascade ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "job_notes" ADD CONSTRAINT "job_notes_author_membership_id_organization_members_id_fk" FOREIGN KEY ("author_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;
--> statement-breakpoint
CREATE INDEX "job_notes_organization_job_recorded_idx" ON "job_notes" USING btree ("organization_id", "job_id", "recorded_at");
--> statement-breakpoint
CREATE UNIQUE INDEX "job_notes_client_operation_unique" ON "job_notes" USING btree ("organization_id", "client_operation_id") WHERE "client_operation_id" is not null;
