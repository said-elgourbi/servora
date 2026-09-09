# Project.md — Servora Engineering & Product Development Rules

> **READ THIS FILE FIRST.**
>
> This is the top-level engineering and product-development contract for every developer and AI agent working on Servora.
>
> Servora is developed **feature by feature, in controlled vertical slices**.
>
> Topic-specific files extend this document. They may add detail but **must not contradict it**.
>
> When this ruleset conflicts with another document, **do not silently choose one**. Surface the conflict and resolve it explicitly.

---

# 1. How to Use This Ruleset

| File                 | Governs                                                                                    |
| -------------------- | ------------------------------------------------------------------------------------------ |
| `Project.md`         | Product identity, scope, development workflow, cross-cutting principles, AI-agent behavior |
| `dev.md`             | Code, Git, dependencies, configuration, database, API and engineering standards            |
| `qa.md`              | Testing, verification and Definition of Done                                               |
| `Angular.md`         | Web application                                                                            |
| `API.md`             | REST API                                                                                   |
| `Android.md`         | Android technician application                                                             |
| `Business Rules.md`  | Authoritative business rules and open product questions                                    |
| `docs/architecture/` | Architecture decisions and technical standards                                             |
| `docs/product/`      | Product requirements, UX and product behavior                                              |

## Precedence

When instructions conflict:

1. Explicit project-owner instruction in the current task.
2. `Project.md`.
3. `Business Rules.md` for business behavior.
4. Approved product and UX documentation.
5. Approved architecture documentation and ADRs.
6. Application-specific rules.
7. Existing code conventions.
8. If still ambiguous: **STOP and identify the ambiguity. Do not guess.**

An AI agent must never silently resolve a contradiction by changing an established decision.

---

# 2. Project Identity

* Product: **Servora**
* Product type: Field-service management platform.
* Repository: one monorepo.
* API + PostgreSQL form the long-lived system of record.
* Angular is the management/office client.
* Android is the native technician field application.
* Android is **offline-first**.
* Android must support **phone and tablet form factors where applicable**.
* Angular will be the dashboard for Managers and Admins, should use the same database and api as mobile
* Initial supported languages are **English and French**.
* The architecture must be designed so additional languages can be introduced later without redesigning the application or database.
* Product design and UX are defined by the approved Figma/design documentation.

The Servora product name, namespaces, package names, database names and other identifiers must remain consistent throughout the repository.

A product rename requires one deliberate, reviewed repo-wide change.

---

# 3. Project Starting Point

This is a **greenfield Servora implementation**.

The project begins from the approved:

* product decisions;
* business decisions;
* business rules;
* architecture decisions;
* UX decisions;
* Figma designs;
* technology decisions;
* offline-first requirements.

The initial project setup must establish the foundation required to implement those decisions.

The agent must **not invent replacement architecture or product behavior simply because implementation has not started yet**.

---

# 4. Product Development Philosophy

Servora is developed deliberately rather than reactively.

The standard development process is:

```text
Product decisions
       ↓
MVP scope
       ↓
Feature backlog
       ↓
Feature selection
       ↓
Requirements
       ↓
UX / Figma
       ↓
Domain + database design
       ↓
API / client technical design
       ↓
Implementation
       ↓
Testing / QA
       ↓
Documentation
       ↓
Commit
       ↓
Next feature
```

## Core rule

> **A conversation about a feature is not automatically approval to implement it.**

Ideas, suggestions and brainstorming must not silently become requirements.

New ideas must be captured as backlog items or open questions until explicitly approved.

---

# 5. MVP and Scope Control

The MVP must have an explicit documented scope.

The MVP must not grow implicitly during implementation.

Every proposed capability must be classified as one of:

* `MVP`
* `POST-MVP`
* `BACKLOG`
* `OPEN QUESTION`
* `REJECTED`

If a new idea appears while another feature is being implemented:

1. Capture it.
2. Record its purpose.
3. Record known dependencies or impact.
4. Do not interrupt the current feature unless the project owner explicitly changes priority.
5. Continue the current feature.

The agent must not expand scope merely because an additional capability is convenient to implement.

---

# 6. Feature-Based Development

Servora is developed **one feature at a time**.

A feature is a complete vertical slice rather than a single UI screen or database table.

A feature may include:

```text
Product requirements
        ↓
Database
        ↓
Domain logic
        ↓
API
        ↓
Shared contracts
        ↓
Angular
        ↓
Android
        ↓
Authorization
        ↓
Offline/sync behavior
        ↓
Localization
        ↓
Tests
        ↓
Documentation
```

