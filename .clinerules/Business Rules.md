# Business Rules.md — Authoritative Business Rules (Servora)

> This document defines **what Servora does from a business/product perspective**.
>
> It is the authoritative business contract for product behavior. Technical implementation details belong in the architecture, API, Android, Angular, database, and development documentation.
>
> When a business behavior is not defined here, it must **not be invented by an implementation agent**. It remains an **OPEN QUESTION** until product ownership decides it.

---

# 1. Rule Status

| Status                   | Meaning                                                                                       |
| ------------------------ | --------------------------------------------------------------------------------------------- |
| **CONFIRMED**            | Product behavior has been explicitly decided and must be implemented accordingly.             |
| **PLANNED**              | Intended product behavior that has been approved but is not yet implemented.                  |
| **TECHNICAL ASSUMPTION** | A technical constraint that affects product behavior but is not itself a product requirement. |
| **OPEN QUESTION**        | Product behavior has not yet been decided. Agents must not invent it.                         |

### Implementation principle

Agents may implement:

- **CONFIRMED** rules
- **PLANNED** rules
- technical requirements documented in the appropriate technical/architecture documents

Agents must **not invent behavior for OPEN QUESTION rules**.

When implementation requires an unresolved business decision, stop at the affected boundary and record the question rather than guessing.

---

# 2. Platform & System of Record

## BR-001 — Backend is the system of record

**Description:**
Servora's backend API and database are the authoritative source of business data.

**Applies to:**
Android, Angular, API, Database

**Conditions:**
Always.

**Expected behavior:**

- Business data is persisted and governed by the backend.
- PostgreSQL is the authoritative persistence layer.
- The API is the authoritative business-operation layer.
- Android and Angular do not maintain an independent authoritative copy of business data.
- Client-side state exists for presentation, caching, offline operation, and temporary work only.
- Backend state always takes precedence when authoritative data is synchronized.

**Exceptions:**
Android may maintain local working data and pending operations to support offline field work. Such local changes remain provisional until accepted by the backend.

**Status:**
**CONFIRMED**

---

## BR-002 — Two client applications serve the platform

**Description:**
Servora provides two application surfaces:

1. **Android application** — used by both Managers and Technicians.
2. **Angular application** — management/office application with the same core business capabilities plus reporting and advanced features.

**Applies to:**
Android, Angular, API

**Expected behavior:**

- Android supports both Manager and Technician workflows.
- Angular provides the broader management and administrative experience.
- Both applications consume the same backend API and business model.
- Business behavior must remain consistent across clients.
- A capability must not behave differently merely because it is accessed from Android versus Angular.

**Exceptions:**
The user experience may differ between clients according to device, workflow, and audience.

**Status:**
**CONFIRMED**

---

# 3. Users, Roles & Permissions

## BR-003 — Servora has two default system roles

**Description:**
Servora starts with exactly two system roles:

- `Manager`
- `Tech`

These roles define the default product audiences.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- A new Servora organization has default Manager and Technician roles available.
- Manager represents the management/office audience.
- Technician represents the field technician audience.
- Default role identifiers are stable machine-readable setup codes, not authorization checks.
- Additional operational roles are organization-defined and must not be hard-coded into the application.
- Managers may create additional custom roles for their organization.

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

## BR-004 — Custom roles are created by Managers

**Description:**
The initial system roles are not intended to represent every organizational responsibility.

Managers may create additional roles for their organization.

Examples may include:

- Dispatcher
- Scheduler
- Lead Technician
- Office Administrator
- Service Coordinator

These are examples of custom roles, not Servora system roles.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Custom roles are organization-specific.
- A custom role has a bilingual name, bilingual description, and permission set.
- Custom roles are permission-based.
- Organizations may name roles however they want.
- The application must not contain hard-coded behavior for names such as `Dispatcher` or `Lead Technician`.
- A Manager may create and manage custom roles according to permissions.
- Authorization must never depend on the role's English or French display name.

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

## BR-005 — Roles have bilingual names and descriptions

**Description:**
Role names and descriptions are business data and must support both English and French.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Roles store an English name and description (`name_en`, `description_en`).
- Roles store a French name and description (`name_fr`, `description_fr`).
- The appropriate language is displayed according to the user's selected/application language.
- Custom roles must provide bilingual role information.
- Authorization must not depend on translated display text.

**Exceptions:**
None.

**Notes:**
System role setup codes remain stable and machine-readable. Display names are separate from authorization identifiers.

**Status:**
**CONFIRMED**

---

## BR-006 — Authorization is permission-based

**Description:**
Servora authorizes actions using permissions rather than role-name checks.

The conceptual model is:

`Organization member → One role + optional direct permissions → Effective permissions → Authorization decision`

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- A user has at most one role in a given organization membership.
- A Manager may grant extra direct permissions to an organization member when necessary.
- Effective permissions are determined from the member's role permissions plus direct member permissions.
- Permission identifiers are stable and machine-readable.
- Permission display names and descriptions are bilingual.
- Roles and permissions are many-to-many.
- Applications use permissions to determine whether functionality should be available.
- Role names must not be used as a substitute for permission checks.
- The backend is the final authority for authorization.

**Exceptions:**
The application may use system-role information for audience-specific UX decisions, such as choosing an appropriate home screen, but this does not replace permission enforcement. A member with direct permissions beyond the base role may be displayed as `Role Name*`; the star is a UI indicator only and is never an authorization input.

**Status:**
**CONFIRMED**

---

## BR-007 — Frontend authorization is never trusted

**Description:**
Client-side authorization exists to control the user experience, not to provide security.

**Applies to:**
Android, Angular, API

**Expected behavior:**

- UI elements may be shown, hidden, enabled, or disabled according to the user's permissions.
- A hidden button does not constitute authorization.
- A modified client must not be able to bypass backend authorization.
- Every protected business operation is validated by the backend.
- Unauthorized API operations are rejected by the backend.

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

## BR-008 — Manager default permissions

**Description:**
The default Manager role has full CRUD capability for the initial core entities:

- Jobs
- Technicians
- Customers

**Applies to:**
Android, Angular, API

**Expected behavior:**

A Manager has the following permissions by default:

- Create jobs
- View jobs
- Update jobs
- Delete jobs
- Create technicians
- View technicians
- Update technicians
- Delete technicians
- Create customers
- View customers
- Update customers
- Delete customers
- View deleted customers

These capabilities are subject to any later domain-specific business rules concerning deletion, lifecycle, historical records, or dependencies.

**Exceptions:**
Domain-specific restrictions may prevent deletion or modification of particular records once those rules are formally defined.

**Notes:**
Property capabilities are defined by BR-085 and are governed by BR-006 like every other capability. No Property capability is implied by the Customer capabilities listed here.

**Status:**
**CONFIRMED**

---

## BR-009 — Technician permissions are intentionally limited

**Description:**
The default Tech role does not receive Manager-level administrative permissions.

**Applies to:**
Android, Angular, API

**Expected behavior:**

- Technicians receive only permissions required for assigned field work.
- The default Technician permission set is:
  - View assigned Visits.
  - Update assigned Visit field status.
  - Add Visit notes.
  - Record Visit outcomes.
- A Technician cannot gain Manager CRUD capabilities merely because the frontend exposes a corresponding screen.

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

# 4. Android Application

## BR-010 — Android supports both Manager and Technician experiences

**Description:**
The Android application is not a technician-only application.

**Applies to:**
Android

**Expected behavior:**

- Managers can use Android for management workflows.
- Technicians can use Android for field workflows.
- The application presents an experience appropriate to the authenticated user's permissions.
- The interface must not present irrelevant management functionality to Technicians.
- The interface must not unnecessarily restrict Managers from capabilities they are authorized to perform.

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

## BR-011 — Android UI is permission-driven

**Description:**
Android determines the availability of UI functionality from the authenticated user's permissions.

**Applies to:**
Android, API

**Expected behavior:**

- Screens, actions, buttons, menus, and other controls may be conditionally displayed based on permissions.
- Permission checks should be centralized rather than scattered throughout the UI.
- The UI should reflect current authorization information.
- Backend authorization remains authoritative.

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

## BR-012 — Android prioritizes field productivity

**Description:**
Technician workflows should minimize unnecessary interaction and keep the primary field action obvious.

**Applies to:**
Android

**Expected behavior:**

- Field workflows prioritize speed and clarity.
- Frequently performed actions require minimal unnecessary interaction.
- The UI avoids unnecessary pop-ups and administrative complexity.
- Management-oriented information should not overwhelm technician workflows.
- The technician should be able to understand what work needs attention and what action is available.

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

## BR-013 — Android supports offline field work

**Description:**
Technicians must be able to continue relevant field work when connectivity is unavailable or unreliable.

**Applies to:**
Android, API

**Expected behavior:**

Offline-capable workflows may include:

- Viewing assigned work
- Viewing relevant customer and site information
- Recording work activity
- Starting work
- Pausing work
- Completing work
- Adding notes/comments
- Capturing photos/evidence
- Recording relevant timestamps
- Recording relevant field/location information
- Viewing evidence already recorded on the technician's work: its **metadata** (phase, note, time) is required to be readable offline, and its **bytes** are available on a best-effort basis (`BR-015`, `BR-088`)

Offline work is stored locally and synchronized with the backend when connectivity becomes available.

**Exceptions:**
Operations that fundamentally require current server information may require connectivity, except that accepted evidence's **metadata** must be readable offline while its bytes are best-effort (`BR-015`).

**Status:**
**CONFIRMED**

---

## BR-014 — Offline work must not be silently lost

**Description:**
Technician work captured offline must be preserved until the backend confirms successful synchronization.

**Applies to:**
Android, API

**Expected behavior:**

- Pending work survives application restart.
- Pending work survives device restart.
- Network failures do not silently discard work.
- Failed synchronization remains visible to the user when intervention is required.
- Automatically retryable operations are retried.
- Permanently failed operations are retained until resolved or explicitly discarded according to future product rules.

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

## BR-015 — Photos are treated as business evidence

**Description:**
Photos captured by technicians are business evidence and must be preserved during synchronization.

**Applies to:**
Android, API

**Expected behavior:**

- A captured photo is immediately available to the technician.
- Photos may remain local while waiting for synchronization.
- A local photo is not deleted merely because an upload failed.
- Upload failures are retryable where appropriate.
- The UI communicates the synchronization state without unnecessarily interrupting field work.

**Exceptions:**
None.

**Notes:**
A photo is **draft material** until the backend accepts it and **immutable historical evidence** from that moment; the whole evidence lifecycle — immutability, removal, retention, metadata and kinds — is defined by `BR-088` – `BR-091`. The metadata of accepted evidence must be readable offline, with its bytes available best-effort (`BR-013`).

**Status:**
**CONFIRMED**

---

# 5. Angular Application

## BR-016 — Angular provides the management experience

**Description:**
The Angular application provides the broader management and office-oriented Servora experience.

**Applies to:**
Angular, API

**Expected behavior:**

- Angular provides the same core business capabilities supported by the platform.
- Management workflows are optimized for desktop/web usage.
- Angular uses the same backend authorization model as Android.
- Angular does not become a separate source of truth.

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

## BR-017 — Angular includes reporting and advanced features

**Description:**
Angular provides capabilities beyond the core transactional workflows.

