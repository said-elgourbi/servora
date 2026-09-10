CREATE TABLE "auth_rate_limit_events" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"scope" varchar(50) NOT NULL,
	"subject_hash" varchar(64) NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "auth_rate_limit_events_scope_check" CHECK ("auth_rate_limit_events"."scope" in ('PASSWORD_RESET_REQUEST', 'SMS_OTP_REQUEST'))
);
--> statement-breakpoint
CREATE TABLE "phone_otp_challenges" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" uuid NOT NULL,
	"code_hash" varchar(255) NOT NULL,
	"attempts" integer DEFAULT 0 NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	"expires_at" timestamp with time zone NOT NULL,
	"consumed_at" timestamp with time zone,
	CONSTRAINT "phone_otp_challenges_attempts_check" CHECK ("phone_otp_challenges"."attempts" >= 0)
);
--> statement-breakpoint
ALTER TABLE "password_reset_tokens" ADD COLUMN "attempts" integer DEFAULT 0 NOT NULL;--> statement-breakpoint
ALTER TABLE "phone_otp_challenges" ADD CONSTRAINT "phone_otp_challenges_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "auth_rate_limit_events_lookup_idx" ON "auth_rate_limit_events" USING btree ("scope","subject_hash","created_at");--> statement-breakpoint
CREATE INDEX "phone_otp_challenges_user_id_idx" ON "phone_otp_challenges" USING btree ("user_id");--> statement-breakpoint
CREATE INDEX "phone_otp_challenges_expires_at_idx" ON "phone_otp_challenges" USING btree ("expires_at");--> statement-breakpoint
CREATE INDEX "phone_otp_challenges_outstanding_idx" ON "phone_otp_challenges" USING btree ("user_id") WHERE "phone_otp_challenges"."consumed_at" is null;--> statement-breakpoint
CREATE INDEX "password_reset_tokens_outstanding_idx" ON "password_reset_tokens" USING btree ("user_id") WHERE "password_reset_tokens"."used_at" is null;--> statement-breakpoint
ALTER TABLE "password_reset_tokens" ADD CONSTRAINT "password_reset_tokens_attempts_check" CHECK ("password_reset_tokens"."attempts" >= 0);