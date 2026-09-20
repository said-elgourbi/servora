# ADR-022 — Customer contact persons: the one-primary invariant, the capability set and the surfaces

**Status:** Accepted (product-owner decision, 2026-09-18: the decisions below were agreed by product
ownership in the planning session that produced tracker 047, and this record is theirs, kept under the
`BR-040` change procedure)

Date: 2026-09-18
Tracker: `docs/tracker/047-customer-contact-persons.md` — this ADR is its Item 1
Rule produced: `BR-095` (new, §9 Customers), with the `BR-040` change records amending `BR-023` and `BR-092`
Contracts: `docs/api/customers.md` §3.2 and §5.2 (two new write routes, the contact's own phone and email,
the primary rule), `docs/api/job-details.md` §3.2/§3.3 (the contacts inside the existing customer block)
Predecessors: `docs/decisions/021-technician-customer-read.md` (the block this record extends, and open
questions 1 and 3, which it closes), `docs/decisions/012-property-lifecycle-and-permissions.md` and
`docs/decisions/015-evidence-capabilities.md` (the dedicated-capability pattern D4 follows),
`docs/decisions/014-android-offline-engine.md` (the engine the reads travel in, and the posture D7 records)

References: `BR-001`, `BR-002`, `BR-004`, `BR-005`, `BR-006`, `BR-007`, `BR-009`, `BR-011`, `BR-012`,
`BR-013`, `BR-014`, `BR-023`, `BR-028`, `BR-031`, `BR-032`, `BR-033`, `BR-041`, `BR-042`, `BR-056`,
`BR-057`, `BR-067`, `BR-069`, `BR-080`, `BR-085`, `BR-086`, `BR-087`, `BR-089`, `BR-092`;
`Project.md` §9, §10, §13–§15, §31; `dev.md` §6, §7, §10; `qa.md` §3.1, §4.3, §8, §13;
`docs/architecture/offline-first-architecture.md` §12 and §13;
`docs/domain/foundation-domain-model.md` §8.

## Context

`customer_contacts` has existed since the foundation domain model (`api/src/database/schema.ts:387-409`): a
cascade FK to `customers`, `first_name`, `last_name`, `email`, `phone`, `role`, `is_primary`,
`is_billing_contact`, `is_job_contact`, timestamps, and indexes on `customer_id` and `email`
(`api/drizzle/migrations/0000_foundation_domain_model.sql:24-26`, `:116`, `:124`). What has never existed is
the behaviour around it, and the gap is visible from every surface:

- **The invariant is not enforced.** `is_primary` is a bare flag; two rows for one customer may be primary,
  or none at all. `BR-023` said contacts "may be marked primary" and nothing said how many may be.
- **There is one write route.** `POST /customers/:id/contacts`
  (`api/src/customers/customers.controller.ts:239-258`), carrying `firstName` and `lastName` only — no phone,
  no email — under `customers.edit` as an interim authorization (`docs/api/customers.md:428-432`). A contact
  cannot be edited, cannot be removed and cannot be made or unmade primary; `docs/api/customers.md:433`
  records all three as open questions.
- **The office cannot see them.** The customer detail's `contacts` array is returned
  (`api/src/customers/customers.service.ts:641`, newest-first) but Android draws only the primary contact's
  **name** under the company name (`CustomerDetailScreen.kt:212-221`); the tappable phone and email on that
  screen are the *customer's own* (`:264-294`). Edit Customer has no contact field at all.
- **The technician cannot reach them.** `BR-092`'s field read deliberately excluded contacts
  (`ADR-021` D3, open question 1), and the Job Details customer block draws the customer's own phone and
  email as non-tappable rows (`JobDetailsScreen.kt:1082-1108`).

Product ownership decided on 2026-09-18 that a customer's contact persons are worth making usable in full:
who they are, their own phone and email, which one is primary, and a technician's ability to reach them
before knocking on the door. The behaviour is undefined by the business rules as they stood, so it is
recorded as `BR-095` (with the `BR-040` change records amending `BR-023` and `BR-092`), and this ADR records
the decisions behind it.

The client scope is **Android only**: the Angular application does not exist in this repository yet, so
`BR-002`'s consistency requirement is a later slice (`docs/tracker/047-customer-contact-persons.md`).

## Decisions

### D1 — The primary contact, and the invariant that holds it

