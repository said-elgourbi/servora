# Servora feature trackers

Trackers are grouped by the application that owns the work. Existing IDs remain stable;
use the next unused repository-wide number for new work. Moving a tracker does not change
its status, evidence or outstanding QA.

| Folder | Scope |
| --- | --- |
| [Angular](angular/README.md) | Browser client, responsive UI, PWA, localization and web authentication |
| [Android](android/README.md) | Android-led slices, including the API work needed to deliver those screens |
| [API](api/README.md) | Backend-only implementation and infrastructure |
| [Shared](shared/README.md) | Cross-application features, domain models, contracts and repository governance |

A cross-application feature keeps one authoritative tracker in `shared`; link to it from
application trackers instead of duplicating completion states. Historical Android-led slices
retain their existing scope even when they also contain API changes.

Current Angular starting point: [063 — Angular foundation and authentication](angular/063-angular-foundation-and-authentication.md).
Remaining web delivery: [064 — Web app delivery roadmap](angular/064-web-app-delivery-roadmap.md),
covering office and technician workflows, including iPhone PWA use, in broad delivery phases.

On 2026-10-18, trackers 001–062 were moved from this directory into these folders.
Repository references and Markdown links were updated; numbers and historical status were preserved.
