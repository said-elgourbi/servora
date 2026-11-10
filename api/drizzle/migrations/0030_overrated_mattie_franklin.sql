ALTER TABLE "job_documents" DROP CONSTRAINT "job_documents_type_check";--> statement-breakpoint
DROP INDEX "job_documents_current_unique";--> statement-breakpoint
DROP INDEX "job_documents_version_unique";--> statement-breakpoint
ALTER TABLE "job_documents" ADD COLUMN "client_operation_id" uuid;--> statement-breakpoint
CREATE UNIQUE INDEX "job_documents_client_operation_unique" ON "job_documents" USING btree ("organization_id","client_operation_id") WHERE "job_documents"."client_operation_id" is not null;--> statement-breakpoint
CREATE UNIQUE INDEX "job_documents_current_unique" ON "job_documents" USING btree ("organization_id","job_id","document_type") WHERE "job_documents"."is_current" = true and "job_documents"."document_type" = 'QUOTE';--> statement-breakpoint
CREATE UNIQUE INDEX "job_documents_version_unique" ON "job_documents" USING btree ("organization_id","job_id","document_type","document_version") WHERE "job_documents"."document_type" = 'QUOTE';--> statement-breakpoint
ALTER TABLE "job_documents" ADD CONSTRAINT "job_documents_type_check" CHECK ("job_documents"."document_type" in ('QUOTE', 'ATTACHMENT'));