# dev.md — Development Standards

> Engineering standards for implementing Servora.
>
> Read this together with `Project.md`, `qa.md`, and the applicable per-application rules (`Angular.md`, `API.md`, `Android.md`).
>
> `Project.md` defines project-level principles and development workflow. This document defines practical engineering standards for writing and changing code.

---

## 1. General Development Principles

### Clean Code

* Write code for the next developer to understand and maintain.
* Use clear, descriptive names.
* Keep functions, classes, and components focused and reasonably small.
* Prefer obvious control flow over clever implementations.
* Keep business logic close to the domain or application layer responsible for it.
* Avoid comments that merely restate the code.
* Comments should explain **why** something is necessary when the reason is not obvious from the implementation.

### SOLID

* Apply SOLID principles pragmatically.
* Prefer clear boundaries and focused responsibilities.
* Do not introduce abstractions solely to satisfy a pattern.
* Do not create interfaces, base classes, factories, or frameworks without a concrete reason.
* A second use case may justify an abstraction; a hypothetical future use case does not.

### KISS

* Prefer the simplest implementation that correctly satisfies the requirement.
* Do not introduce enterprise-style complexity without a demonstrated need.
* Avoid infrastructure, patterns, or abstractions that provide no current value.

### DRY Without Over-Abstraction

* Avoid unnecessary duplication of stable business logic.
* Do not extract code merely because two implementations currently look similar.
* Shared code should have a clear and stable responsibility.
* Cross-application wire contracts should use the approved shared contract mechanism.
* Android-specific implementations remain in Android; do not force server/web abstractions onto the mobile application.

### Separation of Concerns

Keep these responsibilities appropriately separated:

* transport / HTTP concerns
* authentication and authorization
* application orchestration
* domain/business rules
* persistence
* presentation/UI
* synchronization and offline behavior

Do not place business rules inside UI components merely because they are convenient to access there.

### Dependency Direction

* Dependencies should point toward stable responsibilities.
* Domain logic should not depend directly on UI or transport concerns.
* Persistence implementations should not leak into presentation code.
* Introduce interfaces where they provide meaningful testability, substitution, or architectural isolation.
* Do not create interfaces for every class by default.

### Immutability

* Prefer immutable data where practical.
* Prefer `const`, `readonly`, and immutable collections where appropriate in TypeScript.
* Prefer `val` and immutable state exposure in Kotlin.
* Mutable state should have a clear owner.
* Avoid exposing mutable internal collections or state directly.

### Explicit Error Handling

* Never silently swallow errors.
* Do not use empty `catch` blocks.
* Handle errors at the layer that has enough context to make the correct decision.
* Expected domain errors should be represented explicitly.
* Unexpected errors should propagate to centralized error handling.
* Cancellation must not be treated as a normal application failure.

---

## 2. Development Workflow

Every feature or change must follow the project's feature-development process.

### Before Coding

Before modifying code:

1. Read `Project.md`.
2. Read applicable development and application rules.
3. Read the relevant business requirements and product decisions.
4. Review the approved UX/design when applicable.
5. Identify affected domain entities and relationships.
6. Identify database impact.
7. Identify API and cross-application contract impact.
8. Identify authentication, authorization, localization, responsive, and offline implications where applicable.
9. Review relevant existing implementation.
10. Confirm the scope of the active feature.

Do not begin implementation when a material requirement or architectural decision is ambiguous.

### Design Before Implementation

For changes involving persistent data:

1. Define the domain change.
2. Define the database change.
3. Design the migration.
4. Review constraints, relationships, indexes, lifecycle, and compatibility.
5. Define API/contracts.
6. Then implement application code.

Do not use application code to compensate for an incorrect or incomplete domain model.

### Vertical Slices

Features should normally be implemented as complete vertical slices.

A slice may include:

* database
* migration
* domain logic
* API
* shared contracts
* Angular
* Android
* authentication/authorization
* offline/synchronization
* localization
* tests
* documentation

Do not implement isolated technical layers merely because they are convenient unless the active plan explicitly calls for it.

### Scope Control

During implementation:

* Work only on the approved feature.
* Do not add unrelated improvements.
* Do not perform speculative refactoring.
* Do not introduce future functionality "while you are there."
* Record useful new ideas in the appropriate backlog/open-question mechanism instead.

If a new requirement materially changes the active feature, stop and surface the change before implementing it.

---

## 3. Git

### Branch Strategy

