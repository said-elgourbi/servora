# Browser field context and scoped requests

`GET /auth/field-context` authenticates the current session and returns
`{ userId, organizationId, membershipId }`. Exactly one ACTIVE membership must exist and hold
`VISIT_VIEW_ASSIGNED`. No membership/capability returns 403 `FIELD_CONTEXT_UNAVAILABLE`;
multiple active memberships return 409 `FIELD_CONTEXT_AMBIGUOUS`. Tenant selection is not supported.

Field requests carry all three UUID headers: `X-Servora-Field-User`,
`X-Servora-Field-Organization`, `X-Servora-Field-Membership`. Partial, repeated or malformed
headers return 400 `FIELD_SCOPE_INVALID`. Changed subject/membership/organization returns
403 `FIELD_SCOPE_CHANGED`. Unsupported routes return 400 `FIELD_SCOPE_UNSUPPORTED`.
These are partition assertions, never authorization grants. Header omission preserves existing clients.

Supported routes: technician Home; Job details/activity; Visit status/completion/notes;
photo/audio upload and content; technician follow-up submission/reply; ad-hoc report submission
and own report reads. Scoped detail/activity/content and writes always use assigned-field reach,
even when the member also has office capabilities. Resources in another organization or outside
current assignments return the existing not-found response. Removed evidence audit reads are refused.

Every scoped handler executes inside a database transaction that rechecks membership and current
capabilities after guards, under membership row locks. The transaction is propagated only through
the asynchronous request context; existing service transactions become savepoints. Uploads therefore
hold the transaction during object storage access. Failed requests roll back database changes;
storage success followed by database failure may leave an unreferenced object under the stable
operation key. A retry reuses that key; automatic provider-side orphan cleanup is not claimed.
No migration or change to unscoped Android requests is required. Responses remain no-store.

Scoped replayable mutations require a non-null operation UUID and capture time, and status/completion
also require an observed version. Scoped ad-hoc submissions require an operation UUID (their work
interval supplies provenance). Missing replay fields return 400 `VALIDATION_FAILED`. Unscoped DTOs
keep their nullable legacy fields. Follow-up submission and replies are online-only and not replayed.

The browser never uses a status retry's current Job version as a receipt for the original result.
An ambiguous status/completion is retried with its original payload/id, then retained for explicit
online review; descendants stay blocked. Reviewing current details retains those original records
and marks the dependency branch reviewed. Changed intent creates a new operation ID and uses the
version the user just reviewed. It does not resume stale descendants.
