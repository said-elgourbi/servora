# Customer screens — design brief

Source of truth checked: `api/src/database/schema.ts`, `docs/domain/foundation-domain-model.md`,
`docs/domain/job-visit-domain-model.md`, `docs/api/customers.md`, and the current decisions in
`docs/decisions/012-property-lifecycle-and-permissions.md` and
`docs/decisions/022-customer-contact-persons.md`. Older Android trackers are useful for prior UI
choices, but the current schema/API and accepted decisions below take precedence.

The four supplied HTML mockups are the visual and information-hierarchy direction for this work:
`Customer screens.html`, `Customer screens – Add Customer.html`,
`Customer screens – Add Property.html`, and `Customer screens – Edit Customer.html`. Keep their
cards, labels, spacing, blue accent, wide desktop form layout and responsive collapse. The latest
product direction changes the Add Property presentation: open it as a popup from View Customer.
Add/Edit Property and Add/Edit Contact use popups from View Customer. Add Customer and Edit Customer
remain full pages.

## Product context

- These are **office/browser** screens for staff maintaining service customers. Design for a desktop
  workspace first, responsive down to tablet/mobile widths; do not simply enlarge Android layouts.
- A **Customer** is the service recipient. The Servora-using organization is a separate tenant.
- Customer identity is Individual or Company. The shared Customer record and exactly one matching
  subtype record hold different fields.
- A **Property** is a service location, not a customer address. A Property is organization-owned and
  linked to a Customer through a relationship table. Jobs and Visits use its address as a snapshot.
- Customer and Property records are online business data. Use plain labels, visible save/cancel
  actions, clear validation, and preserve typed values on failure.

## Data model and field inventory

### Customer header (`customers`)

| Field | Storage / rules | Design guidance |
| --- | --- | --- |
| Type | `INDIVIDUAL` or `COMPANY` | Required choice; drives subtype fields. |
| Display name | Required, max 255; API does not derive it | Derive from Individual first + last name, or Company legal name; this is the customer name shown in lists/detail. |
| Email | Optional, max 320 | Customer's general email, not a contact person's email. |
| Phone | Optional, max 50 | Customer's general phone, not a contact person's phone. |
| Billing email | Optional | Separate billing channel; keep distinct from general email. |
| Billing phone | Optional | Separate billing channel; keep distinct from general phone. |
| Notes | Optional free text | General customer notes. |
| Preferred contact method | `EMAIL`, `PHONE`, `SMS`, `NONE`; default `NONE` | Optional form choice if product wants it; use localized labels, store code. |
| Language | `en-CA` or `fr-CA`; default `en-CA` | Optional form choice if useful; not the UI locale. |
| Status | `ACTIVE` / `INACTIVE`; default `ACTIVE` | Edit screen control. Customer archive/deactivation is a separate action, not status edit. |
| System metadata | UUID, organization, timestamps, soft-delete fields | Not editable form fields. Normal customer removal is soft archive, never hard delete. |

### Individual subtype (`customer_individuals`)

| Field | Storage / rules |
| --- | --- |
| First name | Required, max 100 |
| Last name | Required, max 100 |
| Date of birth | Optional date |

### Company subtype (`customer_companies`)

| Field | Storage / rules |
| --- | --- |
| Legal name | Required, max 255 |
| Business / trading name | Optional, max 255 |
| Tax number | Optional, max 100 |

Exactly one subtype must exist and match the customer type. Switching type is a real conversion:
require the new type's required fields, replace the subtype in one transaction, and explain before
save that the old subtype values are removed and are not copied/mapped. Existing customer header,
contacts, addresses, Properties, Jobs, and history relationships remain attached.

### Contact people (`customer_contacts`)

- Any number per customer; none required. At most one is primary. A Company primary contact is a
  contact row; an Individual is their own primary and should not be duplicated as a contact row.
- Each contact has required first and last name (max 100), optional own email (max 320), optional
  own phone (max 50), optional `role` (max 100), primary flag, billing-contact flag, job-contact
  flag, version and timestamps. v1 client forms do not expose `role` or the billing/job flags.
- Customer header email/phone remain the customer's general line and must not be presented as the
  contact's details.
- Detail read returns current contacts only, primary first then oldest-first. Removed contacts are
  soft-removed and absent from ordinary views. Removing a primary does not promote another person.
- Permissions are independent: `customers.contacts.create`, `.edit`, `.remove`. Edit Customer keeps
  contacts read-only with a link/hint to View Customer, where contact management belongs.

### Property (`properties`) and customer relationship

