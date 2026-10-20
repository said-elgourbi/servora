import { test, expect, type Page } from '@playwright/test';
import { mockAuth } from './fixtures/auth';
import { fieldHome, officeHome, visit, job } from './fixtures/workspace';
async function signIn(page: Page) {
  await page.goto('/sign-in');
  await page.getByLabel('Email address').fill('member@example.com');
  await page.getByLabel('Password', { exact: true }).fill('test-password');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Your working day' })).toBeVisible();
}
test('office day opens its precise visit, contact actions and returns Home', async ({ page }) => {
  await mockAuth(page);
  await signIn(page);
  await expect(page.getByText('Hello, Alex Tremblay')).toBeVisible();
  await page
    .getByRole('link')
    .filter({ has: page.getByRole('heading', { name: 'Furnace repair' }) })
    .click();
  await expect(page).toHaveURL(/\/work\/jobs\/job-one\?visit=visit-one&view=office/);
  await expect(page.getByRole('heading', { name: 'Furnace repair' })).toBeVisible();
  await expect(
    page.getByRole('link', { name: 'Call · +15145551234', exact: true }),
  ).toHaveAttribute('href', 'tel:+15145551234');
  await expect(page.getByRole('article', { name: 'Visit 1', exact: true })).toHaveClass(
    /\bselected\b/,
  );
  await expect(page.getByRole('link', { name: 'Directions' })).toHaveAttribute(
    'href',
    /destination=987%20Cedar%20Lane/,
  );
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Furnace repair' })).toBeVisible();
  await page.getByRole('link', { name: 'Back to Home' }).click();
  await expect(page.getByRole('heading', { name: 'Your working day' })).toBeVisible();
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
  await expect(page.getByRole('heading', { name: 'Votre journée de travail' })).toBeVisible();
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
  await expect(page.getByText('No visits today.')).toBeVisible();
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
