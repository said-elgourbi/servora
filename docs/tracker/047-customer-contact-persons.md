# Tracker 047 — Customer contact persons

**Status: IN PROGRESS — Items 1 (the rule and the decision record), 2 (the schema, the capability
catalogue and the seed fix), 3 (the API writes), 4 (the API reads and the authorization matrix),
5 (the Android data layer), 6 (Android Customer Detail and the contact destination) and 8 (the Job Details
customer block, with the effective-primary presentation product ownership decided on 2026-09-18) are
complete as of 2026-09-18.** Migration `0014` is written and applied to the local development database, the
contact capability set is in the catalogue and the seed, the three contact write routes are implemented,
authorized by the contact capabilities and covered by `customers-contacts.e2e-spec.ts`, both reads now
report the contacts — the office detail orders them primary-first and hides a removed one, and the Job
Details customer block carries them, with which of them is primary, under the same second authorization
question — and Android now carries a contact's `version`, sends the three contact writes, reads the Job
Details contacts into the domain, draws the contacts card on Customer Detail with its Add/Edit Contact
destination, its confirmed removal, its read-only block on Edit Customer and its English and French copy,
and marks the **effective primary contact** on both surfaces — the Customer's own phone line included —
while the Job card presents one phone and keeps every other number behind a disclosure that starts closed.
**Item 7 (Android Add Customer: the primary-contact block's own phone and email) is where the next
session starts.**

Date: 2026-09-18

Decisions of record: product ownership, 2026-09-18 (this tracker is their record, kept under the `BR-040`
change procedure). `BR-095`, the `BR-040` change records for `BR-023` and `BR-092`, and `ADR-022` were
**Item 1**; they exist as of 2026-09-18, and every item below them implements what they record.

Predecessors: `docs/tracker/010-customer-detail.md` (the detail screen and the `contacts` array),
`docs/tracker/014-android-add-customer.md` (the primary contact the create form records),
`docs/tracker/015-android-edit-customer.md` (the edit form this slice keeps read-only for contacts),
`docs/tracker/017-android-job-details.md` (the Job card the field block sits on),
`docs/tracker/042-technician-customer-read.md` and `docs/decisions/021-technician-customer-read.md` (the
field read whose two open questions this slice closes)

Business rules this slice will touch: `BR-023` (customers, amendment), `BR-092` (the assigned-customer
read, amendment), **`BR-095` (new)**, with `BR-040` change records for the two amendments. Rules relied on
rather than changed: `BR-001`, `BR-004`, `BR-005`, `BR-006`, `BR-007`, `BR-009`, `BR-011`, `BR-012`, `BR-013`,
`BR-014`, `BR-028`, `BR-031`, `BR-032`, `BR-033`, `BR-041`, `BR-042`, `BR-056`, `BR-057`, `BR-067`, `BR-080`,
`BR-085` (the capability-set precedent), `BR-086`, `BR-087`, `BR-089` (the soft-removal precedent), `BR-090`.

Domain model: `docs/domain/foundation-domain-model.md` §8 (Contacts) — **extended by this slice**.
API contracts: `docs/api/customers.md` §3.2 and §5.2 — **extended** (two new write routes, the contact's own
phone/email, the primary rule); `docs/api/job-details.md` §3.2/§3.3 — **extended** (the contacts inside the
existing customer block).
ADR: `docs/decisions/022-customer-contact-persons.md` — **new; this slice is its implementation**.
Database: `api/drizzle/migrations/0014_customer_contact_persons.sql` — **landed in Item 2 and applied to
the local development database.** It carries the one-primary partial unique index, `version` with its
check, the two soft-removal columns with their paired check, and the three permission rows with their
default-Manager grants, together with `meta/0014_snapshot.json` and its `_journal.json` entry. The
previous latest migration was `0013_customer_assigned_permissions.sql`.
Offline standard: `docs/architecture/offline-first-architecture.md` §12 (the adopter list) and §13 (the
check a new read or mutation is written against).
Design reference: **none** — no Figma screen exists for a contacts section. `Figma/src/screens/Customers.tsx`
draws the New Customer view with its "Primary contact" block only. Product ownership approved the layout
described in D6 instead of waiting for a design.
Client scope: **Android only.** The Angular application does not exist in this repository yet, so `BR-002`'s
consistency requirement is a later slice and no Angular work is planned here.

## Why this slice exists

A customer's contact people (`customer_contacts`) are modelled but almost unusable: the office can record
exactly one of them, only for a company, only while the customer is being created, with no phone or email;
the office cannot see them on the customer's page, cannot edit them and cannot remove them; and a technician
cannot reach them at all from the Job they are standing in front of.

| Item | Status today |
| --- | --- |
| `customer_contacts` table — `firstName`, `lastName`, `email`, `phone`, `role`, `isPrimary`, `isBillingContact`, `isJobContact`, timestamps | **Exists** (`api/src/database/schema.ts:394-430`), cascade FK to `customers`, indexes on `customer_id` and `email` (`api/drizzle/migrations/0000_foundation_domain_model.sql:24-26`, `:116`, `:124`); **Item 2 added `version`, `removedAt` and `removedByMembershipId`** (`0014_customer_contact_persons.sql`) |
| One primary per customer | **Enforced by the database as of Item 2** — `customer_contacts_primary_unique`, a partial unique index on `(customer_id) WHERE is_primary` (migration `0014`); zero primaries stays legal. Before Item 2 `isPrimary` was a bare flag and two rows could be primary, or none |
| `POST /customers/:id/contacts` (create), `customers.edit` | **Exists** (`api/src/customers/customers.controller.ts:239-258`) |
| Contact update / removal / change-primary | **Do not exist**; `docs/api/customers.md:433` records them as open questions |
| `GET /customers/:id` → `contacts[]` | **Exists**, ordered newest-first (`api/src/customers/customers.service.ts:641`) |
| Android Add Customer → primary contact | **Exists for COMPANY only** (`AddCustomerScreen.kt:123`), first/last name only — the customer's own phone/email are captured on the header and the contact is deliberately created with both absent (`CustomerCreateDtos.kt:77-94`, `AddCustomerViewModel.kt:271`) |
| Android Customer Detail → contacts | Only the primary contact's **name** under the company name (`CustomerDetailScreen.kt:212-221`); the tappable phone/email are the **customer's own** (`:264-294`) |
| Android Edit Customer → contacts | **Nothing** — no contact field exists on the form |
| Android Job Details → contacts | **Nothing**; the customer's own phone/email/notes are drawn as **non-tappable** rows (`JobDetailsScreen.kt:1082-1108`) |
| Technician's read of the customer's contacts (`BR-092`) | **Deliberately excluded** (`docs/api/job-details.md:213`; `ADR-021` D3, open question 1) |
| Tap-to-call / tap-to-email on the Job card | **Not implemented** — `ADR-021` open question 3, a design-system decision |

## Decisions (agreed by product ownership, 2026-09-18)

### D1 — The primary contact, and the invariant that holds it

- A customer may have any number of contacts, and **none is required** (`BR-023`).
- **At most one contact per customer is primary**, enforced by the database with a partial unique index —
  `customer_contacts_primary_unique ON customer_contacts (customer_id) WHERE is_primary`. This follows the
  two indexes this repository already keeps for exactly this shape: `visit_technicians_lead_unique … WHERE
  role_code = 'LEAD'` (`0003_purple_black_tarantula.sql:366`) and `customer_addresses_default_service_unique
  … WHERE type = 'SERVICE' and is_default = true` (`0004_woozy_spitfire.sql:142`).
- A **COMPANY** customer's primary is the contact person recorded for it.
- An **INDIVIDUAL** customer is their own primary: the person *is* the customer, so **no `customer_contacts`
  row is written for them** and none is expected. The individual is never duplicated into the contact table,
  because two writable copies of one fact need new "which one wins" rules (`Project.md` §9).
- **Zero primary contacts is a legal state.** Contacts are optional (`BR-023`, `foundation-domain-model.md` §8),
  so a customer may have no contact at all, or contacts none of which is primary.

### D2 — A contact owns its own details

- A contact carries its **own** first name, last name, phone and email. Those four fields are the feature.
- The customer header's `phone` and `email` **remain the customer's general line** and are not a contact's
  details. The Add Customer form therefore captures the primary contact's own phone and email in the contact
  block, which is a change to what it does today (`CustomerCreateDtos.kt:77-94` deliberately leaves them
  absent).
- The existing optional header phone/email stay optional and unchanged (`BR-023`).

### D3 — What a technician may read

- The technician reads the customer's contacts through the **existing** assigned-customer read
  (`BR-092`) — the same assignment scope, the same second authorization question on `GET /jobs/:id`, the
  same block. **No new route, no new capability.**
- **All** of the customer's contacts are readable, because the point of the read is "who do I call before I
  knock".
- **Excluded:** `billingEmail`, `billingPhone` and the `isBillingContact` flag. They describe how Servora
  bills the customer, not who the technician should speak to.
- The block's existing `email`, `phone` and `notes` (the customer's own) stay byte-for-byte as they are, so
  the addition is backward compatible for every existing client.
- The contacts ride **inside** the existing block, so the read still answers exactly one authorization
  question and a caller who is not admitted receives `null` for all of it (`ADR-021` D2).

### D4 — Capabilities

| Code | Route | Default grant |
| --- | --- | --- |
| `customers.contacts.create` | `POST /customers/:id/contacts` | Manager |
| `customers.contacts.edit` | `PATCH /customers/:id/contacts/:contactId` | Manager |
| `customers.contacts.remove` | `DELETE /customers/:id/contacts/:contactId` | Manager |

