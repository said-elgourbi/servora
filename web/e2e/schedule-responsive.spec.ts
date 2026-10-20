import { test, expect, type Page } from '@playwright/test';
import { mockAuth } from './fixtures/auth';
import { visit } from './fixtures/workspace';
const technician = { membershipId: '11111111-1111-4111-8111-111111111111', name: 'Alex Tremblay' };
function schedule(date = '2026-10-20') {
  return {
    generatedAt: '2026-10-20T12:00:00Z',
    day: {
      localDate: date,
      timeZone: 'America/Toronto',
      start: `${date}T04:00:00Z`,
      end: '2026-10-21T04:00:00Z',
    },
    scope: { kind: 'ORGANIZATION', membershipId: technician.membershipId },
    technicians: [technician],
    visits: [visit],
    unassigned: {
      total: 25,
      items: [
        {
          ...visit,
          visitId: 'unassigned-one',
          jobTitle: 'Boiler service',
          technicians: [],
          scheduledStart: null,
          scheduledEnd: null,
        },
      ],
    },
  };
}
async function signIn(page: Page, permissions = ['customers.view', 'schedule.view_org']) {
  await mockAuth(page, permissions);
  await page.goto('/sign-in');
  await page.getByLabel('Email address').fill('member@example.com');
  await page.getByLabel('Password', { exact: true }).fill('test-password');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Your working day' })).toBeVisible();
}
test('office date, crew filter, unassigned totals and precise visit navigation', async ({
  page,
}) => {
  const queries: URL[] = [];
  await page.route('**/schedule?**', (route) => {
    const url = new URL(route.request().url());
    queries.push(url);
    expect(route.request().headers()['authorization']).toBe('Bearer test-access');
    expect(route.request().headers()['ngsw-bypass']).toBe('true');
    return route.fulfill({ json: schedule(url.searchParams.get('date') ?? undefined) });
  });
  await signIn(page);
  await page.goto('/work/schedule');
  await expect(page.getByRole('heading', { name: 'Office schedule' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Furnace repair #1042' })).toBeVisible();
  await page.getByLabel('Date', { exact: true }).fill('2026-12-31');
  await page.getByRole('button', { name: 'Next day', exact: true }).click();
  await expect(page.getByLabel('Date', { exact: true })).toHaveValue('2027-01-01');
  await expect.poll(() => queries.at(-1)?.searchParams.get('date')).toBe('2027-01-01');
  await page.getByRole('combobox', { name: 'Technicians' }).click();
  await page.getByRole('option', { name: 'Alex Tremblay' }).click();
  await page.keyboard.press('Escape');
  await expect
    .poll(() => queries.at(-1)?.searchParams.getAll('membershipId'))
    .toEqual([technician.membershipId]);
  expect(queries.at(-1)?.searchParams.get('timeZone')).toBeTruthy();
  await page.getByRole('button', { name: 'Unassigned (25)' }).click();
  await expect(page.getByText('Not scheduled', { exact: true })).toBeVisible();
  await expect(
    page.getByText('Showing the first visits waiting for a crew.', { exact: false }),
  ).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Boiler service #1042' })).toBeVisible();
  await page.getByRole('button', { name: 'Day schedule', exact: true }).click();
  await page
    .getByRole('link')
    .filter({ has: page.getByRole('heading', { name: 'Furnace repair #1042' }) })
    .click();
  await expect(page).toHaveURL(/work\/jobs\/job-one\?visit=visit-one&view=office/);
});
test('failure clears protected rows, retry recovers, French and phone layout work', async ({
  page,
}) => {
  await page.route('**/schedule?**', (route) => route.fulfill({ json: schedule() }));
  await signIn(page);
  await page.goto('/work/schedule');
  await expect(page.getByRole('heading', { name: 'Furnace repair #1042' })).toBeVisible();
  await page.route('**/schedule?**', (route) =>
    route.fulfill({ status: 403, json: { code: 'FORBIDDEN' } }),
  );
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('does not have permission');
  await expect(page.getByText('Furnace repair')).toHaveCount(0);
  await page.route('**/schedule?**', (route) =>
    route.fulfill({ json: { ...schedule(), visits: [], unassigned: { total: 0, items: [] } } }),
  );
  await page.getByRole('button', { name: 'Try again', exact: true }).click();
  await expect(page.getByText('No visits on this day.')).toBeVisible();
  if ((page.viewportSize()?.width ?? 1440) <= 900)
    await page.locator('.mobile-menu summary').click();
  await page.getByRole('radio', { name: 'Français', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Planning du bureau' })).toBeVisible();
  await expect(page.getByText('Aucune visite ce jour-là.')).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
test('direct links without dispatch permission issue no schedule request', async ({ page }) => {
  let requests = 0;
  await page.route('**/schedule?**', (route) => {
    requests++;
    return route.fulfill({ json: schedule() });
  });
  await signIn(page, ['customers.view']);
  await page.goto('/work/schedule');
  await expect(page.getByRole('alert')).toContainText('does not have permission');
  expect(requests).toBe(0);
  await expect(page.locator('nav').getByRole('link', { name: 'Office schedule' })).toHaveCount(0);
});

test('a slow previous day cannot overwrite the newly selected day', async ({ page }) => {
  await page.route('**/schedule?**', async (route) => {
    const date = new URL(route.request().url()).searchParams.get('date') ?? '2026-10-20';
    if (date === '2026-12-30') await new Promise((resolve) => setTimeout(resolve, 350));
    const response = schedule(date);
    response.visits = [{ ...visit, jobTitle: date }];
    await route.fulfill({ json: response });
  });
  await signIn(page);
  await page.goto('/work/schedule');
  const date = page.getByLabel('Date', { exact: true });
  const first = page.waitForRequest(
    (request) => request.url().includes('/schedule?') && request.url().includes('2026-12-30'),
  );
  await date.fill('2026-12-30');
  await first;
  await date.fill('2026-12-31');
  await expect(page.getByRole('heading', { name: '2026-12-31 #1042' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '2026-12-30 #1042' })).toHaveCount(0);
});

test('a narrower server scope never appears as an office schedule', async ({ page }) => {
  await page.route('**/schedule?**', (route) =>
    route.fulfill({
      json: {
        ...schedule(),
        scope: { kind: 'SELF', membershipId: technician.membershipId },
        technicians: [],
        unassigned: null,
      },
    }),
  );
  await signIn(page);
  await page.goto('/work/schedule');
  await expect(page.getByRole('alert')).toContainText('does not have permission');
  await expect(page.getByRole('heading', { name: 'Furnace repair #1042' })).toHaveCount(0);
});

test('network failure and sign-out discard loaded schedule rows', async ({ page }) => {
  await page.route('**/schedule?**', (route) => route.fulfill({ json: schedule() }));
  await signIn(page);
  await page.goto('/work/schedule');
  await expect(page.getByRole('heading', { name: 'Furnace repair #1042' })).toBeVisible();
  await page.route('**/schedule?**', (route) => route.abort('internetdisconnected'));
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Furnace repair #1042' })).toHaveCount(0);
  await page.route('**/schedule?**', (route) => route.fulfill({ json: schedule() }));
  await page.getByRole('button', { name: 'Try again', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Furnace repair #1042' })).toBeVisible();
  if ((page.viewportSize()?.width ?? 1440) <= 900)
    await page.locator('.mobile-menu summary').click();
  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Furnace repair #1042' })).toHaveCount(0);
});
