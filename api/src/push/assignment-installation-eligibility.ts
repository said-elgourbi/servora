import { and, eq, sql } from 'drizzle-orm';
import { pushAssignmentEvents, pushInstallations } from '../database/schema.js';
import {
  CUSTOMER_PERMISSIONS,
  VISIT_PERMISSIONS,
} from '../auth/permissions.js';
import type { PushRegistrationConfig } from './push-registration-config.js';

type Event = typeof pushAssignmentEvents.$inferSelect;

/** Shared expansion/send eligibility. Event scope is authoritative across installation rebinding. */
export function assignmentInstallationEligibility(
  event: Event,
  config: PushRegistrationConfig,
) {
  return and(
    eq(pushInstallations.organizationId, event.organizationId),
    eq(pushInstallations.membershipId, event.recipientMembershipId),
    eq(pushInstallations.state, 'ACTIVE'),
    eq(pushInstallations.alertsEnabled, true),
    eq(pushInstallations.notificationPermission, 'GRANTED'),
    eq(pushInstallations.channelEnabled, true),
    eq(pushInstallations.targetKind, 'FCM_TOKEN'),
    sql`${pushInstallations.targetValue} is not null`,
    sql`exists (select 1 from push_assignment_events e
              where e.organization_id = ${event.organizationId} and e.id = ${event.id}
              and e.expires_at > now() and ${pushInstallations.eligibleSince} <= e.occurred_at)`,
    sql`${pushInstallations.registeredAt} > now() - interval '30 days'`,
    eq(pushInstallations.projectId, config.projectId ?? ''),
    eq(pushInstallations.applicationId, config.applicationId ?? ''),
    sql`exists (
            select 1 from organization_members m
            join organizations o on o.id = m.organization_id and o.status = 'ACTIVE'
            join users u on u.id = m.user_id and u.status = 'ACTIVE'
            join organization_roles r on r.id = m.role_id and r.organization_id = m.organization_id and r.status = 'ACTIVE'
            join auth_sessions s on s.id = ${pushInstallations.sessionId} and s.user_id = m.user_id
            join visits v on v.organization_id = m.organization_id and v.id = ${event.visitId} and v.job_id = ${event.jobId}
            join jobs j on j.organization_id = m.organization_id and j.id = v.job_id
            join customers c on c.organization_id = m.organization_id and c.id = j.customer_id and c.deleted_at is null
            join visit_technicians vt on vt.organization_id = m.organization_id and vt.visit_id = v.id and vt.technician_membership_id = m.id
            where m.organization_id = ${event.organizationId} and m.id = ${event.recipientMembershipId}
              and m.status = 'ACTIVE' and m.user_id = ${pushInstallations.userId} and m.user_id <> ${event.actorUserId}
              and s.platform = 'ANDROID' and s.revoked_at is null and s.expires_at > now()
              and v.status not in ('COMPLETED', 'CANCELED') and j.status not in ('COMPLETED', 'CANCELED')
              and exists (
                select 1 from permissions p where p.code in (${CUSTOMER_PERMISSIONS.VIEW}, ${VISIT_PERMISSIONS.VIEW_ASSIGNED})
                and (exists (select 1 from role_permissions rp where rp.organization_id = m.organization_id and rp.role_id = m.role_id and rp.permission_id = p.id)
                  or exists (select 1 from organization_member_permissions mp where mp.organization_id = m.organization_id and mp.member_id = m.id and mp.permission_id = p.id))
              )
          )`,
  );
}
