CREATE TABLE "customer_addresses" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"customer_id" uuid NOT NULL,
	"type" varchar(20) NOT NULL,
	"address_line1" varchar(255) NOT NULL,
	"address_line2" varchar(255),
	"city" varchar(100) NOT NULL,
	"province" varchar(100) NOT NULL,
	"postal_code" varchar(20) NOT NULL,
	"country" varchar(100) DEFAULT 'Canada' NOT NULL,
	"is_default" boolean DEFAULT false NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "customer_addresses_type_check" CHECK ("customer_addresses"."type" in ('SERVICE', 'BILLING', 'OTHER'))
);
--> statement-breakpoint
CREATE TABLE "customer_companies" (
	"customer_id" uuid PRIMARY KEY NOT NULL,
	"legal_name" varchar(255) NOT NULL,
	"business_name" varchar(255),
	"tax_number" varchar(100)
);
--> statement-breakpoint
CREATE TABLE "customer_contacts" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"customer_id" uuid NOT NULL,
	"first_name" varchar(100) NOT NULL,
	"last_name" varchar(100) NOT NULL,
	"email" varchar(320),
	"phone" varchar(50),
	"role" varchar(100),
	"is_primary" boolean DEFAULT false NOT NULL,
	"is_billing_contact" boolean DEFAULT false NOT NULL,
	"is_job_contact" boolean DEFAULT false NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "customer_individuals" (
	"customer_id" uuid PRIMARY KEY NOT NULL,
	"first_name" varchar(100) NOT NULL,
	"last_name" varchar(100) NOT NULL,
	"date_of_birth" date
);
--> statement-breakpoint
CREATE TABLE "customers" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"type" varchar(20) NOT NULL,
	"display_name" varchar(255) NOT NULL,
	"email" varchar(320),
	"phone" varchar(50),
	"billing_email" varchar(320),
	"billing_phone" varchar(50),
	"notes" text,
	"status" varchar(20) DEFAULT 'ACTIVE' NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "customers_type_check" CHECK ("customers"."type" in ('INDIVIDUAL', 'COMPANY')),
	CONSTRAINT "customers_status_check" CHECK ("customers"."status" in ('ACTIVE', 'INACTIVE'))
);
--> statement-breakpoint
CREATE TABLE "organization_members" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"user_id" uuid NOT NULL,
	"role" varchar(20) NOT NULL,
	"status" varchar(20) DEFAULT 'ACTIVE' NOT NULL,
	"joined_at" timestamp with time zone DEFAULT now() NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "organization_members_organization_id_user_id_unique" UNIQUE("organization_id","user_id"),
	CONSTRAINT "organization_members_role_check" CHECK ("organization_members"."role" in ('MANAGER', 'TECHNICIAN')),
	CONSTRAINT "organization_members_status_check" CHECK ("organization_members"."status" in ('ACTIVE', 'INACTIVE'))
);
--> statement-breakpoint
CREATE TABLE "organizations" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"name" varchar(200) NOT NULL,
	"email" varchar(320),
	"phone" varchar(50),
	"status" varchar(20) DEFAULT 'ACTIVE' NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "organizations_status_check" CHECK ("organizations"."status" in ('ACTIVE', 'INACTIVE'))
);
--> statement-breakpoint
CREATE TABLE "user_profiles" (
	"user_id" uuid PRIMARY KEY NOT NULL,
	"first_name" varchar(100) NOT NULL,
	"last_name" varchar(100) NOT NULL,
	"display_name" varchar(201),
	"phone" varchar(50),
	"avatar_url" varchar(500),
	"locale" varchar(10) DEFAULT 'en-CA' NOT NULL,
	"timezone" varchar(100) DEFAULT 'America/Toronto' NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "users" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"email" varchar(320) NOT NULL,
	"password_hash" varchar(255) NOT NULL,
	"phone" varchar(50),
	"status" varchar(20) DEFAULT 'ACTIVE' NOT NULL,
	"last_login_at" timestamp with time zone,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "users_email_unique" UNIQUE("email"),
	CONSTRAINT "users_status_check" CHECK ("users"."status" in ('ACTIVE', 'INACTIVE', 'SUSPENDED'))
);
--> statement-breakpoint
ALTER TABLE "customer_addresses" ADD CONSTRAINT "customer_addresses_customer_id_customers_id_fk" FOREIGN KEY ("customer_id") REFERENCES "public"."customers"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "customer_companies" ADD CONSTRAINT "customer_companies_customer_id_customers_id_fk" FOREIGN KEY ("customer_id") REFERENCES "public"."customers"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "customer_contacts" ADD CONSTRAINT "customer_contacts_customer_id_customers_id_fk" FOREIGN KEY ("customer_id") REFERENCES "public"."customers"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "customer_individuals" ADD CONSTRAINT "customer_individuals_customer_id_customers_id_fk" FOREIGN KEY ("customer_id") REFERENCES "public"."customers"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "customers" ADD CONSTRAINT "customers_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "organization_members" ADD CONSTRAINT "organization_members_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "organization_members" ADD CONSTRAINT "organization_members_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "user_profiles" ADD CONSTRAINT "user_profiles_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "customer_addresses_customer_id_idx" ON "customer_addresses" USING btree ("customer_id");--> statement-breakpoint
CREATE INDEX "customer_addresses_type_idx" ON "customer_addresses" USING btree ("type");--> statement-breakpoint
CREATE INDEX "customer_contacts_customer_id_idx" ON "customer_contacts" USING btree ("customer_id");--> statement-breakpoint
CREATE INDEX "customer_contacts_email_idx" ON "customer_contacts" USING btree ("email");--> statement-breakpoint
CREATE INDEX "customers_organization_id_idx" ON "customers" USING btree ("organization_id");--> statement-breakpoint
CREATE INDEX "customers_organization_type_idx" ON "customers" USING btree ("organization_id","type");--> statement-breakpoint
CREATE INDEX "customers_organization_status_idx" ON "customers" USING btree ("organization_id","status");--> statement-breakpoint
CREATE INDEX "customers_organization_display_name_idx" ON "customers" USING btree ("organization_id","display_name");--> statement-breakpoint
CREATE INDEX "customers_organization_email_idx" ON "customers" USING btree ("organization_id","email");--> statement-breakpoint
CREATE INDEX "organization_members_organization_id_idx" ON "organization_members" USING btree ("organization_id");--> statement-breakpoint
CREATE INDEX "organization_members_user_id_idx" ON "organization_members" USING btree ("user_id");--> statement-breakpoint
CREATE INDEX "organization_members_role_idx" ON "organization_members" USING btree ("role");--> statement-breakpoint
CREATE INDEX "organizations_status_idx" ON "organizations" USING btree ("status");--> statement-breakpoint
CREATE INDEX "organizations_email_idx" ON "organizations" USING btree ("email");