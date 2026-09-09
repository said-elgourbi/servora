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

* **CONFIRMED** rules
* **PLANNED** rules
* technical requirements documented in the appropriate technical/architecture documents

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

* Business data is persisted and governed by the backend.
* PostgreSQL is the authoritative persistence layer.
* The API is the authoritative business-operation layer.
* Android and Angular do not maintain an independent authoritative copy of business data.
* Client-side state exists for presentation, caching, offline operation, and temporary work only.
* Backend state always takes precedence when authoritative data is synchronized.

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

* Android supports both Manager and Technician workflows.
* Angular provides the broader management and administrative experience.
* Both applications consume the same backend API and business model.
* Business behavior must remain consistent across clients.
* A capability must not behave differently merely because it is accessed from Android versus Angular.

**Exceptions:**
The user experience may differ between clients according to device, workflow, and audience.

**Status:**
**CONFIRMED**

---

# 3. Users, Roles & Permissions

## BR-003 — Servora has two default system roles

**Description:**
Servora starts with exactly two system roles:

* `Manager`
* `Tech`

These roles define the default product audiences.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

* A new Servora organization has the Manager and Tech system roles available.
* Manager represents the management/office audience.
* Tech represents the field technician audience.
* Additional operational roles are not hard-coded into the application.
* Managers may create additional custom roles later.

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

* Dispatcher
* Scheduler
* Lead Technician
* Office Administrator
* Service Coordinator

These are examples of custom roles, not Servora system roles.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

* Custom roles are organization-specific.
* A custom role has a name, description, and permission set.
* Custom roles are permission-based.
* The application must not contain hard-coded behavior for names such as `Dispatcher` or `Lead Technician`.
* A Manager may create and manage custom roles according to the authorization rules.

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

* Roles store an English name and description.
* Roles store a French name and description.
* The appropriate language is displayed according to the user's selected/application language.
* Custom roles must provide bilingual role information.
* Authorization must not depend on translated display text.

**Exceptions:**
None.

**Notes:**
System role identifiers remain stable and machine-readable. Display names are separate from authorization identifiers.

**Status:**
**CONFIRMED**

---

## BR-006 — Authorization is permission-based

**Description:**
Servora authorizes actions using permissions rather than role-name checks.

The conceptual model is:

`User → Roles → Permissions → Authorization decision`

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

* Users may have one or more roles.
* Effective permissions are determined from the user's assigned roles.
* Permission identifiers are stable and machine-readable.
* Applications use permissions to determine whether functionality should be available.
* Role names must not be used as a substitute for permission checks.
* The backend is the final authority for authorization.

**Exceptions:**
The application may use system-role information for audience-specific UX decisions, such as choosing an appropriate home screen, but this does not replace permission enforcement.

**Status:**
**CONFIRMED**

---

## BR-007 — Frontend authorization is never trusted

**Description:**
Client-side authorization exists to control the user experience, not to provide security.

**Applies to:**
Android, Angular, API

**Expected behavior:**

* UI elements may be shown, hidden, enabled, or disabled according to the user's permissions.
* A hidden button does not constitute authorization.
* A modified client must not be able to bypass backend authorization.
* Every protected business operation is validated by the backend.
* Unauthorized API operations are rejected by the backend.

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

## BR-008 — Manager default permissions

**Description:**
The default Manager role has full CRUD capability for the initial core entities:

* Jobs
* Technicians
* Customers

**Applies to:**
Android, Angular, API

**Expected behavior:**

A Manager can, by default:

* Create jobs
* View jobs
* Update jobs
* Delete jobs
* Create technicians
* View technicians
* Update technicians
* Delete technicians
* Create customers
* View customers
* Update customers
* Delete customers

These capabilities are subject to any later domain-specific business rules concerning deletion, lifecycle, historical records, or dependencies.

**Exceptions:**
Domain-specific restrictions may prevent deletion or modification of particular records once those rules are formally defined.

**Status:**
**CONFIRMED**

---

## BR-009 — Technician permissions are intentionally limited

**Description:**
The default Tech role does not receive Manager-level administrative permissions.

**Applies to:**
Android, Angular, API

**Expected behavior:**

* Technicians receive only permissions required for their field responsibilities.
* Technician permissions are defined incrementally as Technician capabilities are confirmed.
* A Technician cannot gain Manager CRUD capabilities merely because the frontend exposes a corresponding screen.

**Exceptions:**
None.

**Notes:**
The complete default Technician permission catalog remains intentionally undefined until the corresponding product capabilities are confirmed.

