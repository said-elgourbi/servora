ALTER TABLE "organization_members" ADD CONSTRAINT "organization_members_scope_id_unique" UNIQUE("organization_id", "id");
--> statement-breakpoint
ALTER TABLE "jobs" ADD CONSTRAINT "jobs_scope_id_unique" UNIQUE("organization_id", "id");
--> statement-breakpoint
ALTER TABLE "visits" ADD CONSTRAINT "visits_scope_job_id_unique" UNIQUE("organization_id", "job_id", "id");
--> statement-breakpoint
CREATE TABLE "push_assignment_events" (
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
  "organization_id" uuid NOT NULL REFERENCES "organizations"("id") ON DELETE CASCADE,
  "job_id" uuid NOT NULL,
  "visit_id" uuid NOT NULL,
  "recipient_membership_id" uuid NOT NULL,
  "actor_user_id" uuid NOT NULL REFERENCES "users"("id") ON DELETE RESTRICT,
  "assignment_version" integer NOT NULL,
  "event_type" varchar(30) DEFAULT 'VISIT_ASSIGNED' NOT NULL,
  "occurred_at" timestamp with time zone DEFAULT now() NOT NULL,
  "expires_at" timestamp with time zone DEFAULT (now() + interval '24 hours') NOT NULL,
  "expanded_at" timestamp with time zone,
  CONSTRAINT "push_assignment_events_scope_id_unique" UNIQUE("organization_id", "id"),
  CONSTRAINT "push_assignment_events_business_key_unique" UNIQUE("organization_id", "visit_id", "assignment_version", "recipient_membership_id", "event_type"),
  CONSTRAINT "push_assignment_events_job_scope_fk" FOREIGN KEY("organization_id", "job_id") REFERENCES "jobs"("organization_id", "id") ON DELETE CASCADE,
  CONSTRAINT "push_assignment_events_visit_job_scope_fk" FOREIGN KEY("organization_id", "job_id", "visit_id") REFERENCES "visits"("organization_id", "job_id", "id") ON DELETE CASCADE,
  CONSTRAINT "push_assignment_events_recipient_scope_fk" FOREIGN KEY("organization_id", "recipient_membership_id") REFERENCES "organization_members"("organization_id", "id") ON DELETE CASCADE,
  CONSTRAINT "push_assignment_events_version_check" CHECK ("assignment_version" > 0),
  CONSTRAINT "push_assignment_events_type_check" CHECK ("event_type" = 'VISIT_ASSIGNED'),
  CONSTRAINT "push_assignment_events_expiry_check" CHECK ("expires_at" > "occurred_at")
);
--> statement-breakpoint
CREATE INDEX "push_assignment_events_unexpanded_idx" ON "push_assignment_events" ("occurred_at") WHERE "expanded_at" IS NULL;
