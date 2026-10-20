ALTER TABLE "organization_members" ADD CONSTRAINT "organization_members_scope_owner_unique" UNIQUE("organization_id", "id", "user_id");
--> statement-breakpoint
ALTER TABLE "auth_sessions" ADD CONSTRAINT "auth_sessions_id_user_unique" UNIQUE("id", "user_id");
--> statement-breakpoint
CREATE TABLE "push_installations" (
  "id" uuid PRIMARY KEY NOT NULL,
  "secret_digest" varchar(64) NOT NULL,
  "organization_id" uuid NOT NULL REFERENCES "organizations"("id") ON DELETE CASCADE,
  "membership_id" uuid NOT NULL,
  "user_id" uuid NOT NULL REFERENCES "users"("id") ON DELETE CASCADE,
  "session_id" uuid NOT NULL,
  "binding_id" uuid DEFAULT gen_random_uuid() NOT NULL,
  "revision" integer DEFAULT 1 NOT NULL,
  "project_id" varchar(30) NOT NULL,
  "application_id" varchar(200) NOT NULL,
  "target_kind" varchar(20) DEFAULT 'FCM_TOKEN' NOT NULL,
  "target_value" text,
  "target_digest" varchar(64),
  "locale" varchar(10) NOT NULL,
  "alerts_enabled" boolean NOT NULL,
  "notification_permission" varchar(20) NOT NULL,
  "channel_enabled" boolean NOT NULL,
  "state" varchar(20) NOT NULL,
  "registered_at" timestamp with time zone DEFAULT now() NOT NULL,
  "created_at" timestamp with time zone DEFAULT now() NOT NULL,
  "updated_at" timestamp with time zone DEFAULT now() NOT NULL,
  CONSTRAINT "push_installations_membership_owner_fk" FOREIGN KEY("organization_id", "membership_id", "user_id") REFERENCES "organization_members"("organization_id", "id", "user_id") ON DELETE CASCADE,
  CONSTRAINT "push_installations_session_owner_fk" FOREIGN KEY("session_id", "user_id") REFERENCES "auth_sessions"("id", "user_id") ON DELETE CASCADE,
  CONSTRAINT "push_installations_revision_check" CHECK ("revision" > 0),
  CONSTRAINT "push_installations_digests_check" CHECK ("secret_digest" ~ '^[0-9a-f]{64}$' AND ("target_digest" IS NULL OR "target_digest" ~ '^[0-9a-f]{64}$')),
  CONSTRAINT "push_installations_target_kind_check" CHECK ("target_kind" = 'FCM_TOKEN'),
  CONSTRAINT "push_installations_locale_check" CHECK ("locale" IN ('en-CA', 'fr-CA')),
  CONSTRAINT "push_installations_permission_check" CHECK ("notification_permission" IN ('GRANTED', 'DENIED', 'NOT_REQUESTED')),
  CONSTRAINT "push_installations_state_check" CHECK ("state" IN ('ACTIVE', 'DISABLED', 'INVALID', 'UNREGISTERED')),
  CONSTRAINT "push_installations_target_check" CHECK (
    ("state" IN ('ACTIVE', 'DISABLED') AND "target_value" IS NOT NULL AND "target_digest" IS NOT NULL AND octet_length("target_value") BETWEEN 1 AND 4096)
    OR ("state" IN ('INVALID', 'UNREGISTERED') AND "target_value" IS NULL AND "target_digest" IS NULL)
  )
);
--> statement-breakpoint
CREATE UNIQUE INDEX "push_installations_target_unique" ON "push_installations" ("project_id", "application_id", "target_kind", "target_digest") WHERE "target_digest" IS NOT NULL;
--> statement-breakpoint
CREATE INDEX "push_installations_session_idx" ON "push_installations" ("session_id");
--> statement-breakpoint
CREATE INDEX "push_installations_member_idx" ON "push_installations" ("organization_id", "membership_id");
--> statement-breakpoint
CREATE INDEX "push_installations_freshness_idx" ON "push_installations" ("registered_at");