Not every feature necessarily touches every layer.

The agent must explicitly determine which layers are affected.

## One active feature

Only one feature should normally be actively implemented at a time.

Do not begin another feature because the current feature exposes an interesting adjacent capability.

Finish, verify and document the current feature first.

---

# 7. Feature Lifecycle

Every feature follows this lifecycle.

## Step 1 — Select

The project owner explicitly selects the feature.

Example:

```text
Implement F-004 Customers.
```

## Step 2 — Inspect

Before changing code, the agent must inspect:

* relevant product decisions;
* applicable business rules;
* Figma/design;
* architecture documentation;
* domain model;
* database schema;
* API contracts;
* affected application code;
* tests;
* current feature tracker.

Never implement against assumptions when relevant project information exists.

## Step 3 — Specify

The agent identifies:

* feature goal;
* actors;
* user flows;
* acceptance criteria;
* affected entities;
* affected applications;
* API changes;
* database changes;
* authorization requirements;
* offline/sync requirements;
* localization requirements;
* testing requirements;
* documentation changes.

## Step 4 — Design

Before implementation, determine the required technical design.

For database-affecting features, domain and database design must be completed **before application implementation**.

For ambiguous product behavior, stop and ask.

## Step 5 — Approval

For material product, database or architectural decisions, the agent presents the implementation plan before coding.

Do not begin implementation while a material ambiguity remains unresolved.

## Step 6 — Implement

Implement the approved vertical slice.

Do not introduce unrelated refactors, features or architectural changes.

## Step 7 — Verify

Run applicable tests, linting, type checking, builds and QA defined by `qa.md`.

Report exactly what was executed and the result.

## Step 8 — Document

Update affected:

* requirements;
* business rules;
* architecture documentation;
* API contracts;
* database documentation;
* feature tracker;
* ADRs when architecture changes;
* configuration documentation when configuration changes.

## Step 9 — Complete

A feature is complete only when its Definition of Done is satisfied.

## Step 10 — Commit

Commit the coherent completed milestone.

Then move to the next explicitly selected feature.

---

# 8. Database-First Domain Discipline

The database is part of Servora's long-lived architecture.

Database changes propagate into the API, clients, synchronization model and existing data. Therefore:

> **For any feature that affects persistent data, domain and database design happen before application implementation.**

The expected sequence is:

```text
Requirement
    ↓
Domain model
    ↓
Database impact
    ↓
Migration design
    ↓
API/client design
    ↓
Implementation
```

## Foundational database model

Before implementing substantial business features, establish and review the core domain model and foundational database structure.

Identify important decisions such as:

* entities;
* ownership;
* relationships;
* one-to-one relationships;
* one-to-many relationships;
* many-to-many relationships;
* lifecycle;
* identifiers;
* organization boundaries;
* audit requirements;
* deletion behavior;
* versioning;
* status representation;
* synchronization requirements.

Do not create speculative database structures for functionality that is not required.

## Schema changes

Every schema change requires:

* schema source update;
* migration;
* SQL review;
* migration-impact assessment;
* relevant tests;
* documentation update.

Never modify an already-applied migration to avoid creating a new migration.

Destructive changes require explicit review and a backward-compatible migration strategy where practical.

---

# 9. Domain Model Before UI Convenience

The UI must not dictate an incorrect domain model merely because it is convenient to implement.

Examples:

If a job can have multiple technicians, the domain model must represent a relationship capable of representing multiple technicians.

If a customer can have multiple contacts, the domain model must support that requirement rather than forcing a single contact.

If a business concept has a lifecycle or invariant, that behavior must be represented consistently in the domain, API and database.

The agent must not simplify domain relationships solely to reduce implementation effort.

---

# 10. Internationalization and Localization

Servora initially supports:

* **English**
* **French**

The architecture must be **language-extensible** so additional languages can be added later without redesigning the domain model or rewriting business logic.

This applies to:

* Android;
* Angular;
* API responses;
* validation messages;
* system-generated activity/messages;
* status labels;
* activity types;
* error messages;
* notifications;
* database-backed domain values where applicable.

## Never hard-code language-specific business values

Business/domain data must be represented using **stable domain values**, codes or enums rather than storing language-specific display text as the canonical value.

For example, imagine a domain concept where:

```text
33 = BLACK
34 = YELLOW
```

