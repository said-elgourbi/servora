-- The technician's read of the Customer behind an assigned Job (`BR-092`, `BR-009`, `BR-006`;
-- `ADR-021` D1).
--
-- `ADR-019` D1 admitted a technician to `GET /jobs/:id`, but the default Technician role held no
-- Customer capability at all, so the customer the Job belongs to was unreachable: the Android Job
-- Details screen offered the office Customer destination, which `customers.view` guards, and the API
-- refused it with `403`. `BR-092` defines the field read the technician actually needs — the
-- Customer's own name, phone, email and notes, view-only, only through a Job their own current crew
-- includes — and it is authorized by this capability of its own rather than by widening
-- `VISIT_VIEW_ASSIGNED` or by granting the organization-wide `customers.view`.
--
-- The default Manager role is deliberately **not** granted it: a manager reads the same Customer
-- through `customers.view`, which they already hold. Only the default Technician role receives it, so
-- a technician holds the read by default (`BR-009`) while any other role or member is granted it
-- through the normal permission model when a company wants it (`BR-004`, `BR-006`).
--
-- Hand-written, like `0005`-`0012`: this migration adds catalogue and grant **rows**, not schema, so
-- no snapshot changes. An applied migration is never modified (`dev.md` §6).
INSERT INTO "permissions" ("code", "name_en", "name_fr", "description_en", "description_fr")
VALUES
	('customers.view_assigned', 'View assigned customers', 'Voir les clients assignes', 'View the customer of a job the member is assigned to.', 'Voir le client d''un travail auquel le membre est assigne.');
--> statement-breakpoint
INSERT INTO "role_permissions" ("organization_id", "role_id", "permission_id")
SELECT "organization_roles"."organization_id", "organization_roles"."id", "permissions"."id"
FROM "organization_roles"
CROSS JOIN "permissions"
WHERE "organization_roles"."system_code" = 'TECHNICIAN'
	AND "permissions"."code" = 'customers.view_assigned';
