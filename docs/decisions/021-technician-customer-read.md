# ADR-021 — The technician's read of the Customer behind an assigned Job

**Status:** Accepted (product-owner decision, 2026-09-18: the capability and read shape below were
recommended by the agent and accepted by product ownership in the same session, as `Project.md` §31
requires)

Date: 2026-09-18
Tracker: `docs/tracker/042-technician-customer-read.md` — this ADR is its Phase 0
Contracts: `docs/api/job-details.md` §2 and §3 (the customer block added by this decision)
Predecessors: `docs/decisions/019-technician-field-experience.md` (D1/D2 — the field read this ADR
extends), `docs/decisions/015-evidence-capabilities.md` and
`docs/decisions/012-property-lifecycle-and-permissions.md` (the same dedicated-capability pattern, D1),
`docs/decisions/014-android-offline-engine.md` (the engine the block travels in)

References: `BR-001`, `BR-004`, `BR-006`, `BR-007`, `BR-009`, `BR-010`, `BR-011`, `BR-012`, `BR-013`,
`BR-014`, `BR-023`, `BR-031`, `BR-040`, `BR-041`, `BR-042`, `BR-047`, `BR-048`, `BR-057`, `BR-068`,
`BR-080`, `BR-092`; `Project.md` §13–§15, §19, §31; `dev.md` §7, §9, §10; `qa.md` §3.1, §4.3, §8, §13.

## Context

`ADR-019` D1 admitted a technician to `GET /jobs/:id`, and D2 scoped that read to the Jobs the caller's
own current `visit_technicians` rows reach. What the projection carries about the Customer, however, is
only `customerId` and `customerName` (`api/src/jobs/job-details.dto.ts:94`), and the default Technician
role holds **no** Customer capability at all: `BR-009`'s set is four Visit capabilities
(`VISIT_VIEW_ASSIGNED`, `VISIT_UPDATE_ASSIGNED_STATUS`, `VISIT_ADD_NOTE`, `VISIT_RECORD_OUTCOME`), and
`customers.view` — the capability every Customer read is guarded by — is a Manager default.

The result is a visible dead end rather than merely a missing feature. The Android Job Details screen
renders the Customer row as tappable and opens the office Customer destination
(`ui/jobs/JobDetailsScreen.kt:993`, `ui/navigation/ServoraNavHost.kt:337`), which reads
`GET /customers/:id` under `customers.view` — so a technician who follows the row the screen offers is
refused `403` and lands on a failure state.

Product ownership decided on 2026-09-18 that a technician must be able, **by default**, to read the
Customer of a Job they are assigned to — view-only — in order to see the phone number, email address and
notes. The behaviour is undefined by the business rules as they stood, so it is recorded as `BR-092`
(with `BR-009` amended to name the new default capability), and this ADR records how it is implemented.

## Decisions

### D1 — A dedicated capability, `customers.view_assigned`, granted to the default Technician role

The Customer block is authorized by a new capability `customers.view_assigned`, which follows the same
resource-prefixed `resource.action` convention as `customers.view` and the Property set (`BR-006`,
`BR-041`), carries bilingual catalogue rows like every permission (`BR-005`), and is granted to the
default **Technician** role by migration `0013` and by the development seed. The default Manager role is
not granted it and does not need it: a manager reads the same Customer through `customers.view`, which
they already hold.

**Why not widen `VISIT_VIEW_ASSIGNED`.** It is the capability `BR-009` names for "view assigned Visits",
and `ADR-019` D1 deliberately kept it as a read of the caller's own assigned work. Customer contact data
is a different asset: a company may reasonably give a contractor the assigned Visit workflow without
handing them the customer's phone number and the office's notes about that customer. `BR-006` requires a
capability to name one thing a role can plausibly hold on its own, and `ADR-012` D1 (Property creation off
`customers.edit`) and `ADR-015` D1 (evidence off `JOB_UPDATE`) took exactly this position. Folding the
Customer read into the Visit capability would make the two inseparable, and there would be no way to grant
or withdraw one without the other.

**Why not reuse `customers.view`.** It is the organization-wide Customer capability — it guards the
customer list, the customer detail, `GET /customers/:id/jobs` and `GET /home/manager`. Granting it to the
default Technician role would hand every technician the whole customer book in order to show one
customer's phone number, which is the opposite of `BR-009`'s "only permissions required for assigned field
work".