**Applies to:**
Angular, API

**Expected behavior:**

The Angular application may provide:

- Reporting
- Management dashboards
- Advanced filtering
- Advanced operational workflows
- Administrative capabilities
- Other management-oriented features

These capabilities must still be governed by backend permissions.

**Exceptions:**
Specific reporting and advanced features remain subject to their own product rules.

**Status:**
**CONFIRMED** — product direction
**OPEN QUESTION** — individual reporting/advanced feature definitions

---

# 6. Authentication

## BR-018 — Email/password authentication

**Description:**
Users may authenticate using an email address and password.

**Applies to:**
Android, Angular, API

**Expected behavior:**

- Email/password authentication is supported.
- Authentication is performed by the backend.
- Clients do not independently authenticate users against business data.
- Successful authentication establishes the user's authenticated identity and authorization context.

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

## BR-019 — Phone/SMS authentication

**Description:**
Users may authenticate using their phone number and an SMS one-time password (OTP).

Phone authentication is an authentication method, not merely phone verification. It exists for
users who already have a Servora account; it never creates one.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

Supported authentication methods are exactly:

- email address/username + password (`BR-018`)
- phone number + SMS OTP (`BR-019`)

Google and Microsoft authentication are not supported.

- A verified phone number may authenticate an existing Servora user.
- Phone authentication must not create a new account. Registration is a separate future flow.
- Phone numbers are stored in canonical **E.164** format. Canadian `+1` numbers are supported,
  and the representation must support international E.164 numbers rather than assuming a
  single country.
- Server-side normalisation and validation of the submitted number are authoritative; a client
  never decides whether a number is acceptable.
- An OTP consists of **6 numeric digits** generated from a cryptographically secure source.
- An OTP expires **10 minutes** after it is issued.
- At most **5 failed verification attempts** are accepted for one OTP.
- Successful verification immediately invalidates the OTP.
- Requesting a new OTP invalidates the previously issued OTP.
- A new OTP may be requested no sooner than **60 seconds** after the previous request
  (server-enforced resend cooldown). A client may display a countdown, but the server decides.
- OTP values are never logged, never returned through the API, and never stored in plaintext.
  Only a secure one-way representation of the OTP is persisted (`BR-046`).
- After a successful verification the client receives the **same** Servora authenticated
  session and token pair as `BR-018`, and authorization continues to use the existing
  Servora user/role/permission model. Phone authentication does not introduce a separate
  session or authorization architecture.
- Successful phone authentication also follows `BR-018`'s eligibility rule: only an `ACTIVE`
  account authenticates.

**Exceptions:**

- A phone number that is absent from Servora, that belongs to a non-`ACTIVE` account, or that
  is ambiguous (associated with more than one account) cannot authenticate; the attempt is
  reported exactly like the generic non-disclosing response required by `BR-044`.
- Rate limits and the resend cooldown are configuration, so a deployment may tighten them
  without a product change. The approved initial values are recorded in
  `docs/decisions/006-authentication-flows-and-sms-provider.md`.

**Notes:**
How a phone number is first associated with a user, changed, released, or verified outside the
sign-in flow is not defined by this rule and remains **OPEN QUESTION** for the user profile/contact
lifecycle.

SMS delivery is provider-specific and is isolated behind an application-level port
(`SmsProvider`). The production provider is Twilio; the domain and application layers
do not depend on provider-specific classes, terminology or response structures.

**Status:**
**CONFIRMED**

---

## BR-043 — Password reset

**Description:**
A user who cannot sign in may regain access to their own account by proving control of the
password-reset delivery channel configured for that account.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Password reset applies to an **existing** account only. It never creates an account and never
  changes which value is the authentication identity.
- The authentication identity is the existing one used by `BR-018`. The reset is delivered to the
  email address configured on that account. Identity and delivery channel are separate concepts,
  and no new identity field is introduced to support the flow.
- The response is externally generic whether or not the submitted identity belongs to an account
  (`BR-044`). The API must not report that an account does not exist.
- The reset credential is:
  - cryptographically random,
  - single-use,
  - time-limited (**30 minutes**),
  - persisted only as a one-way digest (`BR-046`),
  - invalidated after a successful reset,
  - invalidated after expiry,
  - protected against brute-force guessing by a maximum of **5 failed verification attempts**.
- Requesting a new reset credential for the same account invalidates the outstanding one.
- A successful reset:
  - hashes the new password with the existing password hashing mechanism and the existing
    password policy — no new password policy is introduced by this rule,
  - invalidates the reset credential that was used and any other outstanding credential for the
    same account,
  - records the password change through the existing audit/event architecture (`BR-033`),
  - never stores or logs the plaintext password,
  - does **not** by itself sign the user in: the user returns to normal authentication.
- Reset requests and reset-credential verification are rate limited (`BR-045`).

**Exceptions:**
None.

**Notes:**

- Whether an existing authentication session is revoked when a password is reset is **OPEN
  QUESTION**. No behaviour is implemented for it until product ownership decides.
- The delivery transport and provider for the reset message is a separate, still-open
  infrastructure decision; it is isolated behind an application-level port
  (`PasswordResetNotifier`) and recorded in `docs/decisions/006-authentication-flows-and-sms-provider.md`.

**Status:**
**CONFIRMED**

---

## BR-044 — Authentication must not disclose account existence or state

**Description:**
An unauthenticated caller must not be able to learn from Servora whether an email address,
username or phone number belongs to an account, nor what state that account is in.

**Applies to:**
Android, Angular, API

**Expected behavior:**

- Responses for an unknown identity and for a known identity are externally equivalent: the same
  message, the same stable error code, the same HTTP status, the same response structure, and no
  avoidable difference in response time or work performed.
- When an identity is unknown, the externally identical response is returned **without** producing
  the effect — for example, no SMS or email message is sent.
- This applies to every authentication entry point: password sign-in (`BR-018`), phone/SMS
  authentication (`BR-019`) and password reset (`BR-043`).
- Rate limiting applies to unknown identities as well, so a throttling response cannot be used to
  distinguish identities (`BR-045`).
- A client may present a friendly generic message, but it must not present different copy for
  "account not found" and "credential rejected".

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

## BR-045 — Authentication attempts are rate limited

**Description:**
Authentication entry points accept a bounded number of attempts in a rolling window.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Limits are enforced by the backend and are configuration, so a deployment may tighten them
  without a product change.
- Approved initial limits:
  - password-reset request: **3 per identity / 15 minutes** and **10 per identity / 24 hours**
  - SMS OTP request: **3 per phone number / 15 minutes** and **10 per phone number / 24 hours**
  - SMS OTP resend cooldown: **60 seconds** between requests for the same phone number
- Limits apply whether or not the identity belongs to an account (`BR-044`).
- Rejecting for a limit must not perform the underlying operation.
- A client may display a countdown or disabled state, but the server remains authoritative.

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

## BR-046 — One-time authentication codes are stored as one-way digests

**Description:**
Servora never retains, transmits or emits a one-time authentication code in a form that can be
re-used.

**Applies to:**
API, Database

**Expected behavior:**

- Applies to SMS OTPs (`BR-019`) and password-reset credentials (`BR-043`).
- A code is never persisted in plaintext and is never returned in an API response.
- A code is never written to logs, traces or error output.
- Only a keyed one-way digest of the code is persisted, and comparison is performed in constant
  time.
- A code is single-use: a successfully verified code, a superseded code and an expired code can
  never be verified again.

**Exceptions:**
None.

**Notes:**
The digest construction is an architectural decision, recorded in
`docs/decisions/006-authentication-flows-and-sms-provider.md` (D1).

**Status:**
**CONFIRMED**

---

# 7. User Profiles & Photos

## BR-020 — Users may have profile photos

**Description:**
Servora supports photos for users/technicians where the product surface requires them.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- A user's profile may contain a photo.
- The UI displays the user's photo when one exists.
- A default avatar is displayed when no photo exists.
- The default avatar must not be treated as user-provided evidence.

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

# 8. Jobs, Properties & Visits

## BR-021 — Jobs are a core Servora entity

**Description:**
A Job represents work that must be performed for a customer.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**
Jobs support the core Servora workflow across management and field applications.

The job lifecycle, scheduling semantics, completion rules, follow-up Visit request workflow and business constraints are defined by BR-047 – BR-080 and BR-FV-001 – BR-FV-013.

**Exceptions:**
None.

**Notes:**
The concrete data model belongs to the Job & Visit data-model slice, which follows once these rules are accepted.

**Status:**
**CONFIRMED** — entity exists
**CONFIRMED** — job, Property and Visit behaviour defined by BR-047 – BR-080
**OPEN QUESTION** — how deletion of a Job relates to preserved business history (BR-008 grants Manager delete capability; the job rules do not define deletion behaviour)

---

## BR-022 — Job lifecycle must be explicitly defined

**Description:**
Job status and lifecycle behavior must be defined by product rules rather than inferred by implementation.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Job statuses are shared across clients.
- Status transitions are validated by the backend.
- Clients do not invent their own job status vocabulary.
- A status transition that has not been defined by product ownership must not be implemented as business behavior.

**Exceptions:**
None.

**Notes:**
The Job lifecycle is defined by BR-058 (states and transitions), BR-060 and BR-061 (derived conditions), BR-062 (completion), BR-063 (reopening) and BR-064/BR-065 (cancellation). The Visit lifecycle is defined by BR-074.

**Status:**
**CONFIRMED** — lifecycle defined by BR-058 – BR-065 and BR-074

---

## BR-047 — Customer, Property, Job and Visit are distinct concepts

**Description:**
Servora separates four concepts:

| Concept      | Meaning                                             |
| ------------ | --------------------------------------------------- |
| **Customer** | The person or company receiving service.            |
| **Property** | The physical location where services are performed. |
| **Job**      | The overall work request/work order.                |
| **Visit**    | One field attempt to perform work for a Job.        |

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Customer, Property, Job and Visit are separate concepts with separate identities.
- A Job is not a Visit, and a Visit is not a Job.
- One Job may have zero, one, or multiple Visits over time.
- A Visit represents a specific field attempt, not a permanent representation of the Job.
- The domain model must not merge Job and Visit into a single entity.
- The domain must not depend on any particular navigation or presentation, such as tabs or screens.

**Exceptions:**
None.

**Notes:**
Customer and Property extend the foundation domain (`docs/domain/foundation-domain-model.md`). Property is not yet modelled there.

**Status:**
**CONFIRMED**

---

## BR-048 — A Job always belongs to a Customer

**Description:**
A Job exists for the party receiving service.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- A Job always belongs to a Customer and cannot exist without one.
- Customer and Property are separate concepts (BR-049): a Customer may have multiple Properties, and a Property may be associated with different Customers over its lifetime.
- Existing Jobs are never automatically transferred to a new Customer when a Customer/Property relationship changes.
- A Job keeps the Customer it was created for until an explicit, authorized change is performed.

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

## BR-049 — Property represents an organization-owned service location

**Description:**
A Property is the physical location where service is performed.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- A Property is owned by the organization (tenant) that owns the Job (BR-001).
- A Property contains an optional Property name, a structured address, and optional notes.
- A Property is an independently identified entity; it is not a copy of a Customer address field.
- A Property address is structured data rather than a single free-text field.

**Exceptions:**
None.

