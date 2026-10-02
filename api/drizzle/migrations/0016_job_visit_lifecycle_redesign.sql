DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM visits WHERE outcome_code = 'NEEDS_QUOTE_APPROVAL'
    UNION ALL
    SELECT 1 FROM visit_outcome_history WHERE outcome_code = 'NEEDS_QUOTE_APPROVAL'
    UNION ALL
    SELECT 1 FROM visit_outcome_history WHERE previous_outcome_code = 'NEEDS_QUOTE_APPROVAL'
  ) THEN
    RAISE EXCEPTION 'Cannot migrate NEEDS_QUOTE_APPROVAL Visit outcomes: no approved canonical mapping exists.';
  END IF;
END $$;--> statement-breakpoint

ALTER TABLE "jobs" DROP CONSTRAINT "jobs_status_check";--> statement-breakpoint
ALTER TABLE "job_status_history" DROP CONSTRAINT "job_status_history_from_status_check";--> statement-breakpoint
ALTER TABLE "job_status_history" DROP CONSTRAINT "job_status_history_to_status_check";--> statement-breakpoint
ALTER TABLE "job_status_history" DROP CONSTRAINT "job_status_history_status_changed_check";--> statement-breakpoint
ALTER TABLE "visits" DROP CONSTRAINT "visits_status_check";--> statement-breakpoint
ALTER TABLE "visits" DROP CONSTRAINT "visits_outcome_code_check";--> statement-breakpoint
ALTER TABLE "visit_status_history" DROP CONSTRAINT "visit_status_history_from_status_check";--> statement-breakpoint
ALTER TABLE "visit_status_history" DROP CONSTRAINT "visit_status_history_to_status_check";--> statement-breakpoint
ALTER TABLE "visit_outcome_history" DROP CONSTRAINT "visit_outcome_history_outcome_code_check";--> statement-breakpoint
ALTER TABLE "visit_outcome_history" DROP CONSTRAINT "visit_outcome_history_previous_outcome_code_check";--> statement-breakpoint

UPDATE "visits"
SET "outcome_code" = 'NEEDS_FOLLOW_UP'
WHERE "outcome_code" = 'NEEDS_FOLLOWUP';--> statement-breakpoint
UPDATE "visit_outcome_history"
SET "outcome_code" = 'NEEDS_FOLLOW_UP'
WHERE "outcome_code" = 'NEEDS_FOLLOWUP';--> statement-breakpoint
UPDATE "visit_outcome_history"
SET "previous_outcome_code" = 'NEEDS_FOLLOW_UP'
WHERE "previous_outcome_code" = 'NEEDS_FOLLOWUP';--> statement-breakpoint

UPDATE "visits"
SET "status" = 'CANCELED'
WHERE "status" = 'NO_SHOW';--> statement-breakpoint
UPDATE "visit_status_history"
SET
  "from_status" = CASE WHEN "from_status" = 'NO_SHOW' THEN 'CANCELED' ELSE "from_status" END,
  "to_status" = CASE WHEN "to_status" = 'NO_SHOW' THEN 'CANCELED' ELSE "to_status" END,
  "cancellation_source" = CASE WHEN "to_status" = 'NO_SHOW' THEN 'MANUAL' ELSE "cancellation_source" END,
  "reason_code" = CASE WHEN "to_status" = 'NO_SHOW' THEN COALESCE("reason_code", 'OTHER') ELSE "reason_code" END,
  "note" = CASE WHEN "to_status" = 'NO_SHOW' THEN COALESCE("note", 'Migrated from obsolete NO_SHOW visit status.') ELSE "note" END
WHERE "from_status" = 'NO_SHOW' OR "to_status" = 'NO_SHOW';--> statement-breakpoint

UPDATE "jobs"
SET "status" = CASE
  WHEN "status" IN ('SCHEDULED', 'IN_PROGRESS') THEN 'ACTIVE'
  WHEN "status" = 'PENDING_REVIEW'
    AND NOT EXISTS (
      SELECT 1
      FROM "visits"
      WHERE "visits"."organization_id" = "jobs"."organization_id"
        AND "visits"."job_id" = "jobs"."id"
        AND "visits"."status" NOT IN ('COMPLETED', 'CANCELED')
    )
    AND (
      SELECT "latest"."outcome_code"
      FROM "visits" "latest"
      WHERE "latest"."organization_id" = "jobs"."organization_id"
        AND "latest"."job_id" = "jobs"."id"
        AND "latest"."status" = 'COMPLETED'
      ORDER BY "latest"."outcome_recorded_at" DESC NULLS LAST, "latest"."updated_at" DESC
      LIMIT 1
    ) = 'RESOLVED'
    THEN 'COMPLETED'
  WHEN "status" = 'PENDING_REVIEW' THEN 'ACTIVE'
  ELSE "status"