**Decided:** a customer may have any number of contacts and none is required (`BR-023`); **at most one
contact per customer is primary**, enforced by the database as a partial unique index —
`customer_contacts_primary_unique ON customer_contacts (customer_id) WHERE is_primary`. A COMPANY customer's
primary is the contact person recorded for it. An INDIVIDUAL customer is their own primary: no
`customer_contacts` row is written for them and none is expected. **Zero primary contacts is a legal state.**

**Why the index rather than a service check.** The repository already holds two indexes of exactly this
shape — `visit_technicians_lead_unique … WHERE role_code = 'LEAD'`
(`api/drizzle/migrations/0003_purple_black_tarantula.sql:366`) and `customer_addresses_default_service_unique
… WHERE type = 'SERVICE' and is_default = true` (`0004_woozy_spitfire.sql:142`) — and `BR-001` plus
`Project.md` §17 put an important invariant in the database rather than in the code path that happens to
remember it. A check that lives only in the create route would hold for that route and not for a later one.

**Why an individual holds no contact row.** The person *is* the customer. Writing them into
`customer_contacts` as well would create two writable copies of one fact and need new "which one wins"
rules, which `Project.md` §9 forbids and `BR-041` rules out for a shared concept. It also keeps a primary
row meaningful: a primary row always means "the person to speak to at this company".

**Why zero primary is legal.** Contacts are optional, and a company that has never recorded one must not be
in an invalid state. Nothing is auto-created, and nothing promotes a contact to primary on its own.

### D2 — A contact owns its own details

**Decided:** a contact carries its **own** first name, last name, phone and email; those four fields are the
feature. The customer header's `phone` and `email` remain the **customer's general line** and are not a
contact's details.

This changes what Add Customer does today: the primary-contact block currently creates the contact with
`firstName`/`lastName` only and deliberately leaves phone and email absent (`CustomerCreateDtos.kt:77-94`,
`AddCustomerViewModel.kt:271`), while the customer's own phone and email are captured on the header. The
block now captures the contact's own phone and email; the header's optional phone and email stay optional
and unchanged (`BR-023`).

**Why not reuse the header fields for the contact.** The header value is the organization's general line for
the customer — often an office or dispatch number — while the contact is the person. Collapsing them would
lose a distinction the model already draws and would leave the contact's own number unrecordable.

### D3 — What a technician may read

**Decided:** the technician reads the customer's contacts through the **existing** assigned-customer read
(`BR-092`) — the same assignment scope, the same second authorization question on `GET /jobs/:id`, the same
block. **No new route and no new capability.** **All** of the customer's contacts are readable, because the
point of the read is "who do I call before I knock". **Excluded:** `billingEmail`, `billingPhone` and the
`isBillingContact` flag. The block's existing `email`, `phone` and `notes` stay byte-for-byte as they are, so
the addition is backward compatible for every existing client.

**Why all contacts, and inside the block.** `ADR-021` D2's shape is what keeps one authorization answer: the
contacts ride inside the block, so a caller who is not admitted receives `null` for all of it rather than a
partial payload that answers a second question. Which contact is primary is not filtered — the technician
sees the same list the office sees, minus the billing fields — because the field rule for a missing value is
to leave it out rather than announce its absence (D6).

### D4 — Capabilities: a dedicated contact set, not `customers.edit`

**Decided:** contact writes are authorized by three new codes, each granted to the default **Manager** role
and none of them to the default Technician role:

| Code                        | Route                                        | Default grant |
| --------------------------- | -------------------------------------------- | ------------- |
| `customers.contacts.create` | `POST /customers/:id/contacts`               | Manager       |
| `customers.contacts.edit`   | `PATCH /customers/:id/contacts/:contactId`   | Manager       |
| `customers.contacts.remove` | `DELETE /customers/:id/contacts/:contactId`  | Manager       |

**No read capability is added.** The office read stays under `customers.view` (the `contacts[]` array is
already part of the customer projection) and the field read reuses `customers.view_assigned`, which the
default Technician role already holds (`ADR-021` D1) — so `BR-009` is **not** amended, and no default
Technician grant changes.

**Why a dedicated set.** This is `BR-085`'s Property position applied to a different asset: a role may
legitimately maintain a customer without being trusted to change or delete the people the company calls.
`customers.edit` lumps "correct the billing address" and "remove the site manager's phone number" into one
capability, and `BR-006` requires a capability to name one thing a role can plausibly hold on its own.