* Use short-lived branches based on `main`.
* Preferred branch prefixes:

  * `feat/`
  * `fix/`
  * `docs/`
  * `chore/`
  * `refactor/`
* Do not create long-lived integration branches unless explicitly approved.
* Keep work organized around coherent feature slices.

### Commit Conventions

Use Conventional Commits:

```text
<type>(<optional-scope>): <imperative summary>
```

Supported types include:

* `feat`
* `fix`
* `refactor`
* `docs`
* `test`
* `chore`
* `build`
* `perf`

Keep commit messages:

* concise
* truthful
* specific
* focused on one logical change

Use a body when additional context is useful, particularly explaining **why** the change was made.

### Checkpoints

Commit and push at coherent milestones and before long pauses or session ends.

Checkpoint commits are allowed on feature branches.

A checkpoint:

* must contain a coherent, buildable state
* must not knowingly introduce broken code
* must accurately describe what has and has not been completed
* must not claim feature completion when work remains

Example:

```text
feat(android): connect technician sign-in to API (wip: recovery flow)
```

Do not use commits as a substitute for correctness.

### Pull Requests

A pull request should:

* solve one logical problem
* contain appropriate tests
* include required documentation changes
* avoid unrelated refactoring
* contain no secrets
* be reasonably reviewable

Multi-application changes are acceptable when they are required by the feature.

### Avoiding Unrelated Changes

Do not combine:

* unrelated refactoring with feature work
* dependency upgrades with unrelated features
* broad documentation rewrites with code changes
* formatting-only changes with functional changes

Exceptions are allowed when the change is necessary to complete the active feature or explicitly approved.

---

## 4. Dependencies

### General Rules

* Prefer existing project capabilities over adding dependencies.
* Every new dependency must solve a concrete problem.
* Avoid dependencies for trivial functionality that can be implemented safely with existing tools.
* Prefer maintained, well-supported libraries compatible with the project's technology stack.
* Remove unused dependencies rather than leaving them for future cleanup.

### Adding Dependencies

Before adding a dependency, determine:

1. What concrete problem does it solve?
2. Why can the existing stack not solve it?
3. Is the dependency actively maintained?
4. Does it introduce security, licensing, performance, or operational concerns?
5. Is the dependency required across applications or only one application?

Dependency additions should be documented in the implementation change when the reason is not obvious.

### Version Management

* Keep dependency versions centralized according to the conventions of the applicable application.
* Avoid scattering version literals across configuration.
* Do not perform unrelated dependency upgrades during feature work.
* Major framework/toolchain upgrades require explicit consideration of compatibility and project impact.

### Security

* Do not knowingly introduce dependencies with unresolved critical security vulnerabilities.
* Do not disable or silence security tooling merely to make a build pass.
* If a vulnerability cannot immediately be resolved, document the risk and obtain an explicit decision.

---

## 5. Configuration and Secrets

### Configuration

* Environment-specific behavior must be configurable.
* Do not hard-code deployment-specific URLs, credentials, ports, hosts, or secrets in application logic.
* Configuration must have a clear source and validation strategy.
* New configuration variables must be documented.
* Development, test, and production configuration must remain clearly separated.

### Secrets

Never commit:

* passwords
* API keys
* private keys
* JWT secrets
* database credentials
* access tokens
* certificates containing private material
* production secrets

Secrets must not appear in:

* source code
* tests
* fixtures
* screenshots
* sample logs
* documentation

Use safe placeholders in examples.

---

## 6. Database Development

### Schema Source of Truth

The database schema must have one authoritative definition and a controlled migration process.

Every persistent schema change must include:

* schema definition change
* reviewed migration
* affected constraints
* affected indexes
* compatibility considerations
* appropriate tests

Never modify an already-applied migration to change historical database state.

### Migrations

* Migrations must be deterministic and reviewable.
* Review generated SQL before applying it.
* Do not use schema push mechanisms against shared, test, staging, or production databases unless explicitly approved.
* Development shortcuts must never become the production migration strategy.

### Domain Schema

For domain tables, explicitly consider:

* primary keys
* foreign keys
* ownership/organization boundaries
* cardinality
* uniqueness
* lifecycle
* status
* audit fields
* deletion behavior
* concurrency/versioning
* synchronization requirements
* indexes

Do not create speculative tables solely for hypothetical future features.

### Constraints

Enforce important data invariants at the database level where appropriate.

Examples include:

* foreign-key integrity
* uniqueness
* non-null requirements
* valid ranges
* valid relationships

Application validation complements database constraints; it does not replace them.

### Transactions

