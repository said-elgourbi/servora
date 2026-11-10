import { test, expect } from '@playwright/test';
import type { JobActivityEvent, OfficeJobDetails } from '@servora/shared-types';
import { mockJobs, photoEvent, jobPermissions } from './fixtures/jobs';
import { job } from './fixtures/workspace';

const assessment = {
  id: 'visit-assessment', sequence: 1, status: 'SCHEDULED', purposeCode: 'ASSESSMENT',
  outcomeCode: null, scheduledStart: '2026-10-29T17:00:00Z', scheduledEnd: '2026-10-29T19:00:00Z',
  technicians: [{ membershipId: 'member-one', name: 'John Tremblay', roleCode: 'LEAD' }],
};
const firstVisit = {
  id: 'visit-assessment-1', sequence: 1, status: 'COMPLETED', purposeCode: 'ASSESSMENT',
  outcomeCode: null, scheduledStart: '2026-10-24T17:00:00Z', scheduledEnd: '2026-10-24T19:00:00Z',
  technicians: [{ membershipId: 'member-one', name: 'John Tremblay', roleCode: 'LEAD' }],
};
const nextAssessment = { ...assessment, id: 'visit-assessment-3', sequence: 3, scheduledStart: '2026-10-30T17:00:00Z', scheduledEnd: '2026-10-30T19:00:00Z' };
const workVisit = {
  id: 'visit-work-2', sequence: 2, status: 'COMPLETED', purposeCode: 'WORK',
  outcomeCode: null, scheduledStart: '2026-10-27T14:00:00Z', scheduledEnd: '2026-10-27T17:00:00Z',
  technicians: [{ membershipId: 'member-one', name: 'John Tremblay', roleCode: 'LEAD' }],
};
function event(id: string, kind: string, recordedAt: string, visitSequence: number | null, body: string): JobActivityEvent {
  return { ...photoEvent, id, kind, recordedAt, visitSequence, body, photoId: null, photoPhase: null };
}
const scheduledJob: Partial<OfficeJobDetails> = {
  ...job, jobNumber: 1003, title: 'Lobby heating not working — Birchfield', customerName: 'Maple Ridge Property Management',
  status: 'NEW', description: 'Lobby heating unresponsive. Assessment needed.', canScheduleVisit: true,
  visits: [assessment], selectedVisit: null,
};
const historicEvents = [
  event('job-note', 'JOB_NOTE_ADDED', '2026-10-27T16:35:00Z', null, 'Customer reports intermittent heating in the lobby. Please assess before quoting repairs.'),
  event('visit-note-2', 'VISIT_NOTE_ADDED', '2026-10-27T17:00:00Z', 2, 'Thermostat fitted. Control module needs replacement before testing can finish.'),
  event('visit-note-1', 'VISIT_NOTE_ADDED', '2026-10-24T19:00:00Z', 1, 'Existing thermostat is obsolete. Recommend replacement.'),
  event('visit-scheduled', 'VISIT_SCHEDULED', '2026-10-23T15:00:00Z', 3, 'Dispatch scheduled the return visit.'),
];

test('redesigned job page covers empty, scheduled, and active timeline states', async ({ page }, testInfo) => {
  const scenarios: { name: string; details: Partial<OfficeJobDetails>; events: JobActivityEvent[]; expected: string[] }[] = [
    { name: 'no-visits', details: { ...scheduledJob, visits: [], selectedVisit: null }, events: [], expected: ['No visits scheduled', 'Schedule a visit'] },
    { name: 'scheduled-assessment', details: scheduledJob, events: [event('pre-visit', 'VISIT_NOTE_ADDED', '2026-10-27T14:15:00Z', 1, 'Call the site contact before arriving.')], expected: ['Visit 1', 'Scheduled'] },
    { name: 'active-history', details: { ...scheduledJob, status: 'ACTIVE', canScheduleVisit: false, visits: [firstVisit, workVisit, nextAssessment], selectedVisit: null }, events: historicEvents, expected: ['Visit 3', 'Timeline'] },
  ];
  for (const scenario of scenarios) {
    await mockJobs(page, [...jobPermissions, 'customers.view', 'evidence.photo.add', 'evidence.audio.add'], scenario.details);
    await page.route('**/jobs/job-one/activity*', (route) => route.fulfill({ json: { jobId: 'job-one', events: scenario.events } }));
    await page.goto('/sign-in');
    await page.getByLabel('Email address').fill('office@example.com');
    await page.getByLabel('Password', { exact: true }).fill('test-password');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Office schedule' })).toBeVisible();
    await page.goto('/work/jobs/job-one?view=office');
    await expect(page.getByRole('heading', { name: 'Lobby heating not working — Birchfield' })).toBeVisible();
    for (const text of scenario.expected) await expect(page.getByText(text, { exact: false }).first()).toBeVisible();
    if (scenario.name === 'scheduled-assessment') {
      await expect(page.locator('#update-destination')).toHaveValue('visit-assessment');
      await expect(page.locator('.timeline-visit-card').first()).toContainText('Assessment');
      await page.getByRole('button', { name: 'Add update' }).first().click();
      await expect(page.getByRole('dialog')).toContainText('Visit 1');
      await expect(page.getByRole('dialog').getByLabel('Write an update…')).toBeVisible();
      await expect(page.getByRole('dialog').locator('select')).toHaveCount(0);
      await page.getByRole('button', { name: 'Cancel' }).click();
      await page.getByRole('button', { name: 'Change visit status' }).click();
      await page.getByRole('menuitem', { name: 'En route' }).click();
      await expect(page.locator('.visit-card-header')).toContainText('En route');
    }
    if (scenario.name === 'active-history') {
      await expect(page.locator('.timeline-visit-card')).toHaveCount(3);
      await expect(page.locator('.timeline-visit-card').filter({ hasText: 'Visit 2 · Work' })).toBeVisible();
      await page.screenshot({ path: `/tmp/servora-job-visits-desktop.png`, fullPage: true });
      await page.getByRole('tab', { name: 'General job updates' }).click();
      await expect(page.locator('.general-group')).toContainText('Customer reports intermittent heating');
    }
    await expect(page.locator('.summary-card')).toBeVisible();
    await expect(page.locator('.summary-card').evaluate((element) => element.compareDocumentPosition(document.querySelector('.location-card')!) & Node.DOCUMENT_POSITION_FOLLOWING)).resolves.toBeTruthy();
    await expect(page.locator('body')).not.toHaveCSS('overflow-x', 'scroll');
    const viewportWidth = await page.evaluate(() => document.documentElement.scrollWidth);
    expect(viewportWidth).toBeLessThanOrEqual(1440);
    await page.screenshot({ path: `/tmp/servora-job-details-${scenario.name}.png`, fullPage: true });
    if (scenario.name === 'active-history') {
      await page.getByRole('tab', { name: /Visits/ }).click();
      await page.getByRole('tab', { name: 'General job updates' }).click();
      await page.getByRole('button', { name: 'Administrative history' }).click();
      await expect(page.getByText('Dispatch scheduled the return visit.').first()).toBeVisible();
    }
    await page.goto('/sign-in');
  }
});