- Granted to the default **Manager** role; **not** granted to the default Technician role.
- A dedicated set rather than `customers.edit`, following `BR-085`'s Property set: a role may legitimately
  maintain a customer without being trusted to change or delete the people the company calls. `customers.edit`
  no longer authorizes any contact write.
- The codes follow the existing `resource.action` convention (`BR-006`, `BR-041`), carry bilingual catalogue
  rows like every permission (`BR-005`), and remain grantable and revocable per role and per member —
  authorization never depends on the Manager role or any role name.
- **No read capability is added.** The office read stays under `customers.view` (the `contacts[]` array is
  already part of the customer projection and the field read reuses `customers.view_assigned`).

### D5 — Where contacts are managed (refined by product ownership)

- **Customer Detail is the canonical surface**: add, edit, remove and change the primary contact all happen
  there.
- **Add Customer records the initial primary contact only.** No arbitrary additional contact rows are added
  inline during customer creation in v1 — after the customer exists, additional contacts are managed from
  Customer Detail. This deliberately keeps the create flow's one-write-per-optional-part model instead of
  turning it into a batch that can half-fail.
- **Edit Customer keeps contacts read-only**, with the hint-and-link pattern the form already uses for
  Properties ("Properties are managed from the customer detail screen").
- The contact **`role`** field (free text, e.g. "Site manager") stays in the model and the API but is **not
  exposed in any client form** in this slice. Whether it should be captured and edited is open question 3.

### D6 — The contacts section (layout approved in place of a Figma design)

- A **Contacts card** on Customer Detail: the primary contact first, then the others.
- Each row states the person's name, their phone and their email. Phone and email are **tappable** — dial and
  mail intents, reusing `dialIntent` / `mailIntent` / `startContactIntent` (`CustomersScreen.kt:1456-1472`).
- Each row carries the edit and remove actions, drawn only when the member holds the capability that would
  perform the write (`BR-007`, `BR-011`).
- The card carries an **"Add more contact persons"** action, drawn on `customers.contacts.create`.
- A customer with no contacts shows a localized empty state, not an empty card.
- On **Job Details**, the customer's contacts are drawn under the customer's own contact details, with the
  same tappable phone and email. A field the office never recorded is **left out** rather than drawn as a row
  announcing its absence (`BR-012`, `ADR-021` D4).
- Every new label ships in English and French.

### D7 — Offline behaviour

- **Reads are existing adopters and need no new store.** The Customer Detail read and the Job Details read are
  both in the Room working set (`offline-first-architecture.md` §12), so the contacts travel in payloads that
  are already cached. Nothing is added to the offline engine for them.
- **Writes are online-only**, and that is a recorded decision rather than an omission
  (`offline-first-architecture.md` §13): `POST /customers/:id/contacts` accepts no client-generated
  idempotency key and no conflict policy has been decided for a create, so the operation may not be queued.
  The same applies to the new edit and removal routes. This is the tracker statement §13 requires; adopting
  the outbox for contacts is a later decision and an API change first.

### D8 — Removal is soft