**Why the codes are shaped this way.** They follow the resource-prefixed `resource.action` convention the
catalogue already uses (`customers.view`, `properties.archive`, `evidence.photo.remove` — `BR-006`,
`BR-041`), carry bilingual catalogue rows like every permission (`BR-005`), and stay grantable and revocable
per role and per member: authorization never depends on the Manager role or on any role name (`BR-004`,
`BR-006`). `customers.edit` no longer authorizes any contact write.

### D5 — Where contacts are managed

**Decided:** **Customer Detail is the canonical surface** — add, edit, remove and change the primary
contact all happen there. **Add Customer records the initial primary contact only**, with no arbitrary
additional contact rows created inline. **Edit Customer keeps contacts read-only**, with the hint-and-link
pattern the form already uses for Properties ("Properties are managed from the customer detail screen").

**Why not a batch of writes during creation.** The create form records one optional part per write today.
Turning it into a batch of contact writes would make a partially successful create possible — the customer
exists, some of its contacts do not — and the operator would have no screen from which to repair it. Once
the customer exists, Customer Detail manages the rest.

### D6 — The contacts section (layout approved in place of a Figma design)

**Decided:** a **Contacts card** on Customer Detail, the primary contact first and the others after it.
Each row states the person's name, their phone and their email, with phone and email **tappable** — dial and
mail intents, reusing `dialIntent` / `mailIntent` / `startContactIntent`
(`CustomersScreen.kt:1456-1472`). Each row carries its edit and remove actions, drawn only when the member
holds the capability that would perform the write (`BR-007`, `BR-011`); the card carries an **"Add more
contact persons"** action drawn on `customers.contacts.create`; and a customer with no contacts shows a
localized empty state rather than an empty card. On **Job Details** the customer's contacts are drawn under
the customer's own contact details, with the same tappable phone and email, and a field the office never
recorded is left out rather than drawn as a row announcing its absence (`BR-012`, `ADR-021` D4). Every new
label ships in English and French (`BR-028`).

**Why the layout is approved without a design.** No Figma screen draws a contacts section —
`Figma/src/screens/Customers.tsx` draws the New Customer view's "Primary contact" block only — and the
layout above is the smallest one that makes the recorded data usable. Product ownership approved it on
2026-09-18 rather than blocking the slice on a design.

**Why tap-to-call is decided here.** `ADR-021` open question 3 left the affordance to the design system.
It is closed with the affordances the repository already has (`dialIntent` / `mailIntent`) rather than with
a new one, because reaching the person is the whole reason the technician reads a contact.

### D7 — Offline behaviour

**Decided:** **reads need no new store** — the Customer Detail read and the Job Details read are both in the
Room working set (`docs/architecture/offline-first-architecture.md` §12), so contacts travel in payloads that
are already cached, and nothing is added to the offline engine for them. **Writes are online-only**, recorded
as a decision rather than left as an omission: `POST /customers/:id/contacts` accepts no client-generated
idempotency key and no conflict policy has been decided for a create, so the operation may not be queued, and
the new edit and removal routes inherit that. This is the tracker statement
`offline-first-architecture.md` §13 requires of a feature that stays online-only; adopting the outbox for
contacts is a later decision and an API change first (`BR-032`, `BR-042`).

### D8 — Removal is soft

**Decided:** removal writes `removed_at` and `removed_by_membership_id` and takes the contact out of ordinary
views, while the record survives so "who removed this person, and when" stays answerable (`BR-033`). **No
reason is required** and no reason catalogue is invented (`BR-042`). A removal is added history, never a
rewrite of what the record previously held (`BR-067`). Removing the primary contact leaves **zero** primary
and **never promotes another contact automatically** — the authorized user decides, which is `BR-069`'s
position for a removed Lead applied to the same shape of problem.

**Why soft, and why nothing promotes itself.** `BR-089` already accepted "removal is soft … records the
actor, the timestamp" for evidence, and a contact is the same kind of record: a business fact whose removal
someone may need to explain later. Auto-promoting a replacement would silently change who the organization
calls, which `BR-067` rules out.

### D9 — Optimistic concurrency

