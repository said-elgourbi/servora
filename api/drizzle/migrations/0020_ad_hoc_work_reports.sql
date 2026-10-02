-- Ad-hoc / unrecorded field work reports.
--
-- A report is not a Visit. It records field work the technician says was performed without a proper
-- Servora Visit and waits for office reconciliation. Reconciliation links it to an existing Job or
-- creates a new Job, then creates a normal completed Visit from the preserved report facts.
CREATE TABLE "ad_hoc_work_reports" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"reporting_technician_membership_id" uuid NOT NULL,
	"customer_id" uuid,
	"property_id" uuid,
	"known_job_id" uuid,
	"work_started_at" timestamp with time zone NOT NULL,
	"work_ended_at" timestamp with time zone NOT NULL,
	"outcome_code" varchar(30) NOT NULL,
	"summary" text NOT NULL,
	"notes" text,
	"status" varchar(20) DEFAULT 'PENDING' NOT NULL,
	"reviewer_membership_id" uuid,
	"reviewed_at" timestamp with time zone,
	"review_note" text,
	"created_job_id" uuid,
	"created_visit_id" uuid,
	"client_operation_id" uuid,
	"version" integer DEFAULT 1 NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "ad_hoc_work_reports_status_check" CHECK ("ad_hoc_work_reports"."status" in ('PENDING', 'LINKED', 'CONVERTED')),
	CONSTRAINT "ad_hoc_work_reports_work_order_check" CHECK ("ad_hoc_work_reports"."work_ended_at" > "ad_hoc_work_reports"."work_started_at"),
	CONSTRAINT "ad_hoc_work_reports_outcome_code_check" CHECK ("ad_hoc_work_reports"."outcome_code" in ('RESOLVED', 'NEEDS_FOLLOW_UP', 'NEEDS_PARTS', 'UNABLE_TO_COMPLETE')),
	CONSTRAINT "ad_hoc_work_reports_summary_check" CHECK (length(btrim("ad_hoc_work_reports"."summary")) > 0),
	CONSTRAINT "ad_hoc_work_reports_review_pair_check" CHECK (("ad_hoc_work_reports"."reviewer_membership_id" is null) = ("ad_hoc_work_reports"."reviewed_at" is null)),
	CONSTRAINT "ad_hoc_work_reports_created_visit_status_check" CHECK ("ad_hoc_work_reports"."created_visit_id" is null or "ad_hoc_work_reports"."status" in ('LINKED', 'CONVERTED')),
	CONSTRAINT "ad_hoc_work_reports_created_job_status_check" CHECK ("ad_hoc_work_reports"."created_job_id" is null or "ad_hoc_work_reports"."status" = 'CONVERTED'),
	CONSTRAINT "ad_hoc_work_reports_version_check" CHECK ("ad_hoc_work_reports"."version" > 0)
);
--> statement-breakpoint
ALTER TABLE "ad_hoc_work_reports" ADD CONSTRAINT "ad_hoc_work_reports_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "ad_hoc_work_reports" ADD CONSTRAINT "ad_hoc_work_reports_reporting_technician_membership_id_organization_members_id_fk" FOREIGN KEY ("reporting_technician_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "ad_hoc_work_reports" ADD CONSTRAINT "ad_hoc_work_reports_customer_id_customers_id_fk" FOREIGN KEY ("customer_id") REFERENCES "public"."customers"("id") ON DELETE no action ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "ad_hoc_work_reports" ADD CONSTRAINT "ad_hoc_work_reports_property_id_properties_id_fk" FOREIGN KEY ("property_id") REFERENCES "public"."properties"("id") ON DELETE no action ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "ad_hoc_work_reports" ADD CONSTRAINT "ad_hoc_work_reports_known_job_id_jobs_id_fk" FOREIGN KEY ("known_job_id") REFERENCES "public"."jobs"("id") ON DELETE set null ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "ad_hoc_work_reports" ADD CONSTRAINT "ad_hoc_work_reports_reviewer_membership_id_organization_members_id_fk" FOREIGN KEY ("reviewer_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "ad_hoc_work_reports" ADD CONSTRAINT "ad_hoc_work_reports_created_job_id_jobs_id_fk" FOREIGN KEY ("created_job_id") REFERENCES "public"."jobs"("id") ON DELETE set null ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "ad_hoc_work_reports" ADD CONSTRAINT "ad_hoc_work_reports_created_visit_id_visits_id_fk" FOREIGN KEY ("created_visit_id") REFERENCES "public"."visits"("id") ON DELETE set null ON UPDATE no action;
--> statement-breakpoint
CREATE INDEX "ad_hoc_work_reports_organization_status_idx" ON "ad_hoc_work_reports" USING btree ("organization_id","status","created_at");
--> statement-breakpoint
CREATE INDEX "ad_hoc_work_reports_reporter_idx" ON "ad_hoc_work_reports" USING btree ("organization_id","reporting_technician_membership_id","created_at");
--> statement-breakpoint
CREATE INDEX "ad_hoc_work_reports_known_job_idx" ON "ad_hoc_work_reports" USING btree ("organization_id","known_job_id");
--> statement-breakpoint
CREATE UNIQUE INDEX "ad_hoc_work_reports_client_operation_unique" ON "ad_hoc_work_reports" USING btree ("organization_id","client_operation_id") WHERE "ad_hoc_work_reports"."client_operation_id" is not null;
--> statement-breakpoint
CREATE UNIQUE INDEX "ad_hoc_work_reports_created_visit_unique" ON "ad_hoc_work_reports" USING btree ("organization_id","created_visit_id") WHERE "ad_hoc_work_reports"."created_visit_id" is not null;
--> statement-breakpoint
INSERT INTO "permissions" ("code", "name_en", "name_fr", "description_en", "description_fr")
VALUES
	('visits.report_ad_hoc_work', 'Report ad-hoc work', 'Signaler du travail non planifie', 'Report field work performed without a recorded Visit.', 'Signaler du travail terrain effectue sans visite enregistree.'),
	('visits.review_ad_hoc_work', 'Review ad-hoc work reports', 'Examiner les rapports de travail non planifie', 'Review and reconcile ad-hoc work reports into Jobs and Visits.', 'Examiner et rapprocher les rapports de travail non planifie en travaux et visites.');
--> statement-breakpoint
INSERT INTO "role_permissions" ("organization_id", "role_id", "permission_id")
SELECT "organization_roles"."organization_id", "organization_roles"."id", "permissions"."id"
FROM "organization_roles"
CROSS JOIN "permissions"
WHERE "organization_roles"."system_code" = 'MANAGER'
	AND "permissions"."code" = 'visits.review_ad_hoc_work';
--> statement-breakpoint
INSERT INTO "role_permissions" ("organization_id", "role_id", "permission_id")
SELECT "organization_roles"."organization_id", "organization_roles"."id", "permissions"."id"
FROM "organization_roles"
CROSS JOIN "permissions"
WHERE "organization_roles"."system_code" = 'TECHNICIAN'
	AND "permissions"."code" = 'visits.report_ad_hoc_work';