**Notes:**
The structured address shape belongs to the Job & Visit data-model slice. Property editing, archiving, restoring and permanent deletion are defined by BR-082 – BR-086.

**Status:**
**CONFIRMED**

---

## BR-050 — A Property has one active Customer relationship at a time

**Description:**
A Property may have multiple Customer relationships over time, but only one active relationship at a time.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

When the Customer associated with a Property changes:

1. End the previous Customer relationship.
2. Create the new Customer relationship.
3. Preserve the historical relationship.
4. Leave existing Jobs associated with their original Customer and Property.

**Exceptions:**
None.

**Notes:**
Whether a Property may exist with no active Customer relationship is not defined.

**Status:**
**CONFIRMED** — single active relationship and history preservation
**OPEN QUESTION** — whether a Property may be unassociated with a Customer

---

## BR-051 — A Job may exist without a Visit

**Description:**
A Job represents the overall business request/work order and is independent of field execution.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- A Job may be created with no Property and no Visit.
- A Job may exist without any Visit (BR-047).
- A Visit is never created merely because a Job exists.
- A Job becomes schedulable once a Property is known and a Visit is created (BR-056, BR-072).
- Additional field attempts are represented by additional Visits on the same Job, not by a new Job.

**Exceptions:**
None.

**Notes:**
Example of a valid Job with no Property and no Visit: Customer "John Smith", Job "Repair furnace", status `NEW`.

**Status:**
**CONFIRMED**

---

## BR-052 — Every Job has an immutable identifier and an organization-scoped Job number

**Description:**
A Job carries both a technical identifier and a human-readable number.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- The technical identifier is a stable UUID and is the primary key. It never changes.
- The Job number is a sequential integer that is unique within an Organization and may repeat across different Organizations.
- The Job number never changes after creation.
- The Job number is not the technical primary key and must not be used as a cross-system key.
- The UI displays the human-readable form (for example `Job #1042`).

**Exceptions:**
None.

**Notes:**
The display format and the number-allocation mechanism are implementation concerns of the Job & Visit data-model slice.

**Status:**
**CONFIRMED**

---

## BR-053 — Job basic information

**Description:**
The minimum information required to create a Job.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- A Job must have a title.
- A Job description is optional.
- A Job does not require a type/category in order to be created.
- Job type/category is optional and is used for reporting, filtering and history.
- Job type/category must not control scheduling, status, or permissions.
- Illustrative categories include Repair, Maintenance, Installation, Inspection, Service Call and Other; these examples do not define the catalogue.

**Exceptions:**
None.

**Notes:**
The authoritative Job category catalogue is not defined.

**Status:**
**CONFIRMED** — title required; category optional and non-controlling
**OPEN QUESTION** — the Job category catalogue

---

## BR-054 — Job priority is out of scope in v1

**Description:**
Servora does not model a Job priority.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- A Job has no priority field in v1 (BR-042).
- Scheduling order is controlled by the Manager/authorized scheduling user rather than by an automatic priority system.
- No priority behaviour is inferred from other Job fields.

**Exceptions:**
None.

**Notes:**
Introducing a priority is a product decision and requires an explicit rule (BR-040).

**Status:**
**CONFIRMED**

---

## BR-055 — A Job may have an optional Owner

**Description:**
The Job Owner represents accountability/work ownership for the Job.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- The Job Owner is separate from Visit technician assignment (BR-068).
- The Job Owner does not automatically become a Visit technician.
- The Job Owner does not automatically become the Lead technician.
- The Job Owner does not affect technician availability (BR-070).
- The Job Owner may be different from the technicians performing the work.
- A Job may have an Owner even when it has no Visits.

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

## BR-056 — Job Property and address snapshot

**Description:**
A Job references a Property when one is known, and preserves the address used at the time.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- A Job references a Property when one is known.
- A Property may be added to a Job after the Job is created.
- A Job cannot be scheduled until a Property exists (BR-072).
- When a Property is associated with a Job, the Job preserves an immutable address snapshot for historical purposes.
- A Job's current Property may change while the Job is active.
- Property changes are allowed until the Job is completed, are recorded in history, preserve the previous Property, do not create a new Job, and do not automatically modify Visits.
- If a Job's Property changes while it has `SCHEDULED` or active Visits, the affected Visits must be flagged for review (for example `Location Changed — Review Schedule`).
- The system must never silently change a Visit's schedule or operational location as a side effect of changing the Job Property; the Manager/authorized user must explicitly review and resolve each affected Visit.

**Exceptions:**
None.

**Notes:**
After a Job is `COMPLETED`, its Property cannot be changed (BR-062). Historical location changes are governed by BR-057. Archiving a Property never changes a Job's Property reference or its address snapshot (BR-083).

**Status:**
**CONFIRMED**

---

## BR-057 — Historical locations must not be rewritten

**Description:**
Changing an address must not rewrite what already happened.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Changing a Customer address, a Property address, or a Job's Property must not rewrite historical Job or Visit information that has already occurred.
- Historical records must continue to show the location that was relevant at that time.
- Location/address history is append-only.

**Exceptions:**
None.

**Notes:**
Editing a Property never retroactively changes a Job's or Visit's stored address snapshot (BR-084). Whether the Property's own address history must additionally be retained remains an **OPEN QUESTION**, and product ownership has **deferred** a dedicated Property address-history table until an audit screen or a compliance requirement needs it. The Job and Visit snapshots remain the authoritative record of the address used at the time.

**Status:**
**CONFIRMED**

---

## BR-058 — Job status lifecycle

**Description:**
Job status represents the business lifecycle of the work request.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

A Job has exactly one status from a shared, backend-validated vocabulary:

| Code             | Meaning                                                |
| ---------------- | ------------------------------------------------------ |
| `NEW`            | Created; not yet scheduled                             |
| `SCHEDULED`      | A Visit is scheduled                                   |
| `IN_PROGRESS`    | Work has started and/or work remains                   |
| `PENDING_REVIEW` | Field work appears finished and awaits business review |
| `COMPLETED`      | Closed by an authorized office user                    |
| `CANCELED`       | Canceled by an authorized user                         |

Permitted transitions:

```text
NEW             → SCHEDULED
NEW             → IN_PROGRESS
NEW             → PENDING_REVIEW
NEW             → COMPLETED

SCHEDULED       → IN_PROGRESS
SCHEDULED       → PENDING_REVIEW
SCHEDULED       → COMPLETED

IN_PROGRESS     → SCHEDULED
IN_PROGRESS     → PENDING_REVIEW
IN_PROGRESS     → COMPLETED

PENDING_REVIEW  → SCHEDULED
PENDING_REVIEW  → IN_PROGRESS
PENDING_REVIEW  → COMPLETED

NEW             → CANCELED
SCHEDULED       → CANCELED
IN_PROGRESS     → CANCELED
PENDING_REVIEW  → CANCELED

COMPLETED       → NEW
CANCELED        → NEW
```

- **Any structurally permitted destination may be selected directly.** While a Job is open (`NEW`, `SCHEDULED`, `IN_PROGRESS`, `PENDING_REVIEW`) an authorized user may select **any other open status, forwards or backwards**, or `COMPLETED`. The Job reaches that status in **one** business operation, and that operation records **one** transition; a client must not reach a destination by issuing a series of transitions.
- A Job is therefore never forced through the lifecycle one step at a time. `SCHEDULED → COMPLETED` and `IN_PROGRESS → SCHEDULED` are each one permitted transition.
- A Job may not "change" to the status it already holds; that is not a transition.
- `NEW` is not a destination for an open Job. It is reached only by reopening a terminal Job (BR-063).
- `COMPLETED` and `CANCELED` are terminal. The **only** destination from either is `NEW`, through the explicit reopen (BR-063). No other destination is reachable from a terminal status.
- The transition list is **structural**. Whether a listed destination may be entered right now is a runtime eligibility question owned by its own rule: BR-061 for `PENDING_REVIEW` and BR-062 for `COMPLETED`. A destination is therefore offered even when the Job does not currently qualify for it, and the operation is refused with that rule's own outcome. Eligibility must never be expressed by removing a structurally valid destination from the list.
- Every status change records, in append-only history, the previous status, the new status, the user who made the change and the timestamp (BR-033, BR-067, BR-080).
- Changing a Job's status **never** changes a Visit's status and never rewrites Visit history. The Job's business lifecycle and the field execution lifecycle are separate state machines (BR-059).
- Job status advances as a consequence of Visit lifecycle events (BR-074) and the conditions in BR-060 and BR-061, and by the explicit authorized action above; the backend applies and validates the transition either way.
- `COMPLETED`, `CANCELED` and reopening are explicit authorized actions (BR-062, BR-064, BR-063).
- Clients must not invent their own Job status vocabulary (BR-041).

**Exceptions:**
None.

**Notes:**
This rule defines the lifecycle required by BR-022. Job cancellation appears above as a permitted transition but is not applied while BR-064's structured reason catalogue is undefined.

**Changed decision (recorded under BR-040):** the transition list previously permitted only the next step forward, so a manager had to move a Job through every status in order and could not move one backwards. Product ownership confirmed that an authorized user selects any permitted destination in one operation, and that `NEW` is not a destination for an open Job. BR-061 and BR-062 remain runtime eligibility conditions over this list, and BR-062 now carries the completion invariant.

**Status:**
**CONFIRMED**

---

## BR-059 — Job status and Visit status are separate state machines

**Description:**
Business lifecycle and field execution lifecycle are different things.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Job status represents the business lifecycle (BR-058).
- Visit status represents the field execution lifecycle (BR-074).
- The two must not be treated as the same state machine and must not be merged.
- A Visit may be `COMPLETED` while its Job remains `IN_PROGRESS` (BR-077).
- A Technician never changes Job status directly (BR-066).

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

## BR-060 — A Job surfaces a derived "Needs Scheduling / No Active Visit" condition

**Description:**
A Job can require attention without carrying a distinct status.

**Applies to:**
Android, Angular, API

**Expected behavior:**

- If a Job's status is `NEW` or `IN_PROGRESS` and no Visit is in `SCHEDULED`, `EN_ROUTE`, `ON_SITE` or `IN_PROGRESS`, the Job surfaces a derived operational signal: "Needs Scheduling" / "No Active Visit".
- A `CANCELED`, `NO_SHOW` or `COMPLETED` Visit does not count as active work.
- The signal is derived, not stored: it is not a Job status and must not be persisted as one.

**Exceptions:**
None.

**Notes:**
The Visit set this condition considers is named `needsSchedulingActiveVisit`; the broader set the archive warning considers (`BR-083`) is named `archiveWarningOpenWork`. The two are deliberately different named concepts, because they answer different questions: a `DRAFT` Visit counts as open work for the archive warning but is not yet scheduled work for this signal. Neither vocabulary replaces the other.

**Status:**
**CONFIRMED**

---

## BR-061 — Entry into PENDING_REVIEW

**Description:**
`PENDING_REVIEW` is a business-review state, not automatic completion.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

A Job may enter `PENDING_REVIEW` only when:

1. No Visit remains active or scheduled.
2. The latest completed Visit outcome indicates that the Job may be resolved (`RESOLVED`), or an
   authorized office decision has resolved the follow-up requirement by determining that no further
   Visit is necessary (BR-078, BR-FV-007).

