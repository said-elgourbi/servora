# Prompt: Regenerate Development Customer/Job/Visit Seeds

You are working in the Servora repository. The existing customer/job/visit demo seed data was intentionally deleted because it was no longer trustworthy. Generate a new coherent development demo dataset.

## Where to work

- The seed runner is `api/src/database/run-development-seed.ts`.
- The default sign-in account seed is `api/src/database/development-seed.ts`; do not replace it unless a test forces a tiny compatibility change.
- The customer/job/visit scenario definitions belong in `api/src/database/development-seed-scenarios.ts` as `SEED_CUSTOMERS`.
- The scenario tests are in `api/src/database/development-seed-scenarios.spec.ts`.
- `make seed` runs `api/package.json` script `db:seed`, which executes `dist/database/run-development-seed.js` after build.

## Current state

`SEED_CUSTOMERS` is currently an empty placeholder. The scenario types, schedule helpers, status-path helpers, and `assertSeedScenariosAreCoherent` still exist. The seed runner still clears existing demo Customers, Properties, Jobs and Visits before writing `SEED_CUSTOMERS`, and it still creates the manager/technician accounts, roles, permissions and extra technician members.

## Goal

Create a new realistic local demo dataset for `SEED_CUSTOMERS` that gives QA/developers useful data for clients/customers, jobs and visits without violating domain rules.

## Dataset requirements

- Use reserved `.test` email addresses only.
- Keep data Canada-oriented unless you have a strong reason not to.
- Include a useful mix of COMPANY and INDIVIDUAL customers.
- Include contacts for COMPANY customers, with at most one primary contact per company.
- Include properties with structured addresses.
- Include jobs across all job statuses: `NEW`, `ACTIVE`, `COMPLETED`, `CANCELED`.
- Include visits across all visit statuses: `DRAFT`, `SCHEDULED`, `EN_ROUTE`, `ON_SITE`, `IN_PROGRESS`, `COMPLETED`, `CANCELED`.
- Use relative schedules only: `DAYS_AGO`, `IN_DAYS`, and `HOURS_FROM_NOW`.
- Never mark a future visit as completed or in progress before its window starts.
- Give every scheduled/non-draft visit a crew, with exactly one lead implied by the first crew member.
- Use available technician keys only: `SEEDED`, `SARAH`, `JOHN`, `PRIYA`, `LUC`.
- Ensure the seeded technician QA account (`SEEDED`) has at least one current or upcoming non-canceled visit.
- Avoid overlapping visits for the same technician; `assertSeedScenariosAreCoherent` should catch this.
- Give completed visits an outcome with a non-empty summary.
- Give canceled visits a structured cancellation reason.
- Keep notes realistic, non-empty, and authored only by `MANAGER` or a technician on that visit's crew.
- Make titles, descriptions, notes and outcomes read like an office/field team wrote them, not like internal tags.

## Testing and cleanup

- Update `api/src/database/development-seed-scenarios.spec.ts` so dataset-level tests assert the regenerated dataset is coherent, covers all job/visit statuses, assigns work to `SEEDED`, and keeps histories in the past and in order.
- Preserve the helper/unit tests for `resolveSeedWindow`, `orderedPastEventTimes`, `pastInstant`, and invalid scenario rejection.
- Run the focused scenario tests, then the relevant API build/test command available in this repo.
- Do not change migrations or product behavior unless the regenerated data reveals a real incompatibility.
