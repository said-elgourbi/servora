CREATE VIEW "user_overview" AS
SELECT
  "users"."id" AS "user_id",
  "users"."email",
  "users"."phone" AS "user_phone",
  "users"."status" AS "user_status",
  "users"."last_login_at",
  "users"."created_at" AS "user_created_at",
  "users"."updated_at" AS "user_updated_at",
  "user_profiles"."first_name",
  "user_profiles"."last_name",
  "user_profiles"."display_name",
  "user_profiles"."phone" AS "profile_phone",
  "user_profiles"."avatar_url",
  "user_profiles"."locale",
  "user_profiles"."timezone",
  COALESCE("membership_counts"."membership_count", 0)::integer AS "membership_count",
  COALESCE("membership_counts"."active_membership_count", 0)::integer AS "active_membership_count",
  COALESCE("session_counts"."active_session_count", 0)::integer AS "active_session_count",
  "session_counts"."last_session_used_at",
  COALESCE("password_reset_counts"."outstanding_password_reset_count", 0)::integer AS "outstanding_password_reset_count"
FROM "users"
LEFT JOIN "user_profiles" ON "user_profiles"."user_id" = "users"."id"
LEFT JOIN LATERAL (
  SELECT
    count(*) AS "membership_count",
    count(*) FILTER (WHERE "organization_members"."status" = 'ACTIVE') AS "active_membership_count"
  FROM "organization_members"
  WHERE "organization_members"."user_id" = "users"."id"
) AS "membership_counts" ON true
LEFT JOIN LATERAL (
  SELECT
    count(*) AS "active_session_count",
    max("auth_sessions"."last_used_at") AS "last_session_used_at"
  FROM "auth_sessions"
  WHERE "auth_sessions"."user_id" = "users"."id"
    AND "auth_sessions"."revoked_at" IS NULL
    AND "auth_sessions"."expires_at" > now()
) AS "session_counts" ON true
LEFT JOIN LATERAL (
  SELECT count(*) AS "outstanding_password_reset_count"
  FROM "password_reset_tokens"
  WHERE "password_reset_tokens"."user_id" = "users"."id"
    AND "password_reset_tokens"."used_at" IS NULL
    AND "password_reset_tokens"."expires_at" > now()
) AS "password_reset_counts" ON true;
--> statement-breakpoint
CREATE VIEW "user_effective_permissions" AS
WITH "permission_sources" AS (
  SELECT
    "organization_members"."id" AS "member_id",
    "role_permissions"."permission_id",
    true AS "from_role",
    false AS "from_direct"
  FROM "organization_members"
  INNER JOIN "role_permissions" ON "role_permissions"."role_id" = "organization_members"."role_id"
  UNION ALL
  SELECT
    "organization_member_permissions"."member_id",
    "organization_member_permissions"."permission_id",
    false AS "from_role",
    true AS "from_direct"
  FROM "organization_member_permissions"
),
"effective_permissions" AS (
  SELECT
    "permission_sources"."member_id",
    "permission_sources"."permission_id",
    bool_or("permission_sources"."from_role") AS "from_role",
    bool_or("permission_sources"."from_direct") AS "from_direct"
  FROM "permission_sources"
  GROUP BY "permission_sources"."member_id", "permission_sources"."permission_id"
)
SELECT
  "organization_members"."id" AS "member_id",
  "organization_members"."organization_id",
  "organization_members"."user_id",
  "users"."email",
  "organizations"."name" AS "organization_name",
  "organization_members"."role_id",
  "organization_roles"."name_en" AS "role_name_en",
  "permissions"."id" AS "permission_id",
  "permissions"."code" AS "permission_code",
  "permissions"."name_en" AS "permission_name_en",
  "permissions"."name_fr" AS "permission_name_fr",
  CASE
    WHEN "effective_permissions"."from_role" AND "effective_permissions"."from_direct" THEN 'ROLE_AND_DIRECT'
    WHEN "effective_permissions"."from_direct" THEN 'DIRECT'
    ELSE 'ROLE'
  END AS "source"
FROM "effective_permissions"
INNER JOIN "organization_members" ON "organization_members"."id" = "effective_permissions"."member_id"
INNER JOIN "users" ON "users"."id" = "organization_members"."user_id"
INNER JOIN "organizations" ON "organizations"."id" = "organization_members"."organization_id"
INNER JOIN "organization_roles" ON "organization_roles"."id" = "organization_members"."role_id"
INNER JOIN "permissions" ON "permissions"."id" = "effective_permissions"."permission_id";
--> statement-breakpoint
CREATE VIEW "user_permission_summary" AS
SELECT
  "user_effective_permissions"."member_id",
  "user_effective_permissions"."organization_id",
  "user_effective_permissions"."user_id",
  "user_effective_permissions"."email",
  "user_effective_permissions"."organization_name",
  "user_effective_permissions"."role_name_en",
  count(*)::integer AS "effective_permission_count",
  array_agg("user_effective_permissions"."permission_code" ORDER BY "user_effective_permissions"."permission_code") AS "permission_codes"
FROM "user_effective_permissions"
GROUP BY
  "user_effective_permissions"."member_id",
  "user_effective_permissions"."organization_id",
  "user_effective_permissions"."user_id",
  "user_effective_permissions"."email",
  "user_effective_permissions"."organization_name",
  "user_effective_permissions"."role_name_en";
--> statement-breakpoint
CREATE VIEW "user_membership_overview" AS
WITH "role_permission_counts" AS (
  SELECT
    "role_permissions"."role_id",
    count(*)::integer AS "role_permission_count"
  FROM "role_permissions"
  GROUP BY "role_permissions"."role_id"
),
"direct_permission_counts" AS (
  SELECT
    "organization_member_permissions"."member_id",
    count(*)::integer AS "direct_permission_count"
  FROM "organization_member_permissions"
  GROUP BY "organization_member_permissions"."member_id"
)
SELECT
  "organization_members"."id" AS "member_id",
  "users"."id" AS "user_id",
  "users"."email",
  "user_profiles"."display_name",
  "user_profiles"."first_name",
  "user_profiles"."last_name",
  "users"."status" AS "user_status",
  "organizations"."id" AS "organization_id",
  "organizations"."name" AS "organization_name",
  "organizations"."status" AS "organization_status",
  "organization_members"."status" AS "membership_status",
  "organization_members"."joined_at",
  "organization_roles"."id" AS "role_id",
  "organization_roles"."system_code" AS "role_system_code",
  "organization_roles"."name_en" AS "role_name_en",
  "organization_roles"."name_fr" AS "role_name_fr",
  "organization_roles"."status" AS "role_status",
  COALESCE("role_permission_counts"."role_permission_count", 0)::integer AS "role_permission_count",
  COALESCE("direct_permission_counts"."direct_permission_count", 0)::integer AS "direct_permission_count",
  COALESCE("user_permission_summary"."effective_permission_count", 0)::integer AS "effective_permission_count"
FROM "organization_members"
INNER JOIN "users" ON "users"."id" = "organization_members"."user_id"
LEFT JOIN "user_profiles" ON "user_profiles"."user_id" = "users"."id"
INNER JOIN "organizations" ON "organizations"."id" = "organization_members"."organization_id"
INNER JOIN "organization_roles" ON "organization_roles"."id" = "organization_members"."role_id"
LEFT JOIN "role_permission_counts" ON "role_permission_counts"."role_id" = "organization_roles"."id"
LEFT JOIN "direct_permission_counts" ON "direct_permission_counts"."member_id" = "organization_members"."id"
LEFT JOIN "user_permission_summary" ON "user_permission_summary"."member_id" = "organization_members"."id";