- If another Visit remains scheduled or active, the Job remains `IN_PROGRESS` even after a `RESOLVED` outcome.
- Outcomes such as `NEEDS_PARTS`, `NEEDS_FOLLOWUP` and `UNABLE_TO_COMPLETE` keep the Job in `IN_PROGRESS`.
- If a follow-up request exists for the latest completed Visit, the Job remains open while that
  request is pending or awaiting clarification. If an authorized office user rejects the request
  because no further Visit is required, that decision resolves the follow-up requirement for the
  purpose of review entry (BR-FV-004, BR-FV-007).
- `PENDING_REVIEW` is a review state and does not itself complete the Job (BR-062).
- These conditions apply to **every** transition into `PENDING_REVIEW`, whatever status the Job moves from (BR-058). They are runtime eligibility, not a structural limit: `PENDING_REVIEW` remains a permitted destination and is offered to authorized users even when the Job does not currently qualify.

**Exceptions:**
None.

**Notes:**
The effect of the `NEEDS_QUOTE_APPROVAL` outcome on Job status is not decided.

**Status:**
**CONFIRMED**
**OPEN QUESTION** — the Job-status effect of the `NEEDS_QUOTE_APPROVAL` outcome

---

## BR-062 — Only an authorized office user closes a Job

**Description:**
Job completion is an explicit business action.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Only a Manager/authorized office user can close a Job.
- Closing requires an explicit business action and is a transition **into** `COMPLETED` (BR-058). `PENDING_REVIEW → COMPLETED` remains the normal path, and an open Job may also be closed directly from `SCHEDULED`, `IN_PROGRESS` or `NEW`, in one operation.
- A consequential transition such as closing a Job requires the user to confirm it explicitly before it is applied; a confirmation is a presentation of the decision, never a substitute for the rules below.
- **A Job must not transition to `COMPLETED` while it has any open Visit.** A Visit is open while its status is not historical — that is, while it is anything other than `COMPLETED`, `CANCELED` or `NO_SHOW` (BR-074; the same classification BR-083 uses for an active Visit). The check is enforced by the backend as part of applying the transition and cannot be overridden by a client confirmation.
  - `IN_PROGRESS` Job with an `IN_PROGRESS` Visit → refused.
  - `SCHEDULED` Job with a `SCHEDULED` Visit → refused.
  - A `DRAFT` Visit is a field attempt that has not happened yet, so it is remaining work and the Job cannot be closed while it exists.
  - `SCHEDULED` Job whose Visits are all `COMPLETED`, `CANCELED` or `NO_SHOW` → permitted.
  - A Job with no Visit at all → permitted (administrative closure).
  - The refusal is reported as its own outcome so a client can say why, and it is not a transition refusal: the destination is structurally permitted by BR-058.
- Closing a Job changes only the Job. It never changes or reopens a Visit's status and never rewrites Visit history (BR-059).
- `final_outcome` is an optional Job field recording the overall result of the Job, set when the Job is closed; it must never be copied automatically from the latest Visit outcome.
- Historical `CANCELED`, `NO_SHOW` or `COMPLETED` Visits do not prevent Job completion.
- The governing condition is that no remaining work requires another Visit, which the open-Visit invariant above makes checkable.
- Once completed:
  - the Job's terminal business history is preserved;
  - Visit outcomes become immutable (BR-079);
  - the Job Property cannot be changed (BR-056);
  - the Job may later be reopened (BR-063).

**Exceptions:**
None.

**Notes:**
The catalogue of `final_outcome` values is not defined.

**Status:**
**CONFIRMED** — explicit authorized completion
**OPEN QUESTION** — the `final_outcome` catalogue

---

## BR-063 — A completed or canceled Job may be reopened

**Description:**
Reopening returns the business request to the scheduling workflow.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Reopening transitions `COMPLETED → NEW` or `CANCELED → NEW` (BR-058).
- Reopening is the **only** way a Job reaches `NEW`: an open Job is never moved back to `NEW` (BR-058).
- Reopening never produces `IN_PROGRESS`; it does not imply that work is currently underway.
- Reopening does not modify or reopen historical completed or canceled Visits.
- Reopening does not create a Visit automatically; a new Visit represents the new field attempt (BR-051).
- Previous completion/cancellation history is preserved.
- An optional reopen reason may be recorded.
- Reopening is an authorized Manager/office action (BR-066).

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

## BR-064 — Job cancellation

**Description:**
Canceling a Job is an explicit business action, not a status edit.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- A Job may be canceled from `NEW`, `SCHEDULED`, `IN_PROGRESS` or `PENDING_REVIEW`, including after field work has started.
- Cancellation is a separate explicit action with its own requirements. It is not folded into the ordinary status selector, and it is not applied as a plain destination while the structured reason catalogue remains undefined.
- Every Job cancellation requires a structured cancellation reason and a mandatory explanation.
- The explanation is especially important when field work has already occurred.
- When a Job is canceled:
  - Job status becomes `CANCELED`;
  - cancellation history is preserved;
  - completed Visits remain historical;
  - open Visits follow the cascade rules (BR-065).
- Before the cancellation is confirmed, the UI should show the operational impact, including affected Visits and assigned technicians.

**Exceptions:**
None.

**Notes:**
The structured reason catalogue for Job cancellation is not defined; only the requirement for a structured reason plus a mandatory explanation is confirmed.

**Status:**
**CONFIRMED**
**OPEN QUESTION** — the Job cancellation reason catalogue

---

## BR-065 — Job cancellation cascade

**Description:**
Canceling a Job affects its open Visits through an explicit, recorded cascade.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

When a Job is canceled:

- `SCHEDULED` Visits are canceled automatically.
- `EN_ROUTE` Visits are canceled automatically.
- `ON_SITE` Visits require explicit confirmation.
- `IN_PROGRESS` Visits require explicit confirmation.

- The system must not silently cancel a technician who is currently on site or actively working.
- Cascade cancellation must be recorded in Visit status history (BR-074) and must preserve: cancellation status, cancellation reason, trigger = Job cancellation, actor, timestamp, and the relationship to the Job cancellation.
- Completed, canceled and no-show Visits are not affected.

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

## BR-066 — Job and Visit actions are authorized separately from field execution

**Description:**
Field execution and dispatch management are different authorization scopes.

**Applies to:**
Android, Angular, API

**Expected behavior:**

- Authorization is enforced by the API; UI restrictions are convenience only (BR-007).
- Technicians can operate the field execution lifecycle of their assigned Visits: `SCHEDULED → EN_ROUTE → ON_SITE → IN_PROGRESS → COMPLETED` (BR-074).
- Technicians cannot perform Job-level or dispatch-management actions, including: cancel a Job, cancel a Visit, mark `NO_SHOW`, reopen a Job, change Job status, assign or remove technicians, or change a Job's Property.
- The exception is an explicit direct scheduling permission: a technician granted that permission may schedule a follow-up Visit directly under BR-FV-011. The permission is granted through the normal permission model and is not implied by the default Technician role.
- Managers/authorized users perform those actions according to their permissions (BR-006).
- The implementation must support custom roles and direct member permissions rather than hard-coding these actions to the Manager system role alone (BR-004).

**Exceptions:**
None.

**Notes:**
The exact permission required for each manager action is part of the permission model (BR-006).

**Status:**
**CONFIRMED**

---

## BR-067 — Operational flexibility with explicit actions and complete history

**Description:**
Guiding principle for the Job, Property and Visit domain.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Servora favours operational flexibility, explicit actions and complete history.
- The system must not silently change business data or make irreversible operational decisions on behalf of the user.
- Where flexibility is allowed, history must preserve what actually happened.
- Status, schedule, assignment, cancellation and outcome history are append-only.
- When a rule requires a decision, the authorized user must make it explicitly.

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

## BR-082 — Property lifecycle: archive, restore and permanent deletion

**Description:**
A Property leaves active service through an explicit lifecycle state, not through generic deletion.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- **Archive is the normal way to remove a Property from active use.**
- Archiving is an explicit business state (`ACTIVE` / `ARCHIVED`), not generic soft deletion and not a side effect of any other action.
- An archived Property remains a real, retrievable business record with its identity, address and relationships intact.
- An archived Property can be **restored** to active use.
- Restoring returns the Property to `ACTIVE`. It never creates a new Property and never changes the Property's identity.
- **Permanent deletion** is allowed only when the Property has **never been referenced** by another business record — including a Job, a Visit, a Property ↔ Customer relationship, a note, an attachment, an address/use history entry or any other record.
- The Property's **own archive/restore lifecycle history is not a blocking reference**: it follows the Property and is removed with it, so archiving and restoring an otherwise never-used Property does not permanently trap it.
- A Property that has ever been referenced by another business record cannot be permanently deleted. Archiving is the available removal for it.
- Referenced records must never be cascade-deleted. Deleting a Property must never delete or rewrite Jobs, Visits, notes, attachments or history.
- Archive, restore and permanent deletion are explicit authorized actions (BR-085), never automatic.
- The API is authoritative for the Property's lifecycle state; clients never decide it (BR-001, BR-007).

**Exceptions:**
None.

**Notes:**

- The permanent-deletion test covers archived and historical references, not only currently active ones. The one exception is the Property's own lifecycle history (BR-086), which is deleted with the Property rather than blocking it.
- A Property created through the API always has an active Customer relationship, so it is not permanently deletable; archiving is its removal. Permanent deletion remains reachable for a Property that has no relationship and no other reference.
- Retention of an archived Property and its history is governed by the audit-retention open question (BR-033).
- This rule defines Property deletion only. Job deletion remains an **OPEN QUESTION** (BR-021) and is not resolved here.

**Status:**
**CONFIRMED**

---

## BR-083 — Archiving a Property does not disturb existing Jobs or Visits

**Description:**
Archiving stops a Property being chosen for new work while work already associated with it can finish normally.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

Archiving a Property:

- Blocks the creation of new Jobs for that Property.
- Removes the Property from normal active lists and Property selectors used to choose a location for new work.
- Does not cancel, archive, reschedule or otherwise modify existing Jobs or Visits.
- Allows existing `SCHEDULED` and ongoing Visits to continue.
- Allows an existing Visit to be rescheduled (BR-073).
- Allows additional Visits to be added to an existing open Job (BR-071).
- Preserves access to the Property from its active and historical Jobs.
- Preserves the Property ↔ Customer relationship (BR-050).
- Preserves the Job's frozen address snapshot (BR-056).
- May be performed even when active work exists, but Android must require **explicit confirmation** before doing so (BR-012, BR-070).

For the archive warning:

- An **active Job** is a Job in `NEW`, `SCHEDULED`, `IN_PROGRESS` or `PENDING_REVIEW` (BR-058). `COMPLETED` and `CANCELED` Jobs are not active.
- An **active Visit** is a Visit whose status is not `COMPLETED`, `CANCELED` or `NO_SHOW` (BR-074).

**Exceptions:**
None.

**Notes:**

- The archive-warning sets above are the sets product ownership defined for this warning. They are **not** the same classification as the derived "Needs Scheduling / No Active Visit" condition (BR-060), which considers only `NEW`/`IN_PROGRESS` Jobs and only `SCHEDULED`/`EN_ROUTE`/`ON_SITE`/`IN_PROGRESS` Visits and therefore excludes `DRAFT`.
- The two sets are kept as two named concepts rather than merged into one "active" vocabulary, because they answer different questions: `needsSchedulingActiveVisit` (BR-060) asks "does this Job need scheduling?", while `archiveWarningOpenWork` (this rule) asks "could archiving disturb current work?".
- The customer-detail Property projection (BR-081) excludes an archived Property, and the customer's `propertyCount` follows the same set.

