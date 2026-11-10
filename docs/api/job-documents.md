# Servora API — Job PDFs

`POST /jobs/:id/documents` accepts a PDF as `multipart/form-data` and requires `JOB_UPDATE`.
The document is stored with authenticated organization and Job scope and read through
`GET /jobs/:id/documents/:documentId/content`.

Use `documentType=ATTACHMENT` for a PDF update attachment. It is append-only, appears in Job Activity
as `JOB_DOCUMENT_ATTACHED`, and does not require a quote workflow. Repeating a request with the same
`clientOperationId` returns the original document. Pass the optional `visitId` when attaching it to a
visit update; it must belong to the same Job, and the activity then appears under that Visit.

Use `documentType=QUOTE` only for the existing quote workflow. Quote replacement keeps its explicit
workflow rules; upload never marks a quote sent or approved.

| Part | Required | Details |
| --- | --- | --- |
| `file` | yes | PDF only, validated from its bytes, at most 20 MiB |
| `documentType` | yes | `ATTACHMENT` or `QUOTE` |
| `displayName` | no | Up to 255 characters |
| `clientOperationId` | no | UUID retained by the client for safe retry |
| `visitId` | no | Same Job's Visit for an `ATTACHMENT`; leave empty for a Job-level update or `QUOTE` |