**Decided:** `customer_contacts` gains a `version` column, and the edit and removal routes require the
caller's `expectedVersion`. A mutation that names a version the contact has left is refused with
`409 CONTACT_VERSION_CONFLICT` rather than applied.

**Why.** `properties`, `jobs` and `visits` already carry `version` (`api/src/database/schema.ts:520`,
`:661`, `:1091`), and `BR-032`/`BR-086` forbid a blind last-write-wins for important business data. Two
office users editing the same contact must not silently overwrite each other: a mutation against state that
has already moved on is a refusal the caller can see, not a lost change.

### Derived behaviours that follow from D1–D9

- **Changing the primary is one operation, in one transaction**: the previous primary is cleared and the new
  one set together, so no stored state exists in which two contacts are primary, or the customer has briefly
  none.
- The office customer detail orders **the primary first, then the rest oldest-first**. The service orders
  newest-first today (`api/src/customers/customers.service.ts:641`); this is a deliberate, visible change
  recorded here rather than slid in, and the ordering belongs to the contract (`docs/api/customers.md` §3.2).
- Editing or removing a contact never touches the customer, its Jobs, its Properties or any preserved address
  snapshot (`BR-056`, `BR-057`, `BR-087`).
- `BR-009` is **not** amended: the technician's contact read reuses the capability already held, so no
  default Technician capability is added and no default-role grant changes for that role.

### D10 — The effective primary contact, and the numbers the Job card folds away (added 2026-09-18)

The decisions D1–D9 left one state undescribed: a Customer may hold **zero** primary contacts (D1 makes that
legal), and nothing said what a surface then presents as "the primary". Product ownership decided it, and
decided how the Job Details customer block presents the numbers that follow from it.

**Decided — what "primary" means.** The Customer's **effective primary contact** is the contact person
flagged primary when one is flagged. When no contact person is flagged, the **Customer itself is the
primary**: for an INDIVIDUAL and a COMPANY alike, its own phone number is the number a surface presents as
the primary one. Nothing is created, promoted or inferred to fill the gap — the state is not an error and is
not "fixed" on the Customer's behalf (`BR-067`, `BR-041`).

**Decided — the marker follows the effective primary.** The **Primary** badge marks that one contact and
nothing else: the flagged contact person's row when one is flagged, and otherwise the Customer's own phone
line. The office customer detail marks it where the effective primary is; a surface that presents one
primary contact presents it there.

**Decided — the Job Details customer block presents one phone.** The block leads with the effective
primary's number under the **Primary** badge, and keeps every **other** way of reaching the Customer — the
other contact persons, and the Customer's own general line when it is not the primary — behind an explicit
disclosure that is **collapsed and hidden by default**. The Customer's own email and notes stay visible,
because `BR-092` names them as part of the block. The disclosure is the disclosure the page already uses
(the heading is the control, the chevron turns a quarter turn, the state is announced), so nothing new is
invented for it (`docs/design/android-design-system.md`).

**Why the field read had to change.** A client cannot answer which contact person is flagged from a list
ordered primary-first: with zero primary contacts the list is still ordered oldest-first, so "the first
entry is primary" and "no entry is primary" look identical. The Job Details block therefore cannot resolve
the effective primary without the flag, and the field read now reports `isPrimary` per contact person —
the contact row's own flag, the same vocabulary the office read has always carried (`BR-041`). That is the
`BR-092` `BR-040` change record; the block's other fields, its assignment scope and its second authorization
question are untouched.

**Corner cases, decided rather than left to an implementation.**

- A flagged contact person with **no** number recorded leaves the card with **no** primary phone rather than
  handing that role to the Customer's own number, which `BR-095` does not give it. That person does not
  disappear from the card: they are listed among the others, because the API reported them (`BR-001`).
- The office customer detail is **not** a "one primary contact" surface — it lists every contact person — so
  nothing is folded away there. Only the marker follows the effective primary.
- An individual Customer with one number has nothing to fold, so the block draws no disclosure at all
  (`BR-012`).

**What this does not change.** No schema change, no migration, no new capability, no new route and no new
authorization question: `isPrimary` is a column the table has always held, and the office read has always
reported it. `BR-095`'s invariant, its capability set, its soft removal and its version check are exactly as
D1–D9 recorded them; `BR-092`'s scope and its view-only nature are exactly as valid as before. `BR-002`'s
client consistency is the same later slice as before: the Angular client will present the same rule from the
same `isPrimary` flag, and no client derives its own definition of it.

