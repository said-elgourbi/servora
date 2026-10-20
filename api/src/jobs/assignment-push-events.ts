import { and, eq, inArray, ne } from 'drizzle-orm';
import type { DatabaseService } from '../database/database.service.js';
import {
  organizationMembers,
  pushAssignmentEvents,
} from '../database/schema.js';

export type AssignmentTransaction = Parameters<
  Parameters<DatabaseService['db']['transaction']>[0]
>[0];

/** Called only with newly added crew, within the assignment's own transaction. No provider I/O. */
export async function enqueueAssignmentPushEvents(
  tx: AssignmentTransaction,
  input: {
    organizationId: string;
    jobId: string;
    visitId: string;
    assignmentVersion: number;
    actorMembershipId: string;
    addedMembershipIds: readonly string[];
  },
): Promise<void> {
  if (input.addedMembershipIds.length === 0) return;
  const [actor] = await tx
    .select({ userId: organizationMembers.userId })
    .from(organizationMembers)
    .where(
      and(
        eq(organizationMembers.organizationId, input.organizationId),
        eq(organizationMembers.id, input.actorMembershipId),
      ),
    );
  if (!actor) throw new Error('Assignment actor is outside the organization.');
  const recipients = await tx
    .select({ id: organizationMembers.id })
    .from(organizationMembers)
    .where(
      and(
        eq(organizationMembers.organizationId, input.organizationId),
        inArray(organizationMembers.id, [...new Set(input.addedMembershipIds)]),
        ne(organizationMembers.userId, actor.userId),
      ),
    );
  if (recipients.length === 0) return;
  // Exact business-key replays preserve the original UUID and expiry. Other integrity errors fail
  // the business transaction rather than acknowledging an assignment without its durable intent.
  await tx
    .insert(pushAssignmentEvents)
    .values(
      recipients.map(({ id }) => ({
        organizationId: input.organizationId,
        jobId: input.jobId,
        visitId: input.visitId,
        recipientMembershipId: id,
        actorUserId: actor.userId,
        assignmentVersion: input.assignmentVersion,
      })),
    )
    .onConflictDoNothing({
      target: [
        pushAssignmentEvents.organizationId,
        pushAssignmentEvents.visitId,
        pushAssignmentEvents.assignmentVersion,
        pushAssignmentEvents.recipientMembershipId,
        pushAssignmentEvents.eventType,
      ],
    });
}
