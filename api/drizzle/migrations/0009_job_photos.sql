-- Job photo evidence (`BR-015`, `BR-027`): one photo a field technician attached to a Job, with the
-- field-work phase it was taken in, an optional note, the object-store key of the bytes, the device
-- instant it was captured and the idempotency key the device generated before its first attempt
-- (`BR-031`). The row holds an object **key**, never a URL and never a signed URL (`ADR-013` D6.4).
--
-- Hand-written, like `0005`-`0008`: `meta/` held no snapshot past `0004`, so a generated migration
-- would diff against a stale snapshot and re-create structures that already exist. This slice
-- regenerated `meta/0009_snapshot.json` from the current schema, so a later migration diffs against
-- the truth; this file contains only what this slice adds. Never modify an applied migration.
CREATE TABLE "job_photos" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"job_id" uuid NOT NULL,
	"uploader_membership_id" uuid NOT NULL,
	"phase" varchar(20) NOT NULL,
	"note" text,
	"object_key" varchar(512) NOT NULL,
	"content_type" varchar(100) NOT NULL,
	"byte_size" integer NOT NULL,
	"captured_at" timestamp with time zone,
	"recorded_at" timestamp with time zone DEFAULT now() NOT NULL,
	"client_operation_id" uuid,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "job_photos_phase_check" CHECK ("job_photos"."phase" in ('BEFORE_WORK', 'DURING_WORK', 'AFTER_WORK')),
	CONSTRAINT "job_photos_content_type_check" CHECK ("job_photos"."content_type" in ('image/jpeg', 'image/png', 'image/webp')),
	CONSTRAINT "job_photos_byte_size_check" CHECK ("job_photos"."byte_size" > 0)
);
--> statement-breakpoint
ALTER TABLE "job_photos" ADD CONSTRAINT "job_photos_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_photos" ADD CONSTRAINT "job_photos_job_id_jobs_id_fk" FOREIGN KEY ("job_id") REFERENCES "public"."jobs"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_photos" ADD CONSTRAINT "job_photos_uploader_membership_id_organization_members_id_fk" FOREIGN KEY ("uploader_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "job_photos_organization_job_recorded_idx" ON "job_photos" USING btree ("organization_id","job_id","recorded_at");--> statement-breakpoint
CREATE UNIQUE INDEX "job_photos_client_operation_unique" ON "job_photos" USING btree ("organization_id","client_operation_id") WHERE "job_photos"."client_operation_id" is not null;