- Removal writes `removed_at` and `removed_by_membership_id` and takes the contact out of ordinary views.
  The record survives, so "who removed this person, and when" stays answerable (`BR-033`), following the
  position `BR-089` already accepted for evidence removal ("removal is soft… records the actor, the
  timestamp").
- **No reason is required**, and no reason catalogue is invented (`BR-042`). Whether a reason is ever needed
  is open question 7.
- A removal is added history, never a rewrite of what the record previously held (`BR-067`).
- Removing the primary contact leaves **zero** primary and **never promotes another contact automatically** —
  the analogue of `BR-069`'s "the system must never automatically promote another technician". The authorized
  user decides.

### D9 — Optimistic concurrency

- `customer_contacts` gains a `version` column, and the edit and removal routes require the caller's
  `expectedVersion`. A mutation that names a version the contact has left is refused with
  `409 CONTACT_VERSION_CONFLICT` rather than applied — the same story Properties, Jobs and Visits already
  tell, and what `BR-032`/`BR-086` require instead of a blind last-write-wins.
- `customer_contacts` has no version column today, while `properties`, `jobs` and `visits` do
  (`api/src/database/schema.ts:520`, `:661`, `:1091`).

### Derived behaviours that follow from D1–D9

- **Changing the primary is one operation, in one transaction**: the previous primary is cleared and the new
  one set together, so no state exists in which two contacts are primary or the customer has briefly none.
- The office customer detail orders **the primary first, then the rest oldest-first**. Today the service
  orders newest-first (`customers.service.ts:641`); this is a deliberate, visible change and it is recorded
  here rather than slid in.
- Editing or removing a contact never touches the customer, its Jobs, its Properties or any preserved address
  snapshot (`BR-056`, `BR-057`, `BR-087`).
- `BR-009` is **not** amended: the technician's contact read reuses the capability already held, so no default
  Technician capability is added and no default-role grant changes for that role.

## Findings recorded while planning (facts, not assumptions)

1. **The one-primary index will apply cleanly to the current data.** The four `isPrimary: true` sites are one
   per customer: `api/test/customers-contacts.e2e-spec.ts:143`/`:150`,
   `api/test/customer-detail-projection.e2e-spec.ts:406`, `api/test/customers.e2e-spec.ts:456` (its second
   contact at `:474` is `isPrimary: false`), and the seed at `api/src/database/run-development-seed.ts:1117`.
   No fixture produces two primaries for one customer.
2. **The development seed contradicts D1 and must change.**
   `api/src/database/run-development-seed.ts:1113-1121` inserts a `customer_contacts` row with
   `isPrimary: true` for **both** template kinds — including `INDIVIDUAL` customers. Under D1 an individual is
   their own primary and holds no contact row, so that row is removed for `INDIVIDUAL` templates in Item 2.
3. **No contact capability exists in the catalogue today.** Writes ride on `customers.edit`
   (`api/src/customers/customers.controller.ts:239-258`), which is the interim authorization
   `docs/api/customers.md:428-432` records. D4 replaces it.
4. **The Job Details block is gated by the read's second authorization question**, asked in the service from
   the caller's permission codes (`ADR-021` D2). The contacts addition changes what the block carries and not
   how it is asked, so no guard, no route declaration and no error code changes for it.

## Items

Each item is a coherent step. **Items 1, 2, 3, 4, 5 and 6 are complete; Item 7 is where the next
session starts.**

**Item 1 — done 2026-09-18 (documentation only).** `BR-095` is in §9 of `.clinerules/Business Rules.md` in the
document's standard shape, `BR-023` and `BR-092` carry their `BR-040` change records, and
`docs/decisions/022-customer-contact-persons.md` records D1–D9 (with `ADR-021` gaining the decision update
that closes its open questions 1 and 3). Nothing was implemented: no code, migration, contract or client
change. No command was executed, because there is nothing to execute; the rules were reviewed against
`BR-001`, `BR-004`, `BR-006`, `BR-007`, `BR-009`, `BR-023`, `BR-033`, `BR-040`, `BR-041`, `BR-042`,
`BR-067`, `BR-069`, `BR-085`, `BR-086`, `BR-089`, `BR-092` and `Project.md` §9/§17 as `BR-042` requires.
The first item is therefore the gate for every item below it: no item may start before it existed.

**Item 2 — done 2026-09-18 (schema, catalogue and seed).** Migration
`0014_customer_contact_persons.sql` was generated from `api/src/database/schema.ts` with
`npx drizzle-kit generate --name customer_contact_persons` and then extended with the capability rows,
the way `0007` is. It carries:

- `customer_contacts_primary_unique`, the partial unique index on `(customer_id) WHERE is_primary`
  (`ADR-022` D1), so the one-primary invariant is the database's and not whichever code path remembers
  it;
- `version` (`integer NOT NULL DEFAULT 1`) with `customer_contacts_version_check` (`> 0`), the column the
  edit and removal routes will take `expectedVersion` against (`ADR-022` D9);
- `removed_at` and `removed_by_membership_id` (FK to `organization_members`) for soft removal
  (`ADR-022` D8);
- `customer_contacts_removal_state_check` — **one constraint beyond the list the item enumerated**:
  `(removed_at is not null) = (removed_by_membership_id is not null)`, so a removal cannot record the
  moment without the member or the member without the moment. It is `properties_archived_state_check` /
  `properties_archived_actor_check`'s pair combined into one, and it is recorded here because the item
  table named `version`'s check only (`Project.md` §17, `dev.md` §6);
- the three catalogue rows and their default-Manager grants (`ADR-022` D4), with no Technician grant.

`api/src/database/schema.ts` gained the same three columns, the index, and the two checks;
`meta/0014_snapshot.json` and the `_journal.json` entry were generated with the migration.
`api/src/auth/permissions.ts` gained `CUSTOMER_CONTACT_PERMISSIONS` (with `CustomerContactPermission` in
the `PermissionCode` union) and `permissions.spec.ts` gained a group pinning the three codes. The seed
now writes a `customer_contacts` row for a **COMPANY** template only (finding 2), and its
`PERMISSION_TEMPLATES` carries the three new codes so the default Manager role holds them.

Two existing fixtures had to move with the row type: `customer-contact.dto.spec.ts` and
`customer-detail.dto.spec.ts` construct a full `CustomerContact`, so both gained
`removedAt: null`, `removedByMembershipId: null` and `version: 1`. No DTO behaviour changed — the wire
shape of a contact is Item 3's.

**The generated file was corrected once, before it was committed.** The paired removal-state check had landed
after the capability rows with an empty `--> statement-breakpoint` beside it; the file was rewritten so the
check sits where `drizzle-kit` generated it — with the other table DDL, before the catalogue rows. Because
that correction came after the migration had been applied to the local development database, the local
database was **rolled back for `0014` alone** (the three columns, the index, the three permission rows and
their grants, and the migration's own bookkeeping row) and migrated again from the corrected file, so the
file and the database are the same statement list: the recorded hash
`f5b6ab4f64b0370c123f7fb2be39791ff1a575959037de4c0861f83ac563f200` (`drizzle.__drizzle_migrations`, id 16)
equals the file's `sha256`. No other database exists to be affected — the local development database is the
only adopter this repository has — and the change is a statement order inside one migration, with the same
statements and the same result. `dev.md` §6's rule that an applied migration is never modified is honoured in
its purpose (no database is left describing a different migration from the file) rather than by leaving the
file in a state nobody applied.

**One fact worth knowing about the generated FK name.** Drizzle names the new foreign key
`customer_contacts_removed_by_membership_id_organization_members_id_fk`, which is 68 characters, so
PostgreSQL truncates it to 63 and the constraint is stored as
`customer_contacts_removed_by_membership_id_organization_members`. This is the repository's existing
behaviour rather than something this item introduced: the constraint behind
`job_photo_removals_actor_membership_id` is truncated the same way. The column name itself is the one `D8`
and `BR-095` name, so the truncation is left as it is and recorded here for a reader who introspects the
database.

**Item 3 — done 2026-09-18 (the API writes).** The three contact write routes exist and are authorized by
the contact capability set:

- `POST /customers/:id/contacts` is re-authorized from `customers.edit` to
  `customers.contacts.create` (`ADR-022` D4) and now clears the customer's previous primary **inside the
  same transaction** as the insert, so a create that states `isPrimary` cannot collide with
  `customer_contacts_primary_unique`;
- `PATCH /customers/:id/contacts/:contactId` (`customers.contacts.edit`) edits a contact partially — a
  member the caller leaves out keeps the value the contact holds, which is what protects the free-text
  `role` no client captures — and promotes the contact to primary in one transaction when it states
  `isPrimary: true`;
- `DELETE /customers/:id/contacts/:contactId` (`customers.contacts.remove`) removes a contact **softly**:
  `removed_at` and the acting membership are written and the record survives (`BR-033`, `BR-067`). The
  removal states `expectedVersion` in its body, like the edit.

`customer-contact.dto.ts` carries the wire shape: `CustomerContactDto` gained `version` (the token the
edit and removal routes take back, `ADR-022` D9), and `parseUpdateCustomerContactDto` /
`parseRemoveCustomerContactDto` validate the two new bodies. Both **require** `expectedVersion`, which is
what `ADR-022` D9 says and what `BR-032`/`BR-086` need to forbid a blind write; `domain-validation.ts`
gained `requirePositiveInteger` for them. The diff accepts only the fields `BR-095` names as writable —
first and last name, phone, email, the primary flag and the free-text `role` — so `isBillingContact` and
`isJobContact` are deliberately **not** editable, because no rule defines what makes a billing or a job
contact (`BR-042`).

`customers.service.ts` gained `ContactNotFoundError` and `ContactVersionConflictError`, `updateContact`,
`removeContact`, and the private `lockCustomerInScope`, `requireContact`, `assertContactVersion` and
`clearPrimaryContact`. Three details are what make the write rules hold rather than merely read well:

- **every contact write takes the customer's row lock first** (`SELECT … FOR UPDATE`), which serialises
  the customer's contact writes. Without it, two simultaneous promotions could each clear the row it saw
  as primary and then both claim the flag — the database's partial unique index would refuse the second,
  turning a race into a server failure — and D9's read-then-check version guard could interleave with
  another write to the same contact. The lock is the one place the tracker's "transactional
  change-primary rule" is actually made atomic (`dev.md` §6);
- **a partial edit writes only the columns the caller stated.** Writing back a value it had just read
  would have let an edit that changed a phone number silently undo a promotion another office member made
  in between;
- **a removed contact is not addressable by a write**: `requireContact` excludes `removed_at is not null`,
  so a removed contact answers `404 CONTACT_NOT_FOUND` rather than being editable, exactly as it is absent
  from an ordinary view (D8).

The row that loses the primary flag is a changed row like any other, so `clearPrimaryContact` moves its
`version` on with the flag. The controller maps the two failures to `404 CONTACT_NOT_FOUND` and
`409 CONTACT_VERSION_CONFLICT` with `details.currentVersion`, alongside the existing
`404 CUSTOMER_NOT_FOUND`, and the routes keep the repository's rule that a tenant boundary is reported as
"not found", never "forbidden" (`BR-001`).

**The contract documentation for what this item changed was updated with it** rather than left
describing behaviour that no longer exists (`dev.md` §14, `Project.md` §26): `docs/api/customers.md` §2
now lists the three contact capabilities and the two new routes, and §5.2 documents all three writes, the
`version` token, the partial edit, the one-primary transaction, the soft removal and the two error codes.
Item 9 therefore verifies that text rather than writing it, and owns what is still outstanding there —
the read-side paragraphs, `docs/api/job-details.md`, the domain model, the offline standard's adopter
list and the README pointer.

`customers-contacts.e2e-spec.ts` covers the writes: the create with its own phone and email, the refusal
of `customers.edit` as authorization for a contact write, the tenant boundary, the one-primary invariant
for both a create and a promotion, the partial edit, the stale-version refusal on both routes, the
missing-version refusal, the soft removal (asserted against the stored row, which is what item 4 will
make visible through the reads) and a contact of another customer answering `CONTACT_NOT_FOUND`.

**Verification (Tier A, `qa.md` §3.1).** `npm test` PASS — 55 files, 510 tests.
`npm run test:e2e -- test/customers-contacts.e2e-spec.ts test/customers.e2e-spec.ts
test/customer-detail-projection.e2e-spec.ts test/foundation-schema.e2e-spec.ts` PASS — 4 files, 53 tests
(the contacts spec is 13 of them). `npm run build` PASS. `npm run lint` PASS — 0 warnings, 0 errors, 206
files. `npx prettier --check` is clean for every file this item touched (`src/customers/customer-contact.dto.ts`
and its spec, `src/customers/customers.controller.ts`, `src/customers/customers.service.ts`,
`src/validation/domain-validation.ts` and its spec, `test/customers-contacts.e2e-spec.ts`); the two files it
reports inside `src/customers/` — `customer-list-filter.dto.ts` and its spec — are untouched by this item and
are unmodified from `HEAD`. `npm run typecheck` reports **two errors, both
pre-existing and neither in this item's files**:
`test/technician-home.e2e-spec.ts:351`/`:354` read `visit.scheduledEnd` as possibly `null`, which the
uncommitted tracker-041/042 change to `src/jobs/visit-assignment.ts` made nullable. That file is
byte-identical to `HEAD`, the two errors are the only ones reported, are the same two item 2's row already
records, and they are not this item's to fix.

**Item 4 — the API reads and the authorization matrix (2026-09-18).**

Both reads report the contacts, through **one** definition of what a customer's contacts are in ordinary
use. `CustomersService.selectContacts` — the read behind `GET /customers/:id`, behind `listContacts` and
behind the Job Details block — excludes a contact whose `removed_at` is set (`BR-095`; `ADR-022` D8) and
orders the rest **primary first, then oldest-first**, which is the order `BR-095` records; the primary
flag is the only thing that reorders the list, so an ordinary edit does not reshuffle the section. The
order and the removal rule live in that one query, so no surface can disagree with another (`BR-041`).

`JobsService.findJobDetailsInOrganization` reads the contacts through `CustomersService` and puts them
**inside** `customerContactDetails`, under the same second authorization question: a caller holding
`customers.view` or `customers.view_assigned` receives them, and a caller holding neither still receives
`null` for the whole block rather than being refused the Job they are assigned to (`BR-092`, `BR-009`,
`BR-011`; `ADR-022` D3, `ADR-021` D2). The `contacts` member is additive on the wire, so the read stays
backward compatible for every existing caller.

One narrowing decision was needed, and it is the least disclosure `BR-092` permits. A contact in the
field block carries the contact person's **`firstName`, `lastName`, `phone` and `email`** — the things
`BR-092` names for a contact person — and nothing else. The billing-contact flag is absent because
`BR-092` excludes it; the free-text `role`, the job-contact flag, the contact's `version` and its
timestamps are absent for the same reason, and the write token in particular has no place in a read,
because a technician holds no contact capability at all (`BR-009`). The office read is unchanged and
still carries the full contact row, because the office is the surface that writes it (`BR-095`).
`JobDetailsContactPersonDto` states the narrowing and `toJobDetailsDto` is the only place it happens, so
the field read cannot drift into reporting more (`BR-041`).

**Flagged for confirmation, not silently decided.** `ADR-022` D3 lists the exclusions as
`billingEmail`, `billingPhone` and the `isBillingContact` flag, and says the technician "sees the same
list the office sees, minus the billing fields". `BR-092` — the rule, and the higher-precedence document
— enumerates what a technician may read of a contact person as *their names, phone numbers and email
addresses* and names nothing else, so this item implemented the enumeration: the free-text `role` and
the `isPrimary` flag are **not** on the field wire. Neither is needed by this slice's client work —
item 8 draws a name, a tappable phone and a tappable email, and the primary is already **first** in the
array, which is where a reader looks — but if product ownership reads `ADR-022` D3 as carrying them, the
change is additive and local: two members on `JobDetailsContactPersonDto`, its mapping in
`toJobDetailsDto`, and the keys assertions in `src/jobs/job-details.dto.spec.ts` and
`test/job-details.e2e-spec.ts`. Recorded here rather than implemented on a guess (`BR-042`,
`Project.md` §30).

The contract text the reads now contradicted was corrected with them (`dev.md` §14):
`docs/api/customers.md` §3.2 gained the ordering, the removal exclusion and the statement that reading
the contacts needs no contact capability; `docs/api/job-details.md` §2, §3.1, §3.2, §3.3 and §5 were
brought up to date, so its two example responses carry `contacts`, the field-notes row states exactly
what is and is not on the wire, and open questions 7 and 8 — both answered by Item 1's decisions, not by
this item — read as decided rather than as stale questions. Item 9 verifies that text and owns the rest
of the documentation list.

**Verification (Tier B, `qa.md` §3.1 — the item crosses a contract and an authorization boundary).**
`npm run lint` PASS — 0 warnings, 0 errors, 206 files. `npm test` PASS — 55 files, **512 tests**, two of
them new in `src/jobs/job-details.dto.spec.ts`. `npm run test:e2e` PASS — 22 files, **379 tests** against
the running foundation stack, including `test/customers-contacts.e2e-spec.ts` (17 tests, four new) and
`test/job-details.e2e-spec.ts` (29 tests, three new). `npm run build` PASS. `npm run typecheck` reports
only the two pre-existing `test/technician-home.e2e-spec.ts` errors `:351`/`:354` that items 2 and 3
already record.

`npx prettier --check` is clean for `src/customers/customers.service.ts`, `src/jobs/job-details.dto.ts`
and `test/customers-contacts.e2e-spec.ts`. It still reports three files whose remaining issues are **not
this item's lines** and are unmodified from their pre-item-4 state: `src/jobs/jobs.service.ts` (nine
hunks, all in trackers 038–046's uncommitted work — `VisitSchedulingConditionNotMetError`'s constructor at
`:249`, `assertPropertyAvailableForCustomerNewWork` at `:535`, the `visitTechnicians` predicate at `:636`,
`requireVisitForWriter` at `:770`, `VisitStatusTransitionNotAllowedError` at `:793` and four more),
`src/jobs/job-details.dto.spec.ts` (three hunks — the `fieldActionable` expectations at `:332`/`:371` and
a duplicated blank line at `:397`) and `test/job-details.e2e-spec.ts` (one hunk at `:887`, the
`signInFor([VISIT_PERMISSIONS.UPDATE_ASSIGNED_STATUS])` call). Reformatting another slice's uncommitted
code is not this item's change (`dev.md` §15), so they are reported rather than rewritten, exactly as
item 3 reported `customer-list-filter.dto.ts`.

**Item 5 — the Android data layer (2026-09-18).** The contact writes and the contact read the Item 6 screens
will draw are in the data layer, and nothing above the data layer changed.

`CustomerContactDto` and the `CustomerContact` domain model gained the contact's **`version`** — the token
the edit and the removal state back as `expectedVersion` (`BR-095`, `ADR-022` D9), which is why `toContact()`
carries it and why the one device-test fixture that builds a `CustomerContact` by hand
(`ui/customers/CustomerDetailScreenTest.kt`) moved with the required member. The DTO's own `version`
defaults to `0`, following the convention `JobDetailsDto.version` and `jobs`/`visits` already use
(`BR-042`, `offline-first-architecture.md` §10): a working-set payload written before the field existed is
still readable, and a contact that reported no version cannot be written with the value it reported rather
than being handed a guessed one.

`CustomerCreateDtos.kt` gained `UpdateCustomerContactRequest` and `RemoveCustomerContactRequest`. The edit
mirrors the API's partial update: `firstName`, `lastName`, `email`, `phone` and `role` keep `null` defaults so
a member the caller does not state is **absent** from the request and the API leaves it as the contact holds
it — which is what protects the free-text `role` no client edits (`ADR-022` D9) — while `isPrimary` is
nullable so an edit that states it changes the customer's primary in one operation and one that omits it
leaves the flag alone. `expectedVersion` has no default: a mutation that names none is not something this
client can express.

`CustomersApi` carries the two routes (`PATCH …/contacts/{contactId}`, and the removal), and `createContact`'s
documentation was corrected to `customers.contacts.create`, which is what item 3 re-authorized it to.

**One declaration had to be got right rather than written the obvious way.** The removal's only input is the
version, so it travels in the body (`docs/api/customers.md` §5.2.3) — and Retrofit's `@DELETE` is a
**body-less** method: `RequestFactory` parses it with `hasBody = false` and refuses a `@Body` when it parses
the method. Retrofit parses a method lazily, on its first call, because this app does not enable
`validateEagerly`, so the mistake would have surfaced on the first removal in the field rather than at
compile time. The route is therefore declared with `@HTTP(method = "DELETE", path = …, hasBody = true)`.
That trap is invisible to a scripted `CustomersApi` double, so `CustomersApiContractTest` builds the
interface the way `NetworkModule` does — the same `Json` configuration and converter, over an OkHttp
interceptor that records the request — **with `validateEagerly` enabled so `create` parses every method**,
and pins that the interface is creatable, that the edit is a `PATCH` whose body is exactly
`{phone, isPrimary, expectedVersion}` (the members left at their defaults are absent, which is the partial
update's whole point), and that the removal is a `DELETE` whose body is exactly `{expectedVersion}`.

`CustomersRepository` gained `updateContact` and `removeContact` and the three operations now classify their
answers with the package's shared `httpFailureReason` rather than the mapping the reads use. The contact
routes are the ones that document `404 CONTACT_NOT_FOUND` and `409 CONTACT_VERSION_CONFLICT`, and
`BR-032`/`BR-086` require that conflict to be reported as a conflict so the user re-reads, rather than being
flattened into an unexpected failure. The existing operations keep the classification they had, so this item
does not silently change how a missing Customer is reported in the Add Property flow. A `401` is renewed once
and retried, exactly as the create already was, and the removal inspects its `Response` because the route
succeeds with `204` and no body. The three writes are documented as **online-only**
(`offline-first-architecture.md` §13): the routes accept no idempotency key, so nothing is queued.

`JobDetailsCustomerContactDto` gained `contacts` and `JobDetailsContactPersonDto` — the four fields `BR-092`
names and nothing else — with `JobContactPerson` and `contacts` on `JobCustomerContact` in the domain model,
mapped in `JobDetailsRepository`. `contacts` defaults to an empty list on both, so a cached payload from an
earlier build still reads (`BR-042`, §10). No screen draws them yet: that is item 8.

`Permission` gained `CUSTOMERS_CONTACTS_CREATE`, `CUSTOMERS_CONTACTS_EDIT` and `CUSTOMERS_CONTACTS_REMOVE`,
and `CustomerPermissionsUiState` gained `canCreateContact`, `canEditContact` and `canRemoveContact`, each
read from its own code and never inferred from `customers.edit` or from a sibling capability
(`BR-095`, `BR-085`, `BR-006`).

**Six repository doubles moved with the interface** — five JVM (`AddCustomerViewModelTest`,
`AddPropertyViewModelTest`, `CustomersViewModelTest`, `EditCustomerViewModelTest`, `CreateJobViewModelTest`)
and one device-test (`ServoraHomeNavigationTest`). Each answers both new operations with
`Failure(UNEXPECTED)`, the outcome those doubles already give an operation they do not script, so no test
can read a write it did not arrange; item 6 will script them where its screens need them.

**Verification (Tier A, `qa.md` §3.1 — one application and one layer, so the affected targets rather than the
full sweep).** One filter was added to the tracker's planned command because this item also changes
`CustomerPermissionsUiState`, and `assembleDebugAndroidTest` was run because a device-test fixture moved with
the interface.

`cd android && ./gradlew compileDebugKotlin` PASS. `cd android && ./gradlew
testDebugUnitTest --tests 'com.servora.android.data.customers.*' --tests 'com.servora.android.data.jobs.*'
--tests 'com.servora.android.ui.customers.CustomerPermissionsUiStateTest'` PASS — the two affected packages
and the one UI-state class, **0 failures, 0 errors**; `CustomersRepositoryTest` **53** tests (8 new),
`JobDetailsRepositoryTest` **47** (2 new), `CustomersApiContractTest` **3** (new),
`CustomerPermissionsUiStateTest` **14** (3 new). That task compiles the whole unit-test source set, so it is
also what proves the six repository doubles above still satisfy the interface. `cd android && ./gradlew
assembleDebugAndroidTest` PASS, so `CustomerDetailScreenTest`'s moved fixture and the device-test double
compile into the androidTest APK. Android lint is **not run here**: the affected application's full lint
belongs to Tier B at feature completion (`qa.md` §3.1, `dev.md` §18). No `adb` and no device or emulator
command was issued (`qa.md` §7.3), so the device tests themselves remain the product owner's.

**A limitation recorded rather than decided.** With these DTO shapes a member left at its default is absent
from the request, so an edit **cannot express "clear this value"** for an optional member — the same shape
`UpdateCustomerRequest` and `UpdatePropertyRequest` already use, and a difference item 6's edit form will
have to respect (it can state a value and it can leave one alone). The API can express it (`null` clears),
so a form that must clear an optional contact field is a DTO-shaping decision for that item rather than
something this layer silently invents (`BR-042`).

**Item 6 — done 2026-09-18 (Android Customer Detail and the contact destination).** The contacts card is
drawn, the form that maintains it exists, and the removal is confirmed. What landed:

- **`CustomerDetailScreen.kt`** gained the Contacts card (`ADR-022` D6): a `SectionLabel` with the count
  the API returned, an `InfoCard` whose rows are the backend's own order (primary first, then
  oldest-first), the **Primary** badge an archived Property wears, and each person's own phone and email
  as tappable `CustomerContactLine`s through `dialIntent` / `mailIntent` / `startContactIntent` — the
  affordances the customers list already has. A value the office never recorded is left out rather than
  drawn as a row announcing its absence (`BR-012`). The card is drawn for every session that may read the
  customer (contacts add no capability — `ADR-022` D4); **Edit** and **Remove** are drawn on
  `customers.contacts.edit` / `.remove` and **Add more contact persons** on `.create`, each from its own
  code and never from `customers.edit` (`BR-007`, `BR-011`). A customer with no contacts gets a localized
  empty state rather than an empty card.
- **`RemoveContactDialog`** confirms the removal before it is taken, names the person, and states that the
  record is kept — the removal is soft (`BR-067`, `ADR-022` D8). The confirmation is remembered as the
  contact's **id** and resolved from the detail, so it survives a configuration change and always names
  the row the backend last reported.
- **`AddContactUiState` / `AddContactViewModel` / `EditContactViewModel` / `AddContactScreen`** are the
  Add/Edit Contact destination, the Add Property pair's sibling: one shared form (four fields plus the
  primary row), two ViewModels, one route each. The form captures exactly what `BR-095` gives a contact —
  first name, last name, its own phone and email, and the primary flag — and no `role`, billing or
  job-contact field, because no rule defines those and no client captures them in this slice (`BR-042`,
  `ADR-022` D5).
