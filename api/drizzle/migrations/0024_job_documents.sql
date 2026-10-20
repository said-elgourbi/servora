-- Store office/commercial documents directly on a Job.
--
-- The first supported document type is a quote PDF for the Assessment quote workflow. Documents are
-- intentionally separate from Visit evidence: they carry no evidence phase and are stored in their
-- own object-key namespace.
CREATE TABLE "job_documents" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"job_id" uuid NOT NULL,
	"document_type" varchar(30) NOT NULL,
	"display_name" varchar(255),
	"document_version" integer DEFAULT 1 NOT NULL,
	"is_current" boolean DEFAULT true NOT NULL,
	"object_key" varchar(512) NOT NULL,
	"content_type" varchar(100) NOT NULL,
	"byte_size" integer NOT NULL,
	"uploaded_by_membership_id" uuid NOT NULL,
	"superseded_at" timestamp with time zone,
	"superseded_by_job_document_id" uuid,
	"recorded_at" timestamp with time zone DEFAULT now() NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "job_documents_type_check" CHECK ("job_documents"."document_type" in ('QUOTE')),
	CONSTRAINT "job_documents_content_type_check" CHECK ("job_documents"."content_type" in ('application/pdf')),
	CONSTRAINT "job_documents_display_name_check" CHECK ("job_documents"."display_name" is null or btrim("job_documents"."display_name") <> ''),
	CONSTRAINT "job_documents_version_check" CHECK ("job_documents"."document_version" > 0),
	CONSTRAINT "job_documents_supersession_check" CHECK (("job_documents"."is_current" = true and "job_documents"."superseded_at" is null and "job_documents"."superseded_by_job_document_id" is null) or ("job_documents"."is_current" = false and "job_documents"."superseded_at" is not null and "job_documents"."superseded_by_job_document_id" is not null)),
	CONSTRAINT "job_documents_byte_size_check" CHECK ("job_documents"."byte_size" > 0)
);
--> statement-breakpoint
ALTER TABLE "job_documents" ADD CONSTRAINT "job_documents_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "job_documents" ADD CONSTRAINT "job_documents_job_id_jobs_id_fk" FOREIGN KEY ("job_id") REFERENCES "public"."jobs"("id") ON DELETE cascade ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "job_documents" ADD CONSTRAINT "job_documents_uploaded_by_membership_id_organization_members_id_fk" FOREIGN KEY ("uploaded_by_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "job_documents" ADD CONSTRAINT "job_documents_superseded_by_job_document_id_job_documents_id_fk" FOREIGN KEY ("superseded_by_job_document_id") REFERENCES "public"."job_documents"("id") ON DELETE no action ON UPDATE no action;
--> statement-breakpoint
CREATE INDEX "job_documents_organization_job_recorded_idx" ON "job_documents" USING btree ("organization_id","job_id","recorded_at");
--> statement-breakpoint
CREATE INDEX "job_documents_organization_job_type_idx" ON "job_documents" USING btree ("organization_id","job_id","document_type");
--> statement-breakpoint
CREATE UNIQUE INDEX "job_documents_current_unique" ON "job_documents" USING btree ("organization_id","job_id","document_type") WHERE "job_documents"."is_current" = true;
--> statement-breakpoint
CREATE UNIQUE INDEX "job_documents_version_unique" ON "job_documents" USING btree ("organization_id","job_id","document_type","document_version");