**Why not a `customers.view.assigned` dotted variant.** The catalogue's convention is `resource.action`
with one action segment (`customers.view`, `properties.archive`; `evidence.photo.add` segments a kind, not
a scope). `customers.view_assigned` mirrors `VISIT_VIEW_ASSIGNED`, the code the same audience already
holds for the same reason (`ADR-019` D1).

### D2 — The block joins the existing Job read; it is a second authorization question, not a second route

`GET /jobs/:id` keeps **one** projection and **one** guard, exactly as `ADR-019` D1 decided: the route
still accepts `customers.view` **or** `VISIT_VIEW_ASSIGNED`, and the field caller's scope is unchanged
(D2's own assignment semi-join, which `BR-092` re-states as its scope).

Whether the Customer block is **included** is a second question with a second answer, asked in the
service rather than in the guard — the shape `ADR-019` D1 already fixed for a route that needs two
questions answered, and which the activity read's `includeRemovedEvidence` flag already uses
(`api/src/jobs/jobs.controller.ts:167-186`). A caller who holds `customers.view` **or**
`customers.view_assigned` receives the block; a caller who holds neither receives `null` for it and the
rest of the Job unchanged. Refusing the whole read instead would take the Job Details screen away from a
technician who is legitimately assigned to the Job (`BR-009`, `BR-011`).

**Why not a second route (`GET /jobs/:id/customer`).** Two reads over one Job is two definitions of the
Customer as the Job sees it, and the Android screen would hold a second parser for the same section
(`BR-041`, `BR-047`). `ADR-019` D1 rejected the same shape for the Job itself.

### D3 — What the block contains, and what it deliberately does not

The projection carries the Customer's own `email`, `phone` and `notes`, beside the `customerName` the read
already returns. Those are the fields `BR-092` names.

It deliberately does **not** carry `billingEmail`, `billingPhone`, `preferredContactMethod`, `language`,
the Customer's addresses, its `contacts` (`BR-023`), its Properties or its Jobs. Retention of a Customer's
historical address (`BR-057`) and the office's account-management surface are the office read's business;
`BR-092` is a field contact read, and the least disclosure that satisfies it is the correct implementation
of "view-only".

The block carries no identifier of its own: `customerId` and `customerName` are already on the response and
are unchanged, so the wire contract stays backward compatible and the Android parser keeps working against
an older API.

### D4 — Android shows the block inline and stops offering a tap it cannot honour

The Job Details Customer row gains a row per detail the Customer actually has — phone, email, notes —
read-only. A field the office never recorded is **left out** rather than drawn as a line announcing its
absence: the card is what a technician reads before knocking on the door, and three rows of missing data
would push the work they came for down the screen (`BR-012`).

The tap that opens the office Customer destination is now drawn on `customers.view`
(`CustomerPermissionsUiState.canOpenCustomers`) instead of being unconditional. A session holding only the
field capabilities therefore sees the contact details in place rather than a navigation affordance that
ends in `403` — which is `BR-011`'s requirement that the UI not present functionality it cannot complete.

**The client adds no permission gate of its own for the block.** Whether a session may read the Customer
is the API's answer, and the API says it by including the block or not (`BR-001`, `BR-007`): a client-side
capability check on top of it would be a second answer that can disagree with the first, and
`BR-007` is explicit that the UI reflects authorization rather than deciding it. The Android `Permission`
enum therefore gains no `customers.view_assigned` constant — an entry nothing draws on would be dead code
(`dev.md` §1) — and the screen renders exactly the block it was given.

### D5 — The block is offline-readable for free, and that is the requirement, not a nicety

`BR-013` lists "viewing relevant customer and site information" as offline-capable field work, and
`BR-092` requires the contact details to survive a lost connection. The Job Details read is already kept in
the Android Room working set as its own JSON payload (`WorkingSetEntry.JOB_DETAILS`), so the block travels
in the cached payload with no new synchronization, no new table and no new conflict policy
(`docs/architecture/offline-first-architecture.md` §13: an existing offline-capable read, not a new
mutation). Nothing here adds an outbox row — the read is a read.

### D6 — No new scope, no new history, no new business operation

`BR-092` is a read. There is no write, no status, no audit row and no lifecycle to invent: the scope is the
assignment scope `ADR-019` D2 already enforces, and the block is a projection of authoritative Customer
columns (`BR-001`, `BR-080`). A technician cannot reach a deleted Customer, because the Job read already
excludes a deleted Customer's Jobs (`BR-023`).

## Implementation

| Layer | Change |
| ----- | ------ |
| Business rules | `BR-092` (new, §9 Customers) and `BR-009` (the default Technician set gains the capability, with its `BR-040` change record) |
| Catalogue | `api/drizzle/migrations/0013_customer_assigned_permissions.sql` adds the permission row with its bilingual name and description and grants it to every organization's default **Technician** role; `api/src/database/run-development-seed.ts` mirrors it |
| API catalogue | `api/src/auth/permissions.ts: CUSTOMER_PERMISSIONS.VIEW_ASSIGNED` |
| API read | `jobs.service.ts` resolves the block for an admitted caller; `jobs.controller.ts` asks the second question from `authorization.permissions`; `job-details.dto.ts` projects it |
| Android | The Job Details Customer row gains a row per contact detail the API reported (`JobCustomerContact`), and the tap into the office Customer destination is drawn on `canOpenCustomers`; EN/FR copy. **No client permission constant and no client-side gate** (D4) |
| Contract and docs | `docs/api/job-details.md` §2/§3/§4, `docs/domain/job-visit-domain-model.md` §8.3, the tracker's open-question register |

## Open questions not decided here

Recorded rather than guessed (`BR-042`); the tracker carries them with what each one blocks.

1. **Whether the technician may read the Customer's contacts** (`customer_contacts`, `BR-023`) — the
   additional people at a customer, with their own name, email and phone. `BR-092` names the Customer's own
   fields only, so nothing is implemented for contacts.
2. **Whether `preferredContactMethod` and `language` belong in the field read** — they describe how Servora
   communicates with the Customer rather than telling the Technician who to call.
3. **Tap-to-call and tap-to-email affordances** on the block (the maps row's `MapAffordance` is the
   precedent). A UX decision for the design system, not an implementation choice.
4. **The `customers.*` capability set and its re-review** — `customers.view_assigned` joins `customers.view`,
   `customers.create`, `customers.edit` and `customers.archive`, so the set now carries an audience split the
   way the Visit codes do. `ADR-019` open question 6 (the `resource.action` re-spelling of the `VISIT_*`
   codes) covers a neighbouring question and is untouched here.
5. **Reaching the Customer from a Job the technician no longer serves** — the read follows *current*
   assignment, which `BR-069` keeps in place until a manager removes the technician, so a completed Visit's
   crew still reads the Customer until then. That is `ADR-019` D2's scope working as decided, not an
   extension of it.

## Decision update — 2026-09-18 (product ownership answers open questions 1 and 3)

**Status:** Accepted (product-owner decision, 2026-09-18). Recorded under `BR-040`; the decisions are
`docs/decisions/022-customer-contact-persons.md` (its D3 and D6) and the rule they produce is `BR-095`.

Two of this record's open questions are now closed, and both were answered the way the question itself was
posed.

1. **Open question 1 — whether a technician may read the Customer's contacts: decided, yes, all of them.**
   The read carries the Customer's contact persons with their own names, phone numbers and email addresses,
   inside this record's existing block, under D2's unchanged assignment scope and unchanged second
   authorization question. The billing fields and the billing-contact flag stay out: they say how Servora
   bills the Customer, not who the technician should speak to. **No new route, no new capability and no new
   client permission constant** — `BR-092` is amended with a `BR-040` change record, and `BR-095` defines who
   may write a contact person (`ADR-022` D3, D4).
2. **Open question 3 — tap-to-call and tap-to-email: decided, yes, on both surfaces.** The Customer Detail
   Contacts card and the Job Details contact rows draw tappable phone and email through the affordances the
   customers list already has (`dialIntent` / `mailIntent` / `startContactIntent`), so the block this record
   added becomes actionable rather than readable only (`ADR-022` D6).

Open question 2 (`preferredContactMethod` and `language`), open question 4 (the `customers.*` capability
set's re-review) and open question 5 (reaching the Customer from a Job the technician no longer serves) are
untouched and remain open. D1, D2, D4 and D5 are unchanged, and the block's own fields (`email`, `phone`,
`notes`) stay byte-for-byte as this record defined them.
