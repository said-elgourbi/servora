-- Persist why a technician is attending a Visit: Work or Assessment.
--
-- Existing historical Visits predate Visit Purpose and are deterministically Work Visits. Purpose is
-- stored on the Visit rather than inferred from status or outcome, and the current Visit row enforces
-- that a recorded outcome belongs to the Visit's purpose.
ALTER TABLE "visits" ADD COLUMN "purpose_code" varchar(20) DEFAULT 'WORK' NOT NULL;--> statement-breakpoint

ALTER TABLE "visits" DROP CONSTRAINT "visits_outcome_code_check";--> statement-breakpoint
ALTER TABLE "visit_outcome_history" DROP CONSTRAINT "visit_outcome_history_outcome_code_check";--> statement-breakpoint
ALTER TABLE "visit_outcome_history" DROP CONSTRAINT "visit_outcome_history_previous_outcome_code_check";--> statement-breakpoint

ALTER TABLE "visits" ADD CONSTRAINT "visits_purpose_code_check" CHECK ("visits"."purpose_code" in ('WORK', 'ASSESSMENT'));--> statement-breakpoint
ALTER TABLE "visits" ADD CONSTRAINT "visits_outcome_code_check" CHECK ("visits"."outcome_code" is null or "visits"."outcome_code" in ('RESOLVED', 'NEEDS_FOLLOW_UP', 'NEEDS_PARTS', 'UNABLE_TO_COMPLETE', 'NO_WORK_REQUIRED', 'WORK_RECOMMENDED', 'QUOTE_REQUIRED', 'UNABLE_TO_ASSESS'));--> statement-breakpoint
ALTER TABLE "visits" ADD CONSTRAINT "visits_purpose_outcome_check" CHECK ("visits"."outcome_code" is null or ("visits"."purpose_code" = 'WORK' and "visits"."outcome_code" in ('RESOLVED', 'NEEDS_FOLLOW_UP', 'NEEDS_PARTS', 'UNABLE_TO_COMPLETE')) or ("visits"."purpose_code" = 'ASSESSMENT' and "visits"."outcome_code" in ('NO_WORK_REQUIRED', 'WORK_RECOMMENDED', 'QUOTE_REQUIRED', 'UNABLE_TO_ASSESS')));--> statement-breakpoint
ALTER TABLE "visit_outcome_history" ADD CONSTRAINT "visit_outcome_history_outcome_code_check" CHECK ("visit_outcome_history"."outcome_code" in ('RESOLVED', 'NEEDS_FOLLOW_UP', 'NEEDS_PARTS', 'UNABLE_TO_COMPLETE', 'NO_WORK_REQUIRED', 'WORK_RECOMMENDED', 'QUOTE_REQUIRED', 'UNABLE_TO_ASSESS'));--> statement-breakpoint
ALTER TABLE "visit_outcome_history" ADD CONSTRAINT "visit_outcome_history_previous_outcome_code_check" CHECK ("visit_outcome_history"."previous_outcome_code" is null or "visit_outcome_history"."previous_outcome_code" in ('RESOLVED', 'NEEDS_FOLLOW_UP', 'NEEDS_PARTS', 'UNABLE_TO_COMPLETE', 'NO_WORK_REQUIRED', 'WORK_RECOMMENDED', 'QUOTE_REQUIRED', 'UNABLE_TO_ASSESS'));