- **The primary flag states a promotion and nothing else.** A create may state it; an edit states
  `isPrimary: true` only when the contact does not already hold the flag. The API documents
  `isPrimary: true` — which promotes a contact and clears the customer's previous primary in one
  transaction — and defines **no un-promotion**, so the form's row is disabled for a contact that already
  holds it and no un-promotion was invented (`BR-042`, `BR-095`, `ADR-022` D9).
- **Edit Contact reads through the customer detail projection**, because the API exposes no single-contact
  route (`docs/api/customers.md` §5.2): `EditContactViewModel` reads the customer and takes the contact
  from the `contacts` array the projection already carries under `customers.view` (`ADR-022` D4). A contact
  the projection does not carry — one removed, or one that never belonged to the customer — is reported as
  `NOT_FOUND` rather than guessed at (`BR-042`).
- **`RemoveContactViewModel`** is scoped to the CUSTOMER_DETAIL destination instance: the row stays exactly
  as the API last described it while the removal is in flight, the removal states the version the row was
  read with, a second tap cannot become a second operation, and the destination re-reads the customer
  (`reloadCustomerDetail`) only once the API accepts it (`BR-001`, `BR-031`, `BR-032`, `BR-033`).
- **`ServoraNavHost.kt`** gained `CUSTOMER_ADD_CONTACT` / `CUSTOMER_EDIT_CONTACT`, their argument lists,
  their top-bar titles with the customer as the context line, and the Customer Detail destination's
  contact wiring (permissions, removal session, re-read on acceptance). `addContactViewModel`,
  `editContactViewModel` and `removeContactViewModel` are constructed in `MainActivity`, passed through
  `AuthFlowScreen` and `ServoraHomeScreen`, and reset when the session ends (`BR-001`).
