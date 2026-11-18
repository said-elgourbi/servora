# Servora API — Technician management

**Status: IMPLEMENTED** for organization-scoped technician contact details and direct permission grants.

Technician assignment continues to use the minimal `GET /technicians` projection documented in
[`job-actions.md`](./job-actions.md). Manager profile management uses the separate routes below; these
responses include contact information and must not be used as a general technician directory.

## Permissions

| Route | Permission |
| --- | --- |
| `GET /technicians/management` | `TECHNICIAN_VIEW` |
| `PATCH /technicians/:membershipId` | `TECHNICIAN_UPDATE` |

Both routes require an authenticated member and are scoped to the caller's organization. Only active
members with the default Technician role are returned or updated. The existing `GET /technicians`
assignment route and its contract are unchanged.

## `GET /technicians/management`

Returns `{ "technicians": [...], "permissions": [...] }`. Each technician has its organization
`membershipId`, account `userId`, email, phone, first and last name, status, effective role permission
codes and organization-scoped direct permission codes. The permission catalog includes bilingual
names and descriptions for the permission editor. No credential or authentication secret is returned.
The catalog is limited to permission codes supported by the shared client contract; unrelated legacy
or test-only database rows are not offered as grants.

## `PATCH /technicians/:membershipId`

```json
{
  "email": "alex@example.com",
  "phone": "+1 613 555 0100",
  "firstName": "Alex",
  "lastName": "Martin",
  "directPermissionCodes": ["visits.review_requests"]
}
```

Email, first name and last name are required. Phone may be `null`. Permission codes must exist in the
catalog. The request replaces the technician's direct grants for this organization; grants already
inherited from the Technician role remain inherited and are not duplicated as member grants. The
response is the updated technician record. Existing direct grants outside the shared permission
catalog are retained but are not exposed as editable choices.

Email and profile name/phone are properties of the user account, so contact edits apply anywhere that
account is used. Direct permission grants are scoped to the caller's organization membership. An
email already used by another account returns `409 TECHNICIAN_EMAIL_IN_USE`; a membership outside the
organization or no longer an active Technician returns `404 TECHNICIAN_NOT_FOUND`.

## Product boundary

This manager workflow covers basic identity/contact fields and permissions only. Technician creation,
deactivation, skills, territory, availability, and credential resets remain outside this API slice.
