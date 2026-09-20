-- The evidence capability set (`BR-006`, `BR-015`, `BR-027`; tracker 029 Phase 1, decisions D1/D1b).
--
-- Evidence is recorded by the technician who is on site, so it is authorized by capabilities of its
-- own rather than by the interim pair the two Job photo routes used: the write required `JOB_UPDATE`
-- and the read required `customers.view`, neither of which the default Technician role holds
-- (`BR-009`). The capability is per kind, so one kind can be withdrawn from a member without
-- withdrawing the others; `evidence.audio.add` is the agreed extension point for audio evidence and
-- is deliberately **not** created here, because no product rule defines audio yet (`BR-042`).
--
-- Hand-written, like `0005`-`0009`: this migration adds catalogue and grant **rows**, not schema, so
-- no snapshot changes. An applied migration is never modified (`dev.md` §6).
INSERT INTO "permissions" ("code", "name_en", "name_fr", "description_en", "description_fr")
VALUES
	('evidence.view', 'View evidence', 'Voir les preuves', 'View evidence attached to a Job and read its stored content.', 'Voir les preuves jointes a un travail et lire leur contenu enregistre.'),
	('evidence.photo.add', 'Add photo evidence', 'Ajouter des preuves photo', 'Attach a photo to a Job as field evidence.', 'Joindre une photo a un travail comme preuve terrain.');
--> statement-breakpoint
INSERT INTO "role_permissions" ("organization_id", "role_id", "permission_id")
SELECT "organization_roles"."organization_id", "organization_roles"."id", "permissions"."id"
FROM "organization_roles"
CROSS JOIN "permissions"
WHERE "organization_roles"."system_code" = 'MANAGER'
	AND "permissions"."code" IN ('evidence.view', 'evidence.photo.add');
--> statement-breakpoint
INSERT INTO "role_permissions" ("organization_id", "role_id", "permission_id")
SELECT "organization_roles"."organization_id", "organization_roles"."id", "permissions"."id"
FROM "organization_roles"
CROSS JOIN "permissions"
WHERE "organization_roles"."system_code" = 'TECHNICIAN'
	AND "permissions"."code" IN ('evidence.view', 'evidence.photo.add');