END
WHERE "status" IN ('SCHEDULED', 'IN_PROGRESS', 'PENDING_REVIEW');--> statement-breakpoint

UPDATE "job_status_history"
SET
  "from_status" = CASE
    WHEN "from_status" IN ('SCHEDULED', 'IN_PROGRESS') THEN 'ACTIVE'
    WHEN "from_status" = 'PENDING_REVIEW' THEN 'COMPLETED'
    ELSE "from_status"
  END,
  "to_status" = CASE
    WHEN "to_status" IN ('SCHEDULED', 'IN_PROGRESS') THEN 'ACTIVE'
    WHEN "to_status" = 'PENDING_REVIEW' THEN 'COMPLETED'
    ELSE "to_status"
  END
WHERE "from_status" IN ('SCHEDULED', 'IN_PROGRESS', 'PENDING_REVIEW')
   OR "to_status" IN ('SCHEDULED', 'IN_PROGRESS', 'PENDING_REVIEW');--> statement-breakpoint

UPDATE "job_status_history"
SET "note" = concat_ws(E'\n', "note", 'Lifecycle migration collapsed obsolete Job statuses; original event row preserved.')
WHERE "from_status" = "to_status";--> statement-breakpoint

ALTER TABLE "jobs" ADD CONSTRAINT "jobs_status_check" CHECK ("jobs"."status" in ('NEW', 'ACTIVE', 'COMPLETED', 'CANCELED'));--> statement-breakpoint
ALTER TABLE "job_status_history" ADD CONSTRAINT "job_status_history_from_status_check" CHECK ("job_status_history"."from_status" is null or "job_status_history"."from_status" in ('NEW', 'ACTIVE', 'COMPLETED', 'CANCELED'));--> statement-breakpoint
ALTER TABLE "job_status_history" ADD CONSTRAINT "job_status_history_to_status_check" CHECK ("job_status_history"."to_status" in ('NEW', 'ACTIVE', 'COMPLETED', 'CANCELED'));--> statement-breakpoint
ALTER TABLE "job_status_history" ADD CONSTRAINT "job_status_history_status_changed_check" CHECK ("job_status_history"."from_status" is distinct from "job_status_history"."to_status" or "job_status_history"."note" like '%Lifecycle migration collapsed obsolete Job statuses;%');--> statement-breakpoint
ALTER TABLE "visits" ADD CONSTRAINT "visits_status_check" CHECK ("visits"."status" in ('DRAFT', 'SCHEDULED', 'EN_ROUTE', 'ON_SITE', 'IN_PROGRESS', 'COMPLETED', 'CANCELED'));--> statement-breakpoint
ALTER TABLE "visits" ADD CONSTRAINT "visits_outcome_code_check" CHECK ("visits"."outcome_code" is null or "visits"."outcome_code" in ('RESOLVED', 'NEEDS_FOLLOW_UP', 'NEEDS_PARTS', 'UNABLE_TO_COMPLETE'));--> statement-breakpoint
ALTER TABLE "visit_status_history" ADD CONSTRAINT "visit_status_history_from_status_check" CHECK ("visit_status_history"."from_status" is null or "visit_status_history"."from_status" in ('DRAFT', 'SCHEDULED', 'EN_ROUTE', 'ON_SITE', 'IN_PROGRESS', 'COMPLETED', 'CANCELED'));--> statement-breakpoint
ALTER TABLE "visit_status_history" ADD CONSTRAINT "visit_status_history_to_status_check" CHECK ("visit_status_history"."to_status" in ('DRAFT', 'SCHEDULED', 'EN_ROUTE', 'ON_SITE', 'IN_PROGRESS', 'COMPLETED', 'CANCELED'));--> statement-breakpoint
ALTER TABLE "visit_outcome_history" ADD CONSTRAINT "visit_outcome_history_outcome_code_check" CHECK ("visit_outcome_history"."outcome_code" in ('RESOLVED', 'NEEDS_FOLLOW_UP', 'NEEDS_PARTS', 'UNABLE_TO_COMPLETE'));--> statement-breakpoint
ALTER TABLE "visit_outcome_history" ADD CONSTRAINT "visit_outcome_history_previous_outcome_code_check" CHECK ("visit_outcome_history"."previous_outcome_code" is null or "visit_outcome_history"."previous_outcome_code" in ('RESOLVED', 'NEEDS_FOLLOW_UP', 'NEEDS_PARTS', 'UNABLE_TO_COMPLETE'));--> statement-breakpoint