| Field | Storage / rules | Design guidance |
| --- | --- | --- |
| Property name | Optional, max 255 | Useful building/site label. |
| Street address | Required, max 255 | `addressLine1`. |
| Unit / suite | Optional, max 255 | `addressLine2`; this is where unit belongs. |
| City | Required, max 100 |  |
| Province / territory | Required stable code | Picker for `AB, BC, MB, NB, NL, NS, NT, NU, ON, PE, QC, SK, YT`; show localized human-readable names and send the code. |
| Postal code | Required, max 20 |  |
| Country | Stored as Canada | Do not ask for or send it; backend fills it. |
| Notes | Optional | Property notes. Do not label as a separate access-instructions field. |
| Status | `ACTIVE` / `ARCHIVED` | Lifecycle state; normal customer detail list shows active Properties by default. |
| Derived values | Job count; most recent completed service date | Read-only. `lastServiceAt = null` means show localized “Never serviced”. |

Do not add Property type, access-instructions, customer billing address, or a `customerId` field to
the Property form: those are not part of this create contract/model. Creating a Property creates an
active Customer relationship in the same operation. Archived Properties are not deleted and are
excluded from the default property list/count.

## Screen 1 — View Customer

### Purpose and hierarchy

One readable office workspace that answers who the customer is, how to reach them, which service
locations belong to them, and their recent operational context without hiding key summary facts.

1. **Header:** breadcrumb, display name, Archive and Edit Customer actions. Archive requires
   `customers.archive`; Edit requires `customers.edit`.
2. **Identity summary card:** initials/avatar, display name, type and Active/Inactive badges, legal
   name for companies, job count, active Property count, general email and phone.
3. **Two-column desktop body:** Customer Details and Contact People on the left; Properties and
   Recent Jobs on the right. Collapse to one column responsively.
4. **Customer Details:** type-specific fields; language; general email/phone; billing email/phone;
   notes. Show a clear “Not recorded” state for absent details, as in the mockup. Include preferred
   contact method if kept in the form scope.
5. **Contact People:** primary first; show name, own email/phone and Primary badge. Add, Edit and
   Remove are permission-gated. The mockup's “No email or phone recorded” is suitable when both are
   absent. Show an empty state when there are no contacts.
6. **Properties:** active Properties by default, with name, active badge, full address, job count,
   last service date/never serviced. “Show archived” reveals archived records. Add Property opens
   the popup below and requires `properties.create`.
7. **Recent Jobs:** show title, Property address, selected Visit date, assigned technicians and
   status/overdue indicator. Use the API's one selected Visit for date and technicians; do not
   combine values from different Visits. “View all” can navigate to the full list.

### Data and behaviors

- Reads: `GET /customers/:id`; Properties from `GET /customers/:id/properties`; Jobs from
  `GET /customers/:id/jobs`. Property read authorization is separate from customer read.
- Detail data includes `propertyCount`, `jobCount`, contacts, and the matching individual/company
  record. Counts and last service date are derived, never editable.
- The mockup's “active jobs” summary is not in `GET /customers/:id` today: detail exposes total
  `jobCount`, while `openJobCount` is only on the customer list projection. Either label the
  available total job count or treat a detail API addition as a separate requirement; do not imply
  the values are interchangeable.
- Property list defaults to active relationships; archived records require a deliberate archived
  view/filter. Job address is the historical snapshot, not necessarily the current Property address.
- Gate Edit on `customers.edit`; Add Property on `properties.create`; contact actions on their
  specific contact permissions. Hiding controls is presentation only; API still authorizes writes.
- Include loading, retry/error, empty, and partial-permission states. A missing optional Properties
  projection must not make the customer identity/details unusable.

### Add / Edit Contact popup

- Open Add or Edit from Contact People without leaving View Customer. Use a focused dialog with a clear title,
  customer context, close/cancel and primary save action. On narrow screens use a near-full-screen
  dialog or sheet while retaining the overlay context.
- Fields: first name*, last name*, email (optional), phone (optional), and “Make primary”. Keep
  contact `role` and billing/job flags out of the v1 client form.
- Add/edit/remove remain on this customer detail surface. Add requires
  `customers.contacts.create`; edit requires `.edit`; removal requires `.remove` and confirmation.
  Making another contact primary replaces the old primary. Removing the primary leaves none; do not
  auto-promote another contact.
- On success, close the popup and refresh the card. On failure, keep it open and retain the input.

## Screen 2 — Add Customer

### Fields

