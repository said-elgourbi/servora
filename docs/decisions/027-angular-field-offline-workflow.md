# ADR-027 — Angular field working set, drafts and replay

Date: 2026-10-19
Status: ACCEPTED — initial API/web implementation delivered; field-device acceptance remains pending in tracker 064 Phase 2.
Authorization: the owner selected the recommendation to settle the existing offline proposal
on 2026-10-19 ("do with your recommendation"). This records that design decision. Implementation and executed verification are tracked separately
in tracker 064; accepting the design alone did not establish field readiness.

## Context and impact

The Angular PWA needs a complete technician Visit workflow during connection loss.
[ADR-026](026-angular-foundation-and-browser-sessions.md) currently permits only an offline shell.
The [reviewed proposal](../architecture/angular-field-workflow-proposal.md) supplies the bounded
field design adopted here, with the scope and replay clarifications below. This ADR is authoritative
if the proposal differs. Android's existing engine and business workflows remain unchanged.

Affected layers: Angular field UI, IndexedDB persistence and session/update integration; additive
API field-context and scope enforcement; shared TypeScript contracts; business rules and acceptance
documentation. No PostgreSQL migration is expected for context resolution against existing membership
tables. Verify that assessment when designing any operation-receipt contract before implementation.

## 1. Local data and authentication boundary

Use IndexedDB for the assigned-field working set, durable editor drafts, pending photo/audio blobs,
immutable submitted commands and retained refusals. Keep these within the field feature's data-access
ownership, using the existing auth feature's public session lifecycle. Do not introduce a global
business cache, a second session system or a generic synchronization framework.

This narrowly extends ADR-026's protected-response persistence prohibition. Persist only the bounded
field projections and work defined here, including their user/organization/membership identifiers as
partition keys. Those identifiers are not a saved identity or permission grant. Credentials,
passwords, OTPs, access tokens, identity profiles and permission grants remain memory-only, apart
from the existing HttpOnly refresh cookie. Service-worker/HTTP caching remains shell-only/no-store;
there are no protected service-worker data groups.

Offline field access requires a previously established session and verified field context in the
currently running application. Access-token expiry alone does not erase unsent work or manufacture
a new session: local work may continue provisionally while that established session is retained,
but replay requires online authentication and context revalidation. Explicit logout, account change,
rejected authentication or known scope invalidation locks local work immediately across tabs.
An unavailable refresh is not successful authentication. After reload or app closure, require online
authentication and context verification to unlock retained work. Cold-start offline unlock is outside
this release.

## 2. Server-verified field scope comes first

`AuthMeDto` exposes a user ID and unioned permissions; it cannot establish a draft's tenant.
Add an authenticated field-context read returning server-verified `userId`, `organizationId` and
`membershipId` only for exactly one active membership with the required field capability. Zero or
multiple active memberships disable persistence/replay with a clear explanation; existing online
capabilities remain available. Tenant selection for multiple memberships is outside this release.

Define the additive endpoint, DTOs, request scope carrier and stable error codes in the API contract
before coding. Scoped requests must validate current membership and permissions server-side, match
the resource's organization and the supplied context, and fail closed rather than select another
qualifying membership. This applies to reads used to populate the working set as well as writes.
Recheck context at mutation execution; a client-only header check or a context read made before a
membership change is insufficient. Preserve existing unscoped Android contracts and authorization.

Revalidate context before replay and after authentication/resume recovery. Fence asynchronous reads,
storage commits and queue callbacks with the current session generation so late responses cannot
unlock or populate another account's data. Once access is explicitly refused, invalidate the affected
cached projection and block its pending work; do not serve it as an offline fallback. Offline clients
cannot detect remote revocation until they reconnect; retained local data is an accepted tradeoff.

## 3. Working set, storage and retention

- Retain successful assigned-work Home and opened Job/Visit details/activity projections, keyed by
  user + organization + membership + resource/query/projection version, with fetch time. A mixed
  office/field user's office-wide detail response must not enter the field working set.
- Serve snapshots only on connection/server failure within an established session. Show their age;
  never mask authentication, forbidden, not-found, validation or conflict responses.
- Expire unpinned snapshots after seven days. Retain at most 50 unpinned Jobs, evicting the least
  recently fetched first. Pin the context required by pending work; never evict drafts, uploads or
  unresolved refusals to satisfy a cache limit. Home snapshots must not bypass those Job bounds.
- Keep accepted photo/audio bytes online-only initially; their metadata remains in scoped activity
  projections. Pending evidence bytes belong to durable drafts and can be reviewed locally.
- Commit a draft and its blobs in one transaction before showing Saved. Report quota/storage failure
  as unsaved and preserve the editor. Request persistent storage where supported; explain that browser
  eviction or clearing site data can still lose unsent work. Do not promise device-level durability.

## 4. Commands, ordering and conflict rules

