# Angular Phase 2 — field workflow proposal

Date: 2026-10-19
Status: ACCEPTED DESIGN — adopted on 2026-10-19 in [ADR-027](../decisions/027-angular-field-offline-workflow.md),
including scope and response-loss clarifications. Implementation remains pending.
Scope: [roadmap 064, Phase 2](../tracker/angular/064-web-app-delivery-roadmap.md#phase-2--technician-field-workflow).

## Decision recorded

The owner selected the recommendation to settle this proposal on 2026-10-19.
[ADR-027](../decisions/027-angular-field-offline-workflow.md) is the authoritative accepted decision;
the proposal below preserves its original rationale and proposed details. Where they differ, follow
the ADR, especially for server-verified read scope and ambiguous replay responses. No runtime
persistence or replay has been enabled.

Adopt a bounded IndexedDB working set, durable field drafts and a foreground replay queue.
This would extend [ADR-026](../decisions/026-angular-foundation-and-browser-sessions.md)'s
prohibition on persistent protected responses. Credentials, identity and permission grants remain
memory-only; service-worker caching remains shell-only. Nothing in this proposal is implemented.

The proposed first release supports offline work during an already authenticated running session.
After a full reload or app closure, drafts survive but require online authentication and scope
verification before they can be opened or sent. Cold-start offline access would require a separate
local-unlock/session decision; it is not implied by retaining drafts.

## Existing contracts and tenant prerequisite

Visit status, completion and notes support `clientOperationId`; status/completion also accept
`expectedVersion`. Photo/audio uploads support stable client-generated operation IDs. Ad-hoc report
submission supports an operation ID. Follow-up requests and replies are online-only because their
API has no idempotency contract. See [actions](../api/job-actions.md),
[photos](../api/job-photos.md), [audio](../api/job-audio.md),
[reports](../api/ad-hoc-work-reports.md) and [requests](../api/visit-requests.md).

`AuthMeDto` currently contains only `userId` and unioned permissions. The API's
`AuthorizationService` resolves a qualifying membership separately for each route. User ID or
session ID alone therefore cannot prove a persistent record's organization, especially after
membership changes. Do not infer an organization from a role or decode one from the access token.

Proposed bounded solution: an additive browser field-context read returns server-verified user,
organization and membership IDs only when the user has exactly one active membership. Field
persistence and replay require that context. Every replayed write must also enforce the same
organization/membership in the API, failing closed if the context changed. This requires a reviewed
contract/guard extension, not a client-only header check. Multi-membership accounts retain existing
online behavior; offline support for them waits for an explicit tenant-selection design. Existing
Android routes and unscoped request behavior remain compatible. No PostgreSQL migration is
expected for this context read; verify that assessment during API implementation.

## Proposed working set and retention

- Store successful assigned-work Home, opened Job/Visit details and activity reads only, keyed by
  user + organization + membership + projection/query scope, with fetch time and schema version.
  Office Home and office-wide customer lists are excluded.
- Keep accepted evidence bytes online-only initially. Pending photo/audio blobs are stored with
  their draft; the technician can review these without fetching accepted evidence.
- Use cached reads only for connection/server failure during an established session. Label the
  last successful fetch time; never mask `401`, `403`, `404` or validation/conflict responses.
- Evict unpinned read snapshots after seven days or when the bounded working set exceeds 50 Jobs.
  Pin records referenced by pending work. Never automatically evict drafts, uploads or refusals.
  These bounds are proposed product choices, not existing retention rules.
- Commit a draft and its blobs transactionally before reporting it saved. Storage/quota failure
  preserves the editor and explains that the draft is unsaved; it must prevent a false success.
  Request persistent storage when available, but never promise immunity from site-data clearing.

## Proposed replay and conflict behavior

Each submitted operation stores an immutable payload, operation UUID, capture time, scope and
dependencies. A draft remains editable until submission. Display separate draft, pending,
uploading, accepted and needs-attention states in EN/FR; pending is never presented as server success.

Replay uses a cross-tab Web Lock and runs in the foreground after authentication, resume,
reconnect or explicit Retry. Apply commands sequentially per Visit; notes/evidence selected with a
completion must be accepted before sending the completion. A refused dependency blocks dependent
commands but does not block unrelated Visits. Do not depend on background execution.

For status/completion, retain the observed version. A dependent status command can use the version
returned by its own accepted predecessor; do not fetch a newer version merely to force a stale
intent through. A conflict pauses that Visit for an explicit reload/review. Changed intent creates
a new operation ID; an ambiguous network retry preserves the original ID and payload. Notes,
evidence and reports use their append/submission idempotency contracts with current server checks.
Read models are replaced from API responses; pending commands are a separate overlay.

Retry network/5xx with bounded backoff and honor `429` retry timing. Refresh authentication once
through the existing session adapter for `401`, then pause for sign-in. Retain `400`, `403`, `404`,
`409`, `413`, `415` and `422` refusals for review; do not retry unchanged invalid work. Verify
idempotency lookup ordering and response-loss recovery against the real API before enabling replay.
Follow-up writes remain online-only with an explicit unsent/error state, without automatic retry.

## Proposed sign-out, closure and updates

Sign-out immediately hides all protected content, stops replay and clears read snapshots. Retain
pending drafts/blobs and refusals in their original scope; another user cannot open or replay them.
Online sign-in and matching field context are required to unlock them again. A scope mismatch
keeps them locked and explains that work has not been sent. Do not silently relabel or migrate work
to another tenant. This is application isolation, not encryption against an attacker with origin or
device access; local protected storage is an explicit tradeoff of this proposal.

Offer explicit discard for unsent work with confirmation. An in-flight/ambiguous submission must
first be reconciled using its operation ID; do not claim discarding it reverses server evidence.
Persist editor changes before offering an update reload. Schema upgrades must migrate pending
work transactionally, never delete/recreate the database as a fallback. Browser eviction or a user
clearing site data can still destroy unsent work; show that limitation in the offline setup UX.

## Implementation and acceptance inside Phase 2

1. Review and implement the field-context contract, scope enforcement and backward-compatible
   shared wire declarations, with real API membership-change and cross-tenant rejection tests.
2. Deliver full field detail/activity and permitted Visit actions with purpose-specific completion,
   notes, photo capture/selection/review and supported audio recording/upload/playback. Check
   runtime audio codec support against the existing MP4/AAC API contract; do not relabel WebM.
3. Integrate the working set, durable drafts, queue and recovery states into that same workflow.
   Include online follow-up/clarification and idempotent ad-hoc submission. These are tasks within
   Phase 2, not new roadmap phases.
4. Verify real API execution, response loss, duplicate retries, stale versions, rejected uploads,
   account/tenant changes, two-tab replay, reload/closure, quota failures and update migration.
   Run affected web/API checks and EN/FR responsive/keyboard browser journeys. Keep Phase 1 manual
   accessibility and deployed/provider acceptance open until actually performed.

Physical iPhone/Safari/installed-PWA checks remain deferred by the owner's 2026-10-18 decision.
No browser version or physical field-readiness acceptance is established by this design review.

Platform references checked 2026-10-19: WebKit's
[storage policy](https://webkit.org/blog/14403/updates-to-storage-policy/) describes quota,
eviction and persistent-storage behavior. Its
[MediaRecorder introduction](https://webkit.org/blog/11353/mediarecorder-api/) documents MP4/AAC;
[Safari 18.4 changes](https://webkit.org/blog/16574/webkit-features-in-safari-18-4/) add recording
formats. These support runtime capability checks, not an untested device-support promise.
