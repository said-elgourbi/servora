import { and, asc, eq } from 'drizzle-orm';
import type { DatabaseService } from '../database/database.service.js';
import {
  organizationMembers,
  userProfiles,
} from '../database/schema.js';
import { memberName } from '../members/member-name.js';
import type { OrganizationScope } from '../tenancy/tenant-scope.js';

/**
 * The signed-in member's display name for a home screen's greeting (`BR-010`, `BR-020`).
 *
 * It is read inside the caller's own ACTIVE membership, so the name belongs to the member the
 * request is authorized for rather than to a member of another organization (`BR-001`). It is
 * defined once because both home reads greet the same member and must resolve the same name the
 * same way (`BR-041`).
 *
 * Returns `null` when the member has no profile and no composed name, so a screen greets without a
 * name rather than inventing one.
 */
export async function readViewerDisplayName(
  db: DatabaseService['db'],
  scope: OrganizationScope,
  userId: string,
): Promise<string | null> {
  const [row] = await db
    .select({
      displayName: userProfiles.displayName,
      firstName: userProfiles.firstName,
      lastName: userProfiles.lastName,
    })
    .from(organizationMembers)
    .leftJoin(userProfiles, eq(userProfiles.userId, organizationMembers.userId))
    .where(
      and(
        eq(organizationMembers.organizationId, scope.organizationId),
        eq(organizationMembers.userId, userId),
        eq(organizationMembers.status, 'ACTIVE'),
      ),
    )
    .orderBy(asc(organizationMembers.joinedAt))
    .limit(1);

  if (row === undefined) {
    return null;
  }
  return memberName(row);
}