## Implementation

| Layer | Change |
| ----- | ------ |
| Business rules | `BR-095` (new, §9 Customers), plus the `BR-040` change records in `BR-023` (the contact person and the one-primary invariant) and `BR-092` (the contacts inside the field read; and, with D10, which of them is primary) |
| Database | Migration `0014_*`: `customer_contacts_primary_unique` (partial unique index on the customer where primary), `version` with its not-null, default and check, `removed_at`, `removed_by_membership_id` FK to `organization_members`, the three permission rows with their bilingual name and description, and the default-Manager grants; `api/src/database/schema.ts`; `meta/_journal.json` and its snapshot; the development seed stops writing a contact row for an `INDIVIDUAL` template, which contradicts D1 today |
| API catalogue | `api/src/auth/permissions.ts` — the contact capability set, with its spec |
| API writes | `POST /customers/:id/contacts` extended (the contact's own phone and email; `isPrimary = true` clears the previous primary in the same transaction), `PATCH /customers/:id/contacts/:contactId`, `DELETE /customers/:id/contacts/:contactId`, the DTOs (a parse for the edit, `expectedVersion`), the service methods, and the errors `CONTACT_NOT_FOUND` and `CONTACT_VERSION_CONFLICT` |
| API reads | The office detail's ordering (primary first, then oldest-first) and its exclusion of removed contacts; the Job Details customer block carrying the contacts under D3, asked as the same second question in the service. Both stay projections of authoritative contact rows, never a second source of truth (`BR-080`) |
| Android | The contact DTOs and repository operations, the three permission constants, the Customer Detail Contacts card, an Add/Edit Contact destination, Add Customer's primary-contact phone and email, the Job Details contact rows, and EN/FR copy — with no client-side gate added for the field block (`BR-007`, `ADR-021` D4) |
| Contract and docs | `docs/api/customers.md` §3.2/§5.2, `docs/api/job-details.md` §3.2/§3.3, `docs/domain/foundation-domain-model.md` §8, `docs/architecture/offline-first-architecture.md` §12 (the online-only statement D7 requires), the README pointer, and the tracker's status |

## What this record does not change

`BR-092`'s scope and its second authorization question (`ADR-021` D2), the block's existing fields (`email`,
`phone`, `notes`), the customer header's own phone and email, the default Technician capability set
(`BR-009`), the assignment scope (`ADR-019` D2), the absence of a client permission constant for the field
block (`ADR-021` D4), and the customers list — which shows and manages no contacts — all stand exactly as
they were.

## ADR-021's open questions 1 and 3 are closed

`ADR-021` recorded two open questions against this slice. Both are answered above, and a decision update has
been appended to that record so a reader of it is not left with a stale question.

1. **Open question 1 — whether a technician may read the customer's contacts: decided, yes, all of them**
   (D3), inside the existing block and under the existing capability. The billing fields and the
   billing-contact flag stay out.
2. **Open question 3 — tap-to-call and tap-to-email: decided, yes, on both surfaces** (D6), using the
   affordances the repository already has.

## Open questions recorded, not decided

1. **A removed-contacts or audit view for the office.** Soft removal (D8) keeps the record, its actor and its
   timestamp, but no screen reads them back. `BR-033`'s detailed audit rules remain open.
2. **The `customers.*` capability set's re-review** — `ADR-021` open question 4. The set gains three more
   codes here, so the question grows rather than closes.
3. **The contact `role`** (free text, for example "Site manager") — modelled and returned by the API,
   deliberately not captured or edited by any client here.
4. **`isBillingContact` and `billingEmail` / `billingPhone`.** `BR-023` names billing contacts while no rule
   defines what makes one, whether it must be unique, or what it changes. Nothing is implemented for them.
5. **Uniqueness of a contact's phone or email.** No constraint is imposed
   (`docs/domain/foundation-domain-model.md` §8): two contacts may share an address and two customers may
   share one. Unchanged.
6. **A removal reason.** D8 requires none. If a compliance or audit requirement appears, that is a product
   decision and an API change rather than something an implementation adds (`BR-042`).
7. **Whether the customer header's own phone and email should eventually be reduced** now that contacts carry
   their own. D2 keeps it as the customer's general line.