| Operation | Offline policy | Reconciliation |
| --- | --- | --- |
| Visit status/completion | Queue with stable operation ID, capture time and observed version | Reject stale versions; explicit reload/review; never silently rebase |
| Visit notes | Queue immutable append with stable operation ID | Idempotent retry, current server capability/assignment checks |
| Visit photo/audio upload | Queue stable operation ID, original payload and blob | Idempotent retry; retain unsupported/oversized/rejected work for review |
| Ad-hoc report submission | Queue stable operation ID and immutable report | Idempotent retry; current server validation/scope |
| Follow-up requests and clarification replies | Online-only | Preserve unsent editor/error state; no automatic write retry |
| Office writes, quote actions and evidence removal | Online-only | Existing contracts; no new replay policy |

Commands store immutable payload, operation UUID, scope, capture time and dependencies. Drafts remain
editable until submitted. Changed intent gets a new operation ID; ambiguous retries keep the original
ID and payload. Distinguish Draft, Pending, Uploading, Accepted and Needs attention in EN/FR.
Pending work is never presented as server acceptance or written into authoritative snapshots.

Use one cross-tab Web Lock for foreground replay; process commands sequentially per Visit. Evidence
and notes submitted with a completion must be accepted before completion is sent. Block descendants
of a refused/ambiguous operation while allowing unrelated Visits to proceed. Retry on authentication,
resume, reconnect and explicit Retry; do not depend on background execution. Avoid nested auth/replay
lock acquisition that could deadlock session refresh.

Retry connection/5xx failures with bounded backoff and honor `429`. Recover `401` through the existing
session adapter once, then pause for sign-in. Retain `400`, `403`, `404`, `409`, `413`, `415`, `422`
and other non-retryable refusals for review. A version conflict requires explicit reload/review and
a new command for changed intent; fetching a current version must never force stale intent through.

### Response-loss prerequisite found during review

`JobsService.changeVisitStatus` checks operation history before version conflicts, but its replay
response calls `requireJobDetails` and returns current details. `findAppliedStatusOperation` currently
returns only `visitId`. Therefore the current replay response does not prove the version produced by
the original command. Do not use its current version to advance dependent status/completion commands
after a lost response; pause for review unless the original resulting version can be established.
If automatic continuation is needed, first add a backward-compatible, server-verified operation
receipt/result contract and test intervening edits. No receipt endpoint is claimed to exist today.

## 5. Logout, discard and application updates

Logout hides protected content, stops replay and clears read snapshots. Retain unsent drafts/blobs,
ambiguous commands and refusals in their original scope, locked until online authentication and a
matching field context. Another account or changed membership cannot inspect, relabel or replay them.
This is application isolation, not encryption against a compromised origin or device.

Allow confirmed discard of never-submitted work. Reconcile ambiguous/in-flight operations before
claiming they were unsent; discarding a local record cannot reverse accepted server evidence. If
reconciliation cannot run, retain the unresolved operation visibly. Never automatically expire unsent
work. Persist editor changes before offering update reload; failure blocks a claim that reload is safe.
Use transactional schema upgrades and pause on blocked/failed upgrades; never delete/recreate the
database to recover. Coordinate open tabs before migrating and resume only with compatible state.

## 6. Implementation order and acceptance

Keep all work in tracker 064 Phase 2, with its existing broad delivery scope:

1. Specify shared field-context/scoped-request contracts; implement server enforcement and real API
   tests for membership changes, multi-membership, cross-tenant access and existing-client compatibility.
2. Deliver full assigned Visit details/activity/actions, purpose-specific outcomes, notes, photos and
   supported MP4/AAC audio capture/upload/playback. Check codecs at runtime; never relabel WebM as MP4.
3. Integrate working set, transactional drafts, foreground replay, follow-ups and ad-hoc submission
   into that workflow with EN/FR status/recovery states, responsive layouts and keyboard access.
4. Prove real API execution, duplicates/response loss with intervening changes, version conflict,
   dependency refusal, assignment revocation, two-tab replay/logout, account/scope changes, closure,
   quota failure, migration failure and update recovery before enabling persistence/replay for users.

Manual Phase 1 accessibility and deployed/provider acceptance remain open. Physical iPhone/Safari
and installed-PWA testing remain deferred by the owner's existing decision; this ADR establishes
neither a tested browser version baseline nor physical field readiness. No runtime code, database
schema, installed dependency or deployment is changed by accepting this design.

## Platform references

Checked 2026-10-19: [IndexedDB specification](https://w3c.github.io/IndexedDB/) for transactional
structured storage, [Web Locks specification](https://w3c.github.io/web-locks/) for origin-scoped
coordination, and [WebKit storage policy](https://webkit.org/blog/14403/updates-to-storage-policy/)
for quota, eviction and persistence limitations. These establish mechanisms, not device acceptance.