Use transactions when multiple related database operations must succeed or fail together.

* Keep transactions short.
* Do not perform unnecessary external calls inside database transactions.
* Do not rely on application-level sequencing when atomicity is required.

### Queries and Performance

* Avoid N+1 query patterns.
* Batch-load related data when appropriate.
* Index columns used by real-world filtering, joining, and sorting patterns.
* Do not add indexes speculatively without a reasonable access pattern.
* Inspect query behavior when implementing performance-sensitive paths.
* Do not optimize prematurely, but do not ignore obvious inefficient queries.

### Schema Evolution

Prefer additive and backward-compatible changes where practical.

For potentially breaking changes:

1. identify affected consumers
2. determine compatibility requirements
3. introduce the replacement safely
4. migrate data if necessary
5. update consumers
6. remove obsolete structures only when safe

Destructive database changes require explicit consideration and documentation.

---

## 7. API Development

### REST

* Follow the project's approved API versioning strategy.
* Use nouns for resources where practical.
* Use explicit actions only when the domain operation cannot reasonably be represented as a resource.
* Use JSON for API payloads unless another format is explicitly approved.
* Use consistent naming conventions across endpoints.
* Use stable identifiers.
* Use standardized timestamp representations.

### Request Validation

Validate all client-controlled input at the API boundary.

Validation must cover:

* required fields
* types
* lengths
* formats
* ranges
* allowed values
* structural requirements

Never assume that validation performed by Angular or Android is sufficient.

### DTOs

* Define explicit request and response contracts.
* Do not expose ORM/database entities directly as API responses.
* API contracts should represent application/domain needs rather than database implementation details.
* Changes to shared contracts must consider all consumers.

### HTTP Status Codes

Use HTTP status codes consistently.

Typical semantics include:

* `200` — successful request
* `201` — resource created
* `204` — successful request with no response body
* `400` — malformed request
* `401` — unauthenticated
* `403` — authenticated but forbidden
* `404` — resource not found
* `409` — state/version/conflict
* `422` — semantically invalid input when appropriate
* `5xx` — server/infrastructure failure

Never return a successful status code for a failed operation merely to simplify client handling.

### Error Responses

API errors must follow one consistent structure.

Errors should:

* contain a stable machine-readable code
* contain an appropriate user-facing message
* optionally contain structured details
* include request/correlation information where supported

Do not expose:

* stack traces
* database internals
* credentials
* secrets
* sensitive infrastructure information

### Authentication and Authorization

* Authentication and authorization are server responsibilities.
* Never rely on UI visibility to enforce authorization.
* Every protected operation must perform appropriate authorization checks.
* Authorization must consider organization/tenant boundaries where applicable.
* Never create development bypasses that can accidentally operate in production.

### Idempotency and Retries

Operations that may be retried must be designed with retry behavior in mind.

For synchronization-sensitive mutations:

* use client-generated mutation identifiers where required
* prevent duplicate application of the same mutation
* use expected-version/concurrency checks where required
* return deterministic results for safe replay

Do not use blind last-write-wins behavior where the product requires conflict detection or resolution.

---

## 8. Cross-Application Contracts

Servora contains multiple clients and services that communicate through explicit contracts.

### Contract Rules

* API contracts must be stable and explicit.
* Shared TypeScript contracts should be centralized where appropriate.
* Android maintains equivalent Kotlin representations for API contracts.
* Database implementation details must not leak into client contracts.
* Do not silently change a contract in a way that breaks another application.

### Stable Domain Values

Business values that cross application boundaries must use stable domain identifiers/codes rather than presentation text.

For example:

```text
33 = BLACK
```

may be presented as:

```text
English: Black
French: Noir
```

The stored/cross-system value represents the domain meaning, not the current language.

Do not use localized display text as a database key, API identifier, synchronization key, or business rule.

---

## 9. Localization

Servora initially supports English and French.

Development must preserve the ability to add additional languages later.

### Rules

* Do not hard-code user-facing strings inside business logic.
* Do not use translated text as domain values.
* Keep stable domain codes separate from localized labels/messages.
* User-entered content must remain in the language entered by the user.
* System-generated messages must follow the project's defined localization behavior.
* Validation and error messages must support localization where user-facing.
* Angular and Android must follow their respective localization standards.

Adding a new language should primarily require translation/catalog work rather than redesigning the domain model.

---

## 10. Offline-First Development

Android is an offline-first client.

Any feature that creates, modifies, deletes, or synchronizes data must explicitly consider offline behavior.

