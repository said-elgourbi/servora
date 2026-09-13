UPDATE "permissions"
SET
  "code" = 'customers.create',
  "name_en" = 'Create customers',
  "name_fr" = 'Creer des clients',
  "description_en" = 'Create customer records.',
  "description_fr" = 'Creer des dossiers client.'
WHERE "code" = 'CUSTOMER_CREATE';
--> statement-breakpoint
UPDATE "permissions"
SET
  "code" = 'customers.view',
  "name_en" = 'View customers',
  "name_fr" = 'Voir les clients',
  "description_en" = 'View customer records.',
  "description_fr" = 'Voir les dossiers client.'
WHERE "code" = 'CUSTOMER_VIEW';
--> statement-breakpoint
UPDATE "permissions"
SET
  "code" = 'customers.edit',
  "name_en" = 'Edit customers',
  "name_fr" = 'Modifier les clients',
  "description_en" = 'Edit customer records.',
  "description_fr" = 'Modifier les dossiers client.'
WHERE "code" = 'CUSTOMER_UPDATE';
--> statement-breakpoint
UPDATE "permissions"
SET
  "code" = 'customers.archive',
  "name_en" = 'Archive customers',
  "name_fr" = 'Archiver des clients',
  "description_en" = 'Archive customer records without physically deleting historical data.',
  "description_fr" = 'Archiver les dossiers client sans supprimer physiquement les donnees historiques.'
WHERE "code" = 'CUSTOMER_DELETE';
--> statement-breakpoint
DELETE FROM "permissions"
WHERE "code" = 'CUSTOMER_VIEW_DELETED';
