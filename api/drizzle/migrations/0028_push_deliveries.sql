ALTER TABLE "push_installations" ADD COLUMN "eligible_since" timestamp with time zone;
--> statement-breakpoint
CREATE TABLE "push_deliveries" (
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
  "organization_id" uuid NOT NULL,
  "event_id" uuid NOT NULL,
  "installation_id" uuid NOT NULL REFERENCES "push_installations"("id") ON DELETE CASCADE,
  "binding_id" uuid NOT NULL,
  "state" varchar(20) DEFAULT 'PENDING' NOT NULL,
  "attempts" integer DEFAULT 0 NOT NULL,
  "next_attempt_at" timestamp with time zone DEFAULT now() NOT NULL,
  "claim_id" uuid,
  "lease_expires_at" timestamp with time zone,
  "registration_revision" integer,
  "last_failure" varchar(30),
  "terminal_at" timestamp with time zone,
  "created_at" timestamp with time zone DEFAULT now() NOT NULL,
  "updated_at" timestamp with time zone DEFAULT now() NOT NULL,
  CONSTRAINT "push_deliveries_scope_id_unique" UNIQUE("organization_id", "id"),
  CONSTRAINT "push_deliveries_binding_unique" UNIQUE("event_id", "installation_id", "binding_id"),
  CONSTRAINT "push_deliveries_event_scope_fk" FOREIGN KEY("organization_id", "event_id") REFERENCES "push_assignment_events"("organization_id", "id") ON DELETE CASCADE,
  CONSTRAINT "push_deliveries_state_check" CHECK ("state" in ('PENDING', 'CLAIMED', 'ACCEPTED', 'SUPPRESSED', 'EXPIRED', 'FAILED')),
  CONSTRAINT "push_deliveries_attempts_check" CHECK ("attempts" between 0 and 8 and ("state" <> 'PENDING' or "attempts" < 8) and ("state" not in ('CLAIMED', 'ACCEPTED') or "attempts" > 0)),
  CONSTRAINT "push_deliveries_claim_check" CHECK (("state" = 'CLAIMED' and "claim_id" is not null and "lease_expires_at" is not null and "registration_revision" is not null and "registration_revision" > 0) or ("state" <> 'CLAIMED' and "claim_id" is null and "lease_expires_at" is null and "registration_revision" is null)),
  CONSTRAINT "push_deliveries_terminal_check" CHECK (("state" in ('ACCEPTED', 'SUPPRESSED', 'EXPIRED', 'FAILED')) = ("terminal_at" is not null)),
  CONSTRAINT "push_deliveries_failure_check" CHECK ("last_failure" is null or "last_failure" in ('invalid-registration', 'retryable', 'configuration', 'rejected', 'disabled'))
);
--> statement-breakpoint
CREATE INDEX "push_deliveries_pending_idx" ON "push_deliveries" ("next_attempt_at") WHERE "state" = 'PENDING';
--> statement-breakpoint
CREATE INDEX "push_deliveries_lease_idx" ON "push_deliveries" ("lease_expires_at") WHERE "state" = 'CLAIMED';
--> statement-breakpoint
CREATE INDEX "push_deliveries_terminal_idx" ON "push_deliveries" ("terminal_at") WHERE "terminal_at" IS NOT NULL;