**Status:**
**CONFIRMED** — archiving behaviour, including the `archiveWarningOpenWork` classification

---

## BR-084 — Editing and restoring a Property

**Description:**
Property data can be corrected in either lifecycle state, and restoring is a separate explicit action.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Authorized users can edit a Property whether it is `ACTIVE` or `ARCHIVED`.
- Editing an `ARCHIVED` Property does **not** restore it. The Property stays archived until an explicit restore.
- Restoring is a separate explicit action that makes the Property available for new Jobs again.
- Editing a Property's address never retroactively changes a historical address snapshot held by a Job or a Visit (BR-056, BR-057).
- Editing a Property does not change its identity and does not end its Property ↔ Customer relationship (BR-050).
- Whether a given client offers edit or restore actions for an archived Property is a presentation decision; the API remains authoritative (BR-001, BR-007).

**Exceptions:**
None.

**Notes:**

- Action labels and confirmation copy are localized (BR-028).
- Whether the Property's own address-change history must additionally be retained remains an **OPEN QUESTION** (BR-057), and a dedicated address-history table is **deferred** until an audit screen or a compliance requirement needs it. This rule only confirms that historical snapshots are not rewritten.

**Status:**
**CONFIRMED**

---

## BR-085 — Property permissions

**Description:**
Property management is authorized by its own capability set, not by a role name and not by the Customer capability set.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

The permission catalogue defines these stable, machine-readable capabilities:

| Permission           | Capability                                                                     |
| -------------------- | ------------------------------------------------------------------------------ |
| `properties.view`    | View Properties and resolve a Property where a location is displayed/selected. |
| `properties.create`  | Create a Property and relate it to a Customer.                                 |
| `properties.edit`    | Edit a Property.                                                               |
| `properties.archive` | Archive and restore a Property.                                                |
| `properties.delete`  | Permanently delete a Property that has never been referenced (BR-082).         |

- The codes follow the existing resource-prefixed `resource.action` convention already used by `customers.view`, `customers.create`, `customers.edit` and `customers.archive` (BR-006, BR-041).
- Creating a Property is a distinct, common permission boundary rather than a reuse of `properties.edit`: a role may legitimately add a service location without being allowed to change existing ones.
- Archive and restore share one capability because they are one lifecycle concern. Permanent deletion is a separate capability because it is destructive and irreversible.
- A Property is an independently identified entity (BR-049). Property capabilities are independent of `customers.*`; a Property capability is never implied by a Customer capability, and vice versa.
- Authorization must never depend on the Manager role or any role name. Custom roles and direct member permissions grant these capabilities (BR-004, BR-006).
- The API enforces every Property capability. Client-side visibility is never authorization (BR-007).

**Exceptions:**
None.

**Notes:**

- **Changed decision.** Property creation (`POST /customers/:id/properties`) was previously authorized by `customers.edit` as an interim decision, because no `properties.*` capability existed. Property actions now use Property capabilities: creation requires `properties.create` and the customer-detail Property projection requires `properties.view`. The interim `customers.edit` authorization no longer applies.
- The Manager system role receives all five Property capabilities (BR-008 direction); the Technician role receives none of them (BR-009).

**Status:**
**CONFIRMED** — `properties.view`, `properties.create`, `properties.edit`, `properties.archive`, `properties.delete`

---

## BR-086 — Property lifecycle actions are audited, offline-safe and API-authoritative

**Description:**
Update, archive, restore and permanent deletion are business actions with the domain's normal traceability, synchronization and authority expectations.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Every Property update, archive, restore and permanent deletion is performed by an authenticated, authorized member as an explicit action.
- Each action is recorded through the existing audit/history architecture (BR-033) so that who performed it, what changed, when, and the relevant previous/current state remain traceable.
- Lifecycle history is append-only. A restore records the previous archived state; it never rewrites or removes the archive event (BR-067).
- Archive and restore are **offline-capable** operations: they follow the project's offline/outbox conventions, are stored durably on the device until the backend accepts them, and are never silently lost (BR-013, BR-014, BR-031).
- **Permanent deletion is online-only.** Because it is destructive, irreversible and must be validated against every referencing record, it is executed against the API and is never queued as an offline operation (BR-014, BR-031).
- Concurrent changes are resolved by the API as the final authority. A client never overwrites a newer Property state, and a mutation that conflicts with newer state is rejected rather than applied (BR-001, BR-031, BR-032).
- Clients present the synchronization state the API reports; they never invent a different outcome.

**Exceptions:**
None.

**Notes:**

- The offline/outbox architecture standard to follow is `docs/architecture/offline-first-architecture.md`, recorded by `docs/decisions/012-property-lifecycle-and-permissions.md`. It defines the local store, outbox, idempotency, replay, synchronization-state and conflict model that archive and restore follow, so no one-off synchronization design is invented for Property lifecycle.
- The per-operation offline conflict strategy remains an open product question (BR-032). The standard defines the general model and records what a specific operation must still decide.

**Status:**
**CONFIRMED** — audit expectations, offline-capable archive/restore, online-only permanent deletion, API authority
**OPEN QUESTION** — the per-operation offline conflict strategy (BR-032)

---

## BR-087 — A Customer can be converted between individual and company

**Description:**
An authorized user may change an existing Customer from an individual to a company, or from a
company to an individual, as an explicit edit of that Customer. The Customer keeps its identity: it
is the same Customer, with the same Jobs, Properties, contacts, addresses and history.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- A Customer is converted by changing its `type` (`INDIVIDUAL` ⇄ `COMPANY`) (`BR-023`). There is no
  separate "convert" operation and no new Customer is created.
- The conversion is an explicit edit performed by an authorized user (`BR-067`); it is never a side
  effect of any other action.
- The edit must supply **the required fields of the new type**: an individual Customer's first and
  last name, or a company Customer's legal name. A conversion that omits them is rejected.
- **No field is mapped between the two subtype concepts.** An individual's name is never turned into
  a company's legal name and a company's name is never split into an individual's names. The
  converting user states the new type's values.
- The conversion is atomic: the Customer's `type` is changed, the previous subtype record is
  removed and the new matching subtype record is written in **one transaction**. There is no state in
  which a Customer's `type` and its subtype record disagree.
- The resulting invariant is unchanged: **exactly one subtype record exists and it matches
  `customers.type`** (`BR-023`).
- The conversion is recorded in Customer lifecycle/audit history (`BR-033`), carrying:
  the Customer, the previous type, the new type, the member who performed it, and the timestamp.
  The previous subtype's personal/business values are **not** copied into that record.
- The Customer's own data is otherwise unaffected: its identity, `displayName`, contact and billing
  fields, notes, preferred contact method, language and status are only changed if the same edit
  changes them. Its Jobs, Properties, Property relationships, contacts and addresses are untouched,
  and preserved address snapshots (`BR-056`, `BR-057`) are never rewritten.
- The backend authorizes the conversion with `customers.edit` and applies it; clients never decide
  it (`BR-001`, `BR-007`).

**Exceptions:**
None.

**Notes:**

- The conversion's own history is the confirmation required by `BR-067`; the lifecycle record is
  append-only and never rewritten.
- Field-level audit of the previous subtype values is not modelled. If a compliance or reporting
  requirement needs it, it is a later decision rather than something an implementation adds now
  (`BR-033`, `BR-042`).
- Removing the previous subtype record is the consequence of the type change, not a deletion of the
  Customer's business history: the subtype table holds the current type-specific values only, and the
  Customer's identity and relationships are preserved.

**Status:**
**CONFIRMED**

---

# 9. Customers

## BR-023 — Customers are a core Servora entity

**Description:**
Customers represent the people or businesses receiving Servora-managed services.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Customers can be created and managed by authorized users.
- Jobs may be associated with customers.
- Customer information is shared through the backend.
- Customers may be individual or company customers.
- Customers may have optional email, phone, billing email, billing phone, notes, preferred contact method, and language.
- Preferred contact method is one of `EMAIL`, `PHONE`, `SMS`, `NONE`.
- Customer language is one of `en-CA`, `fr-CA`.
- Customers may have multiple contacts.
- Customer contacts may be marked primary, billing contact, or job contact.
- Customers may have multiple addresses.
- Customer address type is one of `SERVICE`, `BILLING`, `OTHER`.
- A customer may have at most one default service address and at most one default billing address.
- Customer deletion is soft deletion, not physical deletion.
- Customer lists show non-deleted customers by default.
- Managers may filter customer lists to include deleted customers.
- Jobs for deleted customers are hidden by default.
- Managers may view jobs for a deleted customer only by explicitly including deleted customers and opening that customer's jobs.

**Exceptions:**
Customer address management and Property management remain separate. Properties are the service-location model for Jobs and Visits.

**Status:**
**CONFIRMED**

---

# 10. Technicians

## BR-024 — Technicians are field users

**Description:**
Technicians are users who perform field work through Servora.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Technicians authenticate as Servora users.
- The default system role for field technicians is `Tech`.
- Technicians can be associated with jobs.
- Technician-specific profile information may be stored where required by the product.

**Exceptions:**
Detailed technician profile, employment, skills, territory, availability, and lifecycle rules remain open.

**Status:**
**CONFIRMED** — core concept
**OPEN QUESTION** — detailed technician domain

---

# 11. Job Assignment

## BR-025 — Jobs may be assigned to technicians

**Description:**
Servora supports assigning field work to technicians.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Authorized users can assign work according to the assignment permissions.
- Assigned technicians can access the work relevant to their assignments.
- Assignment changes are authoritative only after acceptance by the backend.
- Assignment behavior must support future expansion to more sophisticated dispatch rules.

**Exceptions:**
Assignment eligibility, skills and territory rules remain open.

**Notes:**
Assignment is recorded at the Visit level (BR-068), assignment changes preserve history and require an explicit new Lead when the Lead is removed (BR-069), and availability conflicts warn rather than block in v1 (BR-070). Eligibility, skills, territory and travel/buffer rules are out of scope in v1.

**Status:**
**CONFIRMED** — assignment concept and Visit-level assignment rules (BR-068 – BR-070)
**OPEN QUESTION** — eligibility, skills, territory and travel/buffer rules

---

## BR-068 — Technician assignment occurs at the Visit level

**Description:**
Work is assigned to technicians on a Visit, not on a Job.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Technician assignment is recorded on the Visit.
- A Job's technicians are derived from the technicians assigned across its Visits.
- A Visit may have multiple technicians.
- While a Visit has technicians assigned, exactly one of them is the Lead.
- Other assigned technicians use the role code `TECHNICIAN`. Assignment role codes are stable, machine-readable values (`LEAD`, `TECHNICIAN`).
- Servora does not use the term "Helper".
- All assigned technicians are considered scheduled for the full Visit time window and all participate in conflict detection (BR-070).
- A Visit may exist as an unscheduled/draft Visit without technicians (BR-072).

**Exceptions:**
None.

**Notes:**
Example: `Mike → LEAD`, `John → TECHNICIAN`, `Sarah → TECHNICIAN`.