- **Edit Customer keeps contacts read-only** (`ADR-022` D5): `EditCustomerUiState` carries the contacts the
  same read already returned, the form draws them as values without actions, and the hint says they are
  managed from the customer detail screen.
- **One refusal surface, not two.** `PropertyDetailScreen`'s `ActionAttention` was made `internal` with a
  per-caller test tag, so a refused contact removal is reported the same way a refused Property lifecycle
  action is, instead of a second copy of the same surface. `CustomersFailureMessage.kt` gained
  `contactMessageRes()`, so a contact write's refusal is explained in the record's own vocabulary rather
  than the Properties copy (`BR-028`, `BR-041`).
- **Every new label ships in English and French** (`BR-028`): the card, the form, the removal dialog and
  the contact failure copy.
- **`docs/design/android-design-system.md`** gained the **Customer detail — the contacts card** section
  the contract documentation points to for the visual detail (`docs/api/job-details.md` §3.3).
**One fixture had to change, and the test it belonged to with it.** `CustomerDetailScreenTest`'s contact
carried the customer's own phone and email, which was invisible while the screen drew only the primary
contact's name; the card makes a person's own phone and email and the customer's general line two facts on
one screen, so the fixture now gives the contact its own values and the test that asserted them is
`showsTheCustomerIdentityAndItsOwnContactDetails`.

**The DTO-shaping limitation item 5 recorded is respected rather than worked around.** The edit form can
state a phone or an email and it can leave one alone: a member left out of the request is left as the
contact holds it, so a **cleared** optional field is a no-op for that member. The API can express clearing
(an explicit `null`) and this client's wire shape cannot, which is asserted by a test rather than left
implicit; giving the form that expression is a DTO-shaping decision for a later slice (`BR-042`).

**Verification (Tier A, `qa.md` §3.1 — the Android application, its `ui.customers` package and its
device-test sources).** `cd android && ./gradlew testDebugUnitTest --tests
'com.servora.android.ui.customers.*' assembleDebugAndroidTest` **PASS** — 132 tests, 0 failures, 0 errors,
of which the three new classes are `AddContactViewModelTest` 12, `EditContactViewModelTest` 13 and
`RemoveContactViewModelTest` 9; the existing `CustomersRepositoryTest`, `CustomersApiContractTest` and
`CustomerPermissionsUiStateTest` were unchanged by this item. That invocation compiles the unit-test source
set and the androidTest sources, so it is also what proves `CustomerDetailScreenTest`,
`EditCustomerScreenTest`, `AddContactScreenTest` and `ServoraHomeNavigationTest` still compile against the
changed signatures. Android lint and the full suites are **Tier B** at feature completion (`qa.md` §3.1,
`dev.md` §18). No `adb` and no device or emulator command was issued (`qa.md` §7.3), so the device tests
themselves remain the product owner's.

**Manual QA runbook for the product owner (item 6).** On a debug build against the local API:
1. Sign in as a Manager and open a customer that has a contact person (a seeded COMPANY customer does).
2. The contacts card is between the customer's own phone/email card and Properties: the primary contact
   first, its **Primary** badge, and the person's own phone and email. Tap the phone (the dialer opens) and
   the email (the mail client opens).
3. Press **Add more contact persons**: fill first and last name, then a phone and an email, leave
   **Primary contact** unchecked, and save. The customer returns with the new person listed after the
   primary one.
4. Open that person's **Edit**, tick **Primary contact**, and save: the new person becomes primary and the
   previous one loses the badge — the customer is never left with two.
5. Open the primary contact's **Edit** again: the **Primary contact** row is drawn checked and disabled
   (the API defines no un-promotion).
6. Press **Remove** on a contact: the confirmation names the person; confirm. The row disappears and the
   list is what the API now reports. Removing a primary leaves the customer with **zero** primary contacts
   and no other row is promoted.
7. Open **Edit Customer**: the contacts are listed read-only with the hint that they are managed from the
   customer detail screen.
8. With `customers.contacts.create` withheld, **Add more contact persons** is absent and the card still
   shows the contacts; with `.edit`/`.remove` withheld, the row's actions are absent (`BR-007`).
9. Airplane mode: the card still shows the contacts (the read is served from the working set, with the
   last-reported notice) and a contact write reports that it could not reach the server — nothing is queued
   (`ADR-022` D7).

Needs particular attention: the removal confirmation against a version that moved on (open the edit form in
one session and remove the contact from another, then save the edit — the refusal must be reported, not
applied), and the primary badge after a promotion (`BR-032`, `BR-095`).

**Manual QA runbook for the product owner (item 8 — the effective primary contact and the folded numbers).**
On a debug build against the local API:
1. Open a **company** customer that has a contact person flagged primary, then open one of that customer's
   Jobs.
2. The Job card's **Phone** row states that contact person's number and wears the **Primary** badge. The
   **Other contacts** heading below it is closed, and neither the customer's own number nor the other
   people's details are anywhere on screen.
3. Tap the **Other contacts** heading: it opens and reveals the customer's own number and the other contact
   persons (name, number, email where one is recorded). Tap it again: it closes.
4. Remove the flagged contact person on Customer Detail (or promote a different one), then reopen the Job:
   the **Phone** row states the effective primary's number with the badge, and every other number is folded
   under **Other contacts** — including the customer's own line when a contact person is primary.
5. With a customer whose contacts are all non-primary: the **Phone** row states the **customer's own**
   number and wears the badge, and every contact person is folded away.
6. Open a customer with no contact persons at all: the card shows one number and **no** Other-contacts
   heading.
7. On Customer Detail, the badge is on the customer's own phone line exactly while no contact person is
   listed as primary, and on that person's row otherwise — never on both, never on neither.
8. Airplane mode: the same numbers and the same folded state, served from the working set with the
   last-reported notice (`BR-013`).

**Item 8 — done 2026-09-18 (the Job Details customer block, reshaped by the effective-primary decision).**
Product ownership decided what "primary" means when no contact person holds the flag, and how the Job card
presents the numbers that follow from it. That decision is `ADR-022` **D10**, and this item implements it.

