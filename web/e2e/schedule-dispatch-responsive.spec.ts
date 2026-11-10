import { expect, test, type Page } from '@playwright/test';
import { mockAuth } from './fixtures/auth';
import { crew, dispatch } from './fixtures/dispatch';
import { job } from './fixtures/workspace';

const today = '2026-10-27';
function shift(date: string, days: number) {
  const value = new Date(`${date}T12:00:00`);
  value.setDate(value.getDate() + days);
  return value.toISOString().slice(0, 10);
}
function daySchedule(date = today, count = dispatch.visits.length) {
  const visits = dispatch.visits.slice(0, count).map((item, index) => ({
    ...item,
    visitId: `visit-${index + 1}`,
    scheduledStart: `${date}T${String(12 + Math.floor(index / 3)).padStart(2, '0')}:${index % 3 ? '30' : '00'}:00Z`,
    scheduledEnd: `${date}T${String(13 + Math.floor(index / 3)).padStart(2, '0')}:${index % 3 ? '30' : '00'}:00Z`,
  }));
  return {
    ...dispatch,
    generatedAt: `${date}T12:00:00Z`,
    day: { localDate: date, timeZone: 'America/Toronto', start: `${date}T04:00:00Z`, end: `${shift(date, 1)}T04:00:00Z` },
    visits,
    unassigned: { total: 1, items: [{ ...visits[0]!, visitId: 'unassigned', technicians: [], scheduledStart: null, scheduledEnd: null }] },
  };
}
async function open(page: Page, response: (date: string) => ReturnType<typeof daySchedule> = daySchedule) {
  await mockAuth(page, ['customers.view', 'schedule.view_org', 'visits.create_schedule', 'JOB_UPDATE', 'VISIT_RECORD_OUTCOME']);
  await page.route('**/schedule?**', (route) => {
    const date = new URL(route.request().url()).searchParams.get('date') ?? today;
    return route.fulfill({ json: response(date) });
  });
  await page.goto('/sign-in');
  await page.getByLabel('Email address').fill('member@example.com');
  await page.getByLabel('Password', { exact: true }).fill('test-password');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('heading', { name: /Good (morning|afternoon|evening), Alex Tremblay/ })).toBeVisible();
  await page.goto('/work/schedule');
  await expect(page.getByRole('heading', { name: 'Office schedule' })).toBeVisible();
  await expect(page.locator('.visit-card').first()).toBeVisible();
}

test('week is the default, shared visits appear once, overlaps remain side by side, and Day grouping duplicates shared work by crew', async ({ page }) => {
  await open(page);
  await expect(page.getByRole('button', { name: 'Week', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('.day-head')).toHaveCount(7);
  const shared = page.locator('.visit-card[aria-label*="Furnace repair"]');
  await expect(shared).toHaveCount(1);
  const first = await shared.boundingBox();
  expect(first?.width).toBeGreaterThan(40);
  await page.getByRole('button', { name: 'Day', exact: true }).click();
  await page.getByLabel('Group by technician').check();
  await expect(page.locator('.day-head')).toHaveCount(4);
  await expect(page.locator('.visit-card[aria-label*="Furnace repair"]')).toHaveCount(2);
});

test('month summaries open Day and crowded dates offer an explicit Day view control', async ({ page }) => {
  await open(page, (date) => {
    const response = daySchedule(date, date === today ? 6 : 0);
    if (date === today) response.visits = response.visits.map((visit) => ({ ...visit, scheduledStart: `${date}T12:00:00Z`, scheduledEnd: `${date}T13:30:00Z` }));
  });
  await page.getByRole('button', { name: 'Week', exact: true }).click();
  await expect(page.getByRole('button', { name: /Crowded day/ })).toBeVisible();
  await page.getByRole('button', { name: /Crowded day/ }).click();
  await expect(page.getByRole('button', { name: 'Day', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await page.getByRole('button', { name: 'Month', exact: true }).click();
  await expect(page.locator('.weekday-head')).toHaveCount(7);
  await expect(page.locator('.month-visit')).toHaveCount(3);
  const more = page.locator('.more');
  await expect(more).toContainText('+3');
  await more.click();
  await expect(page.getByRole('button', { name: 'Day', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('.visit-card')).toHaveCount(6);
});

test('empty slot opens existing-job search and carries the selected date and time into the scheduling form', async ({ page }) => {
  await open(page);
  await page.locator('.day-column').nth(4).click({ position: { x: 24, y: 8 * 66 + 10 } });
  await expect(page.getByRole('heading', { name: 'Schedule a visit' }).last()).toBeVisible();
  await expect(page.getByText('Selected time')).toContainText('2:00:00');
  await page.route('**/jobs/search?**', (route) => route.fulfill({ json: [{ id: 'job-one', jobNumber: 1042, title: 'Furnace repair', status: 'ACTIVE', customerName: 'Cedar Properties', propertyName: 'Cedar House' }] }));
  await page.getByLabel('Search existing jobs by number, title, or customer').fill('1042');
  await expect(page.getByRole('button', { name: /#1042 · Furnace repair/ })).toContainText('Cedar Properties');
  await expect(page.getByRole('button', { name: /#1042 · Furnace repair/ })).toContainText('Cedar House');
  await page.route('**/jobs/job-one', (route) => route.fulfill({ json: { ...job, canScheduleVisit: true, selectedVisit: null, visits: [] } }));
  await page.route('**/technicians', (route) => route.fulfill({ json: crew }));
  await page.getByRole('button', { name: /#1042 · Furnace repair/ }).click();
  await expect(page.getByRole('heading', { name: 'Schedule a visit' })).toHaveCount(2);
  await expect(page.getByLabel('Start date')).toHaveValue(/Nov 6/);
  await expect(page.getByLabel('Start time')).toHaveValue(/2:00/);
});

test('visit panel opens the corresponding job details page', async ({ page }) => {
  await open(page);
  await page.route('**/jobs/job-one/activity', (route) => route.fulfill({ json: { events: [] } }));
  await page.locator('.visit-card').first().click();
  await page.getByRole('link', { name: 'Visit details' }).click();
  await expect(page).toHaveURL(/\/work\/jobs\/job-one\?visit=visit-1&view=office/);
  await expect(page.getByRole('heading', { name: 'Furnace repair' })).toBeVisible();
});