**Status:**
**CONFIRMED**

---

## BR-069 — Technician assignment changes preserve history

**Description:**
Assignments change during real operations, and history must not be rewritten.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Technicians may be added, removed, changed from `TECHNICIAN` to `LEAD`, and changed from `LEAD` to `TECHNICIAN`.
- Assignment changes are allowed during an active Visit.
- Assignment history is append-only; the system must never rewrite previous assignment history.
- If the current Lead is removed, the Manager/authorized user must explicitly select the new Lead as part of the same action.
- The system must never automatically promote another technician.

**Exceptions:**
None.

**Notes:**
Example history: `09:00 Mike assigned as LEAD`, `09:00 John assigned as TECHNICIAN`, `09:15 Sarah assigned as TECHNICIAN`, `09:30 John removed`, `09:45 Sarah promoted to LEAD`.

**Status:**
**CONFIRMED**

---

## BR-070 — Technician availability conflicts are surfaced, not hard-blocked

**Description:**
Servora warns about overlapping technician assignments in v1.

**Applies to:**
Android, Angular, API

**Expected behavior:**

- If the same technician is already assigned to another non-canceled Visit whose scheduled window overlaps the Visit being scheduled, the system must:
  - show the conflict,
  - identify the conflicting Visit,
  - show the conflicting time,
  - require explicit confirmation to proceed.
- Different technicians do not create a conflict.
- Multiple technicians on the same Visit are all considered booked for the complete Visit interval.
- The Job Owner does not participate in availability checks (BR-055).
- Conflict detection uses the internal scheduled start/end, which is authoritative (BR-072).
- Travel/buffer time is out of scope in v1.
- A confirmed conflict may proceed; in v1 the conflict is a warning, not a prohibition.

**Exceptions:**
None.

**Notes:**
Moving from a warning to a hard block is a product decision, not an implementation choice.

**Status:**
**CONFIRMED**

---

# 12. Scheduling & Visits

## BR-026 — Jobs may have scheduled work

**Description:**
Servora supports scheduling jobs for field execution.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**
Scheduled job information is shared between management and field workflows.

**Exceptions:**
Capacity planning, travel/buffer time and timezone behaviour remain open.

**Notes:**
Scheduling is performed on Visits (BR-071, BR-072). Rescheduling edits the existing Visit while it is `SCHEDULED` (BR-073). Technician conflicts warn rather than block in v1 (BR-070). Visit status (BR-074), status corrections (BR-075) and cancellation (BR-076) are defined.

**Status:**
**CONFIRMED** — scheduling concept and Visit scheduling rules (BR-071 – BR-076)
**OPEN QUESTION** — capacity planning, travel/buffer time and timezone behaviour

---

## BR-071 — A Visit represents one field attempt

**Description:**
A Visit is the unit of field execution for a Job.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- A Visit represents one field attempt to perform work for a Job.
- A Job may have multiple Visits (BR-047).
- A Visit is not created merely because a Job exists; a new Visit represents a new field attempt.
- A Visit may exist as an unscheduled/draft Visit without technicians.
- A Visit cannot be scheduled without a Property (BR-072).
- Visit status (BR-074) and Visit outcome (BR-077, BR-078) are separate concepts and must not be merged.

**Exceptions:**
None.

**Notes:**
Example: `Job #1042` with `Visit #1 — Diagnosis`, `Visit #2 — Repair`, `Visit #3 — Follow-up`.

**Status:**
**CONFIRMED**

---

## BR-072 — Visit scheduling

**Description:**
A Visit carries internal scheduling and an optional customer-facing window.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

A Visit has:

- an internal scheduled start,
- an internal scheduled end,
- an optional customer-facing arrival window.

- Internal scheduling is authoritative for dispatch and conflict detection (BR-070).
- The customer-facing arrival window is optional; when it is not supplied, the UI may present the scheduled time.
- A Visit cannot become `SCHEDULED` unless:
  - the Job has a Property (BR-056),
  - the scheduled start/end are valid,
  - at least one technician is assigned,
  - exactly one assigned technician is Lead (BR-068),
  - scheduling conflict checks have been performed (BR-070),
  - any detected conflicts have been explicitly confirmed by the authorized user.
- A Visit may exist as an unscheduled/draft Visit without technicians.

**Exceptions:**
None.

**Notes:**
A Job cannot be scheduled until a Property exists (BR-056).

**Status:**
**CONFIRMED**

---

## BR-073 — Visit rescheduling edits the existing Visit

**Description:**
Rescheduling is a schedule change, not a new Visit.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Rescheduling edits the existing Visit; the Visit identity does not change.
- Rescheduling is permitted only while the Visit is `SCHEDULED`.
- Every schedule change is recorded in schedule history.
- Schedule history records: previous schedule, new schedule, actor, timestamp, and an optional reason.
- A Visit that is `EN_ROUTE`, `ON_SITE`, `IN_PROGRESS`, `COMPLETED`, `CANCELED` or `NO_SHOW` cannot be rescheduled.
- If another field attempt is required, a new Visit is created (BR-071).

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

## BR-074 — Visit status lifecycle

**Description:**
Visit status represents the field execution lifecycle.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

A Visit has exactly one status from a shared, backend-validated vocabulary:

| Code          | Meaning                                  |
| ------------- | ---------------------------------------- |
| `DRAFT`       | Created; not scheduled                   |
| `SCHEDULED`   | Scheduled (BR-072)                       |
| `EN_ROUTE`    | Technician is travelling to the Property |
| `ON_SITE`     | Technician has arrived                   |
| `IN_PROGRESS` | Work is being performed                  |
| `COMPLETED`   | Field attempt completed (BR-077)         |
| `CANCELED`    | Canceled (BR-076)                        |
| `NO_SHOW`     | The field attempt could not be performed |

Normal lifecycle:

```text
DRAFT → SCHEDULED → EN_ROUTE → ON_SITE → IN_PROGRESS → COMPLETED
```

Terminal alternatives:

```text
SCHEDULED   → CANCELED
EN_ROUTE    → CANCELED
ON_SITE     → CANCELED
IN_PROGRESS → CANCELED

SCHEDULED   → NO_SHOW
```

- Once a Visit is `COMPLETED`, `CANCELED` or `NO_SHOW` it is historical and cannot return to an active state.
- Every transition is validated by the backend (BR-022); clients must not invent their own Visit status vocabulary (BR-041).
- Status history is append-only.

**Exceptions:**
None.

**Notes:**
Who may drive each transition is defined by BR-066 and the permission model (BR-006).

**Status:**
**CONFIRMED**

---

## BR-075 — Visit status corrections preserve history

**Description:**
Operational mistakes must be correctable without rewriting history.

**Applies to:**
Android, Angular, API

**Expected behavior:**

- At minimum, `EN_ROUTE → SCHEDULED` is allowed as an explicit correction (a technician may leave without arriving).
- A correction preserves the original status event, records the correction, does not create a new Visit, and does not change the Visit identity.
- Status history remains append-only.
- Corrections are performed by an authorized user according to permissions (BR-006, BR-066).

**Exceptions:**
None.

**Notes:**
The complete set of permitted corrections and the exact permission required for each are not defined.

**Status:**
**CONFIRMED** — the explicit `EN_ROUTE → SCHEDULED` correction
**OPEN QUESTION** — the complete set of permitted corrections and their permissions

---

## BR-076 — Visit cancellation

**Description:**
Canceling a Visit is an explicit action with a structured reason.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Canceling a Visit does not automatically change the Job status (BR-058).
- A canceled Visit requires a structured cancellation reason from the shared vocabulary:

| Code                   | Meaning                                       |
| ---------------------- | --------------------------------------------- |
| `CUSTOMER_RESCHEDULED` | The Customer requested a different time       |
| `CUSTOMER_CANCELED`    | The Customer canceled the work                |
| `WEATHER`              | Conditions prevented the field attempt        |
| `TECH_UNAVAILABLE`     | No technician could perform the field attempt |
| `DUPLICATE`            | The Visit duplicated another Visit            |
| `OTHER`                | Any other reason                              |

- An explanation/note may accompany the cancellation.
- `OTHER` requires an explanation.
- Cancellation history must identify whether the cancellation was manually initiated or caused by Job cancellation (BR-065).
- Cancellation caused by Job cancellation must be identifiable as a cascade from the Job.

**Exceptions:**
None.

**Notes:**
Whether organizations may add their own cancellation reasons is not defined.

**Status:**
**CONFIRMED** — reasons and the mandatory structured reason
**OPEN QUESTION** — organization-specific cancellation reasons

---

## BR-077 — Visit completion requires an outcome

**Description:**
A Visit cannot be completed without recording what resulted from the field attempt.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- A Technician must provide an outcome before completing a Visit.
- Required: outcome type (BR-078) and outcome summary.
- Work notes/comments are optional but strongly encouraged.
- Photos, audio and files are optional in v1 unless a future business workflow explicitly requires evidence (BR-027).
- The outcome records the authoring actor and a timestamp.
- A Visit may be `COMPLETED` while the Job remains `IN_PROGRESS` (BR-059).

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

## BR-078 — Visit outcome types and follow-up semantics

**Description:**
The outcome records what resulted from the field attempt.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

The initial outcome types are:

| Code                   | Meaning                                 | Follow-up                   |
| ---------------------- | --------------------------------------- | --------------------------- |
| `RESOLVED`             | The work was completed successfully     | None expected               |
| `NEEDS_PARTS`          | The work requires parts                 | Follow-up expected          |
| `NEEDS_FOLLOWUP`       | The work requires another field attempt | Follow-up expected          |
| `NEEDS_QUOTE_APPROVAL` | The work requires a quote/approval      | Business follow-up expected |
| `UNABLE_TO_COMPLETE`   | The work could not be completed         | Follow-up expected          |

- Outcome describes what resulted from the field attempt; Visit status describes whether the attempt has completed (BR-074). They must not be merged.
- Follow-up expectation is derived from the outcome type; no separate `follow_up_required` boolean is modelled.
- A new field attempt is represented by a new Visit, not by reopening or editing a completed Visit (BR-071).
- Outcome codes are stable, machine-readable values; their labels are localized (BR-028, BR-041).

**Exceptions:**
None.

**Notes:**
Whether additional outcome types may be added is a future product decision.

**Status:**
**CONFIRMED**

---

## BR-079 — Visit outcome corrections are recorded and outcomes become immutable

**Description:**
An outcome may be corrected while it is still actionable, but not after the Job is closed.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Technicians may correct their own Visit outcome subject to the future edit policy.
- Managers/authorized office users may edit outcomes according to their permissions (BR-006, BR-066).
- Every outcome change preserves: previous outcome, new outcome, actor, timestamp, and an optional reason.
- Once the Job is `COMPLETED` (BR-062), Visit outcomes are immutable.

**Exceptions:**
None.

**Notes:**
The technician self-edit time window/policy is not defined. It is independent of the Job-level immutability rule, which is confirmed.

**Status:**
**CONFIRMED** — recorded corrections and immutability after Job completion
**OPEN QUESTION** — the technician self-edit window/policy

---

# 12A. Follow-Up Visit Requests

## BR-FV-001 — Technician may request a follow-up visit