- **`BR-095`** gained the effective-primary rule, the marker rule and the collapsed presentation, and
  **`BR-092`** gained "which of them is primary" — each with its `BR-040` change record — and `ADR-022` gained
  D10, which records the decision, its rationale, its corner cases and what it does not change.
- **The field read reports the flag.** `JobDetailsContactPersonDto` gained `isPrimary`, taken from the
  contact row's own column. A list ordered primary-first cannot distinguish "the first entry is flagged"
  from "no entry is flagged", so without the flag the Job card could not resolve the effective primary at
  all (`BR-041`). No schema change, no new capability, no new route and no new authorization question: the
  table has always held the column and the office read has always reported it.
- **Android**: the DTO and the domain model carry `isPrimary` (defaulted `false`, so a working-set row an
  earlier build wrote reads as "nothing is flagged" — which is the same thing the missing field describes,
  `offline-first-architecture.md` §10); `JobDetailsOverviewModel.kt` gained `contactSummary(customerName)`,
  the **one** place the effective primary is resolved, with D10's corner cases decided there rather than in
  a composable; `JobDetailsOverviewScreen.kt` draws the primary phone row with the badge and the
  **N other contacts** disclosure beneath it; `ServoraCards.kt` gained the shared `ContactPrimaryBadge`
  (moved out of the customer detail screen, so one badge has one definition, `BR-041`); and
  `CustomerDetailScreen.kt` marks the customer's own phone line when nothing is listed as primary.
- **Copy**: the disclosure's heading plural and its show/hide labels, in English and French (`BR-028`).
- **Tests**: `JobDetailsRepositoryTest` (the flag mapped, and an answer without it), `JobDetailsOverviewModelTest`
  (the four resolution cases), `JobDetailsOverviewScreenTest` (the row, the badge, the folded numbers, the
  expansion, and no disclosure when there is nothing to fold) and `CustomerDetailScreenTest` (the customer's
  own line marked only while nothing is listed as primary).

**The retired screen is left untouched, deliberately.** `JobDetailsScreen.kt` is the frozen previous
presentation of the destination (`docs/tracker/044-job-details-job-visit-separation.md`): it draws no
contact rows, so restoring it restores the presentation that predates this item, and nothing else reads its
customer block.

**Verification of item 8.** The change touches the Android application **and the Job Details field read's
contract**, so both affected applications are checked. The product owner asked for a light run on this
machine (`dev.md` §18), so only the commands below were executed and the full sweep is deferred.

- **API** — `npm run typecheck`: only the two **pre-existing** `test/technician-home.e2e-spec.ts` errors
  351/354, neither in a file this change touches (`test/technician-home.e2e-spec.ts` is untouched).
  `npx vitest run src/jobs/job-details.dto.spec.ts` **PASS** — 13 tests, including the case that now pins
  the two-contact projection, its exact key set and the flag. `npm run test:e2e --
  test/job-details.e2e-spec.ts` **PASS** — 29 tests. That run caught one expectation the change made stale —
  the office-capability block at `test/job-details.e2e-spec.ts:1168` compared the contact exactly and did
  not carry the new member — which was corrected and re-run green; the field caller's block and its key set
  were updated for the same reason.
- **Android** — `cd android && ./gradlew compileDebugKotlin` **PASS** and `cd android && ./gradlew
  assembleDebug` **PASS** (`app-debug.apk` built). The targeted unit tests
  (`testDebugUnitTest --tests 'com.servora.android.ui.jobs.*' --tests 'com.servora.android.data.jobs.*'`) and
  the device-test source compile (`assembleDebugAndroidTest`) were **NOT RUN against the final sources**: an
  earlier invocation had reached `compileDebugUnitTestKotlin` and `compileDebugAndroidTestKotlin`
  successfully, then the machine restarted and its log was lost, so no pass is claimed for it here.
  **Command for the product owner, when the machine is free:** `cd android && ./gradlew testDebugUnitTest
  --tests 'com.servora.android.ui.jobs.*' --tests 'com.servora.android.data.jobs.*' assembleDebugAndroidTest`.
- **Not run, and reported rather than assumed**: `npm run build` for the API (`npm run typecheck` runs the
  same compiler pass with `--noEmit`), the full API lint/unit/e2e suites, Android lint, and every device
  test. Android lint and the full suites are **Tier B** at feature completion (`qa.md` §3.1).
- **No `adb`** and no device or emulator command was issued (`qa.md` §7.3), so the Compose tests this item
  adds are for the product owner's device to run.

**Hygiene.** `make android-stop` was run after the last Gradle command, so the Gradle and Kotlin daemons the
build started are stopped (`dev.md` §18). The foundation stack was left running and untouched; the e2e run
applied no migration, because this change has none. The two log files the verification wrote under `/tmp`
were removed, and `git status` shows only the intended changes.

