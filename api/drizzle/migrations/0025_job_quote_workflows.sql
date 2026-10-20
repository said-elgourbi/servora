-- Persist the current Assessment quote workflow and its append-only transition history.
--
-- Quote handling is a commercial/customer-decision workflow. It stays separate from Job status: an
-- approved quote makes Work scheduling possible, and a rejected quote asks the office to cancel the
-- Job through the normal Job lifecycle instead of canceling it automatically.
CREATE TABLE "job_quote_workflows" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"job_id" uuid NOT NULL,
	"source_visit_id" uuid NOT NULL,
	"state" varchar(30) DEFAULT 'REQUIRED' NOT NULL,
	"sent_at" timestamp with time zone,
	"sent_by_membership_id" uuid,
	"sent_note" text,
	"external_reference" varchar(255),
	"decided_at" timestamp with time zone,
	"decided_by_membership_id" uuid,
	"decision_note" text,
	"version" integer DEFAULT 1 NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "job_quote_workflows_state_check" CHECK ("job_quote_workflows"."state" in ('REQUIRED', 'SENT', 'APPROVED', 'REJECTED')),
	CONSTRAINT "job_quote_workflows_sent_pair_check" CHECK (("job_quote_workflows"."sent_at" is null) = ("job_quote_workflows"."sent_by_membership_id" is null)),
	CONSTRAINT "job_quote_workflows_decision_pair_check" CHECK (("job_quote_workflows"."decided_at" is null) = ("job_quote_workflows"."decided_by_membership_id" is null)),
	CONSTRAINT "job_quote_workflows_required_check" CHECK ("job_quote_workflows"."state" <> 'REQUIRED' or ("job_quote_workflows"."sent_at" is null and "job_quote_workflows"."decided_at" is null)),
	CONSTRAINT "job_quote_workflows_sent_check" CHECK ("job_quote_workflows"."state" <> 'SENT' or ("job_quote_workflows"."sent_at" is not null and "job_quote_workflows"."decided_at" is null)),
	CONSTRAINT "job_quote_workflows_decided_check" CHECK ("job_quote_workflows"."state" not in ('APPROVED', 'REJECTED') or ("job_quote_workflows"."sent_at" is not null and "job_quote_workflows"."decided_at" is not null)),
	CONSTRAINT "job_quote_workflows_sent_note_check" CHECK ("job_quote_workflows"."sent_note" is null or btrim("job_quote_workflows"."sent_note") <> ''),
	CONSTRAINT "job_quote_workflows_external_reference_check" CHECK ("job_quote_workflows"."external_reference" is null or btrim("job_quote_workflows"."external_reference") <> ''),
	CONSTRAINT "job_quote_workflows_decision_note_check" CHECK ("job_quote_workflows"."decision_note" is null or btrim("job_quote_workflows"."decision_note") <> ''),
	CONSTRAINT "job_quote_workflows_version_check" CHECK ("job_quote_workflows"."version" > 0),
	CONSTRAINT "job_quote_workflows_job_unique" UNIQUE("organization_id","job_id")
);
--> statement-breakpoint
ALTER TABLE "job_quote_workflows" ADD CONSTRAINT "job_quote_workflows_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "job_quote_workflows" ADD CONSTRAINT "job_quote_workflows_job_id_jobs_id_fk" FOREIGN KEY ("job_id") REFERENCES "public"."jobs"("id") ON DELETE cascade ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "job_quote_workflows" ADD CONSTRAINT "job_quote_workflows_source_visit_id_visits_id_fk" FOREIGN KEY ("source_visit_id") REFERENCES "public"."visits"("id") ON DELETE no action ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "job_quote_workflows" ADD CONSTRAINT "job_quote_workflows_sent_by_membership_id_organization_members_id_fk" FOREIGN KEY ("sent_by_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "job_quote_workflows" ADD CONSTRAINT "job_quote_workflows_decided_by_membership_id_organization_members_id_fk" FOREIGN KEY ("decided_by_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;
--> statement-breakpoint
CREATE INDEX "job_quote_workflows_organization_state_idx" ON "job_quote_workflows" USING btree ("organization_id","state");
--> statement-breakpoint
CREATE INDEX "job_quote_workflows_source_visit_idx" ON "job_quote_workflows" USING btree ("organization_id","source_visit_id");
--> statement-breakpoint
CREATE TABLE "job_quote_history" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"quote_workflow_id" uuid NOT NULL,
	"job_id" uuid NOT NULL,
	"source_visit_id" uuid NOT NULL,
	"event" varchar(30) NOT NULL,
	"from_state" varchar(30),
	"to_state" varchar(30) NOT NULL,
	"note" text,
	"actor_membership_id" uuid NOT NULL,
	"recorded_at" timestamp with time zone DEFAULT now() NOT NULL,
	"captured_at" timestamp with time zone,
	"client_operation_id" uuid,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "job_quote_history_event_check" CHECK ("job_quote_history"."event" in ('REQUIRED', 'SENT', 'APPROVED', 'REJECTED', 'CORRECTED')),
	CONSTRAINT "job_quote_history_from_state_check" CHECK ("job_quote_history"."from_state" is null or "job_quote_history"."from_state" in ('REQUIRED', 'SENT', 'APPROVED', 'REJECTED')),
	CONSTRAINT "job_quote_history_to_state_check" CHECK ("job_quote_history"."to_state" in ('REQUIRED', 'SENT', 'APPROVED', 'REJECTED')),
	CONSTRAINT "job_quote_history_event_to_state_check" CHECK ("job_quote_history"."event" = 'CORRECTED' or "job_quote_history"."event" = "job_quote_history"."to_state"),
	CONSTRAINT "job_quote_history_note_check" CHECK ("job_quote_history"."note" is null or btrim("job_quote_history"."note") <> '')
);
--> statement-breakpoint
ALTER TABLE "job_quote_history" ADD CONSTRAINT "job_quote_history_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "job_quote_history" ADD CONSTRAINT "job_quote_history_quote_workflow_id_job_quote_workflows_id_fk" FOREIGN KEY ("quote_workflow_id") REFERENCES "public"."job_quote_workflows"("id") ON DELETE cascade ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "job_quote_history" ADD CONSTRAINT "job_quote_history_job_id_jobs_id_fk" FOREIGN KEY ("job_id") REFERENCES "public"."jobs"("id") ON DELETE cascade ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "job_quote_history" ADD CONSTRAINT "job_quote_history_source_visit_id_visits_id_fk" FOREIGN KEY ("source_visit_id") REFERENCES "public"."visits"("id") ON DELETE no action ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "job_quote_history" ADD CONSTRAINT "job_quote_history_actor_membership_id_organization_members_id_fk" FOREIGN KEY ("actor_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;
--> statement-breakpoint
CREATE INDEX "job_quote_history_organization_job_recorded_idx" ON "job_quote_history" USING btree ("organization_id","job_id","recorded_at");
--> statement-breakpoint
CREATE INDEX "job_quote_history_workflow_recorded_idx" ON "job_quote_history" USING btree ("quote_workflow_id","recorded_at");
--> statement-breakpoint
CREATE UNIQUE INDEX "job_quote_history_client_operation_unique" ON "job_quote_history" USING btree ("organization_id","client_operation_id") WHERE "job_quote_history"."client_operation_id" is not null;
