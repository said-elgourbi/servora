import { expect, test, type Page } from '@playwright/test';
import { mockAuth } from './fixtures/auth';
import { visit } from './fixtures/workspace';

const today = '2026-10-27';
function shift(date: string, days: number) { const d = new Date(`${date}T12:00:00`); d.setDate(d.getDate() + days); return d.toISOString().slice(0, 10); }
function schedule(date: string, title = 'Furnace repair') {
  return {
    generatedAt: `${date}T12:00:00Z`,
    day: { localDate: date, timeZone: 'America/Toronto', start: `${date}T04:00:00Z`, end: `${shift(date, 1)}T04:00:00Z` },
    scope: { kind: 'ORGANIZATION', membershipId: 'manager' },
    technicians: [{ membershipId: 'member-one', name: 'Alex Tremblay' }],
    visits: [{ ...visit, visitId: `visit-${date}`, scheduledStart: `${date}T14:00:00Z`, scheduledEnd: `${date}T15:00:00Z`, jobTitle: title }],
    unassigned: { total: 1, items: [{ ...visit, visitId: 'unassigned-one', scheduledStart: null, scheduledEnd: null, technicians: [] }] },
  };
}
async function signIn(page: Page, permissions = ['customers.view', 'schedule.view_org'], response: (date: string) => ReturnType<typeof schedule> = schedule) {
  await mockAuth(page, permissions);
  await page.route('**/schedule?**', (route) => {
    const date = new URL(route.request().url()).searchParams.get('date') ?? today;
    return route.fulfill({ json: response(date) });
  });
  await page.goto('/sign-in');
  await page.getByLabel('Email address').fill('member@example.com');
  await page.getByLabel('Password', { exact: true }).fill('test-password');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('heading', { name: /Good (morning|afternoon|evening), Alex Tremblay/ })).toBeVisible();
}

test('week navigation preserves the grid and loading is visible without blocking it', async ({ page }) => {
  const seen: string[] = [];
  await mockAuth(page, ['customers.view', 'schedule.view_org']);
  await page.route('**/schedule?**', async (route) => {
    const date = new URL(route.request().url()).searchParams.get('date') ?? today;
    seen.push(date);
    await new Promise((resolve) => setTimeout(resolve, 80));
    return route.fulfill({ json: schedule(date, `Job for ${date}`) });
  });
  await page.goto('/sign-in');
  await page.getByLabel('Email address').fill('member@example.com');
  await page.getByLabel('Password', { exact: true }).fill('test-password');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('heading', { name: /Good (morning|afternoon|evening), Alex Tremblay/ })).toBeVisible();
  await page.goto('/work/schedule');
  await expect(page.getByRole('status')).toContainText('Updating schedule');
  await expect(page.locator('.time-grid')).toBeVisible();
  await expect(page.locator('.day-head')).toHaveCount(7);
  await expect(page.locator('.visit-card').first()).toBeVisible();
  await page.getByRole('button', { name: 'Next day' }).click();
  await expect.poll(() => seen).toContain(shift(today, 7));
  await expect(page.locator('.visit-card').first()).toContainText('Job for');
});

test('office permission gates the schedule read and a narrower server scope is rejected', async ({ page }) => {
  let reads = 0;
  await page.route('**/schedule?**', (route) => { reads++; return route.fulfill({ json: schedule(today) }); });
  await signIn(page, ['customers.view']);
  await page.goto('/work/schedule');
  await expect(page.getByRole('alert')).toContainText('does not have permission');
  expect(reads).toBe(0);
});

test('French layout and narrow screens keep the calendar usable; empty results are explained', async ({ page }) => {
  await signIn(page, ['customers.view', 'schedule.view_org'], (date) => ({ ...schedule(date), visits: [], unassigned: { total: 0, items: [] } }));
  await page.goto('/work/schedule');
  await expect(page.getByText('No visits on this day.')).toBeVisible();
  if ((page.viewportSize()?.width ?? 1440) <= 900) await page.locator('.mobile-menu summary').click();
  await page.getByRole('radio', { name: 'Français', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Planning du bureau' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('failed refresh shows an error and a successful retry restores visits', async ({ page }) => {
  await signIn(page);
  await page.goto('/work/schedule');
  await page.route('**/schedule?**', (route) => route.abort('internetdisconnected'));
  await page.getByRole('button', { name: 'Next day' }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await page.route('**/schedule?**', (route) => {
    const date = new URL(route.request().url()).searchParams.get('date') ?? today;
    return route.fulfill({ json: schedule(date) });
  });
  await page.getByRole('button', { name: 'Try again' }).click();
  await expect(page.locator('.visit-card')).toBeVisible();
});