**Description:**
A technician assigned to a Visit may indicate that additional on-site work is required and submit a request for a follow-up Visit.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- The request is associated with the Job.
- The request identifies the source Visit that caused the need for additional work (BR-FV-008).
- The request is an operational decision record; it is not itself a Visit (BR-FV-002).

**Status:**
**CONFIRMED**

---

## BR-FV-002 — Follow-up request is not a scheduled Visit

**Description:**
Submitting a follow-up request does not automatically create or schedule a new Visit.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- A request remains pending until reviewed by an authorized office user, unless the technician has explicit scheduling permission (BR-FV-011).
- A pending request does not count as an active or scheduled Visit for scheduling, conflict detection, or the derived "Needs Scheduling / No Active Visit" condition (BR-060).
- The Job may remain open because of the request, but no Visit exists until approval creates one (BR-FV-005).

**Status:**
**CONFIRMED**

---

## BR-FV-003 — Technician provides proposed scheduling information

**Description:**
When requesting a follow-up Visit, the technician may provide proposed scheduling information.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

The request may include:

- reason for the follow-up;
- preferred or customer-agreed date/time;
- expected duration;
- relevant notes;
- whether the same technician is preferred.

The proposed date/time is informational until approved. It is not the Visit's authoritative schedule (BR-072).

**Status:**
**CONFIRMED**

---

## BR-FV-004 — Office controls final scheduling

**Description:**
An authorized office user reviews and decides follow-up requests.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

A Manager, Dispatcher, Scheduler, or another member with the appropriate permission may:

- accept the proposed schedule;
- change the date/time;
- assign different technician(s);
- return the request for clarification;
- reject the request if no additional Visit is required.

Authorization is permission-based and must not depend on role display names (BR-004, BR-006).

**Status:**
**CONFIRMED**

---

## BR-FV-005 — Approval creates the Visit

**Description:**
A new Visit is created only when an authorized user approves and schedules the follow-up request.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Approval creates a new Visit on the same Job.
- The created Visit follows the normal Visit scheduling and assignment rules (BR-068, BR-070, BR-072).
- Approval records the created Visit so the request and resulting Visit remain traceable.

**Status:**
**CONFIRMED**

---

## BR-FV-006 — Technician may continue without knowing the final schedule

**Description:**
The technician may complete the current Visit after submitting a follow-up request.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- The current Visit can be completed according to the normal Visit completion rules (BR-077, BR-078).
- The follow-up request remains associated with the Job for office review.
- The technician does not need to know the final follow-up schedule in order to complete the current Visit.

**Status:**
**CONFIRMED**

---

## BR-FV-007 — Job remains open when additional work is required

**Description:**
If a completed Visit indicates that additional work is required, the Job must not be considered completed solely because the Visit was completed.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- The Job remains open until the required follow-up work is resolved or an authorized user determines that no further Visit is necessary.
- A pending follow-up request prevents the Job from being treated as ready for completion.
- Rejecting a follow-up request because no additional Visit is required is an explicit office decision and must be recorded.

**Status:**
**CONFIRMED**

---

## BR-FV-008 — Follow-up request retains its source Visit

**Description:**
Every follow-up request must reference the Visit that caused the request.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- The request stores the source Visit identifier.
- The source Visit must belong to the same Job and organization as the request.
- The source reference is preserved so the business can trace why additional work was requested.

**Status:**
**CONFIRMED**

---

## BR-FV-009 — Multiple follow-up requests are allowed

**Description:**
A Job may require multiple Visits.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Each completed Visit may independently result in another follow-up request.
- Multiple requests may exist for the same Job over time.
- Each request carries its own source Visit, status, decision and audit information.

**Status:**
**CONFIRMED**

---

## BR-FV-010 — Customer agreement does not bypass office approval

**Description:**
A technician may agree on a preferred return time with the customer, but that agreement is not a confirmed Servora appointment.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Customer-agreed timing is stored as proposed scheduling information (BR-FV-003).
- The appointment is not confirmed until an authorized user approves and schedules it, unless the technician has explicit scheduling permission (BR-FV-011).

**Status:**
**CONFIRMED**

---

## BR-FV-011 — Authorized technicians may schedule directly

**Description:**
A company may grant selected technicians explicit scheduling permission.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- A technician with explicit scheduling permission may schedule a follow-up Visit directly instead of submitting it for office approval.
- Without that permission, technicians may only request follow-up Visits.
- Direct scheduling still follows the normal Visit scheduling, assignment, conflict and audit rules (BR-067, BR-068, BR-070, BR-072).

**Status:**
**CONFIRMED**

---

## BR-FV-012 — Follow-up request status must be explicit

**Description:**
A follow-up request must have an explicit lifecycle.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- The request lifecycle uses stable status codes.
- The minimum lifecycle is `PENDING → APPROVED | REJECTED`.
- A request returned for clarification remains unresolved until it is later approved or rejected.
- If the office modifies the technician's proposed schedule before approval, the request is still considered approved once the resulting Visit is created.

**Status:**
**CONFIRMED**

---

## BR-FV-013 — Follow-up decision must be auditable

**Description:**
Follow-up request decisions are business decisions and must be traceable.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Servora retains who requested the follow-up and when.
- Servora retains who approved, rejected, or returned the request for clarification and when.
- Approval records the resulting Visit.
- Rejection records that no further Visit is required or why the request was refused.

**Status:**
**CONFIRMED**

---

# 13. Evidence, Activity & History

## BR-027 — Technician-generated evidence has business value

**Description:**
Information generated during field work may provide operational, customer-service, billing, and accountability value.

**Applies to:**
Android, Angular, API, Database

**Examples include:**

- Photos
- Notes
- Comments
- Work timestamps
- Completion information
- Location information
- Status changes
- Assignment changes

**Expected behavior:**

- Important evidence must not be silently discarded.
- Evidence must remain associated with the appropriate business entity.
- Retention and audit behavior must be defined explicitly rather than inferred.

**Exceptions:**
None — retention, removal, visibility and audit of evidence are defined by `BR-088` – `BR-091`.

**Notes:**
Photos, audio and files are optional Visit evidence in v1 unless a future business workflow explicitly requires them (BR-077). Job Activity is a derived read model over the authoritative records (BR-080). The evidence lifecycle — what makes evidence immutable, how it may be removed, how long it is kept, and what metadata it carries — is defined by `BR-088` – `BR-091`.

**Status:**
**CONFIRMED** — evidence-preservation principle
**CONFIRMED** — retention, removal, visibility and audit defined by `BR-088` – `BR-091`

---

## BR-080 — Job Activity is a unified, derived read model

**Description:**
Job Activity gives a chronological account of everything that happened to a Job.

**Applies to:**
Android, Angular, API

**Expected behavior:**

- Job Activity is a unified chronological read model over a Job.
- It may include events originating from:
  - Job status changes,
  - Visit status changes,
  - Visit notes,
  - photos, audio and files,
  - outcome changes,
  - assignment changes,
  - schedule changes,
  - Property changes,
  - cancellation and reopen actions.
- Activity is a projection of authoritative domain records and must not become a duplicate source of truth.
- Activity communicates who → when → what happened, and is ordered newest-first by default.
- The domain must not depend on any particular UI presentation of activity (BR-047).

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

## BR-081 — Customer detail projections are derived, not stored

**Description:**
The customer detail view presents a customer's Properties and Jobs. The values shown on those rows
are derived projections over authoritative records, not stored fields.

**Applies to:**
Android, Angular, API

**Expected behavior:**

- Nothing in this rule is stored. Every value is derived from authoritative records and must never
  become a duplicate source of truth (`BR-001`, `BR-080`, `BR-042`).
- **Property row** (an `ACTIVE` Property the customer is currently related to, `BR-050`, `BR-082`):
  - `jobCount` is the total number of Jobs associated with the Property (`BR-048`, `BR-056`),
    whatever their status.
  - `lastServiceDate` is the scheduled date of the most recent Visit with status `COMPLETED`
    (`BR-074`) across all Jobs associated with the Property. When no such Visit exists, no date is
    shown and the client presents a localized "never serviced" state.
  - An `ARCHIVED` Property is excluded from the default projection, and the customer header's
    `propertyCount` counts the same set, so the header and the list agree. The archived Property
    remains a retrievable business record, reached through the explicit archived/history views
    (`BR-082`, `BR-083`), which this rule does not define.
- **Job row** (a Job belonging to the customer, `BR-048`):
  - Exactly one **selected Visit** is chosen for the Job. The displayed date and the displayed
    technicians both come from that same selected Visit; they are never taken from different Visits.
  - The selected Visit is the Job's earliest **upcoming** Visit whose scheduled start is at or after
    the current time and whose status is not `CANCELED` (`BR-074`), ordered by scheduled start.
  - When the Job has no such upcoming Visit, the selected Visit is the Job's Visit with the most
    recent past scheduled start.
  - The displayed date is the selected Visit's scheduled start.
  - The displayed technicians are the technicians currently assigned to the selected Visit
    (`BR-068`), with the Lead first.
  - When the Job has no selected Visit, or the selected Visit has no assigned technicians, no
    technicians are shown and the client presents a localized "unassigned" state. A Job with no
    Visit shows no date and the "unassigned" state.
- The API exposes these projections so that every client presents the same values. A client must not
  derive a different definition of the same value locally (`BR-041`).
- The projections carry stable identifiers and dates, never localized display text (`BR-028`,
  `BR-041`).

**Exceptions:**
None.

**Notes:**

- The past-Visit fallback is defined over any past Visit by scheduled start. It does not exclude a
  `CANCELED` past Visit; only the upcoming candidate excludes `CANCELED`. This is the literal reading
  of the confirmed decision and is flagged for product confirmation.
- A Job has no scheduled date of its own; scheduling is performed on Visits (`BR-072`). The Job row's
  date is therefore the selected Visit's scheduled start, not a Job field.
- Ordering and row limits of the customer's Property and Job lists are presentation concerns and are
  not fixed by this rule.
- Row-level actions on these projections (open a Job, add a Property) are governed by their own
  permissions (`BR-006`) and are not defined by this rule.
- Archiving a Property removes it from normal active lists and Property selectors (`BR-083`). The
  default projection is therefore the customer's current `ACTIVE` relationships (`BR-050`,
  `BR-082`): an `ARCHIVED` Property, and its contribution to the customer's `propertyCount`, are
  excluded. The archived Property is not deleted and stays retrievable through the explicit
  archived/history views.

**Status:**
**CONFIRMED**

---

## BR-088 — Evidence is immutable once it is accepted

**Description:**
A captured photo or audio recording is **draft material** until the update it belongs to is submitted and the backend accepts it. From the moment the backend has recorded it, evidence is **immutable historical evidence**. Servora's guiding position for evidence is: append-only once accepted, offline-first for the technician, and a storage lifecycle that follows the Job/evidence lifecycle rather than UI convenience.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Before submission evidence is draft material: the technician may discard it, re-take it, change its phase and change its note. Nothing has been recorded and nothing is evidence yet.
- **Acceptance is the boundary.** Evidence becomes immutable when the API has recorded it (BR-001). A client's own confirmation is not the boundary; the backend's acceptance is.
- After acceptance, no operation may overwrite the evidence, replace its bytes, change its phase, edit its note in place, or silently delete it.
- Servora provides **no edit-evidence operation**. A correction is a new, explicit, audited action — a new update, or a removal under BR-089 — never a mutation of what was recorded.
- A note or caption correction is recorded as new Activity, so the original remains readable (BR-067, BR-080).
- The API is the authority for both the acceptance boundary and the immutability that follows it (BR-007).