The values `BLACK` and `YELLOW` are examples only.

The application should persist and exchange the stable domain identifier:

```text
33
34
```

and resolve the localized display value separately:

```text
English:
33 → Black
34 → Yellow

French:
33 → Noir
34 → Jaune
```

A future language could then provide:

```text
Spanish:
33 → Negro
34 → Amarillo
```

without changing the underlying business data.

### General rule

> **Store domain meaning, not presentation language.**

Do not use strings such as `"Black"`, `"Noir"`, `"Yellow"` or `"Jaune"` as the canonical business value when a stable domain identifier can represent the concept.

Likewise, do not embed translated text directly into business logic.

Use:

```text
Stable domain code
        ↓
Localization key
        ↓
Current application language
        ↓
Localized presentation
```

The same principle applies to:

* statuses;
* activity types;
* categories;
* permissions;
* error codes;
* workflow states;
* other controlled vocabularies.

Human-entered content is different: user-entered text must remain the content entered by the user and must not be translated or replaced merely for localization.

---

# 11. Responsive and Multi-Form-Factor Design

Servora must be designed for both **phone and tablet** form factors.

This applies to both:

* Android;
* Angular/Web where responsive layouts are relevant.

The design must not assume a single screen size.

## Android

The technician application must support appropriate layouts for:

* phone portrait;
* phone landscape where applicable;
* tablet portrait;
* tablet landscape where applicable.

The UI must preserve the technician-first principles across form factors rather than simply stretching the phone layout.

## Angular

The Web application must support responsive layouts appropriate for:

* desktop;
* tablet;
* smaller screens where the workflow requires it.

The Figma design system should define responsive behavior, breakpoints and layout adaptation where appropriate.

The agent must not create a separate unrelated UI design for each form factor unless the product design explicitly requires it.

---

# 12. Architecture Principles

## Modular monolith first

The API is a modular monolith.

Do not introduce microservices without explicit architectural approval supported by concrete requirements.

## No premature abstraction

Do not create abstractions merely because two pieces of code look similar.

Prefer the simplest maintainable implementation.

Extract shared functionality when reuse is demonstrated or clearly required.

## Feature ownership

Feature-specific behavior belongs to the feature/module that owns it.

Do not place feature logic in generic `core`, `common`, `utils`, dependency-injection or shell layers merely for convenience.

## Replaceable clients

The API contract and PostgreSQL database are the long-lived core.

Angular and Android clients must remain replaceable.

---

# 13. Offline-First Is a Product Requirement

The Android technician application is offline-first.

Offline behavior is not an optimization to be added later.

For features involving mutable business data such as:

* jobs;
* customers;
* assignments;
* statuses;
* comments;
* updates;
* photos;
* files;
* GPS;
* check-in/out;
* invoices;
* payments;

the agent must follow the approved Offline-First Architecture Standard.

The agent must not implement generic CRUD synchronization or silent last-write-wins behavior.

Where required, each feature must explicitly define:

* local persistence;
* synchronization;
* retry behavior;
* idempotency;
* conflict handling;
* version checks;
* server authority;
* timestamps;
* deletion/tombstone behavior;
* evidence retention.

---

# 14. API Is the System of Record

The API + PostgreSQL database are the authoritative system of record.

Android maintains a local working set for offline operation.

Local Android state must never silently become a competing source of truth.

Server-side authorization and validation remain authoritative.

---

# 15. Cross-Application Contracts

All clients use the approved API contract.

Wire conventions should remain consistent across applications:

* JSON;
* `camelCase`;
* UUID identifiers;
* ISO-8601 UTC timestamps;
* shared error envelope;
* shared pagination envelope.

`@servora/shared-types` is the source of truth for TypeScript contracts shared between Angular and API.

Android mirrors the same wire contracts in Kotlin DTOs.

Cross-application status codes, error codes and stable business identifiers must have one authoritative definition.

---

# 16. Security

Security is implemented from the beginning.

Mandatory principles:

* no committed secrets;
* no hard-coded credentials;
* no environment-specific secrets in source;
* server-side authentication;
* server-side authorization;
* validated inputs;
* safe output handling;
* redacted logs;
* secure Android credential storage;
* restricted CORS;
* HTTPS outside local development;
* no authentication bypasses;
* no client-only security enforcement.

Development shortcuts must never become production backdoors.

---

# 17. Data Integrity

Database constraints and transactions enforce important invariants.

Use:

* foreign keys;
* appropriate unique constraints;
* appropriate check constraints;
* transactions for atomic business operations;
* deliberate deletion behavior;
* server-side versioning where required.

Do not rely solely on application code for critical relational integrity.

---

# 18. API Design

The API must:

* use the approved versioning strategy;
* use JSON;
* use `camelCase`;
* use stable identifiers;
* use consistent error responses;
* use DTOs for request/response contracts;
* validate all external input;
* enforce authorization server-side;
* preserve backward compatibility unless a deliberate breaking change is approved.

Detailed API rules belong in `API.md` and the API documentation.

---

# 19. Authentication and Authorization

Authentication and authorization are server-side concerns.

The API is authoritative for:

* identity;
* authentication;
* authorization;
* role/permission enforcement;
* resource ownership;
* organization boundaries.

UI-level access control is a usability feature, never the security boundary.

No developer or agent may create hidden authentication bypasses for convenience.

---

# 20. Error Handling

Errors must use a consistent application-wide model.

Expected domain failures must be explicit and typed.

Unexpected failures must be handled centrally.

Never silently swallow errors.

HTTP status codes must carry meaningful semantics.

Sensitive internal details must never be exposed to clients.

---

# 21. Logging and Observability

Logging must be structured and useful for diagnosing production behavior.

Never log:

* passwords;
* authentication tokens;
* secrets;
* sensitive credentials.

Sensitive headers and authentication material must be redacted.

Request correlation should be available for API operations.

---

# 22. Configuration

Environment-specific values must not be hard-coded.

Configuration must be:

* explicit;
* validated;
* documented;
* environment-aware;
* safe to deploy.

Secrets belong in environment-specific secret management, not source control.

Whenever a configuration variable is added, update the relevant configuration schema, example configuration and documentation.

---

# 23. Technology Stack

The approved technology stack is authoritative.

| Application          | Stack                                                                                                             |
| -------------------- | ----------------------------------------------------------------------------------------------------------------- |
| Angular              | Angular, TypeScript strict, Angular Material/M3, signals, standalone components, testing framework and Playwright |
| API                  | NestJS, TypeScript ESM, Drizzle ORM, PostgreSQL, structured logging, validation                                   |
| Android              | Kotlin, Jetpack Compose, Material 3, Hilt, Retrofit, kotlinx-serialization, Coroutines, MVVM                      |
| Shared types         | `@servora/shared-types`                                                                                           |
| Shared configuration | `@servora/shared-config`                                                                                          |
| Database             | PostgreSQL + versioned migrations                                                                                 |
| Infrastructure       | Docker Compose and project tooling                                                                                |
| CI                   | GitHub Actions                                                                                                    |

Technology changes require explicit architectural approval.

Do not replace technologies merely because another option appears easier.

---

# 24. Coding Standards

Detailed standards live in `dev.md` and application-specific rules.

At project level:

* TypeScript remains strict.
* Existing formatting standards must be followed.
* Linting must pass.
* Type checking must pass.
* Builds must pass.
* Existing architectural patterns must be followed.
* New dependencies require justification.
* Dead code must not be introduced.
* Temporary hacks must not become permanent architecture.

---

# 25. Testing

Testing is part of implementation, not a final cleanup activity.

Behavior changes require appropriate tests.

Expected testing may include:

* unit tests;
* API integration tests;
* database tests;
* Angular feature tests;
* Playwright e2e;
* Android JVM tests;
* Android Compose UI tests;
* migration verification;
* offline/synchronization tests where applicable.

Detailed testing policy and Definition of Done are defined in `qa.md`.

---

# 26. Documentation

Documentation is part of implementation.

When behavior changes, update the relevant documentation.

Documentation must remain synchronized with:

* architecture;
* API contracts;
* database schema;
* business rules;
* localization;
* responsive behavior;
* configuration;
* commands;
* workflows;
* feature status.

Architectural changes require an ADR.

---

# 27. Git and Milestones

Use short-lived branches:

```text
feat/<short-name>
fix/<short-name>
docs/<short-name>
chore/<short-name>
```

Use Conventional Commits with appropriate scopes.

Commit at coherent milestones.

Push periodically so work is recoverable.

Do not commit known broken code merely as a backup checkpoint.

A commit should represent a reasonably verified state for the current development stage.

---

# 28. AI-Agent Operating Rules

AI agents are disciplined engineering agents, not autonomous product managers.

## Before coding

The agent must:

1. Read `Project.md`.
2. Read relevant application rules.
3. Read applicable product documentation.
4. Read applicable business rules.
5. Read applicable architecture documentation.
6. Inspect the relevant Figma/design.
7. Inspect the domain model.
8. Inspect the database impact.
9. Inspect relevant existing code.
10. Identify ambiguity and dependencies.
11. Confirm the requested work belongs to the currently active feature.

## During coding

The agent must:

* follow the approved plan;
* make small, reviewable changes;
* avoid unrelated improvements;
* preserve existing functionality;
* follow established patterns;
* update tests;
* update documentation;
* stop when the approved scope is complete.

## The agent must NOT

* invent product requirements;
* silently change business rules;
* silently change architecture;
* add unrelated features;
* perform speculative refactors;
* introduce unnecessary dependencies;
* simplify domain relationships for implementation convenience;
* modify database structure without a migration;
* bypass authentication or authorization;
* treat UI behavior as a substitute for server-side enforcement;
* claim work is complete without verification.

---

# 29. Handling New Ideas During Implementation

If the project owner introduces a new idea while another feature is active, classify it.

Determine whether it is:

* already covered by an existing decision;
* part of the current feature;
* a change to an existing requirement;
* a new backlog item;
* an open product question.

If it is outside the active feature, record it and continue the active feature.

If it materially changes the current feature, stop and surface the impact before continuing.

---

# 30. Handling Ambiguity

When requirements are ambiguous, the agent must not guess.

Use:

```text
OPEN QUESTION
```

and describe:

* what is ambiguous;
* why it matters;
* affected areas;
* possible interpretations;
* recommendation, if appropriate.

Once resolved, record the decision in the appropriate authoritative document.

---

# 31. Architectural Change Control

An architectural decision must not be silently changed because implementation becomes inconvenient.

Architectural changes include:

* replacing a technology;
* changing persistence strategy;
* changing API architecture;
* changing synchronization architecture;
* changing authentication architecture;
* introducing a new architectural layer;
* changing core domain relationships.

Such changes require:

1. identification;
2. impact assessment;
3. project-owner approval;
4. ADR update or new ADR where appropriate;
5. implementation;
6. documentation synchronization.

---

# 32. Definition of Done

A feature is Done only when:

* requirements are satisfied;
* approved UX is implemented;
* responsive behavior is appropriate;
* database changes are correct and migrated;
* API contracts are correct;
* authorization is enforced;
* offline behavior is correct where applicable;
* English localization works;
* French localization works;
* the implementation does not prevent future languages;
* appropriate domain codes/identifiers are used instead of language-specific business values;
* tests exist and pass;
* lint passes;
* type checking passes;
* affected builds pass;
* documentation is updated;
* tracker status is updated;
* no known unrelated regressions were introduced;
* verification results have been honestly reported.

"Code exists" is not Definition of Done.

---

# 33. Canonical References

| Subject                 | Reference                                         |
| ----------------------- | ------------------------------------------------- |
| Project rules           | `Project.md`                                      |
| Development standards   | `dev.md`                                          |
| QA / Definition of Done | `qa.md`                                           |
| Angular                 | `Angular.md`                                      |
| API                     | `API.md`                                          |
| Android                 | `Android.md`                                      |
| Business rules          | `Business Rules.md`                               |
| Architecture            | `docs/architecture/`                              |
| ADRs                    | `docs/architecture/adr/`                          |
| Product                 | `docs/product/`                                   |
| API contracts           | `docs/api/`                                       |
| Database                | `docs/architecture/database.md`                   |
| Offline-first           | `docs/architecture/offline-first-architecture.md` |
| UX                      | `docs/product/`                                   |
| Development setup       | `docs/development/setup.md`                       |
| Git workflow            | `docs/development/git.md`                         |
| CI                      | `.github/workflows/`                              |

---

# 34. Final Operating Principle

> **Servora is built deliberately, not reactively.**

The project owner may brainstorm freely.

The agent must maintain engineering and product discipline.

Ideas are captured.

Decisions are documented.

Features are planned.

Domain and database design happen before implementation where applicable.

One feature is implemented at a time.

Each feature is completed as a vertical slice.

English and French are supported from the beginning, while the architecture remains ready for additional languages.

Phone and tablet experiences are considered from the beginning.

Domain values are represented independently from presentation language.

QA verifies the result.

Documentation records the result.

Then—and only then—does the project move to the next feature.