| # | Item | Deliverables | Verification |
| --- | --- | --- | --- |
| **1** | **The rule and the decision record** (documentation only, no code) | `BR-095` in §9 of `.clinerules/Business Rules.md` in the document's standard shape; the `BR-040` change records amending `BR-023` and `BR-092`; `docs/decisions/022-customer-contact-persons.md` recording D1–D9 and closing `ADR-021` open questions 1 and 3 | **DONE 2026-09-18** — documentation only, nothing to execute. Reviewed against `BR-001`, `BR-006`, `BR-007`, `BR-009`, `BR-023`, `BR-033`, `BR-040`, `BR-041`, `BR-042`, `BR-067`, `BR-085`, `BR-089`, `BR-092` |
| 2 | **Schema, catalogue and seed** | Migration `0014_customer_contact_persons.sql` (the partial unique index, `version` and its check, `removed_at`, `removed_by_membership_id` FK, the paired removal-state check, three permission rows with bilingual name and description, the default-Manager grants), `api/src/database/schema.ts`, `meta/0014_snapshot.json` and `meta/_journal.json`, the seed fix of finding 2 plus the three `PERMISSION_TEMPLATES` entries, `api/src/auth/permissions.ts` (`CUSTOMER_CONTACT_PERMISSIONS`), its spec, and the two existing fixtures the row type moved | **DONE 2026-09-18 — Tier B for the API, PASS** (`qa.md` §3.1: a schema and migration change escalates ahead of the item boundary; the API is the only affected application). `npm run typecheck` PASS for this change (only the two pre-existing `test/technician-home.e2e-spec.ts` errors 351/354 recorded by tracker 041 remain; before the fixtures moved with the row type it reported two new ones, in `customer-contact.dto.spec.ts` and `customer-detail.dto.spec.ts`). `npm run lint` PASS — 0 warnings, 0 errors, 206 files. `npm run build` PASS. `npm test` PASS — 55 files, 495 tests. `npm run test:e2e` PASS — 22 files, 364 tests against the running foundation stack, which includes `customers-contacts.e2e-spec.ts` and `foundation-schema.e2e-spec.ts`, so the new index is exercised by the fixtures that already existed and **no fixture produced two primaries for one customer**. `npx vitest run src/auth/permissions.spec.ts src/customers/customer-contact.dto.spec.ts src/customers/customer-detail.dto.spec.ts` PASS — 23 tests. `npm run db:migrate` applied `0014` to the local development database, which was rolled back once for `0014` alone and re-applied from the corrected file (the paragraph above), and the recorded hash equals the file's `sha256`. Verified against that database with `psql`: the partial index exists as `CREATE UNIQUE INDEX customer_contacts_primary_unique … WHERE is_primary`, the three columns and both checks are present, the three catalogue rows carry English and French, the default **Manager** roles hold all three and the default **Technician** roles hold none (`MANAGER` 3, `TECHNICIAN` 0), a **second** primary for one customer is refused with `23505`, `version = 0` is refused, a removal recording one column without the other is refused, and no customer in the database has two primaries. `npm run db:seed` PASS — 11 seeded COMPANY customers hold 11 contact rows, all primary, and 9 seeded INDIVIDUAL customers hold **0**, which is finding 2's fix. Contract documentation — `docs/api/customers.md`, `docs/api/job-details.md`, `docs/domain/foundation-domain-model.md` §8, `docs/architecture/offline-first-architecture.md` §12 — is Item 9's and was not touched here |
| 3 | **API writes** | `POST /customers/:id/contacts` extended (the contact's own phone/email; `isPrimary = true` clears the previous primary in the same transaction) and re-authorized to `customers.contacts.create`, `PATCH /customers/:id/contacts/:contactId`, `DELETE /customers/:id/contacts/:contactId`, the DTOs (the contact's `version` on the wire, a parse for the edit and one for the removal, `expectedVersion` required), the service methods, the transactional change-primary rule, the errors `CONTACT_NOT_FOUND` and `CONTACT_VERSION_CONFLICT` | **DONE 2026-09-18 — Tier A, PASS** (the paragraph above records the detail). `npm run build` PASS. `npm test` PASS — 55 files, 510 tests. `npx vitest run src/customers/customer-contact.dto.spec.ts src/validation/domain-validation.spec.ts` PASS — 55 tests. `npm run test:e2e -- test/customers-contacts.e2e-spec.ts test/customers.e2e-spec.ts test/customer-detail-projection.e2e-spec.ts test/foundation-schema.e2e-spec.ts` PASS — 4 files, 53 tests. `npm run typecheck` reports only the two pre-existing `test/technician-home.e2e-spec.ts` errors 351/354 that item 2's row already records |
| 4 | **API reads and authorization tests** | The office detail's ordering (primary first, then oldest-first) and its exclusion of removed contacts; the Job Details block's contacts under the existing second question; the projection's unit specs; the e2e authorization matrix (`401`/`403`/`404`, tenant scope, one-primary enforcement, change-primary atomicity, soft removal hidden from both reads, the field block present/absent, no billing field on the wire). **Escalated to Tier B** — the item crosses a contract and an authorization boundary | **DONE 2026-09-18 — Tier B, PASS** (the paragraph above records the detail). `npm run lint` PASS — 0 warnings, 0 errors, 206 files. `npm test` PASS — 55 files, 512 tests. `npm run test:e2e` PASS — 22 files, 379 tests, including `test/customers-contacts.e2e-spec.ts` (17) and `test/job-details.e2e-spec.ts` (29). `npm run build` PASS. `npm run typecheck` reports only the two pre-existing `test/technician-home.e2e-spec.ts` errors 351/354 |
| 5 | **Android data layer** | The contact DTOs and domain model (create, update and removal requests; `contacts[]` on the Job Details DTO), the repository mapping and the three operations, the three `Permission` constants and the permissions UI state, the JVM tests for the mapping | **DONE 2026-09-18 — Tier A, PASS** (the paragraph above records the detail, including the `@HTTP(method = "DELETE", …, hasBody = true)` declaration the removal route needs and the contract test that guards it). `cd android && ./gradlew compileDebugKotlin` PASS. `cd android && ./gradlew testDebugUnitTest --tests 'com.servora.android.data.customers.*' --tests 'com.servora.android.data.jobs.*' --tests 'com.servora.android.ui.customers.CustomerPermissionsUiStateTest'` PASS — 0 failures, 0 errors, with `CustomersRepositoryTest` 53, `JobDetailsRepositoryTest` 47, `CustomersApiContractTest` 3 and `CustomerPermissionsUiStateTest` 14. `cd android && ./gradlew assembleDebugAndroidTest` PASS. Android lint and the full suites are **Tier B** at feature completion; no `adb` or device command was issued |
| 6 | **Android Customer Detail and the contact destination** | The Contacts card (D6), the tappable phone and email, the per-row edit and remove actions drawn on their capabilities, "Add more contact persons", a new Add/Edit Contact destination mirroring `ServoraRoutes.customerAddProperty` / `AddPropertyScreen` / `AddPropertyViewModel`, the removal confirmation, and Edit Customer's read-only contacts with its hint | **DONE 2026-09-18 — Tier A, PASS** (the paragraph above records the detail). `cd android && ./gradlew testDebugUnitTest --tests 'com.servora.android.ui.customers.*' assembleDebugAndroidTest` **PASS** — 132 tests, 0 failures, 0 errors, including the new `AddContactViewModelTest` (12), `EditContactViewModelTest` (13) and `RemoveContactViewModelTest` (9). That invocation compiles the androidTest sources too, so `CustomerDetailScreenTest`, `EditCustomerScreenTest`, `AddContactScreenTest` and `ServoraHomeNavigationTest` are proven to build against the changed signatures. Android lint and the full suites are Tier B at feature completion; no `adb` or device command was issued |
| 7 | **Android Add Customer** | The primary-contact block gains its own phone and email (D2); **no** inline additional contact rows (D5); the existing "primary contact could not be saved" handling is kept as it is | **Tier A:** `./gradlew testDebugUnitTest --tests 'com.servora.android.ui.customers.*'` |
| 8 | **Android Job Details and the effective primary contact** (`ADR-022` D10) | The field read's `isPrimary` on the contact person (API DTO and projection, Android DTO and domain model), `contactSummary(customerName)` in `JobDetailsOverviewModel.kt`, the Job card's primary phone row with the shared `ContactPrimaryBadge` and the **N other contacts** disclosure that starts closed, the customer's own phone line marked on Customer Detail, the moved badge component, the EN/FR plural and labels, and the four test classes | **DONE 2026-09-18.** API: `src/jobs/job-details.dto.spec.ts` 13 PASS, `test/job-details.e2e-spec.ts` 29 PASS (one stale office-block expectation corrected and re-run), `npm run typecheck` clean apart from the two pre-existing `test/technician-home.e2e-spec.ts` errors. Android: `compileDebugKotlin` PASS and `assembleDebug` PASS. **NOT RUN:** the Android unit tests, `assembleDebugAndroidTest`, API build/lint/full suites and Android lint — the product owner asked for a light run; the API and Android sections above record exactly what ran. Long command for the product owner, when the machine is free: `cd android && ./gradlew testDebugUnitTest --tests 'com.servora.android.ui.jobs.*' --tests 'com.servora.android.data.jobs.*' assembleDebugAndroidTest` |
| 9 | **Contract documentation and the tracker** | **`docs/api/customers.md` §2 and §5.2 landed with Item 3, and §3.2's read detail, `docs/api/job-details.md` §2/§3.1/§3.2/§3.3/§5 and the reading of that file's open questions 7 and 8 landed with Item 4** — this item verifies all of it against the implementation; what is still outstanding: `docs/domain/foundation-domain-model.md` §8, `docs/architecture/offline-first-architecture.md` §12 (the online-only statement for contact writes), the README pointer, and this tracker's status | Documentation review against the implemented behaviour |

Every item ships its own English and French copy (`BR-028`); no item defers localization to another.

## Carried open questions (nothing here is implemented as an assumption)

1. **A removed-contacts or audit view for the office.** Soft removal (D8) keeps the record, its actor and its
   timestamp, but no screen reads them back. `BR-033`'s detailed audit rules remain open.
2. **The `customers.*` capability set's re-review** — `ADR-021` open question 4. The set gains three more codes
   here, so the question grows rather than closes.
3. **`role`** (free text, e.g. "Site manager") — modelled and returned by the API, deliberately not captured or
   edited by any client in this slice.
4. **`isBillingContact` and `billingEmail` / `billingPhone`.** `BR-023` names billing contacts but no rule
   defines what makes one, whether it must be unique, or what it changes. Nothing is implemented for them.
5. **Contacts outside Customer Detail** — the customers list does not show or manage them, which was not
   requested.
6. **Contact uniqueness.** No uniqueness constraint is imposed on a contact's email or phone
   (`foundation-domain-model.md` §8); two contacts may share an address and two customers may share one.
   Unchanged.
7. **A removal reason.** D8 requires none. If a compliance or audit requirement appears, it is a product
   decision and an API change rather than something an implementation adds.
8. **Whether the customer header's own phone and email should eventually be reduced** now that contacts carry
   their own. D2 keeps it as the customer's general line.
9. **Two promotions of *different* contacts at the same moment.** Every contact write serialises on the
   customer's row, so the two operations can neither corrupt the primary flag nor collide with
   `customer_contacts_primary_unique`; the later promotion simply supersedes the earlier one, and the
   earlier caller is told its own promotion succeeded. Nothing records who was primary before, because no
   contact history is written (`BR-095` names none). Whether a promotion should instead be **refused** when
   the primary flag has changed since the caller read it would need a customer-scoped token — the contacts'
   own versions do not cover a sibling's flag — and that is a product/design question for the
   permission-and-concurrency review `ADR-021` open question 4 already carries, not something this slice
   invents.
10. **Clearing an optional contact field from the edit form.** The API clears `email` or `phone` on an
    explicit `null`, while this client's wire shape leaves a member it does not state **absent** — so the
    form can state a value and it can leave one alone, and clearing a field is a no-op for that member
    (asserted by `EditContactViewModelTest`). Letting the form express clearing needs a DTO shape that
    distinguishes "not stated" from "stated null", which is a later change rather than something this slice
    invented (`BR-042`, `ADR-022` D9).

## Verification and hygiene plan for the slice

- **Tier A per item, Tier B when the slice completes** (`qa.md` §3.1): the full lint, unit suite, e2e suite,
  build and typecheck of **both** affected applications —
  `make api-lint && make api-test && make api-test-e2e && make api-build` and
  `make android-lint && make android-test && make android-build`, plus
  `cd android && ./gradlew assembleDebugAndroidTest`. Items 2 and 4 escalate earlier because they touch the
  schema, a shared contract and an authorization boundary.
- **The agent runs no `adb` and no device or emulator command** (`qa.md` §7.3). Android device-test sources are
  compiled, and physical-device QA is the product owner's, with a written runbook (`qa.md` §7.2).
- **After the last Android command of an item**, `make android-stop`; `make tidy` before reporting
  (`dev.md` §18, `qa.md` §7.4).
- **Documentation is part of each item**, and this tracker's rows are updated with the exact commands and their
  results as each item lands (`qa.md` §16).

## Hygiene for this tracker

**Item 1 (documentation only).** No code, migration, build or test was written or run, so no Gradle or Kotlin
build daemon was started and none needed stopping, and no `adb` or device command was issued. The foundation
stack (`make up`) and its volumes were not touched. `git status` shows this one new file as this task's change;
the uncommitted work already present in the tree (trackers 038–046 and their code) belongs to previous slices
and was not modified.