- Type selector: Individual / Company.
- Individual: first name*, last name*, date of birth (optional).
- Company: legal name*, business/trading name (optional), tax number (optional).
- Shared: general phone, general email, billing phone, billing email, notes. Optional preferences:
  preferred contact method and customer language (both have model/API support; decide whether they
  belong in v1 visual scope).
- Company-only optional initial primary contact: first name*, last name*, own phone, own email.
  No additional contact list in this flow. Individual customers do not get a duplicated contact row.
- Optional **First service property** section only if caller has `properties.create`: name, street*,
  unit/suite, city*, province*, postal code*, notes. Clearly state this is a service location, not a
  billing address. Leaving it untouched creates no Property.

`*` required when that section/type is active. Phone and email are optional in the model despite old
prototype validation. If the optional Property section is started, require its required address
fields rather than silently skipping a partial entry.

### Save flow and states

- Permission: `customers.create`. Writes occur in order: create customer, optionally create first
  Property, optionally create Company primary contact. These are separate API operations because
  their capabilities are independent.
- If customer creation fails, stay on form and preserve input. If a later optional step fails, the
  customer still exists: clearly say which part did not save and provide **Open customer** to finish
  there; do not offer a duplicate customer create.
- On full success, open View Customer. Include cancel/back, validation, saving, success and server
  error states. Do not require customer phone/email.

## Popups — Add Property / Edit Property

### Fields and copy

- Open Add or Edit from View Customer's Properties card without changing pages. Follow the supplied
  Add Property mockup inside a focused popup with the customer context; Edit uses the same fields,
  prefilled. On narrow screens it may use a near-full-screen dialog/sheet while retaining the overlay
  context.
- Property name (optional), street address* (required), unit/suite (optional), city* (required),
  province/territory* (required picker), postal code* (required), notes (optional).
- No country field (Canada is stored by backend); no property type/access-instructions field.
- State that this is a service location, not a billing address.

### Behavior

- Permission: `properties.create`, independent of all `customers.*` permissions.
- Save calls `POST /customers/:id/properties`; API creates the property and active customer
  relationship atomically. New property has job count 0 and no service date.
- Save closes the popup and refreshes View Customer; Cancel/close returns without changes.
- Handle invalid province/address, offline/network, authorization and not-found errors without
  clearing entered values.

## Screen 4 — Edit Customer

### Fields

Same customer fields as Add Customer, prefilled, plus status Active/Inactive. Customer status is not
archive. Contacts and Properties are read-only here; show a concise hint/link to View Customer for
those areas.

### Behavior and conversion

- Permission: `customers.edit`. Save uses `PATCH /customers/:id` (or `PUT`) and returns refreshed
  customer data.
- Ordinary edits may submit changed fields only. If type is stated, request must include display
  name and the complete matching required subtype; do not send both subtype objects.
- Switching Individual ↔ Company requires an explicit warning/confirmation: conversion replaces
  the old subtype data, maps no values, and cannot be reversed through a restore action. Keep
  current shared customer fields and linked records.
- Show validation, save-in-progress, API error, conflict/reload if available, cancel/back, and saved
  states. Preserve all fields on failure.

## Cross-screen design notes / questions for designer

- Use separate labels for **customer general contact**, **billing contact details**, and **contact
  people**; they are different data.
- Show optional markers where helpful; do not visually imply optional model fields are required.
- Use localized labels for stable codes and the shared EN/FR dictionaries in implementation.
- Treat contact and Property actions as permission-dependent; do not imply that Edit Customer grants
  them.
- Decide whether date of birth, tax number, billing channels, preferred contact method and language
  should appear in the initial forms. They exist in the model/API but older screen prototypes omitted
  several; omitting them is a design-scope choice, not a schema limitation.

## Primary references

- `api/src/database/schema.ts` — current persistence model and constraints.
- `docs/domain/foundation-domain-model.md` §7–§9 — customer types, contacts and address distinction.
- `docs/domain/job-visit-domain-model.md` §3 and §8.4 — Property/Job relationship and derived reads.
- `docs/api/customers.md` §3, §3.5–§3.6, §5 — read/write payloads and permissions.
- `docs/decisions/012-property-lifecycle-and-permissions.md` — independent Property capabilities and archived projection.
- `docs/decisions/022-customer-contact-persons.md` — contact ownership, permissions and lifecycle.
- `docs/tracker/android/014-android-add-customer.md`, `015-android-edit-customer.md`, `011-android-add-property.md` — prior implementation notes, with later decisions superseding old contact/property permissions.
