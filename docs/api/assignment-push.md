# Assignment push data contract

Status: Version 1 assignment messages are emitted by the opt-in scheduled backend worker and
consumed by Android display/tap handling, 2026-10-16. Physical QA and deployment activation remain
outstanding; see [runbook](../development/firebase.md#assignment-notification-device-qa).

FCM carries a data-only map with exactly these string fields. Version `1` rejects extra fields;
extend the version deliberately rather than silently adding private presentation data.

| Field | Value |
| --- | --- |
| `version` | `1` |
| `eventType` | `VISIT_ASSIGNED` |
| `eventId` | Server event UUID, stable across retries |
| `bindingId` | Immutable installation binding UUID captured by the delivery |
| `organizationId` | Event organization UUID |
| `jobId` | Job UUID |
| `visitId` | Assigned Visit UUID |
| `expiresAt` | ISO-8601 instant with timezone; strictly later than receiver time |

UUIDs require the complete `8-4-4-4-12` hexadecimal format and are normalized to lowercase.
Unknown versions/types, missing fields, malformed identifiers, extra fields and expired messages
are rejected. Include no customer names, addresses, notes, credentials or provider targets.
The transport supplies `eventId` through its existing reserved field. The worker must calculate
FCM TTL from the same expiry immediately before sending. BR-029 establishes the approved 24-hour
event expiry separately from this wire format.

## Deduplication and integration requirements

`AssignmentPushMessage` parses without accessing Android services or authorizing a resource.
`PushReceiptLedger` reserves `(bindingId, eventId)` durably before presentation. Its singleton
serializes concurrent reservations. Retrying the same pair, including after process restart,
returns false until expiry. UUID casing cannot create another reservation. A later reservation
prunes only expired receipts; account changes do not erase unexpired history. The ledger stores
only binding/event IDs and expiry in an atomic app-private file under `noBackupFilesDir`.
Malformed history fails closed without overwriting evidence; storage I/O failures propagate and
must prevent presentation. There is no automatic history reset or backup restoration.

Reserve only after restored-session, installation binding, organization, preference, OS permission,
channel and expiry checks. Use `notificationTag` with a fixed numeric notification ID so a repeated
post updates the same notification. A crash after reservation but before display can lose one
alert; a reservation proves neither display nor read. Do not reserve on tap.

`ServoraMessagingService` now connects parsing and durable reservations to presentation after
local session restoration. Authenticated registration supplies the trusted `organizationId` stored
with the installation binding. A legacy registration without it cannot present until refreshed.
The receiver rechecks eligibility before posting, uses generic English/French resources and clears
account-specific alerts on sign-out. Taps wait behind authentication, revalidate the binding and
open the normal Job/Visit route with a fresh authorized read. Missing/refused resources and offline
reads use existing Job screen behavior. Pending taps never move to another binding/account.

Tests cover payload boundaries, expiry, casing, process-restart deduplication, concurrent callbacks,
binding isolation, pruning, corrupt history and write failure. AtomicFile device tests use isolated
test-APK storage and require product-owner QA; compiling them is not device acceptance.

See [ADR-025](../decisions/025-assignment-push-delivery.md) and
[tracker 062](../tracker/shared/062-notifications-and-firebase.md).