**Status:**
**CONFIRMED** — limited-access principle
**OPEN QUESTION** — complete Technician permission catalog

---

# 4. Android Application

## BR-010 — Android supports both Manager and Technician experiences

**Description:**
The Android application is not a technician-only application.

**Applies to:**
Android

**Expected behavior:**

* Managers can use Android for management workflows.
* Technicians can use Android for field workflows.
* The application presents an experience appropriate to the authenticated user's permissions.
* The interface must not present irrelevant management functionality to Technicians.
* The interface must not unnecessarily restrict Managers from capabilities they are authorized to perform.

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

* Screens, actions, buttons, menus, and other controls may be conditionally displayed based on permissions.
* Permission checks should be centralized rather than scattered throughout the UI.
* The UI should reflect current authorization information.
* Backend authorization remains authoritative.

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

* Field workflows prioritize speed and clarity.
* Frequently performed actions require minimal unnecessary interaction.
* The UI avoids unnecessary pop-ups and administrative complexity.
* Management-oriented information should not overwhelm technician workflows.
* The technician should be able to understand what work needs attention and what action is available.

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

* Viewing assigned work
* Viewing relevant customer and site information
* Recording work activity
* Starting work
* Pausing work
* Completing work
* Adding notes/comments
* Capturing photos/evidence
* Recording relevant timestamps
* Recording relevant field/location information

Offline work is stored locally and synchronized with the backend when connectivity becomes available.

**Exceptions:**
Operations that fundamentally require current server information may require connectivity.

**Status:**
**CONFIRMED**

---

## BR-014 — Offline work must not be silently lost

**Description:**
Technician work captured offline must be preserved until the backend confirms successful synchronization.

**Applies to:**
Android, API

**Expected behavior:**

* Pending work survives application restart.
* Pending work survives device restart.
* Network failures do not silently discard work.
* Failed synchronization remains visible to the user when intervention is required.
* Automatically retryable operations are retried.
* Permanently failed operations are retained until resolved or explicitly discarded according to future product rules.

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

* A captured photo is immediately available to the technician.
* Photos may remain local while waiting for synchronization.
* A local photo is not deleted merely because an upload failed.
* Upload failures are retryable where appropriate.
* The UI communicates the synchronization state without unnecessarily interrupting field work.

**Exceptions:**
None.

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

* Angular provides the same core business capabilities supported by the platform.
* Management workflows are optimized for desktop/web usage.
* Angular uses the same backend authorization model as Android.
* Angular does not become a separate source of truth.

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

* Reporting
* Management dashboards
* Advanced filtering
* Advanced operational workflows
* Administrative capabilities
* Other management-oriented features

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

* Email/password authentication is supported.
* Authentication is performed by the backend.
* Clients do not independently authenticate users against business data.
* Successful authentication establishes the user's authenticated identity and authorization context.

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

## BR-019 — SMS/OTP authentication

**Description:**
Users may authenticate using SMS and a one-time password.

**Applies to:**
Android, Angular, API

**Expected behavior:**

* SMS/OTP authentication is supported.
* OTP validation is performed by the backend/authentication service.
* Clients do not determine whether an OTP is valid.
* Successful authentication establishes the same user identity and authorization context as other supported authentication methods.

**Exceptions:**
Detailed OTP expiration, retry, rate-limit, recovery, and phone-number lifecycle rules remain open.

**Status:**
**CONFIRMED** — authentication method
**OPEN QUESTION** — detailed OTP business rules

---

# 7. User Profiles & Photos

## BR-020 — Users may have profile photos

**Description:**
Servora supports photos for users/technicians where the product surface requires them.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

* A user's profile may contain a photo.
* The UI displays the user's photo when one exists.
* A default avatar is displayed when no photo exists.
* The default avatar must not be treated as user-provided evidence.

**Exceptions:**
None.

**Status:**
**CONFIRMED**

---

# 8. Jobs

## BR-021 — Jobs are a core Servora entity

**Description:**
A Job represents work that must be performed for a customer.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**
Jobs support the core Servora workflow across management and field applications.

The exact job data model, lifecycle, scheduling semantics, completion rules, and business constraints are defined by additional job rules.

**Exceptions:**
None.

**Status:**
**CONFIRMED** — entity exists
**OPEN QUESTION** — complete job domain definition

---

## BR-022 — Job lifecycle must be explicitly defined

**Description:**
Job status and lifecycle behavior must be defined by product rules rather than inferred by implementation.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

