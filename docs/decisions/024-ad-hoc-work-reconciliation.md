# ADR-024 — Ad-hoc work reports: lifecycle, completed-Job reconciliation and the office decision record

**Status:** Accepted (product-owner decisions, 2026-10-01 — given as the task that produced this record,
under the `BR-040` change procedure)

Date: 2026-10-01

Tracker: `docs/tracker/059-technician-ad-hoc-work-reporting.md` — Phase 1 (this ADR finalises the decision
record; §5/§5a are the open decisions it answers)

Rules produced: `BR-AH-005` – `BR-AH-009` in `.clinerules/Business Rules.md` §23 Ad-hoc / Unrecorded Field
Work

Contracts: `docs/api/ad-hoc-work-reports.md`, `docs/domain/job-visit-domain-model.md` §22

Predecessors: `docs/decisions/023-follow-up-clarification-conversation.md` (the office-review request
lifecycle this record's `REJECTED` mirrors), `docs/decisions/020-manager-schedule-and-unassigned-lane.md`
(the office review lane), `docs/decisions/015-evidence-capabilities.md` and
`docs/tracker/056-evidence-belongs-to-a-visit.md` (why report evidence is deferred)

References: `BR-001`, `BR-006`, `BR-007`, `BR-009`, `BR-012`, `BR-013`, `BR-014`, `BR-028`, `BR-031`,
`BR-032`, `BR-033`, `BR-040`, `BR-041`, `BR-042`, `BR-047`, `BR-050`, `BR-051`, `BR-058`, `BR-060`,
`BR-062`, `BR-063`, `BR-066`, `BR-067`, `BR-071`, `BR-078`, `BR-080`, `BR-092`, `BR-094`, `BR-AH-001`
through `BR-AH-009`; `Project.md` §9, §10, §13, §14; `dev.md` §6, §7, §14.

## Context

Tracker 059 Phase 0 traced an existing WIP ad-hoc work slice: `ad_hoc_work_reports` with statuses
`PENDING`/`LINKED`/`CONVERTED`, `submit`/`list`/`link`/`convert` routes, the `visits.report_ad_hoc_work`
and `visits.review_ad_hoc_work` capabilities, and a prototype Android dialog. It also recorded seven open
product decisions (AH-D1–AH-D7) that had to be answered before the slice could be reshaped into the product
behaviour the request described.

Product ownership answered AH-D1–AH-D7 on 2026-10-01. This record fixes those answers and the invariants
that go with them.

## Decisions

### D1 — Report lifecycle (`BR-AH-005`)

`PENDING` → `LINKED` | `CONVERTED` | `REJECTED`. `PENDING` is the actionable state; the other three are
terminal and one-way. A terminal report cannot be reconciled again, and no reopen/resubmission workflow
exists in v1. The original report and its reconciliation/rejection metadata are retained for audit.

### D2 — Completed-Job reconciliation is a privileged, audited exception (`BR-AH-006`)

Historical reconciliation is a distinct privileged domain operation. A Manager may reconcile a report into
an existing `COMPLETED` or `CANCELED` Job by inserting the historical Visit without first performing the
ordinary reopen transition — the Visit already happened, so manufacturing `COMPLETED → NEW → COMPLETED`
would invent lifecycle events that did not occur.

The exception is narrow and for historical reconciliation only. It does not weaken the invariant that
ordinary Visit creation and scheduling against a closed Job remain prohibited (`BR-062`, `BR-066`). The
operation is server-enforced, explicitly audited, and the resulting Visit retains provenance back to the
report. The resulting Job state is decided by the reconciled Visit's outcome (D3).

### D3 — The reconciled Visit's outcome has its normal consequence (`BR-AH-008`)

The reconciled Visit is canonical historical work, so its outcome participates in the same Job-level
semantics as an equivalent normally completed Visit — no ad-hoc-specific consequence table.

- Open Job (`NEW`/`ACTIVE`): the existing `BR-062`/`BR-078` rule applies — `RESOLVED` closes the Job when no
  other Visit is open; any other outcome leaves it `ACTIVE` with derived attention (`BR-060`).
- Terminal Job (`COMPLETED`/`CANCELED`): `RESOLVED` leaves a `COMPLETED` Job `COMPLETED` and moves a
  `CANCELED` Job to `COMPLETED`; a follow-up-required outcome (`NEEDS_FOLLOW_UP`, `NEEDS_PARTS`,
  `UNABLE_TO_COMPLETE`) moves the Job to `ACTIVE`.

A contradictory state — a terminal Job whose latest reconciled Visit records `NEEDS_FOLLOW_UP` — must never
be produced. No new Job status is introduced.

### D4 — Unknown customer/property is report provenance (`BR-AH-009`)

A technician who cannot identify the canonical Customer or Property records what they know (reported name,
phone/contact, address/location, other minimal identifying facts) as report provenance only. Free text never
creates a Customer, Property or Job. The Manager performs canonical reconciliation. When a Customer is
selected, Property choice is scoped to it; Customer discovery is authorized type-ahead/search, never a full
list.

### D5 — Rejection (`BR-AH-007`)

`REJECTED` is a terminal state. Rejection records the reviewer, timestamp and reason/note; the report is
retained for audit and never hard-deleted. A rejected report cannot subsequently be linked or converted —
no reopen/resubmission workflow exists in v1.

### D6 — Report-level evidence is deferred for v1

Evidence is now Visit-scoped (`docs/tracker/056`), and a report has no Visit. No report-level
photo/evidence capture is built in the initial Technician report; introducing a report-scoped evidence
store merely to ship this feature would be a parallel evidence system. The deferral is an explicit v1
limitation, not accidental omission. When report-level evidence is designed later it must preserve the
assets through reconciliation into the resulting Visit.

### D7 — Technician visibility after submission

No third top-level tab. The technician receives immediate confirmation that the report is "submitted,
pending office review", and reads their own reports through the existing Requests surface — where
Follow-up Visit Requests and Ad-hoc Work Reports stay visually and semantically distinguishable (they are
different domain objects, `BR-AH-004`). A report shows what was reported, Customer/Property/location
context, submitted time, status, and the office decision when resolved; a reconciled report states its
human-readable result without exposing internal mechanics.

## Invariants this record preserves

1. Ordinary Visit creation/scheduling against a `COMPLETED` Job remains prohibited.
2. Historical ad-hoc reconciliation is a privileged and audited exception.
3. Historical reconciliation does not require a fake `COMPLETED → NEW → COMPLETED` lifecycle.
4. The reconciled Visit's canonical outcome has its normal business consequence for the Job.
5. A follow-up-required outcome must not leave the Job falsely represented as completed.
6. Technicians do not gain Job creation, reopening, or office reconciliation authority.
7. The original report survives reconciliation/rejection as provenance.
8. Ad-hoc Work Reports and Follow-up Visit Requests remain separate domain concepts.
9. Report-level evidence is explicitly deferred for v1.
10. A `CANCELED` Job may receive historical reconciliation, and a `RESOLVED` outcome moves it to `COMPLETED` — the work actually happened, so the request was not truly canceled.