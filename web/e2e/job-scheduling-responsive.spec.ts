import { test, expect, type Page } from '@playwright/test';
import { jobPermissions, mockJobs } from './fixtures/jobs';
import { propertySignIn } from './fixtures/properties';
import { job } from './fixtures/workspace';

test.use({ timezoneId: 'America/Toronto' });
test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-10-20T12:00:00Z'));
});

const permissions = [...jobPermissions, 'visits.create_schedule', 'TECHNICIAN_VIEW'];
const finished = { ...job.visits[0], status: 'COMPLETED', outcomeCode: 'RESOLVED' };
const reopened = { canScheduleVisit: true, visits: [finished] };

async function fillVisit(page: Page) {
  const date = page.getByRole('textbox', { name: 'Visit date', exact: true });
  await expect(date).toHaveAttribute('readonly', /true|^$/);
  await date.click();
  await page.getByRole('button', { name: 'Next month' }).click();
  await page.getByRole('button', { name: /November 5, 2026/ }).click();
  const twentyFourHour = page.getByRole('radio', { name: '24h', exact: true });
  if (!(await twentyFourHour.isChecked())) await twentyFourHour.click();
  const startTime = page.getByRole('combobox', { name: 'Start time', exact: true });
  await startTime.fill('1210kkk');
  await expect(startTime).toHaveValue('12:10');
  await startTime.fill('09:00');
  await page.getByRole('combobox', { name: 'End time', exact: true }).fill('10:00');
  await page.getByRole('combobox', { name: 'Technicians', exact: true }).click();
  await page.getByRole('option', { name: 'Alex Tremblay', exact: true }).click();
  await page.getByRole('option', { name: 'Jo Tremblay', exact: true }).click();
  await page.keyboard.press('Escape');
  await page.getByRole('combobox', { name: 'Lead technician', exact: true }).click();
  await page.getByRole('option', { name: 'Jo Tremblay', exact: true }).click();
  if (page.viewportSize()!.width > 600) {
    const startBox = await page.getByRole('combobox', { name: 'Start time', exact: true }).boundingBox();
    const endBox = await page.getByRole('combobox', { name: 'End time', exact: true }).boundingBox();
    expect(startBox && endBox && Math.abs(startBox.y - endBox.y) < 1).toBe(true);
  }
}

