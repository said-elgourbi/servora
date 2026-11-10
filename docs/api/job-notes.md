# Servora API — Job updates

`POST /jobs/:id/notes` records an append-only update directly on a Job. It returns the refreshed
Job Activity projection. The write does not change Job status, Visit status, Visit outcome, or any
outstanding need.

## Permission and scope

Requires `JOB_UPDATE`. The Job is looked up within the authenticated organization. Reusing a
`clientOperationId` returns the existing Job Activity without adding a duplicate.

## Request

```json
{
  "body": "Access confirmed for Tuesday.",
  "clientOperationId": "6f1a8d1e-4b26-4f8f-9a34-2b7c9e0d5a11"
}
```

`body` is required, trimmed and limited to 4,000 characters. `clientOperationId` is an optional UUID
used to make retries safe.

## Response

`201 Created`, with a `JOB_NOTE_ADDED` event in `GET /jobs/:id/activity`. The event has a null
`visitSequence`, the actual posting timestamp and the authenticated author.
