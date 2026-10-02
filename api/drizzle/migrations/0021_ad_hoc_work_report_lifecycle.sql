-- Ad-hoc work reports: add the REJECTED lifecycle status and the unknown-customer/location
-- provenance columns (`BR-AH-005`, `BR-AH-009`; ADR-024 D1, D4).
--
-- `REJECTED` joins `LINKED` and `CONVERTED` as a terminal, one-way status the office records when a
-- report is not legitimate Servora work. The provenance columns hold what the technician reported
-- when they could not identify the canonical Customer or Property; they are facts for reconciliation,
-- never canonical records.
ALTER TABLE "ad_hoc_work_reports" DROP CONSTRAINT "ad_hoc_work_reports_status_check";
--> statement-breakpoint
ALTER TABLE "ad_hoc_work_reports" ADD CONSTRAINT "ad_hoc_work_reports_status_check" CHECK ("ad_hoc_work_reports"."status" in ('PENDING', 'LINKED', 'CONVERTED', 'REJECTED'));
--> statement-breakpoint
ALTER TABLE "ad_hoc_work_reports" ADD COLUMN "reported_customer_name" text;
--> statement-breakpoint
ALTER TABLE "ad_hoc_work_reports" ADD COLUMN "reported_customer_phone" text;
--> statement-breakpoint
ALTER TABLE "ad_hoc_work_reports" ADD COLUMN "reported_customer_address" text;
