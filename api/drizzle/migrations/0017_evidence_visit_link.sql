-- Evidence belongs to the Visit it was recorded during (`BR-047`, `BR-071`, `BR-080`).
--
-- Both columns are **nullable**, because rows written before the link existed were recorded on the Job
-- and no rule attributes them to a Visit retroactively: choosing one for them would be inventing
-- business data (`BR-042`, `BR-088`). The API requires a Visit on every write (`job-photo.dto.ts`,
-- `job-audio.dto.ts`), so no new evidence can be stored without one.
--
-- Hand-written rather than generated: the schema snapshot chain has no snapshot for
-- `0016_job_visit_lifecycle_redesign`, so `db:generate` diffed against `0014` and produced a migration
-- that repeated 0015's table and 0016's constraints. `meta/0017_snapshot.json` is generated and
-- re-baselines the chain at this migration.

ALTER TABLE "job_photos" ADD COLUMN "visit_id" uuid;--> statement-breakpoint
ALTER TABLE "job_audio_notes" ADD COLUMN "visit_id" uuid;--> statement-breakpoint
ALTER TABLE "job_photos" ADD CONSTRAINT "job_photos_visit_id_visits_id_fk" FOREIGN KEY ("visit_id") REFERENCES "public"."visits"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_audio_notes" ADD CONSTRAINT "job_audio_notes_visit_id_visits_id_fk" FOREIGN KEY ("visit_id") REFERENCES "public"."visits"("id") ON DELETE cascade ON UPDATE no action;
