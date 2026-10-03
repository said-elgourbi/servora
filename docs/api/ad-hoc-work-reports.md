# Ad-hoc Work Reports API

Ad-hoc work reports let a Technician say: “I was asked to perform work, but it is not in Servora.” A report is not a Visit and does not create a Job silently. It waits for office review.

> This document reflects the API after the office-reconciliation phase (`ADR-024`, `BR-AH-005` –
> `BR-AH-009`). The `REJECTED` status, the unknown-customer provenance fields, the reject route and
> closed-Job historical reconciliation are implemented. Offline/idempotent Android submission is the
> Android client's slice.

## Permissions

- `visits.report_ad_hoc_work`: submit and read the caller's own reports. Granted to default Technician.
- `visits.review_ad_hoc_work`: read all reports and reconcile or reject pending reports. Granted to default Manager.

## Lifecycle

| Status | Meaning |
| ------ | ------- |
| `PENDING` | Submitted and awaiting office review (actionable). |
| `LINKED` | Reconciled into an existing Job and its completed Visit. |
| `CONVERTED` | Reconciled into a new Job and its completed Visit. |
| `REJECTED` | Voided as not legitimate Servora work. |

`LINKED`, `CONVERTED` and `REJECTED` are terminal and one-way (`BR-AH-005`). A terminal report cannot be
reconciled again, and no reopen/resubmission workflow exists in v1.

## Routes

`POST /jobs/ad-hoc-work-reports` creates a pending report.

```json
{
  "customerId": "optional-uuid",
  "propertyId": "optional-uuid",
  "knownJobId": "optional-uuid",
  "workStartedAt": "2026-10-01T13:00:00Z",
  "workEndedAt": "2026-10-01T15:00:00Z",
  "outcomeCode": "RESOLVED",
  "summary": "Replaced the leaking valve.",
  "notes": "Customer called the technician directly.",
  "reportedCustomerName": "optional free text",
  "reportedCustomerPhone": "optional free text",
  "reportedCustomerAddress": "optional free text",
  "clientOperationId": "optional-uuid"
}
```

Submission is idempotent by `clientOperationId`.

When the technician cannot identify the canonical Customer or Property, the report captures the facts as
provenance only — never creating a Customer, Property or Job (`BR-AH-009`). The `reportedCustomerName`,
`reportedCustomerPhone` and `reportedCustomerAddress` columns carry that provenance. When a Customer is
selected it must exist in the organization, be `ACTIVE` and not deleted; a selected Property must be
currently related to that Customer and `ACTIVE`; and a selected related Job must belong to that Customer.
The API enforces this scoping server-side. Customer discovery is authorized type-ahead/search, not a full
list.

`GET /jobs/ad-hoc-work-reports` lists reports. Managers receive organization reports; technicians receive only their own.

`GET /jobs/ad-hoc-work-reports/:reportId` reads one report with the same scoping: a reviewer reads any
organization report, a reporter reads only their own, and any other report is reported as not found.

`POST /jobs/ad-hoc-work-reports/:reportId/link` links a pending report to an existing Job and creates a normal completed Work Visit from the reported work facts. Ad-hoc reports are always `WORK`: they preserve work already performed without a scheduled Visit and are not an assessment workflow.

```json
{
  "jobId": "uuid",
  "note": "Linked to the existing seasonal maintenance job.",
  "expectedStatus": "PENDING",
  "expectedVersion": 1
}
```

`POST /jobs/ad-hoc-work-reports/:reportId/convert` creates a new Job from the report's customer/property and then creates the corresponding normal completed Work Visit.

```json
{
  "title": "Emergency valve replacement",
  "description": "Created from ad-hoc work report.",
  "note": "New work request was needed.",
  "expectedStatus": "PENDING",
  "expectedVersion": 1
}
```

`POST /jobs/ad-hoc-work-reports/:reportId/reject` rejects a pending report as not legitimate Servora work
(`BR-AH-007`). It is guarded by `visits.review_ad_hoc_work` and returns the updated report.

```json
{
  "note": "Not legitimate Servora work.",
  "expectedStatus": "PENDING",
  "expectedVersion": 1
}
```

Rejection is terminal and one-way: it records the reviewer, timestamp and the optional note, and a
`REJECTED` report can never be linked or converted (`BR-AH-005`, `BR-AH-007`). A report that is not
`PENDING`, or whose `expectedStatus`/`expectedVersion` no longer match, is refused with
`AD_HOC_WORK_REPORT_NOT_REVIEWABLE` / `AD_HOC_WORK_REPORT_CONFLICT`.

Reconciliation returns the resulting `JobDetailsDto`. The created Visit has normal Visit status `COMPLETED`,
carries the report's work window as its schedule, records the report outcome, assigns the reporting
technician as Lead, and copies report notes into Visit activity.

## Discovery options

The report form does not type a Customer, Property or Job by id. It discovers them through three scoped
option reads, all guarded by `visits.report_ad_hoc_work` (`BR-AH-009`):

- `GET /jobs/ad-hoc-work-reports/customer-options?q=<name>` — a type-ahead Customer search. The `q` must be
  at least two characters. The scope is decided from the caller's Customer capabilities: `customers.view`
  searches the whole organization's active Customers, `customers.view_assigned` searches only the Customers
  of Jobs the caller's own current crew includes (`BR-092`), and a caller holding neither gets no matches —
  the report's unknown-customer path is then the only way to state who the work was for.
- `GET /jobs/ad-hoc-work-reports/property-options?customerId=<uuid>` — the active Properties of a selected
  Customer (`BR-050`). A Customer the caller is not allowed to reach is reported as `404`, so a Customer id
  cannot be probed.
- `GET /jobs/ad-hoc-work-reports/job-options?customerId=<uuid>` — the Jobs of a selected Customer, the
  optional "related Job" hint. The same `404` scoping applies.

Each option carries a stable identifier plus the human-readable values the form presents. The options are
discoverability only: the submit route re-validates whatever ids the reporter chose, so a client cannot
bypass the server's scoping by submitting a value it was never offered (`BR-001`, `BR-007`).

## Closed-Job historical reconciliation

For a `COMPLETED` or `CANCELED` Job, `link` is the distinct privileged historical-reconciliation operation
(`BR-AH-006`): it inserts the historical Visit without a reopen transition.

The reconciled Visit's canonical outcome then has its normal Job-state consequence (`BR-AH-008`, `BR-062`,
`BR-078`):

- `RESOLVED` closes an open Job (when no other Visit is open), leaves a `COMPLETED` Job `COMPLETED`, and
  moves a `CANCELED` Job to `COMPLETED` — the work actually happened, so the request was not truly canceled.
- `NEEDS_FOLLOW_UP` / `NEEDS_PARTS` / `UNABLE_TO_COMPLETE` leave an open Job `ACTIVE` and move a terminal
  Job to `ACTIVE`, because the outcome means additional work is required.

Reconciliation never produces a Job that is terminal while its latest reconciled Visit records a
follow-up-required outcome.

## Evidence

Report-level evidence is deferred for v1 (`ADR-024`). Photos and audio are Visit-scoped and a report has no
Visit, so evidence is attached to the reconciled Visit after reconciliation rather than to the report.