**Exceptions:**
None.

**Notes:**
Discarding a draft is not a removal: a photo the technician discards before submitting was never evidence, and BR-014 governs it while it is pending. Job Activity is a derived read model (BR-080) and never the record it derives from.

**Status:**
**CONFIRMED**

---

## BR-089 — Removing accepted evidence is explicit, authorized and audited

**Description:**
Accepted evidence may be taken out of ordinary use, but only through an explicit, authorized, recorded action. A removal never rewrites history.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- A Technician cannot remove accepted evidence. A technician may only discard their own unsubmitted draft (BR-088).
- Removing accepted evidence requires the dedicated capability `evidence.photo.remove`, which follows BR-006's `resource.action` convention and is a **Manager-level** capability: it is granted to the default Manager role and not to the default Technician role.
- Removal is **soft**: the evidence disappears from ordinary views — technician field views in particular — while its record and its history are preserved.
- The removal records the actor, the timestamp and the reason (BR-033, BR-067).
- The record stays append-only: a removal is added history, never a deletion of the record or of what it previously held.
- Soft-removed evidence remains visible in an audit/history context to authorized managers.
- Physically purging the stored object is a retention concern (BR-090) and is separate from the removal itself.
- The API enforces the capability; a hidden or disabled control is never the boundary (BR-007).

**Exceptions:**
None.

**Notes:**
The exact default-role grant and the bilingual capability name and description (BR-005) are recorded when the capability is created. A structured removal-reason catalogue is not defined; if one is required it is a product decision, not something an implementation invents (BR-042).

**Status:**
**CONFIRMED**

---

## BR-090 — Evidence retention and visibility follow the Job's lifecycle

**Description:**
Evidence belongs to the historical Job record, and its retention follows that record rather than UI convenience.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- Archiving or canceling a Job **never deletes its evidence**.
- Archived Jobs and their evidence remain readable to members whose permissions allow viewing archived Jobs (BR-082, BR-083).
- In v1 retention is **indefinite for as long as the tenant/account exists**. Servora does not invent a regulatory retention period it cannot universally justify.
- Configurable retention is a later capability for tenants with their own compliance requirements; it is not implemented in v1.
- Servora does **not** support hard Job deletion in normal product flows: a Job is archived or canceled, not deleted. Evidence objects therefore never become orphaned through Job deletion.
- If a future administrative or GDPR-style hard purge is introduced, it is a deliberate **cascading purge** — Job → Visits → evidence records → storage objects and derived objects — with object deletion performed asynchronously and idempotently.
- A storage object must never outlive its database record accidentally.

**Exceptions:**
None.

**Notes:**
Job deletion itself remains an open question (BR-021). This rule confirms only that normal product flows do not hard-delete Jobs, and defines the shape any future purge must take.

**Status:**
**CONFIRMED**
**OPEN QUESTION** — configurable per-tenant retention, and the administrative purge workflow itself

---

## BR-091 — Evidence classification, metadata and evidence kinds

**Description:**
Evidence carries only the classification and metadata Servora needs, and the kinds of evidence are explicit.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

- `phase` — `BEFORE_WORK`, `DURING_WORK`, `AFTER_WORK` — is the **only** structured classification of a photo in v1. No tags or categories are introduced; the technician's note covers anything further.
- The selected phase is **draft state for the update in progress**: it survives a sheet or process recreation while that update is unfinished, and it must not become a global preference that leaks into another Job or another update.
- Servora strips **GPS/location EXIF and other metadata it does not need** from uploaded evidence by default. Only what the application explicitly needs is preserved — normalized orientation and dimensions, and Servora's own capture/upload timestamps.
- EXIF GPS is **never** treated as Job-location evidence. Location would require an explicit product feature with clear disclosure (BR-038).
- **Audio notes are evidence** of their own kind, using the same storage abstraction, offline outbox, upload lifecycle, authorization and immutable-history rules as photos. Audio's capability (`evidence.audio.add`) is activated when the audio feature is implemented.
- **Generic file attachments** — documents, PDFs, arbitrary files — are **out of scope in v1**.
- A kind's capability and affordance are not exposed in any client before the kind actually works (BR-042).

**Exceptions:**
None.

**Notes:**
How audio is modelled (a kind on one evidence model versus a parallel structure), its content-type and size vocabulary, and its playback surfaces are that feature's design decisions, recorded in an ADR when it lands. Label localization is unchanged (BR-028).

**Status:**
**CONFIRMED** — `phase` is the only photo classification, phase as draft state, metadata stripping, audio is evidence, generic files out of scope
**OPEN QUESTION** — the audio evidence data model, its content types and its size limits

---

# 14. Localization

## BR-028 — Servora supports English and French

**Description:**
Servora is bilingual and must support English and French across user-facing product experiences.

**Applies to:**
Android, Angular, API

**Expected behavior:**

- User-facing product content supports English and French.
- Business data requiring bilingual presentation stores the necessary language-specific values.
- Stable machine-readable identifiers are not translated.
- Authorization logic does not depend on translated display text.

**Exceptions:**
Third-party content may have its own localization limitations.

**Status:**
**CONFIRMED**

---

# 15. Notifications

## BR-029 — Notification behavior must be explicitly defined

**Description:**
Servora may notify users about important events.

**Applies to:**
Android, Angular, API

**Expected behavior:**
Notification behavior must be defined per event and audience.

**Exceptions:**
None.

**Status:**
**OPEN QUESTION**

---

# 16. Reporting

## BR-030 — Reporting is a management capability

**Description:**
Servora will provide reporting capabilities primarily through the Angular application.

**Applies to:**
Angular, API

**Expected behavior:**

- Reports use authoritative backend data.
- Reports respect the requesting user's permissions.
- Reports must not expose information the user is not authorized to access.

**Exceptions:**
Specific reports, metrics, filters, exports, and retention rules remain open.

**Status:**
**CONFIRMED** — capability direction
**OPEN QUESTION** — individual reports and metrics

---

# 17. Offline Synchronization

## BR-031 — Offline synchronization must preserve business integrity

**Description:**
Offline synchronization is a product requirement for field work, not merely a technical optimization.

**Applies to:**
Android, API, Database

**Expected behavior:**

- Offline-capable operations are stored durably on the device.
- Synchronization occurs when connectivity becomes available.
- Operations are not considered authoritative until accepted by the backend.
- Duplicate submissions must not create duplicate business outcomes.
- Synchronization must preserve technician work.
- Conflicts must be handled according to explicit business rules.
- The backend remains authoritative.

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

## BR-032 — Offline conflict behavior must not be invented

**Description:**
Different business entities may require different conflict strategies.

**Applies to:**
Android, API, Database

**Expected behavior:**

- Conflict behavior is explicitly defined for each offline-capable business operation.
- The implementation must not blindly apply last-write-wins to important business data.
- If conflict behavior has not been defined, it remains an open product question.

**Exceptions:**
None.

**Status:**
**CONFIRMED** — principle
**OPEN QUESTION** — per-entity conflict rules

---

# 18. Auditability

## BR-033 — Important business changes should be traceable

**Description:**
Servora needs sufficient history to understand important changes to business data and field work.

**Applies to:**
API, Database, Angular, Android

**Expected behavior:**

Important business events may require recording:

- Who performed the action
- What changed
- When it changed
- Relevant previous/current state
- Related evidence

**Exceptions:**
Exact audit scope, retention, visibility, and immutable-history requirements remain open.

**Status:**
**CONFIRMED** — traceability principle
**OPEN QUESTION** — detailed audit rules

---

# 19. Future Domain Areas

The following capabilities are expected areas of the Servora product but their detailed business behavior has not yet been defined.

## BR-034 — Time tracking

**Description:**
Rules for starting, pausing, resuming, completing, correcting, and potentially billing time.

**Status:**
**OPEN QUESTION**

---

## BR-035 — Parts and materials

**Description:**
Rules for recording parts/materials used on jobs, inventory implications, quantities, and technician permissions.

**Status:**
**OPEN QUESTION**

---

## BR-036 — Assets and equipment

**Description:**
Rules for customer assets/equipment, their relationship to jobs, and their lifecycle.

**Status:**
**OPEN QUESTION**

---

## BR-037 — Invoices and billing

**Description:**
Rules for invoicing, billing basis, approval, payment status, and relationships to jobs and evidence.

**Status:**
**OPEN QUESTION**

---

## BR-038 — GPS and location

**Description:**
Rules governing when location is captured, precision, storage, visibility, retention, and business use.

**Status:**
**OPEN QUESTION**

---

## BR-039 — Organizations and multi-tenancy

**Description:**
Rules governing organizations, organization membership, tenant isolation, invitations, subscriptions, and organizational administration.

**Expected behavior:**

- Organizations are Servora client tenants.
- Organization-owned business data is isolated by organization.
- Organization administration, invitations, subscriptions and owner/admin workflows are handled later in the Angular owner/admin application.
- No additional organization lifecycle, subscription, invitation or ownership behavior is implemented in the core field-service schema until that Angular owner/admin slice is designed.

**Status:**
**CONFIRMED**

---

# 20. Business Rule Maintenance

## BR-040 — Business rules are changed explicitly

A confirmed business rule must not be silently changed.

When product ownership changes a confirmed rule:

1. Record the new decision.
2. Update this document.
3. Identify affected product/architecture/API documentation.
4. Update affected implementation plans.
5. Ensure clients and backend remain consistent.

Historical implementation details belong in the project tracker, ADRs, or relevant technical documentation rather than this document.

**Status:**
**CONFIRMED**

---

## BR-041 — Shared business vocabulary has one definition

**Description:**
Shared concepts must have one canonical definition across Servora.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

Shared concepts such as:

- Roles
- Permissions
- Job statuses
- Activity types
- Error categories
- Synchronization operation types

must not receive conflicting definitions in individual applications.

The API contract and shared domain definitions provide the implementation-level representation of these concepts.

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

# 21. Product Decision Principle

## BR-042 — Do not invent business behavior

When a requirement is not defined by a **CONFIRMED** or **PLANNED** rule, the implementation must not create business behavior merely because it appears technically convenient.

Examples:

- Do not invent a job status.
- Do not invent a permission.
- Do not assume who can delete a record.
- Do not assume how conflicts are resolved.
- Do not assume notification behavior.
- Do not assume billing rules.
- Do not assume technician eligibility rules.
- Do not assume retention periods.

Instead, identify the **OPEN QUESTION** and obtain the product decision.

**Status:**
**CONFIRMED**

---

# 22. Current Product Direction

At the current stage of Servora, the product is intentionally being built incrementally.

The initial foundation is:

**Backend authority → authentication → users/roles/permissions → customers → technicians → jobs → assignment → field execution → offline synchronization → management/reporting**

Additional domains are added only when their business rules are sufficiently defined.

The goal is to maintain a small, coherent business contract rather than prematurely specifying behavior that the product has not yet decided.

**Final principle:**

> **Servora implements confirmed product behavior, not assumptions.**
>
> **When the business does not yet have an answer, the rule stays open.**
