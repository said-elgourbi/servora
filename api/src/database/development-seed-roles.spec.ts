import { describe, expect, it } from 'vitest';
import {
  ROLE_TEMPLATES,
  TECHNICIAN_ONLY_PERMISSION_CODES,
} from './run-development-seed.js';

/**
 * The development seed's default-role grants (`BR-003`, `BR-006`).
 *
 * The seed is local developer tooling, but it still must not grant a role a capability the migrations
 * reserve for another role: a Manager who was over-granted `visits.request_follow_up` saw the
 * technician's **Request another visit** action on Job Details, which the office capability set does
 * not include (`BR-FV-001`, `BR-FV-004`).
 */
describe('development seed role templates', () => {
  it('does not grant the Manager the technician-only capabilities', () => {
    const managerCodes = ROLE_TEMPLATES.MANAGER.permissionCodes;

    expect(managerCodes).not.toContain('visits.request_follow_up');
    expect(managerCodes).not.toContain('customers.view_assigned');
    expect(managerCodes).not.toContain('visits.report_ad_hoc_work');
  });

  it('keeps the Manager office scheduling and review capabilities', () => {
    const managerCodes = ROLE_TEMPLATES.MANAGER.permissionCodes;

    expect(managerCodes).toContain('visits.create_schedule');
    expect(managerCodes).toContain('visits.update_schedule');
    expect(managerCodes).toContain('visits.assign_technicians');
    expect(managerCodes).toContain('visits.review_requests');
    expect(managerCodes).toContain('visits.review_ad_hoc_work');
    expect(managerCodes).toContain('schedule.view_org');
  });

  it('keeps field request/report capabilities on the Technician role', () => {
    expect(ROLE_TEMPLATES.TECHNICIAN.permissionCodes).toContain(
      'visits.request_follow_up',
    );
    expect(ROLE_TEMPLATES.TECHNICIAN.permissionCodes).toContain(
      'visits.report_ad_hoc_work',
    );
  });

  it('names the technician-only codes the Manager grant filters out', () => {
    expect(TECHNICIAN_ONLY_PERMISSION_CODES).toContain(
      'visits.request_follow_up',
    );
    expect(TECHNICIAN_ONLY_PERMISSION_CODES).toContain(
      'customers.view_assigned',
    );
    expect(TECHNICIAN_ONLY_PERMISSION_CODES).toContain(
      'visits.report_ad_hoc_work',
    );
  });
});
