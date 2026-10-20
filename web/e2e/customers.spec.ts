import { test, expect } from '@playwright/test';
import { mockCustomers, signInCustomers } from './fixtures/customers';

test('customer search, server filters, details, permitted address read and direct reload', async ({
  page,
}) => {
  await mockCustomers(page);
  await signInCustomers(page);
  await page.getByLabel('Search name, email or phone').fill('CAMIlle@');
  await expect(page.getByRole('link', { name: 'Camille Roy', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Inactive Company', exact: true })).toHaveCount(0);
  const filtered = page.waitForRequest((request) =>
    request.url().includes('status=INACTIVE&jobs=ALL'),
  );
  await page.getByLabel('Customer status', { exact: true }).selectOption('INACTIVE');
  const request = await filtered;
  expect(request.headers()['authorization']).toBe('Bearer test-access');
  expect(request.headers()['ngsw-bypass']).toBe('true');
  const overdue = page.waitForRequest((request) =>
    request.url().includes('jobs=HAS_OVERDUE_VISITS'),
  );
  await page.getByLabel('Work filter').selectOption('HAS_OVERDUE_VISITS');
  await overdue;
  await page.getByRole('link', { name: 'Camille Roy', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Camille Roy', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Cedar House' })).toBeVisible();
  await expect(page.getByRole('link', { name: '+15145551234' })).toHaveAttribute(
    'href',
    'tel:+15145551234',
  );
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Camille Roy', exact: true })).toBeVisible();
});

test('creates a customer, validates subtype fields and confirms conversion with only the new subtype', async ({
  page,
}) => {
  const fixture = await mockCustomers(page);
  await signInCustomers(page);
  await page.getByRole('link', { name: 'New customer', exact: true }).click();
  await page.getByLabel('Display name').fill('Web customer');
  await page.getByLabel('First name', { exact: true }).fill('   ');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(
    page.getByText('Check the required fields and email format before saving.'),
  ).toBeVisible();
  expect(fixture.writes).toHaveLength(0);
  await page.getByLabel('First name', { exact: true }).fill('Jo');
  await page.getByLabel('Last name', { exact: true }).fill('Roy');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Web customer', exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'Edit customer', exact: true }).click();
  await page.getByLabel('Customer type').selectOption('COMPANY');
  await expect(page.getByLabel('Legal name')).toHaveValue('');
  await page.getByLabel('Legal name').fill('Web Company Ltd.');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('dialog')).toContainText('cannot be recovered');
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  expect(fixture.writes).toHaveLength(1);
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await page.getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Web customer', exact: true })).toBeVisible();
  expect(fixture.writes[1].body).toMatchObject({
    type: 'COMPANY',
    company: { legalName: 'Web Company Ltd.' },
  });
  expect(fixture.writes[1].body).not.toHaveProperty('individual');
  expect(fixture.writes[1].body).not.toHaveProperty('billingEmail');
});

test('contact editing preserves input on conflict, then reloads and removes with the read version', async ({
  page,
}) => {
  const fixture = await mockCustomers(page);
  await signInCustomers(page);
  await page.getByRole('link', { name: 'Camille Roy', exact: true }).click();
  await page.getByRole('button', { name: 'Add contact', exact: true }).click();
  await page.getByLabel('First name', { exact: true }).fill('Jo');
  await page.getByLabel('Last name', { exact: true }).fill('Tremblay');
  await page.getByLabel('Primary contact', { exact: true }).check();
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await page.getByRole('button', { name: 'Edit contact · Jo Tremblay', exact: true }).click();
  await page.getByLabel('Phone number', { exact: true }).fill('+15145559999');
  await page.route('**/customers/customer-one/contacts/contact-one', (route) =>
    route.request().method() === 'PATCH'
      ? route.fulfill({ status: 409, json: { code: 'CONTACT_VERSION_CONFLICT' } })
      : route.fallback(),
  );
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('This contact changed');
  await expect(page.getByLabel('Phone number', { exact: true })).toHaveValue('+15145559999');
  await expect(page.getByLabel('Phone number', { exact: true })).toBeEnabled();
  await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: 'Reload latest and discard these edits' }).click();
  await page.getByRole('button', { name: 'Remove contact · Jo Tremblay' }).click();
  await page.getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect(page.getByText('No contact people recorded.')).toBeVisible();
  expect(fixture.writes.at(-1)).toMatchObject({ method: 'DELETE', body: { expectedVersion: 1 } });
  expect(fixture.writes[0].body).not.toHaveProperty('role');
});

test('customer archive requires confirmation and leaves ordinary listings', async ({ page }) => {
  const fixture = await mockCustomers(page);
  await signInCustomers(page);
  await page.getByRole('link', { name: 'Camille Roy', exact: true }).click();
  await page.getByRole('button', { name: 'Archive customer', exact: true }).click();
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  expect(fixture.writes).toHaveLength(0);
  await page.getByRole('button', { name: 'Archive customer', exact: true }).click();
  await page.getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect(page).toHaveURL(/\/work\/customers$/);
  await expect(page.getByRole('heading', { name: 'No matching customers' })).toBeVisible();
});