**Item 2 (schema, catalogue and seed).** Only the API was touched, so **no Gradle or Kotlin build daemon was
started** and `make tidy` reported `No Gradle daemons are running.`; no `adb` or device or emulator command
was issued. No file in `android/` was changed. The foundation stack was used, not restarted: the migration was
applied to the local development database through `npm run db:migrate` (the documented path, `dev.md` §6 — no
schema push), rolled back once for `0014` alone and applied again from the corrected file as the paragraph
above records, and the development data was refreshed with `npm run db:seed`. What changed in the local
database is this item's own objects — the three `customer_contacts` columns and their checks, the partial
unique index, the three catalogue rows and the three Manager grants — plus the disappearance of the seeded
individual customers' contact rows, and the `0014` bookkeeping row. The compose services, the database and
MinIO volumes and the object storage were not touched. The migrations folder was backed up to
`/tmp/drizzle-backup` while the snapshot chain was checked; the backup and the scratch file used to rewrite the
migration were removed, so nothing of the task remains outside the repository. `git status` shows this item's
changes only in the files the item table lists, alongside the uncommitted work of trackers 038–046, which
belongs to previous slices: `api/drizzle/migrations/meta/_journal.json` was already modified by tracker 042's
`0013` entry, so its diff now carries both entries. `make tidy`'s report after the item was
`Java build daemons: none`, `Node/API processes: none`, and the three foundation containers running — the
product owner's stack, untouched. The full API unit and e2e suites were run for this item and finished; a
`pgrep` for `vitest` and `nest start` reports nothing left, and the temporary log file the suite wrote to was
removed.

**Item 3 (the API writes).** Only the API was touched, so **no Gradle or Kotlin build daemon was started**;
`make tidy` after the last API command reported `No Gradle daemons are running.`, `Java build daemons: none`,
`Node/API processes: none`, and the three foundation containers running. No `adb` and no device or emulator
command was issued, and no file in `android/` was changed — Android work starts at item 5. The verification
ran against the product owner's running foundation stack (`make up`), which was **used, not restarted**: each
e2e spec creates its own organization, role, users and customers and deletes what it created in `afterAll`,
so the development database gains nothing persistent from the run, and the tests left no `vitest` process
behind. The `/tmp` Kotlin-daemon logs and `adb.1000.log` present on the machine were not created by this
item and were left alone, as were the compose services, the database and MinIO volumes. `git status` shows
this item's changes only in the files the item table's row 3 and the paragraph above name — the four API
source files, the two API spec files, `api/test/customers-contacts.e2e-spec.ts`, `docs/api/customers.md`, and
this tracker — alongside the uncommitted work of trackers 038–046, which belongs to previous slices. No
scratch file, log or backup was created by this item.


**Item 4 (the API reads and the authorization matrix).** Only the API and documentation were touched, so **no
Gradle or Kotlin build daemon was started** and no `adb` or device or emulator command was issued; no file in
`android/` was changed — Android work starts at item 5. The verification ran against the product owner's
running foundation stack (`make up`), which was **used, not restarted**: every e2e spec creates its own
organization, role, users and customers and deletes what it created in `afterAll`, so the development
database gains nothing persistent from the run. `make tidy` after the last API command reports
`No Gradle daemons are running.`, `Java build daemons: none`, `Node/API processes: none` and the three
foundation containers running — the product owner's stack, untouched by this item, as were the database and
MinIO volumes. No migration was written or applied by this item and no seed was run: item 4 changes reads
only. `git status` shows this item's changes only in the files the item table's row 4 and the paragraphs
above name — `api/src/customers/customers.service.ts`, `api/src/jobs/jobs.service.ts`,
`api/src/jobs/job-details.dto.ts`, `api/src/jobs/job-details.dto.spec.ts`,
`api/test/customers-contacts.e2e-spec.ts`, `api/test/job-details.e2e-spec.ts`, `docs/api/customers.md`,
`docs/api/job-details.md`, and this tracker — alongside the uncommitted work of trackers 038–046, which
belongs to previous slices: `api/jobs/jobs.service.ts`, `api/jobs/job-details.dto.spec.ts` and
`api/test/job-details.e2e-spec.ts` were already modified before this item, which is why their pre-existing
formatting findings are reported above rather than reformatted. The `/tmp` files this item wrote
(`/tmp/e2e-contacts.log`, `/tmp/api-unit.log`, `/tmp/api-e2e.log`, `/tmp/prettier-residual.txt`) were
removed after the runs, and a `pgrep` for `vitest` and `nest start` reports nothing left running.

**Item 5 (the Android data layer).** Only `android/` was touched, and within it only the data layer, the two
domain models, the permission vocabulary and the tests — so **no API file, migration, seed or contract
document was changed**, and the API was not run at all. The Gradle and Kotlin build daemons the verification
started were stopped after the last Android command with `make android-stop` (`1 Daemon stopped`), and
`make tidy` then reports `No Gradle daemons are running.`, `Java build daemons: none`, `Node/API processes:
none` and the three foundation containers running — the product owner's stack, used and not restarted, as
were the database and MinIO volumes. No `adb` and no device or emulator command was issued (`qa.md` §7.3):
the device tests are the product owner's. The `/tmp` logs this item wrote were deleted with the task, as were
the two scratch extractions of Retrofit's own sources under `/tmp` that confirmed how `@DELETE` is parsed and
that `validateEagerly` defaults to off. `git status` shows this item's changes in
`android/app/src/main/java/com/servora/android/`
(`data/customers/CustomerCreateDtos.kt`, `data/customers/CustomerDetailDtos.kt`,
`data/customers/CustomersApi.kt`, `data/customers/CustomersRepository.kt`, `data/customers/CustomersResult.kt`,
`data/jobs/JobDetailsDtos.kt`, `data/jobs/JobDetailsRepository.kt`, `domain/auth/Permission.kt`,
`domain/model/CustomerContact.kt`, `domain/model/JobDetails.kt`,
`ui/customers/CustomerPermissionsUiState.kt`), in the JVM tests (`data/customers/CustomersApiContractTest.kt`
new, `data/customers/CustomersRepositoryTest.kt`, `data/jobs/JobDetailsRepositoryTest.kt`,
`ui/customers/CustomerPermissionsUiStateTest.kt`, and the two new overrides each in
`ui/customers/AddCustomerViewModelTest.kt`, `ui/customers/AddPropertyViewModelTest.kt`,
`ui/customers/CustomersViewModelTest.kt`, `ui/customers/EditCustomerViewModelTest.kt`,
`ui/jobs/CreateJobViewModelTest.kt`), in one device test
(`ui/navigation/ServoraHomeNavigationTest.kt`) and in the one fixture that builds a `CustomerContact` by hand
(`ui/customers/CustomerDetailScreenTest.kt`), alongside this tracker. Seven of those files **were already
modified before this item** by trackers 038–046, which is why `git status` shows more in them than this item's
lines: `Permission.kt`, `CustomerPermissionsUiState.kt`, `CustomerDetailScreenTest.kt`,
`JobDetailsRepositoryTest.kt`, `JobDetailsDtos.kt`, `JobDetails.kt` and `JobDetailsRepository.kt`. None of that
earlier work was reverted, reformatted or otherwise touched.

**Item 6 (Android Customer Detail and the contact destination).** Only `android/` and two documentation
files were touched, and no API file was. The Gradle builds this item ran — `compileDebugKotlin` while the code
was being written, then the two Task runs — started a Gradle daemon and a Kotlin build daemon, which were
**released after the last Android command**: `make android-stop` reported `Stopping Daemon(s)` / `1 Daemon
stopped`, and `make tidy` after it reported `No Gradle daemons are running.`, `Java build daemons: none`,
`Node/API processes: none`, and the three foundation containers (`api`, `minio`, `postgres`) still running —
the product owner's stack, used for nothing by this item and left untouched. No `adb` and no device or
emulator command was issued (`qa.md` §7.3): the device tests were **compiled**
(`assembleDebugAndroidTest`, PASS) and never run, so physical-device QA of the contacts card, the Add/Edit
Contact form and the confirmed removal remains the product owner's (`qa.md` §7.2). The background build logs
this item wrote to `/tmp` were deleted with the task, so nothing of it remains outside the repository. Files
this item changed: `CustomerDetailScreen.kt`, `CustomersFailureMessage.kt`, `EditCustomerScreen.kt`,
`EditCustomerUiState.kt`, `EditCustomerViewModel.kt`, `PropertyDetailScreen.kt` (one visibility change and a
test-tag parameter on `ActionAttention`, as the narrative above records), `CustomersScreen.kt`,
`ui/auth/AuthFlowScreen.kt`, `MainActivity.kt`, `ui/navigation/ServoraNavHost.kt`, `res/values/strings.xml`
and `res/values-fr/strings.xml`; the new `AddContactUiState.kt`, `AddContactViewModel.kt`,
`AddContactScreen.kt`, `EditContactViewModel.kt`, `RemoveContactUiState.kt` and `RemoveContactViewModel.kt`;
the unit tests `ui/customers/AddContactViewModelTest.kt`, `EditContactViewModelTest.kt` and
`RemoveContactViewModelTest.kt`; the device tests `ui/customers/AddContactScreenTest.kt`,
`CustomerDetailScreenTest.kt` (the moved fixture and its test) and `EditCustomerScreenTest.kt`, plus
`ui/navigation/ServoraHomeNavigationTest.kt` (the three new ViewModels and its contact-navigation tests); and
`docs/design/android-design-system.md` with this tracker. Seven of the edited files **were already modified
before this item** by trackers 038–046 and item 5 of this one — `CustomerDetailScreen.kt`,
`CustomersScreen.kt`, `MainActivity.kt`, `ServoraNavHost.kt`, `strings.xml` (both locales),
`CustomerDetailScreenTest.kt` and `ServoraHomeNavigationTest.kt` — which is why `git status` shows more in
them than this item's lines; none of that earlier work was reverted, reformatted or otherwise touched, and no
file was opened in the product owner's editor.

