CREATE TABLE "job_customer_history" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"job_id" uuid NOT NULL,
	"previous_customer_id" uuid,
	"new_customer_id" uuid NOT NULL,
	"actor_membership_id" uuid NOT NULL,
	"recorded_at" timestamp with time zone DEFAULT now() NOT NULL,
	"captured_at" timestamp with time zone,
	"client_operation_id" uuid,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "job_property_history" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"job_id" uuid NOT NULL,
	"previous_property_id" uuid,
	"previous_address_snapshot" jsonb,
	"new_property_id" uuid NOT NULL,
	"new_address_snapshot" jsonb NOT NULL,
	"note" text,
	"actor_membership_id" uuid NOT NULL,
	"recorded_at" timestamp with time zone DEFAULT now() NOT NULL,
	"captured_at" timestamp with time zone,
	"client_operation_id" uuid,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "job_property_history_previous_snapshot_check" CHECK (("job_property_history"."previous_property_id" is null) = ("job_property_history"."previous_address_snapshot" is null))
);
--> statement-breakpoint
CREATE TABLE "job_status_history" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"job_id" uuid NOT NULL,
	"from_status" varchar(20),
	"to_status" varchar(20) NOT NULL,
	"reason_code" varchar(30),
	"note" text,
	"actor_membership_id" uuid NOT NULL,
	"recorded_at" timestamp with time zone DEFAULT now() NOT NULL,
	"captured_at" timestamp with time zone,
	"client_operation_id" uuid,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "job_status_history_from_status_check" CHECK ("job_status_history"."from_status" is null or "job_status_history"."from_status" in ('NEW', 'SCHEDULED', 'IN_PROGRESS', 'PENDING_REVIEW', 'COMPLETED', 'CANCELED')),
	CONSTRAINT "job_status_history_to_status_check" CHECK ("job_status_history"."to_status" in ('NEW', 'SCHEDULED', 'IN_PROGRESS', 'PENDING_REVIEW', 'COMPLETED', 'CANCELED')),
	CONSTRAINT "job_status_history_status_changed_check" CHECK ("job_status_history"."from_status" is distinct from "job_status_history"."to_status")
);
--> statement-breakpoint
CREATE TABLE "jobs" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"job_number" integer NOT NULL,
	"customer_id" uuid NOT NULL,
	"property_id" uuid,
	"property_address_snapshot" jsonb,
	"title" varchar(255) NOT NULL,
	"description" text,
	"type_code" varchar(50),
	"status" varchar(20) DEFAULT 'NEW' NOT NULL,
	"owner_membership_id" uuid,
	"final_outcome_code" varchar(30),
	"version" integer DEFAULT 1 NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "jobs_organization_job_number_unique" UNIQUE("organization_id","job_number"),
	CONSTRAINT "jobs_job_number_check" CHECK ("jobs"."job_number" > 0),
	CONSTRAINT "jobs_title_not_blank_check" CHECK (btrim("jobs"."title") <> ''),
	CONSTRAINT "jobs_status_check" CHECK ("jobs"."status" in ('NEW', 'SCHEDULED', 'IN_PROGRESS', 'PENDING_REVIEW', 'COMPLETED', 'CANCELED')),
	CONSTRAINT "jobs_property_snapshot_check" CHECK (("jobs"."property_id" is null) = ("jobs"."property_address_snapshot" is null)),
	CONSTRAINT "jobs_version_check" CHECK ("jobs"."version" > 0)
);
--> statement-breakpoint
CREATE TABLE "organization_job_number_counters" (
	"organization_id" uuid PRIMARY KEY NOT NULL,
	"last_job_number" integer DEFAULT 0 NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "properties" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"name" varchar(255),
	"address_line1" varchar(255) NOT NULL,
	"address_line2" varchar(255),
	"city" varchar(100) NOT NULL,
	"province" varchar(100) NOT NULL,
	"postal_code" varchar(20) NOT NULL,
	"country" varchar(100) DEFAULT 'Canada' NOT NULL,
	"notes" text,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "property_customer_relationships" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"property_id" uuid NOT NULL,
	"customer_id" uuid NOT NULL,
	"started_at" timestamp with time zone DEFAULT now() NOT NULL,
	"ended_at" timestamp with time zone,
	"actor_membership_id" uuid NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "property_customer_relationships_time_check" CHECK ("property_customer_relationships"."ended_at" is null or "property_customer_relationships"."ended_at" >= "property_customer_relationships"."started_at")
);
--> statement-breakpoint
CREATE TABLE "visit_location_history" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"visit_id" uuid NOT NULL,
	"previous_property_id" uuid,
	"previous_address_snapshot" jsonb,
	"new_property_id" uuid NOT NULL,
	"new_address_snapshot" jsonb NOT NULL,
	"job_property_history_id" uuid,
	"note" text,
	"actor_membership_id" uuid NOT NULL,
	"recorded_at" timestamp with time zone DEFAULT now() NOT NULL,
	"captured_at" timestamp with time zone,
	"client_operation_id" uuid,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "visit_location_history_previous_snapshot_check" CHECK (("visit_location_history"."previous_property_id" is null) = ("visit_location_history"."previous_address_snapshot" is null)),
	CONSTRAINT "visit_location_history_property_changed_check" CHECK ("visit_location_history"."previous_property_id" is distinct from "visit_location_history"."new_property_id")
);
--> statement-breakpoint
CREATE TABLE "visit_location_review_flags" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"visit_id" uuid NOT NULL,
	"job_property_history_id" uuid NOT NULL,
	"raised_at" timestamp with time zone DEFAULT now() NOT NULL,
	"resolved_at" timestamp with time zone,
	"resolved_by_membership_id" uuid,
	"resolution_note" text,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "visit_notes" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"visit_id" uuid NOT NULL,
	"author_membership_id" uuid NOT NULL,
	"body" text NOT NULL,
	"recorded_at" timestamp with time zone DEFAULT now() NOT NULL,
	"captured_at" timestamp with time zone,
	"client_operation_id" uuid,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "visit_outcome_history" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"visit_id" uuid NOT NULL,
	"outcome_code" varchar(30) NOT NULL,
	"outcome_summary" text NOT NULL,
	"previous_outcome_code" varchar(30),
	"previous_outcome_summary" text,
	"reason" text,
	"actor_membership_id" uuid NOT NULL,
	"recorded_at" timestamp with time zone DEFAULT now() NOT NULL,
	"captured_at" timestamp with time zone,
	"client_operation_id" uuid,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "visit_outcome_history_outcome_code_check" CHECK ("visit_outcome_history"."outcome_code" in ('RESOLVED', 'NEEDS_PARTS', 'NEEDS_FOLLOWUP', 'NEEDS_QUOTE_APPROVAL', 'UNABLE_TO_COMPLETE')),
	CONSTRAINT "visit_outcome_history_previous_outcome_code_check" CHECK ("visit_outcome_history"."previous_outcome_code" is null or "visit_outcome_history"."previous_outcome_code" in ('RESOLVED', 'NEEDS_PARTS', 'NEEDS_FOLLOWUP', 'NEEDS_QUOTE_APPROVAL', 'UNABLE_TO_COMPLETE')),
	CONSTRAINT "visit_outcome_history_previous_pair_check" CHECK (("visit_outcome_history"."previous_outcome_code" is null) = ("visit_outcome_history"."previous_outcome_summary" is null))
);
--> statement-breakpoint
CREATE TABLE "visit_schedule_history" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"visit_id" uuid NOT NULL,
	"previous_scheduled_start" timestamp with time zone,
	"previous_scheduled_end" timestamp with time zone,
	"new_scheduled_start" timestamp with time zone NOT NULL,
	"new_scheduled_end" timestamp with time zone NOT NULL,
	"previous_arrival_window_start" timestamp with time zone,
	"previous_arrival_window_end" timestamp with time zone,
	"new_arrival_window_start" timestamp with time zone,
	"new_arrival_window_end" timestamp with time zone,
	"reason" text,
	"actor_membership_id" uuid NOT NULL,
	"recorded_at" timestamp with time zone DEFAULT now() NOT NULL,
	"captured_at" timestamp with time zone,
	"client_operation_id" uuid,
	"confirmed_conflicts" jsonb,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "visit_status_history" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"visit_id" uuid NOT NULL,
	"from_status" varchar(20),
	"to_status" varchar(20) NOT NULL,
	"is_correction" boolean DEFAULT false NOT NULL,
	"reason_code" varchar(30),
	"note" text,
	"cancellation_source" varchar(20),
	"job_status_history_id" uuid,
	"actor_membership_id" uuid NOT NULL,
	"recorded_at" timestamp with time zone DEFAULT now() NOT NULL,
	"captured_at" timestamp with time zone,
	"client_operation_id" uuid,
	"confirmed_conflicts" jsonb,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "visit_status_history_from_status_check" CHECK ("visit_status_history"."from_status" is null or "visit_status_history"."from_status" in ('DRAFT', 'SCHEDULED', 'EN_ROUTE', 'ON_SITE', 'IN_PROGRESS', 'COMPLETED', 'CANCELED', 'NO_SHOW')),
	CONSTRAINT "visit_status_history_to_status_check" CHECK ("visit_status_history"."to_status" in ('DRAFT', 'SCHEDULED', 'EN_ROUTE', 'ON_SITE', 'IN_PROGRESS', 'COMPLETED', 'CANCELED', 'NO_SHOW')),
	CONSTRAINT "visit_status_history_status_changed_check" CHECK ("visit_status_history"."from_status" is distinct from "visit_status_history"."to_status"),
	CONSTRAINT "visit_status_history_correction_check" CHECK (not "visit_status_history"."is_correction" or ("visit_status_history"."from_status" = 'EN_ROUTE' and "visit_status_history"."to_status" = 'SCHEDULED')),
	CONSTRAINT "visit_status_history_canceled_source_check" CHECK (("visit_status_history"."to_status" <> 'CANCELED' or "visit_status_history"."cancellation_source" is not null) and ("visit_status_history"."to_status" = 'CANCELED' or "visit_status_history"."cancellation_source" is null)),
	CONSTRAINT "visit_status_history_cancellation_source_check" CHECK ("visit_status_history"."cancellation_source" is null or "visit_status_history"."cancellation_source" in ('MANUAL', 'JOB_CANCELLATION')),
	CONSTRAINT "visit_status_history_job_cancellation_check" CHECK ("visit_status_history"."cancellation_source" is distinct from 'JOB_CANCELLATION' or "visit_status_history"."job_status_history_id" is not null),
	CONSTRAINT "visit_status_history_reason_scope_check" CHECK ("visit_status_history"."reason_code" is null or ("visit_status_history"."to_status" = 'CANCELED' and "visit_status_history"."cancellation_source" = 'MANUAL')),
	CONSTRAINT "visit_status_history_reason_code_check" CHECK ("visit_status_history"."reason_code" is null or "visit_status_history"."reason_code" in ('CUSTOMER_RESCHEDULED', 'CUSTOMER_CANCELED', 'WEATHER', 'TECH_UNAVAILABLE', 'DUPLICATE', 'OTHER'))
);
--> statement-breakpoint
CREATE TABLE "visit_technician_history" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"visit_id" uuid NOT NULL,
	"technician_membership_id" uuid NOT NULL,
	"event" varchar(20) NOT NULL,
	"role_code" varchar(20),
	"previous_role_code" varchar(20),
	"actor_membership_id" uuid NOT NULL,
	"recorded_at" timestamp with time zone DEFAULT now() NOT NULL,
	"captured_at" timestamp with time zone,
	"client_operation_id" uuid,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "visit_technician_history_event_check" CHECK ("visit_technician_history"."event" in ('ASSIGNED', 'REMOVED', 'ROLE_CHANGED')),
	CONSTRAINT "visit_technician_history_assigned_check" CHECK ("visit_technician_history"."event" <> 'ASSIGNED' or ("visit_technician_history"."role_code" is not null and "visit_technician_history"."previous_role_code" is null)),
	CONSTRAINT "visit_technician_history_removed_check" CHECK ("visit_technician_history"."event" <> 'REMOVED' or ("visit_technician_history"."role_code" is null and "visit_technician_history"."previous_role_code" is not null)),
	CONSTRAINT "visit_technician_history_role_changed_check" CHECK ("visit_technician_history"."event" <> 'ROLE_CHANGED' or ("visit_technician_history"."role_code" is not null and "visit_technician_history"."previous_role_code" is not null and "visit_technician_history"."role_code" <> "visit_technician_history"."previous_role_code")),
	CONSTRAINT "visit_technician_history_role_code_check" CHECK ("visit_technician_history"."role_code" is null or "visit_technician_history"."role_code" in ('LEAD', 'TECHNICIAN')),
	CONSTRAINT "visit_technician_history_previous_role_code_check" CHECK ("visit_technician_history"."previous_role_code" is null or "visit_technician_history"."previous_role_code" in ('LEAD', 'TECHNICIAN'))
);
--> statement-breakpoint
CREATE TABLE "visit_technicians" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"visit_id" uuid NOT NULL,
	"technician_membership_id" uuid NOT NULL,
	"role_code" varchar(20) NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "visit_technicians_assignment_unique" UNIQUE("organization_id","visit_id","technician_membership_id"),
	CONSTRAINT "visit_technicians_role_code_check" CHECK ("visit_technicians"."role_code" in ('LEAD', 'TECHNICIAN'))
);
--> statement-breakpoint
CREATE TABLE "visits" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"job_id" uuid NOT NULL,
	"property_id" uuid,
	"location_address_snapshot" jsonb,
	"status" varchar(20) DEFAULT 'DRAFT' NOT NULL,
	"scheduled_start" timestamp with time zone,
	"scheduled_end" timestamp with time zone,
	"arrival_window_start" timestamp with time zone,
	"arrival_window_end" timestamp with time zone,
	"outcome_code" varchar(30),
	"outcome_summary" text,
	"outcome_recorded_at" timestamp with time zone,
	"outcome_recorded_by_membership_id" uuid,
	"version" integer DEFAULT 1 NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "visits_status_check" CHECK ("visits"."status" in ('DRAFT', 'SCHEDULED', 'EN_ROUTE', 'ON_SITE', 'IN_PROGRESS', 'COMPLETED', 'CANCELED', 'NO_SHOW')),
	CONSTRAINT "visits_schedule_pair_check" CHECK (("visits"."scheduled_start" is null) = ("visits"."scheduled_end" is null)),
	CONSTRAINT "visits_schedule_order_check" CHECK ("visits"."scheduled_end" is null or "visits"."scheduled_end" > "visits"."scheduled_start"),
	CONSTRAINT "visits_arrival_window_pair_check" CHECK (("visits"."arrival_window_start" is null) = ("visits"."arrival_window_end" is null)),
	CONSTRAINT "visits_arrival_window_order_check" CHECK ("visits"."arrival_window_end" is null or "visits"."arrival_window_end" > "visits"."arrival_window_start"),
	CONSTRAINT "visits_location_snapshot_check" CHECK (("visits"."property_id" is null) = ("visits"."location_address_snapshot" is null)),
	CONSTRAINT "visits_scheduled_requirements_check" CHECK ("visits"."status" <> 'SCHEDULED' or ("visits"."property_id" is not null and "visits"."scheduled_start" is not null and "visits"."scheduled_end" is not null)),
	CONSTRAINT "visits_outcome_code_check" CHECK ("visits"."outcome_code" is null or "visits"."outcome_code" in ('RESOLVED', 'NEEDS_PARTS', 'NEEDS_FOLLOWUP', 'NEEDS_QUOTE_APPROVAL', 'UNABLE_TO_COMPLETE')),
	CONSTRAINT "visits_outcome_recorded_pair_check" CHECK (("visits"."outcome_code" is null) = ("visits"."outcome_recorded_at" is null)),
	CONSTRAINT "visits_outcome_actor_pair_check" CHECK (("visits"."outcome_recorded_at" is null) = ("visits"."outcome_recorded_by_membership_id" is null)),
	CONSTRAINT "visits_completed_outcome_check" CHECK ("visits"."status" <> 'COMPLETED' or ("visits"."outcome_code" is not null and "visits"."outcome_summary" is not null)),
	CONSTRAINT "visits_version_check" CHECK ("visits"."version" > 0)
);
--> statement-breakpoint
ALTER TABLE "job_customer_history" ADD CONSTRAINT "job_customer_history_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_customer_history" ADD CONSTRAINT "job_customer_history_job_id_jobs_id_fk" FOREIGN KEY ("job_id") REFERENCES "public"."jobs"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_customer_history" ADD CONSTRAINT "job_customer_history_previous_customer_id_customers_id_fk" FOREIGN KEY ("previous_customer_id") REFERENCES "public"."customers"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_customer_history" ADD CONSTRAINT "job_customer_history_new_customer_id_customers_id_fk" FOREIGN KEY ("new_customer_id") REFERENCES "public"."customers"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_customer_history" ADD CONSTRAINT "job_customer_history_actor_membership_id_organization_members_id_fk" FOREIGN KEY ("actor_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_property_history" ADD CONSTRAINT "job_property_history_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_property_history" ADD CONSTRAINT "job_property_history_job_id_jobs_id_fk" FOREIGN KEY ("job_id") REFERENCES "public"."jobs"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_property_history" ADD CONSTRAINT "job_property_history_previous_property_id_properties_id_fk" FOREIGN KEY ("previous_property_id") REFERENCES "public"."properties"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_property_history" ADD CONSTRAINT "job_property_history_new_property_id_properties_id_fk" FOREIGN KEY ("new_property_id") REFERENCES "public"."properties"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_property_history" ADD CONSTRAINT "job_property_history_actor_membership_id_organization_members_id_fk" FOREIGN KEY ("actor_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_status_history" ADD CONSTRAINT "job_status_history_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_status_history" ADD CONSTRAINT "job_status_history_job_id_jobs_id_fk" FOREIGN KEY ("job_id") REFERENCES "public"."jobs"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_status_history" ADD CONSTRAINT "job_status_history_actor_membership_id_organization_members_id_fk" FOREIGN KEY ("actor_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "jobs" ADD CONSTRAINT "jobs_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "jobs" ADD CONSTRAINT "jobs_customer_id_customers_id_fk" FOREIGN KEY ("customer_id") REFERENCES "public"."customers"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "jobs" ADD CONSTRAINT "jobs_property_id_properties_id_fk" FOREIGN KEY ("property_id") REFERENCES "public"."properties"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "jobs" ADD CONSTRAINT "jobs_owner_membership_id_organization_members_id_fk" FOREIGN KEY ("owner_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "organization_job_number_counters" ADD CONSTRAINT "organization_job_number_counters_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "properties" ADD CONSTRAINT "properties_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "property_customer_relationships" ADD CONSTRAINT "property_customer_relationships_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "property_customer_relationships" ADD CONSTRAINT "property_customer_relationships_property_id_properties_id_fk" FOREIGN KEY ("property_id") REFERENCES "public"."properties"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "property_customer_relationships" ADD CONSTRAINT "property_customer_relationships_customer_id_customers_id_fk" FOREIGN KEY ("customer_id") REFERENCES "public"."customers"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "property_customer_relationships" ADD CONSTRAINT "property_customer_relationships_actor_membership_id_organization_members_id_fk" FOREIGN KEY ("actor_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_location_history" ADD CONSTRAINT "visit_location_history_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_location_history" ADD CONSTRAINT "visit_location_history_visit_id_visits_id_fk" FOREIGN KEY ("visit_id") REFERENCES "public"."visits"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_location_history" ADD CONSTRAINT "visit_location_history_previous_property_id_properties_id_fk" FOREIGN KEY ("previous_property_id") REFERENCES "public"."properties"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_location_history" ADD CONSTRAINT "visit_location_history_new_property_id_properties_id_fk" FOREIGN KEY ("new_property_id") REFERENCES "public"."properties"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_location_history" ADD CONSTRAINT "visit_location_history_job_property_history_id_job_property_history_id_fk" FOREIGN KEY ("job_property_history_id") REFERENCES "public"."job_property_history"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_location_history" ADD CONSTRAINT "visit_location_history_actor_membership_id_organization_members_id_fk" FOREIGN KEY ("actor_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_location_review_flags" ADD CONSTRAINT "visit_location_review_flags_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_location_review_flags" ADD CONSTRAINT "visit_location_review_flags_visit_id_visits_id_fk" FOREIGN KEY ("visit_id") REFERENCES "public"."visits"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_location_review_flags" ADD CONSTRAINT "visit_location_review_flags_job_property_history_id_job_property_history_id_fk" FOREIGN KEY ("job_property_history_id") REFERENCES "public"."job_property_history"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_location_review_flags" ADD CONSTRAINT "visit_location_review_flags_resolved_by_membership_id_organization_members_id_fk" FOREIGN KEY ("resolved_by_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_notes" ADD CONSTRAINT "visit_notes_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_notes" ADD CONSTRAINT "visit_notes_visit_id_visits_id_fk" FOREIGN KEY ("visit_id") REFERENCES "public"."visits"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_notes" ADD CONSTRAINT "visit_notes_author_membership_id_organization_members_id_fk" FOREIGN KEY ("author_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_outcome_history" ADD CONSTRAINT "visit_outcome_history_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_outcome_history" ADD CONSTRAINT "visit_outcome_history_visit_id_visits_id_fk" FOREIGN KEY ("visit_id") REFERENCES "public"."visits"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_outcome_history" ADD CONSTRAINT "visit_outcome_history_actor_membership_id_organization_members_id_fk" FOREIGN KEY ("actor_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_schedule_history" ADD CONSTRAINT "visit_schedule_history_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_schedule_history" ADD CONSTRAINT "visit_schedule_history_visit_id_visits_id_fk" FOREIGN KEY ("visit_id") REFERENCES "public"."visits"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_schedule_history" ADD CONSTRAINT "visit_schedule_history_actor_membership_id_organization_members_id_fk" FOREIGN KEY ("actor_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_status_history" ADD CONSTRAINT "visit_status_history_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_status_history" ADD CONSTRAINT "visit_status_history_visit_id_visits_id_fk" FOREIGN KEY ("visit_id") REFERENCES "public"."visits"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_status_history" ADD CONSTRAINT "visit_status_history_job_status_history_id_job_status_history_id_fk" FOREIGN KEY ("job_status_history_id") REFERENCES "public"."job_status_history"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_status_history" ADD CONSTRAINT "visit_status_history_actor_membership_id_organization_members_id_fk" FOREIGN KEY ("actor_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_technician_history" ADD CONSTRAINT "visit_technician_history_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_technician_history" ADD CONSTRAINT "visit_technician_history_visit_id_visits_id_fk" FOREIGN KEY ("visit_id") REFERENCES "public"."visits"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_technician_history" ADD CONSTRAINT "visit_technician_history_technician_membership_id_organization_members_id_fk" FOREIGN KEY ("technician_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_technician_history" ADD CONSTRAINT "visit_technician_history_actor_membership_id_organization_members_id_fk" FOREIGN KEY ("actor_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_technicians" ADD CONSTRAINT "visit_technicians_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_technicians" ADD CONSTRAINT "visit_technicians_visit_id_visits_id_fk" FOREIGN KEY ("visit_id") REFERENCES "public"."visits"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visit_technicians" ADD CONSTRAINT "visit_technicians_technician_membership_id_organization_members_id_fk" FOREIGN KEY ("technician_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visits" ADD CONSTRAINT "visits_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visits" ADD CONSTRAINT "visits_job_id_jobs_id_fk" FOREIGN KEY ("job_id") REFERENCES "public"."jobs"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visits" ADD CONSTRAINT "visits_property_id_properties_id_fk" FOREIGN KEY ("property_id") REFERENCES "public"."properties"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "visits" ADD CONSTRAINT "visits_outcome_recorded_by_membership_id_organization_members_id_fk" FOREIGN KEY ("outcome_recorded_by_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "job_customer_history_organization_job_recorded_idx" ON "job_customer_history" USING btree ("organization_id","job_id","recorded_at");--> statement-breakpoint
CREATE UNIQUE INDEX "job_customer_history_client_operation_unique" ON "job_customer_history" USING btree ("organization_id","client_operation_id") WHERE "job_customer_history"."client_operation_id" is not null;--> statement-breakpoint
CREATE INDEX "job_property_history_organization_job_recorded_idx" ON "job_property_history" USING btree ("organization_id","job_id","recorded_at");--> statement-breakpoint
CREATE UNIQUE INDEX "job_property_history_client_operation_unique" ON "job_property_history" USING btree ("organization_id","client_operation_id") WHERE "job_property_history"."client_operation_id" is not null;--> statement-breakpoint
CREATE INDEX "job_status_history_organization_job_recorded_idx" ON "job_status_history" USING btree ("organization_id","job_id","recorded_at");--> statement-breakpoint
CREATE UNIQUE INDEX "job_status_history_client_operation_unique" ON "job_status_history" USING btree ("organization_id","client_operation_id") WHERE "job_status_history"."client_operation_id" is not null;--> statement-breakpoint
CREATE INDEX "jobs_organization_status_idx" ON "jobs" USING btree ("organization_id","status");--> statement-breakpoint
CREATE INDEX "jobs_organization_customer_idx" ON "jobs" USING btree ("organization_id","customer_id");--> statement-breakpoint
CREATE INDEX "jobs_organization_property_idx" ON "jobs" USING btree ("organization_id","property_id");--> statement-breakpoint
CREATE INDEX "jobs_organization_owner_membership_idx" ON "jobs" USING btree ("organization_id","owner_membership_id");--> statement-breakpoint
CREATE INDEX "properties_organization_id_idx" ON "properties" USING btree ("organization_id");--> statement-breakpoint
CREATE INDEX "properties_organization_postal_code_idx" ON "properties" USING btree ("organization_id","postal_code");--> statement-breakpoint
CREATE UNIQUE INDEX "property_customer_relationships_active_property_unique" ON "property_customer_relationships" USING btree ("organization_id","property_id") WHERE "property_customer_relationships"."ended_at" is null;--> statement-breakpoint
CREATE INDEX "property_customer_relationships_organization_customer_idx" ON "property_customer_relationships" USING btree ("organization_id","customer_id");--> statement-breakpoint
CREATE INDEX "visit_location_history_organization_visit_recorded_idx" ON "visit_location_history" USING btree ("organization_id","visit_id","recorded_at");--> statement-breakpoint
CREATE UNIQUE INDEX "visit_location_history_client_operation_unique" ON "visit_location_history" USING btree ("organization_id","client_operation_id") WHERE "visit_location_history"."client_operation_id" is not null;--> statement-breakpoint
CREATE UNIQUE INDEX "visit_location_review_flags_open_visit_unique" ON "visit_location_review_flags" USING btree ("organization_id","visit_id") WHERE "visit_location_review_flags"."resolved_at" is null;--> statement-breakpoint
CREATE INDEX "visit_notes_organization_visit_recorded_idx" ON "visit_notes" USING btree ("organization_id","visit_id","recorded_at");--> statement-breakpoint
CREATE UNIQUE INDEX "visit_notes_client_operation_unique" ON "visit_notes" USING btree ("organization_id","client_operation_id") WHERE "visit_notes"."client_operation_id" is not null;--> statement-breakpoint
CREATE INDEX "visit_outcome_history_organization_visit_recorded_idx" ON "visit_outcome_history" USING btree ("organization_id","visit_id","recorded_at");--> statement-breakpoint
CREATE UNIQUE INDEX "visit_outcome_history_client_operation_unique" ON "visit_outcome_history" USING btree ("organization_id","client_operation_id") WHERE "visit_outcome_history"."client_operation_id" is not null;--> statement-breakpoint
CREATE INDEX "visit_schedule_history_organization_visit_recorded_idx" ON "visit_schedule_history" USING btree ("organization_id","visit_id","recorded_at");--> statement-breakpoint
CREATE UNIQUE INDEX "visit_schedule_history_client_operation_unique" ON "visit_schedule_history" USING btree ("organization_id","client_operation_id") WHERE "visit_schedule_history"."client_operation_id" is not null;--> statement-breakpoint
CREATE INDEX "visit_status_history_organization_visit_recorded_idx" ON "visit_status_history" USING btree ("organization_id","visit_id","recorded_at");--> statement-breakpoint
CREATE UNIQUE INDEX "visit_status_history_client_operation_unique" ON "visit_status_history" USING btree ("organization_id","client_operation_id") WHERE "visit_status_history"."client_operation_id" is not null;--> statement-breakpoint
CREATE INDEX "visit_technician_history_organization_visit_recorded_idx" ON "visit_technician_history" USING btree ("organization_id","visit_id","recorded_at");--> statement-breakpoint
CREATE UNIQUE INDEX "visit_technician_history_client_operation_unique" ON "visit_technician_history" USING btree ("organization_id","client_operation_id") WHERE "visit_technician_history"."client_operation_id" is not null;--> statement-breakpoint
CREATE UNIQUE INDEX "visit_technicians_lead_unique" ON "visit_technicians" USING btree ("visit_id") WHERE "visit_technicians"."role_code" = 'LEAD';--> statement-breakpoint
CREATE INDEX "visit_technicians_organization_technician_idx" ON "visit_technicians" USING btree ("organization_id","technician_membership_id");--> statement-breakpoint
CREATE INDEX "visits_organization_job_idx" ON "visits" USING btree ("organization_id","job_id");--> statement-breakpoint
CREATE INDEX "visits_organization_status_idx" ON "visits" USING btree ("organization_id","status");--> statement-breakpoint
CREATE INDEX "visits_organization_scheduled_start_idx" ON "visits" USING btree ("organization_id","scheduled_start");