test('read-only permissions hide writes and avoid unauthorized property requests', async ({
  page,
}) => {
  await mockCustomers(page, ['customers.view', 'customers.edit']);
  const properties: string[] = [];
  page.on('request', (request) => {
    if (new URL(request.url()).pathname.endsWith('/properties')) properties.push(request.url());
  });
  await signInCustomers(page);
  await expect(page.getByRole('link', { name: 'New customer' })).toHaveCount(0);
  await page.getByRole('link', { name: 'Camille Roy', exact: true }).click();
  await expect(page.getByRole('link', { name: 'Edit customer' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Add contact' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Archive customer' })).toHaveCount(0);
  expect(properties).toEqual([]);
});

test('assigned-only users cannot load office customers, including direct URLs', async ({
  page,
}) => {
  await mockCustomers(page, ['VISIT_VIEW_ASSIGNED']);
  const requests: string[] = [];
  page.on('request', (request) => {
    if (
      /\/customers(?:\/|\?|$)/.test(new URL(request.url()).pathname) &&
      !new URL(request.url()).pathname.startsWith('/work/')
    )
      requests.push(request.url());
  });
  await page.goto('/sign-in');
  await page.getByLabel('Email address').fill('field@example.com');
  await page.getByLabel('Password', { exact: true }).fill('test-password');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Up next' })).toBeVisible();
  for (const path of [
    '/work/customers',
    '/work/customers/customer-one',
    '/work/customers/new',
    '/work/customers/customer-one/edit',
  ]) {
    await page.goto(path);
    await expect(page.getByRole('alert')).toContainText('does not have permission');
  }
  expect(requests).toEqual([]);
  await expect(page.getByRole('link', { name: 'Customers', exact: true })).toHaveCount(0);
});

test('read errors clear records, and uncertain creates preserve the editor without resending', async ({
  page,
}) => {
  const fixture = await mockCustomers(page);
  await signInCustomers(page);
  await page.route('**/customers?**', (route) =>
    route.fulfill({ status: 403, json: { code: 'FORBIDDEN' } }),
  );
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('does not have permission');
  await expect(page.getByRole('link', { name: 'Camille Roy', exact: true })).toHaveCount(0);
  await page.goto('/work/customers/new');
  await page.getByLabel('Display name').fill('Uncertain');
  await page.getByLabel('First name', { exact: true }).fill('Jo');
  await page.getByLabel('Last name', { exact: true }).fill('Roy');
  let sends = 0;
  await page.route('**/customers', (route) => {
    sends++;
    return route.abort('failed');
  });
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('result could not be confirmed');
  await expect(page.getByLabel('Display name')).toHaveValue('Uncertain');
  await expect(page.getByLabel('Display name')).toBeEnabled();
  await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();
  expect(sends).toBe(1);
  expect(fixture.writes).toHaveLength(0);
});

for (const width of [390, 768]) {
  test(`customer navigation and French forms fit ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await mockCustomers(page);
    await signInCustomers(page);
    await page.locator('.mobile-menu summary').click();
    await page.getByRole('radio', { name: 'Français', exact: true }).click();
    await page.getByRole('link', { name: 'Clients', exact: true }).click();
    await page.locator('.mobile-menu summary').click();
    await page.getByRole('link', { name: 'Nouveau client', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Nouveau client' })).toBeVisible();
    await page.getByLabel('Type de client').selectOption('COMPANY');
    await expect(page.getByLabel('Dénomination légale')).toBeVisible();
    await expect(page).toHaveTitle('Nouveau client | Servora');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
  });
}

for (const width of [1440, 768, 390]) {
  test(`customer directory shows work and aligned filters at ${width}px in both languages and themes`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 950 });
    await mockCustomers(page);
    await signInCustomers(page);
    const row = page
      .getByRole('row')
      .filter({ has: page.getByRole('link', { name: 'Camille Roy', exact: true }) });
    await expect(row).toContainText('5 total jobs');
    await expect(row.locator('.work-count')).toHaveText('2');
    await expect(row.locator('.overdue-count')).toHaveText('1');
    const search = await page.locator('.search-control').boundingBox();
    const status = await page.getByLabel('Customer status', { exact: true }).boundingBox();
    const work = await page.getByLabel('Work filter', { exact: true }).boundingBox();
    expect(status?.y).toBe(work?.y);
    expect(search?.height).toBe(status?.height);
    if (width === 1440) expect(search?.y).toBe(status?.y);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({ path: testInfo.outputPath('directory-light-en.png'), fullPage: true });
    if (width <= 900) await page.locator('.mobile-menu summary').click();
    await page.getByRole('radio', { name: 'Français', exact: true }).click();
    await page.getByRole('radio', { name: 'Sombre', exact: true }).click();
    await expect(page.getByRole('radio', { name: 'Sombre', exact: true })).toBeChecked();
    if (width <= 900) await page.locator('.mobile-menu summary').click();
    await expect(
      page.getByRole('columnheader', { name: 'Travaux actifs', exact: true }),
    ).toBeVisible();
    await expect(row).toContainText('5 travaux au total');
    await page.screenshot({ path: testInfo.outputPath('directory-dark-fr.png'), fullPage: true });
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('lang', 'fr');
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await page.getByLabel('Rechercher par nom, courriel ou téléphone').fill('no match');
    await expect(page.getByRole('link', { name: 'Camille Roy', exact: true })).toHaveCount(0);
    await page.getByRole('button', { name: 'Effacer les filtres', exact: true }).click();
    await expect(page.getByRole('link', { name: 'Camille Roy', exact: true })).toBeVisible();
  });
}
