-- Follow-up Visit requests.
--
-- A request is not a Visit: it carries a proposed schedule until an authorized reviewer approves it.
-- Approval writes the created Visit id back to this row, giving duplicate approval attempts one record
-- to converge on instead of creating another field attempt.
CREATE TABLE "follow_up_visit_requests" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"organization_id" uuid NOT NULL,
	"job_id" uuid NOT NULL,
	"source_visit_id" uuid,
	"requesting_technician_membership_id" uuid NOT NULL,
	"proposed_start" timestamp with time zone NOT NULL,
	"proposed_end" timestamp with time zone NOT NULL,
	"reason" text NOT NULL,
	"same_technician_preferred" boolean DEFAULT false NOT NULL,
	"status" varchar(30) DEFAULT 'PENDING' NOT NULL,
	"reviewer_membership_id" uuid,
	"reviewed_at" timestamp with time zone,
	"review_note" text,
	"created_visit_id" uuid,
	"version" integer DEFAULT 1 NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "follow_up_visit_requests_status_check" CHECK ("follow_up_visit_requests"."status" in ('PENDING', 'NEEDS_CLARIFICATION', 'APPROVED', 'REJECTED')),
	CONSTRAINT "follow_up_visit_requests_schedule_order_check" CHECK ("follow_up_visit_requests"."proposed_end" > "follow_up_visit_requests"."proposed_start"),
	CONSTRAINT "follow_up_visit_requests_reason_check" CHECK (length(btrim("follow_up_visit_requests"."reason")) > 0),
	CONSTRAINT "follow_up_visit_requests_review_pair_check" CHECK (("follow_up_visit_requests"."reviewer_membership_id" is null) = ("follow_up_visit_requests"."reviewed_at" is null)),
	CONSTRAINT "follow_up_visit_requests_created_visit_status_check" CHECK ("follow_up_visit_requests"."created_visit_id" is null or "follow_up_visit_requests"."status" = 'APPROVED'),
	CONSTRAINT "follow_up_visit_requests_version_check" CHECK ("follow_up_visit_requests"."version" > 0)
);
--> statement-breakpoint
ALTER TABLE "follow_up_visit_requests" ADD CONSTRAINT "follow_up_visit_requests_organization_id_organizations_id_fk" FOREIGN KEY ("organization_id") REFERENCES "public"."organizations"("id") ON DELETE cascade ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "follow_up_visit_requests" ADD CONSTRAINT "follow_up_visit_requests_job_id_jobs_id_fk" FOREIGN KEY ("job_id") REFERENCES "public"."jobs"("id") ON DELETE cascade ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "follow_up_visit_requests" ADD CONSTRAINT "follow_up_visit_requests_source_visit_id_visits_id_fk" FOREIGN KEY ("source_visit_id") REFERENCES "public"."visits"("id") ON DELETE set null ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "follow_up_visit_requests" ADD CONSTRAINT "follow_up_visit_requests_requesting_technician_membership_id_organization_members_id_fk" FOREIGN KEY ("requesting_technician_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "follow_up_visit_requests" ADD CONSTRAINT "follow_up_visit_requests_reviewer_membership_id_organization_members_id_fk" FOREIGN KEY ("reviewer_membership_id") REFERENCES "public"."organization_members"("id") ON DELETE no action ON UPDATE no action;
--> statement-breakpoint
ALTER TABLE "follow_up_visit_requests" ADD CONSTRAINT "follow_up_visit_requests_created_visit_id_visits_id_fk" FOREIGN KEY ("created_visit_id") REFERENCES "public"."visits"("id") ON DELETE set null ON UPDATE no action;
--> statement-breakpoint
CREATE INDEX "follow_up_visit_requests_organization_status_idx" ON "follow_up_visit_requests" USING btree ("organization_id","status","created_at");
--> statement-breakpoint
CREATE INDEX "follow_up_visit_requests_job_idx" ON "follow_up_visit_requests" USING btree ("organization_id","job_id");
--> statement-breakpoint
CREATE INDEX "follow_up_visit_requests_requester_idx" ON "follow_up_visit_requests" USING btree ("organization_id","requesting_technician_membership_id","created_at");
--> statement-breakpoint
CREATE UNIQUE INDEX "follow_up_visit_requests_created_visit_unique" ON "follow_up_visit_requests" USING btree ("organization_id","created_visit_id") WHERE "follow_up_visit_requests"."created_visit_id" is not null;
--> statement-breakpoint
INSERT INTO "permissions" ("code", "name_en", "name_fr", "description_en", "description_fr")
VALUES
	('schedule.view_org', 'View organization schedule', 'Voir l horaire de l organisation', 'View the organization dispatch schedule.', 'Voir l horaire de repartition de l organisation.'),
	('visits.create_schedule', 'Create and schedule visits', 'Creer et planifier des visites', 'Create and schedule Visits directly.', 'Creer et planifier des visites directement.'),
	('visits.update_schedule', 'Update visit schedules', 'Modifier les horaires de visite', 'Schedule or reschedule existing Visits.', 'Planifier ou replanifier des visites existantes.'),
	('visits.assign_technicians', 'Assign visit technicians', 'Assigner des techniciens aux visites', 'Assign technicians to Visits.', 'Assigner des techniciens aux visites.'),
	('visits.request_follow_up', 'Request follow-up visits', 'Demander des visites de suivi', 'Submit follow-up Visit requests.', 'Soumettre des demandes de visite de suivi.'),
	('visits.review_requests', 'Review follow-up visit requests', 'Examiner les demandes de visite de suivi', 'Approve, clarify, or reject follow-up Visit requests.', 'Approuver, clarifier ou refuser les demandes de visite de suivi.');
--> statement-breakpoint
INSERT INTO "role_permissions" ("organization_id", "role_id", "permission_id")
SELECT "organization_roles"."organization_id", "organization_roles"."id", "permissions"."id"
FROM "organization_roles"
CROSS JOIN "permissions"
WHERE "organization_roles"."system_code" = 'MANAGER'
	AND "permissions"."code" IN (
		'schedule.view_org',
		'visits.create_schedule',
		'visits.update_schedule',
		'visits.assign_technicians',
		'visits.review_requests'
	);
--> statement-breakpoint
INSERT INTO "role_permissions" ("organization_id", "role_id", "permission_id")
SELECT "organization_roles"."organization_id", "organization_roles"."id", "permissions"."id"
FROM "organization_roles"
CROSS JOIN "permissions"
WHERE "organization_roles"."system_code" = 'TECHNICIAN'
	AND "permissions"."code" = 'visits.request_follow_up';
