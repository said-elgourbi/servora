import { test, expect, type Page } from '@playwright/test';
import { mockAuth } from './fixtures/auth';
import { fieldHome, officeHome, visit, job } from './fixtures/workspace';
async function signIn(page: Page) {
  await page.goto('/sign-in');
  await page.getByLabel('Email address').fill('member@example.com');
  await page.getByLabel('Password', { exact: true }).fill('test-password');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.locator('h1')).toBeVisible();
}
test('office day opens its precise visit, contact actions and returns Home', async ({ page }) => {
  await mockAuth(page);
  await signIn(page);
  await expect(page.getByText(/Sunday, October 18, 2026/)).toBeVisible();
  await page.locator('.attention-table tr').filter({ hasText: 'Furnace repair' }).getByRole('link').click();
  await expect(page).toHaveURL(/\/work\/jobs\/job-one\?visit=visit-one&view=office/);
  await expect(page.getByRole('heading', { name: 'Furnace repair' })).toBeVisible();
  await expect(
    page.getByRole('link', { name: 'Call · +15145551234', exact: true }),
  ).toHaveAttribute('href', 'tel:+15145551234');
  await expect(page.getByRole('article', { name: 'Visit 1', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Directions' })).toHaveAttribute(
    'href',
    /destination=987%20Cedar%20Lane/,
  );
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Furnace repair' })).toBeVisible();
  await page.getByRole('link', { name: 'Back to Home' }).click();
  await expect(page.getByRole('heading', { name: 'Office overview' })).toBeVisible();
});
test('office overview pages attention and opens the exact completed visit', async ({ page }) => {
  await mockAuth(page, ['customers.view', 'schedule.view_org']);
  const codes = ['VISIT_OVERDUE', 'JOB_NEEDS_SCHEDULING', 'JOB_QUOTE_REQUIRED'];
  const rows = Array.from({ length: 23 }, (_, index) => ({
    kind: index >= 20 ? 'JOB_QUOTE_REQUIRED' : codes[index % 2],
    jobId: `job-${index}`,
    jobNumber: 2000 + index,
    jobTitle: index === 0 ? 'Very long boiler maintenance title for a mixed-use property' : `Repair ${index}`,
    jobStatus: 'ACTIVE',
    customerId: 'customer-one',
    customerName: 'Cedar Properties',
    visitId: index % 3 === 0 ? `visit-${index}` : null,
    reasonCode: null,
    scheduledStart: index % 3 === 0 ? '2026-10-17T14:00:00Z' : null,
    scheduledEnd: null,
  }));
  const completedVisit = {
    ...visit,
    visitId: 'visit-completed',
    scheduledStart: '2026-10-18T13:00:00Z',
    visitStatus: 'COMPLETED',
    jobId: 'job-completed',
    jobTitle: 'Completed tune-up',
  };
  await page.route('**/home/manager**', (route) => {
    const offset = Number(new URL(route.request().url()).searchParams.get('attentionOffset') || 0);
    return route.fulfill({ json: {
      ...officeHome,
      today: { total: 2, completed: 1, inProgress: 0, upcoming: 1 },
      attention: {
        total: rows.length,
        countsByKind: { VISIT_OVERDUE: 10, JOB_NEEDS_SCHEDULING: 10, JOB_QUOTE_REQUIRED: 3 },
        items: rows.slice(offset, offset + 20),
      },
      visits: [
        { ...completedVisit, scheduledStart: '2026-10-18T13:00:00Z' },
        { ...visit, visitId: 'visit-early', scheduledStart: '2026-10-18T12:00:00Z', jobTitle: 'Scheduled inspection' },
      ],
    } });
  });
  await page.goto('/sign-in');
  await page.getByLabel('Email address').fill('member@example.com');
  await page.getByLabel('Password', { exact: true }).fill('test-password');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Office overview' })).toBeVisible();
  await expect(page.getByText('Very long boiler maintenance title for a mixed-use property')).toBeVisible();
  await expect(page.getByText('Scheduled inspection', { exact: true })).toBeVisible();
  await expect(page.getByText('Completed tune-up', { exact: true })).toBeVisible();
  await expect(page.getByText('10', { exact: true }).first()).toBeVisible();
  await expect(page.getByRole('link', { name: 'Open visit' }).first()).toHaveAttribute(
    'href',
    /job-0\?visit=visit-0&view=office/,
  );
  await page.getByRole('button', { name: /Visits 2/ }).click();
  await expect(page.getByText('Completed tune-up', { exact: true })).toBeVisible();
  await expect(page.getByText('Repair 0', { exact: true })).toHaveCount(0);
  const completedVisitRow = page.locator('.attention-table tr').filter({ hasText: 'Completed tune-up' });
  await expect(completedVisitRow.getByRole('link')).toHaveAttribute(
    'href',
    /job-completed\?visit=visit-completed&view=office/,
  );
  await expect(page.locator('.attention-table tbody tr').first()).toContainText(/8:00/);
  await page.getByRole('button', { name: 'All' }).click();
  await page.getByRole('button', { name: /Quotes & decisions/ }).click();
  await expect(page.getByText('Repair 20')).toBeVisible();
  await expect(page.getByText('Repair 22')).toBeVisible();
  await expect(page.getByText('Repair 2', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Open schedule' })).toHaveAttribute('href', '/work/schedule');
  for (const width of [1440, 1024, 768, 720, 390]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
});
test('assigned-only user never requests office data, and server classifications remain visible', async ({
  page,
}) => {
  await mockAuth(page, ['VISIT_VIEW_ASSIGNED']);
  const officeRequests: string[] = [];
  page.on('request', (request) => {
    if (request.url().includes('/home/manager')) officeRequests.push(request.url());
  });
  await page.route('**/home/technician**', (route) =>
    route.fulfill({
      json: {
        ...fieldHome,
        nextVisit: { ...visit, staleStarted: true },
        attention: { total: 24, items: [] },
        upcomingTotal: 7,
      },
    }),
  );
  await signIn(page);
  await expect(page.getByRole('heading', { name: 'Up next' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Needs attention 24' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'After today 7' })).toBeVisible();
  await expect(page.getByText('Earlier work still open')).toBeVisible();
  await page.goto('/?view=office');
  await expect(page.getByRole('heading', { name: 'Up next' })).toBeVisible();
  expect(officeRequests).toEqual([]);
});
test('mixed permissions switch Home, translate dates and clear protected data after sign-out', async ({
  page,
}) => {
  await mockAuth(page, ['customers.view', 'VISIT_VIEW_ASSIGNED']);
  await signIn(page);
  await page.getByRole('link', { name: 'My work', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Up next' })).toBeVisible();
  await page.getByRole('radio', { name: 'Français', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Mon travail' })).toBeVisible();
  await expect(page.getByText(/dimanche 18 octobre 2026/)).toBeVisible();
  await page.getByRole('button', { name: 'Se déconnecter', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Bienvenue' })).toBeVisible();
  await expect(page.getByText('Furnace repair')).toHaveCount(0);
});
test('Home failures clear stale data, distinguish forbidden, and allow retry', async ({ page }) => {
  await mockAuth(page);
  await signIn(page);
  await page.route('**/home/manager**', (route) =>
    route.fulfill({ status: 403, json: { code: 'FORBIDDEN' } }),
  );
  await page.getByRole('button', { name: 'Refresh' }).click();
  await expect(page.getByRole('alert')).toContainText('does not have permission');
  await expect(page.getByText('Furnace repair')).toHaveCount(0);
  await page.route('**/home/manager**', (route) =>
    route.fulfill({ json: { ...officeHome, visits: [] } }),
  );
  await page.getByRole('button', { name: 'Try again' }).click();
  await expect(page.getByText('No work to show today. Your schedule is clear.')).toBeVisible();
});
test('job reassignments and omitted contacts do not disclose stale customer information', async ({
  page,
}) => {
  await mockAuth(page, ['VISIT_VIEW_ASSIGNED']);
  await signIn(page);
  await page.route(/^http:\/\/[^/]+\/jobs\/[^/]+$/, (route) =>
    route.fulfill({ json: { ...job, customerContactDetails: null } }),
  );
  await page.goto('/work/jobs/job-one?visit=missing');
  await expect(page.getByText('This visit is unavailable.')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Customer contact' })).toHaveCount(0);
  await page.route(/^http:\/\/[^/]+\/jobs\/[^/]+$/, (route) =>
    route.fulfill({ status: 404, json: { code: 'JOB_NOT_FOUND' } }),
  );
  await page.getByRole('button', { name: 'Refresh' }).click();
  await expect(page.getByRole('alert')).toContainText('no longer assigned');
  await expect(page.getByText('Use the side entrance.')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Furnace repair' })).toHaveCount(0);
});