* Job statuses are shared across clients.
* Status transitions are validated by the backend.
* Clients do not invent their own job status vocabulary.
* A status transition that has not been defined by product ownership must not be implemented as business behavior.

**Exceptions:**
None.

**Status:**
**OPEN QUESTION**

---

# 9. Customers

## BR-023 — Customers are a core Servora entity

**Description:**
Customers represent the people or businesses receiving Servora-managed services.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

* Customers can be created and managed by authorized users.
* Jobs may be associated with customers.
* Customer information is shared through the backend.

**Exceptions:**
Detailed customer fields, contacts, sites, lifecycle, archival, and deletion rules remain open.

**Status:**
**CONFIRMED** — entity and Manager CRUD capability
**OPEN QUESTION** — detailed customer domain rules

---

# 10. Technicians

## BR-024 — Technicians are field users

**Description:**
Technicians are users who perform field work through Servora.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**

* Technicians authenticate as Servora users.
* The default system role for field technicians is `Tech`.
* Technicians can be associated with jobs.
* Technician-specific profile information may be stored where required by the product.

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

* Authorized users can assign work according to the assignment permissions.
* Assigned technicians can access the work relevant to their assignments.
* Assignment changes are authoritative only after acceptance by the backend.
* Assignment behavior must support future expansion to more sophisticated dispatch rules.

**Exceptions:**
Detailed assignment eligibility, skills, territory, availability, conflict handling, and reassignment rules remain open.

**Status:**
**CONFIRMED** — assignment concept
**OPEN QUESTION** — detailed assignment rules

---

# 12. Scheduling

## BR-026 — Jobs may have scheduled work

**Description:**
Servora supports scheduling jobs for field execution.

**Applies to:**
Android, Angular, API, Database

**Expected behavior:**
Scheduled job information is shared between management and field workflows.

**Exceptions:**
Detailed scheduling windows, timezone behavior, conflict rules, capacity, rescheduling, cancellation, and dispatch behavior remain open.

**Status:**
**CONFIRMED** — scheduling concept
**OPEN QUESTION** — scheduling rules

---

# 13. Evidence, Activity & History

## BR-027 — Technician-generated evidence has business value

**Description:**
Information generated during field work may provide operational, customer-service, billing, and accountability value.

**Applies to:**
Android, Angular, API, Database

**Examples include:**

* Photos
* Notes
* Comments
* Work timestamps
* Completion information
* Location information
* Status changes
* Assignment changes

**Expected behavior:**

* Important evidence must not be silently discarded.
* Evidence must remain associated with the appropriate business entity.
* Retention and audit behavior must be defined explicitly rather than inferred.

**Exceptions:**
Specific retention, deletion, visibility, and audit rules remain open.

**Status:**
**CONFIRMED** — evidence-preservation principle
**OPEN QUESTION** — detailed retention/audit rules

---

# 14. Localization

## BR-028 — Servora supports English and French

**Description:**
Servora is bilingual and must support English and French across user-facing product experiences.

**Applies to:**
Android, Angular, API

**Expected behavior:**

* User-facing product content supports English and French.
* Business data requiring bilingual presentation stores the necessary language-specific values.
* Stable machine-readable identifiers are not translated.
* Authorization logic does not depend on translated display text.

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

* Reports use authoritative backend data.
* Reports respect the requesting user's permissions.
* Reports must not expose information the user is not authorized to access.

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

* Offline-capable operations are stored durably on the device.
* Synchronization occurs when connectivity becomes available.
* Operations are not considered authoritative until accepted by the backend.
* Duplicate submissions must not create duplicate business outcomes.
* Synchronization must preserve technician work.
* Conflicts must be handled according to explicit business rules.
* The backend remains authoritative.

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

* Conflict behavior is explicitly defined for each offline-capable business operation.
* The implementation must not blindly apply last-write-wins to important business data.
* If conflict behavior has not been defined, it remains an open product question.

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

* Who performed the action
* What changed
* When it changed
* Relevant previous/current state
* Related evidence

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

**Status:**
**OPEN QUESTION**

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

* Roles
* Permissions
* Job statuses
* Activity types
* Error categories
* Synchronization operation types

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

* Do not invent a job status.
* Do not invent a permission.
* Do not assume who can delete a record.
* Do not assume how conflicts are resolved.
* Do not assume notification behavior.
* Do not assume billing rules.
* Do not assume technician eligibility rules.
* Do not assume retention periods.

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