test('reopens a resolved job and schedules a new visit without changing its history', async ({
  page,
}, testInfo) => {
  const { writes } = await mockJobs(page, permissions, {
    ...reopened,
    canScheduleVisit: false,
    status: 'COMPLETED',
    allowedStatusTransitions: ['ACTIVE'],
  });
  await propertySignIn(page, '/work/jobs/job-one?view=office');
  await expect(page.getByRole('button', { name: 'Schedule a visit', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Change job status', exact: false }).click();
  await page.getByRole('menuitem', { name: 'Active', exact: true }).click();
  await page.getByRole('button', { name: 'Confirm', exact: true }).click();
  await page.getByRole('button', { name: 'Schedule a visit', exact: true }).click();
  const submit = page
    .getByRole('dialog')
    .getByRole('button', { name: 'Schedule a visit', exact: true });
  await expect(submit).toBeDisabled();
  await fillVisit(page);
  await page.screenshot({ path: testInfo.outputPath('schedule-pickers.png') });
  await page.getByRole('radio', { name: 'AM/PM', exact: true }).click();
  await expect(page.getByRole('combobox', { name: 'Start time', exact: true })).toHaveValue('09:00 AM');
  await expect(page.getByRole('combobox', { name: 'End time', exact: true })).toHaveValue('10:00 AM');
  expect(await page.locator('#visit-time-suggestions option').count()).toBe(96);
  await expect(page.locator('#visit-time-suggestions option[value="09:15 AM"]')).toHaveCount(1);
  expect(await page.evaluate(() => localStorage.getItem('servora.time-format'))).toBe('12H');
  await page.screenshot({ path: testInfo.outputPath(`schedule-12-hour-${testInfo.project.name}.png`) });
  await page.getByRole('radio', { name: '24h', exact: true }).click();
  await expect(page.getByRole('combobox', { name: 'Start time', exact: true })).toHaveValue('09:00');
  await page.getByRole('combobox', { name: 'End time', exact: true }).fill('08:00');
  await expect(submit).toBeDisabled();
  await expect(page.getByRole('alert')).toContainText('end time must be after');
  await page.getByRole('radio', { name: 'AM/PM', exact: true }).click();
  await page.getByRole('combobox', { name: 'End time', exact: true }).fill('10:00 AM');
  await submit.click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(writes.at(-1)).toEqual({
    path: '/jobs/job-one/visits',
    body: {
      purposeCode: 'WORK',
      scheduledStart: '2026-11-05T14:00:00.000Z',
      scheduledEnd: '2026-11-05T15:00:00.000Z',
      technicians: [
        { membershipId: 'member-one', roleCode: 'TECHNICIAN' },
        { membershipId: 'member-two', roleCode: 'LEAD' },
      ],
      confirmConflicts: false,
    },
  });
  await expect(page.getByRole('button', { name: 'Schedule a visit', exact: true })).toHaveCount(0);
  await expect(page.getByText('Visit 2 · Work', { exact: true })).toBeVisible();
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth > window.innerWidth,
  );
  expect(overflow).toBe(false);
});

test('shows scheduling only when the API allows it', async ({ page }) => {
  await mockJobs(page, permissions, { ...reopened, canScheduleVisit: false });
  await propertySignIn(page, '/work/jobs/job-one?view=office');
  await expect(page.getByRole('heading', { name: 'Furnace repair', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Schedule a visit', exact: true })).toHaveCount(0);
});

test('blocks scheduling while a past-dated visit is still scheduled', async ({ page }, testInfo) => {
  await mockJobs(page, permissions, {
    canScheduleVisit: true,
    visits: [{
      ...job.visits[0],
      status: 'SCHEDULED',
      scheduledStart: '2026-10-18T13:00:00Z',
      scheduledEnd: '2026-10-18T14:00:00Z',
    }],
  });
  await propertySignIn(page, '/work/jobs/job-one?view=office');
  await expect(page.getByRole('heading', { name: 'Visit 1 · Work' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Schedule a visit', exact: true })).toHaveCount(0);
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.screenshot({ path: testInfo.outputPath(`job-scheduled-visit-blocks-${testInfo.project.name}.png`), fullPage: true });
});

test('requires explicit conflict confirmation and refreshes the job on success', async ({
  page,
}) => {
  const { writes } = await mockJobs(page, permissions, reopened);
  const attempts: Record<string, unknown>[] = [];
  await page.route('**/jobs/job-one/visits', (route) => {
    const body = route.request().postDataJSON();
    attempts.push(body);
    if (body.confirmConflicts) return route.fallback();
    return route.fulfill({
      status: 409,
      json: {
        code: 'SCHEDULE_CONFLICT',
        details: {
          conflicts: [
            {
              visitId: 'overlap',
              jobId: 'other-job',
              jobNumber: 1200,
              technicianMembershipId: 'member-one',
              technicianName: 'Alex Tremblay',
              scheduledStart: '2026-11-05T14:00:00Z',
              scheduledEnd: '2026-11-05T15:00:00Z',
            },
          ],
        },
      },
    });
  });
  await propertySignIn(page, '/work/jobs/job-one?view=office');
  await page.getByRole('button', { name: 'Schedule a visit', exact: true }).click();
  await fillVisit(page);
  await page
    .getByRole('dialog')
    .getByRole('button', { name: 'Schedule a visit', exact: true })
    .click();
  await expect(page.getByRole('alert')).toContainText('1200');
  expect(attempts).toHaveLength(1);
  await page.getByRole('button', { name: 'Schedule anyway', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(attempts).toHaveLength(2);
  expect(attempts[1]).toEqual({ ...attempts[0], confirmConflicts: true });
  expect(writes.at(-1)?.body['confirmConflicts']).toBe(true);
});

test('retains the form after a lost response and prevents duplicate submissions', async ({
  page,
}) => {
  await mockJobs(page, permissions, reopened);
  let attempts = 0;
  await page.route('**/jobs/job-one/visits', (route) => {
    attempts++;
    return route.abort('failed');
  });
  await propertySignIn(page, '/work/jobs/job-one?view=office');
  await page.getByRole('button', { name: 'Schedule a visit', exact: true }).click();
  await fillVisit(page);
  const submit = page
    .getByRole('dialog')
    .getByRole('button', { name: 'Schedule a visit', exact: true });
  await submit.click();
  await expect(page.getByRole('alert')).toContainText('result could not be confirmed');
  await expect(submit).toBeDisabled();
  await expect(page.getByRole('combobox', { name: 'Start time', exact: true })).toHaveValue('09:00');
  expect(attempts).toBe(1);
});

test('opens the scheduling form in French', async ({ page }) => {
  await mockJobs(page, permissions, reopened);
  await propertySignIn(page, '/work/jobs/job-one?view=office');
  await page.evaluate(() => localStorage.setItem('servora.language', 'fr'));
  await page.reload();
  await page.getByRole('button', { name: 'Planifier une visite', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'Planifier une visite', exact: true }),
  ).toBeVisible();
  await expect(page.getByRole('textbox', { name: 'Date de la visite', exact: true })).toBeVisible();
  await expect(
    page.getByRole('combobox', { name: 'Technicien responsable', exact: true }),
  ).toBeVisible();
});
