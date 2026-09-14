-- Customer lifecycle history (`BR-033`, `BR-087`): the append-only record of a Customer's
-- lifecycle events. Today the only event is a type conversion, recorded with the previous and the
-- new type, the member who performed it and the database timestamp. The previous subtype's own
-- values are deliberately not stored.
--
-- Hand-written, like `0005`-`0007`: `meta/` holds no snapshot past `0004`, so a generated migration
-- would diff against a stale snapshot and try to re-create structures that already exist. Never
-- modify an applied migration.
CREATE TABLE "customer_lifecycle_history" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"customer_id" uuid NOT NULL,
	"action" varchar(32) NOT NULL,
	"from_type" varchar(20) NOT NULL,
	"to_type" varchar(20) NOT NULL,
	"actor_membership_id" uuid NOT NULL,
	"recorded_at" timestamp with time zone DEFAULT now() NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "customer_lifecycle_history_action_check" CHECK ("customer_lifecycle_history"."action" in ('TYPE_CONVERTED')),
	CONSTRAINT "customer_lifecycle_history_types_check" CHECK ("customer_lifecycle_history"."from_type" in ('INDIVIDUAL', 'COMPANY') and "customer_lifecycle_history"."to_type" in ('INDIVIDUAL', 'COMPANY') and "customer_lifecycle_history"."from_type" <> "customer_lifecycle_history"."to_type")
);
--> statement-breakpoint
ALTER TABLE "customer_lifecycle_history" ADD CONSTRAINT "customer_lifecycle_history_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "customer_lifecycle_history" ADD CONSTRAINT "customer_lifecycle_history_customer_id_customers_id_fk" FOREIGN KEY ("customer_id") REFERENCES "public"."customers"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "customer_lifecycle_history" ADD CONSTRAINT "customer_lifecycle_history_actor_membership_id_organization_members_id_fk" FOREIGN KEY ("actor_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "customer_lifecycle_history_organization_customer_idx" ON "customer_lifecycle_history" USING btree ("organization_id","customer_id","recorded_at");