At minimum, determine:

* local persistence
* synchronization trigger
* retry behavior
* idempotency
* server authority
* version/concurrency handling
* conflict behavior
* deletion/tombstone behavior
* timestamp authority
* failure recovery
* user-visible synchronization state

Do not add generic synchronization behavior without understanding the domain operation being synchronized.

Do not assume that network connectivity exists.

---

## 11. Security

Treat all external input and client state as untrusted.

### Required Practices

* Validate server-side.
* Enforce authorization server-side.
* Use secure credential/token storage.
* Do not log secrets.
* Do not expose sensitive data unnecessarily.
* Use HTTPS outside explicitly controlled local-development scenarios.
* Keep CORS and network access restrictive.
* Do not bypass security checks for convenience.
* Do not trust client-provided organization/user ownership information.
* Verify resource ownership on the server.

Security-sensitive behavior must fail closed rather than silently granting access.

---

## 12. Logging and Observability

Logs should help diagnose real problems without exposing sensitive information.

### Rules

* Prefer structured logs.
* Include useful contextual information such as request/correlation identifiers where available.
* Do not log passwords, tokens, secrets, or sensitive personal data unnecessarily.
* Avoid logging entire request/response bodies by default.
* Log meaningful domain failures and infrastructure failures at appropriate levels.
* Do not use logging as a substitute for proper error handling.

---

## 13. Testing During Development

Testing is part of implementation, not a separate cleanup phase.

The appropriate test level depends on the change.

Consider:

* unit tests
* API integration tests
* database/migration tests
* Angular tests
* Playwright end-to-end tests
* Android JVM tests
* Android UI/Compose tests
* offline/synchronization tests
* authorization tests
* contract tests where appropriate

A change that modifies behavior must include appropriate verification.

Do not weaken or delete tests merely because they make implementation inconvenient.

Detailed verification requirements belong in `qa.md`.

---

## 14. Documentation

Documentation is part of the implementation.

Update documentation when a change affects:

* architecture
* API contracts
* database structure
* business rules
* configuration
* deployment
* offline/synchronization behavior
* localization
* workflows
* developer setup

Architecture decisions that materially change the system should be documented using the project's ADR process.

Do not leave documentation describing behavior that no longer exists.

---

## 15. Refactoring

Refactoring is allowed when it improves the active feature or removes a clear defect.

### Good Reasons to Refactor

* remove duplication that is now meaningful
* correct an architectural boundary
* simplify difficult code
* improve testability
* remove dead code
* fix a demonstrated performance problem
* support the approved feature cleanly

### Avoid

* speculative redesign
* framework migration during unrelated work
* broad renaming without functional need
* abstraction for hypothetical requirements
* "cleanup" unrelated to the active feature

If a refactor is large enough to represent a separate piece of work, track it separately.

---

## 16. AI Agent Development Rules

AI agents working on Servora must follow the same engineering standards as human developers.

The agent must:

* read the applicable project rules before coding
* understand the active feature before modifying code
* inspect relevant existing implementation
* identify database impact before persistent-data changes
* follow approved architecture and business rules
* make the smallest coherent change that satisfies the requirement
* run appropriate verification
* update required documentation
* report limitations honestly

The agent must not:

* invent business requirements
* invent architectural decisions
* invent API behavior
* invent database relationships
* add unrelated features
* perform speculative refactors
* add unnecessary dependencies
* simplify domain relationships for UI convenience
* modify the database without a migration
* bypass authorization
* silently change approved behavior
* claim tests passed when they were not run
* claim completion when known work remains

When requirements conflict or a material decision is missing, the agent must surface the ambiguity rather than guessing.

---

## 17. Definition of a Good Change

A good change is:

* correctly scoped
* easy to understand
* consistent with the domain model
* consistent with existing architecture
* appropriately tested
* secure
* localized where required
* compatible with offline requirements where applicable
* documented where necessary
* free of unrelated modifications
* honest about its verification status

The goal is not the smallest possible diff.

The goal is the **smallest correct, maintainable, and complete change** that satisfies the approved requirement.

---

## 18. Final Rule

When deciding between two valid implementations:

1. Prefer the one that better represents the domain.
2. Prefer the simpler implementation.
3. Prefer explicit behavior over hidden magic.
4. Prefer established project patterns over introducing new ones.
5. Prefer reversible changes when uncertainty exists.
6. Prefer correctness and maintainability over implementation speed.

**Build deliberately. Keep the domain correct. Keep changes scoped. Verify the result.**
