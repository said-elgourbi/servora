import { test, expect } from '@playwright/test';
import { mockProperties, propertySignIn } from './fixtures/properties';
const detailPath = '/work/customers/customer-one/properties/property-one';

test('customer opens property details, edits its address with a version, and preserves job snapshots', async ({
  page,
}) => {
  const fixture = await mockProperties(page);
  await propertySignIn(page, '/work/customers/customer-one');
  await page.getByRole('link', { name: 'Cedar House', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Cedar House', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: '#1042 · Historic furnace service' })).toBeVisible();
  await expect(page.getByText('Other property private job')).toHaveCount(0);
  await page.getByRole('link', { name: 'Edit property', exact: true }).click();
  await page.getByLabel('Street address', { exact: true }).fill('321 New Street');
  await page.getByRole('button', { name: 'Save property', exact: true }).click();
  await expect(page.getByText(/321 New Street/)).toBeVisible();
  await expect(page.getByText(/123 Original Street/)).toBeVisible();
  expect(fixture.writes[0].body).toMatchObject({
    expectedVersion: 4,
    province: 'QC',
    addressLine1: '321 New Street',
  });
  expect(fixture.writes[0].body).not.toHaveProperty('country');
  await page.reload();
  await expect(page.getByText(/321 New Street/)).toBeVisible();
});

test('creates a service property from a customer using required address fields and province codes', async ({
  page,
}) => {
  const fixture = await mockProperties(page);
  await propertySignIn(page, '/work/customers/customer-one');
  await page.getByRole('link', { name: 'Add property', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Add property', exact: true })).toBeVisible();
  await expect(page.getByText('Camille Roy', { exact: true })).toBeVisible();
  await page.getByLabel('Street address', { exact: true }).fill('   ');
  await page.getByRole('button', { name: 'Save property', exact: true }).click();
  await expect(
    page.getByText('Check the required address fields and province before saving.'),
  ).toBeVisible();
  expect(fixture.writes).toHaveLength(0);
  await page.getByLabel('Property name (optional)').fill('New office');
  await page.getByLabel('Street address', { exact: true }).fill('12 Office Lane');
  await page.getByLabel('City', { exact: true }).fill('Ottawa');
  await page.getByLabel('Province or territory', { exact: true }).selectOption('ON');
  await page.getByLabel('Postal code', { exact: true }).fill('K1P 1J1');
  await page.getByRole('button', { name: 'Save property', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'New office', exact: true })).toBeVisible();
  expect(fixture.writes[0].body).toMatchObject({ province: 'ON', addressLine1: '12 Office Lane' });
  expect(fixture.writes[0].body).not.toHaveProperty('expectedVersion');
  await page.getByRole('link', { name: 'Back to customer' }).click();
  await expect(page.getByRole('link', { name: 'New office', exact: true })).toBeVisible();
});

test('archive confirms server impact, archived edits retain state, and restore returns the same property', async ({
  page,
}) => {
  const fixture = await mockProperties(page);
  await propertySignIn(page);
  await page.getByRole('button', { name: 'Archive property', exact: true }).click();
  await expect(page.getByRole('dialog')).toContainText('2 active jobs · 3 active visits');
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  expect(fixture.writes).toHaveLength(0);
  await page.getByRole('button', { name: 'Archive property', exact: true }).click();
  await page.getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Restore property', exact: true })).toBeVisible();
  expect(fixture.writes[0].body).toMatchObject({ expectedVersion: 4 });
  expect(fixture.writes[0].body?.['clientOperationId']).toMatch(/^[0-9a-f-]{36}$/);
  await page.getByRole('link', { name: 'Edit property', exact: true }).click();
  await expect(page.getByText(/Editing it does not restore it/)).toBeVisible();
  await page.getByLabel('Property name (optional)').fill('Archived Cedar House');
  await page.getByRole('button', { name: 'Save property', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Restore property', exact: true })).toBeVisible();
  expect(fixture.writes[1].body).toMatchObject({ expectedVersion: 5 });
  await page.getByRole('link', { name: 'Back to customer' }).click();
  await expect(page.getByLabel('Property status', { exact: true })).toHaveValue('ARCHIVED');
  await expect(page.getByRole('link', { name: 'Archived Cedar House', exact: true })).toBeVisible();
  await page.getByLabel('Property status', { exact: true }).selectOption('ACTIVE');
  await expect(page.getByText('No active properties for this customer.')).toBeVisible();
  await page.getByLabel('Property status', { exact: true }).selectOption('ALL');
  await page.getByRole('link', { name: 'Archived Cedar House', exact: true }).click();
  await page.getByRole('button', { name: 'Restore property', exact: true }).click();
  await page.getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Archive property', exact: true })).toBeVisible();
  expect(fixture.writes[2].body).toMatchObject({ expectedVersion: 6 });
  await expect(page).toHaveURL(new RegExp(detailPath + '$'));
});

test('version conflict preserves editable values and requires an explicit fresh read before new intent', async ({
  page,
}) => {
  const fixture = await mockProperties(page);
  await propertySignIn(page, detailPath + '/edit');
  await page.getByLabel('Street address', { exact: true }).fill('My intended address');
  let conflict = true;
  await page.route('**/customers/customer-one/properties/property-one', (route) =>
    conflict && route.request().method() === 'PATCH'
      ? route.fulfill({ status: 409, json: { code: 'PROPERTY_VERSION_CONFLICT' } })
      : route.fallback(),
  );
  await page.getByRole('button', { name: 'Save property', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('This property changed');
  await expect(page.getByLabel('Street address', { exact: true })).toHaveValue(
    'My intended address',
  );
  await expect(page.getByLabel('Street address', { exact: true })).toBeEnabled();
  await expect(page.getByRole('button', { name: 'Save property', exact: true })).toBeDisabled();
  fixture.change({ version: 9, addressLine1: 'Other writer address' });
  conflict = false;
  await page.getByRole('button', { name: 'Reload latest and discard these edits' }).click();
  await expect(page.getByLabel('Street address', { exact: true })).toHaveValue(
    'Other writer address',
  );
  await page.getByLabel('Street address', { exact: true }).fill('Reviewed new intent');
  await page.getByRole('button', { name: 'Save property', exact: true }).click();
  await expect(page.getByText(/Reviewed new intent/)).toBeVisible();
  expect(fixture.writes.at(-1)?.body).toMatchObject({ expectedVersion: 9 });
});

test('property-only reads do not fetch customer records or customer jobs', async ({ page }) => {
  await mockProperties(page, ['properties.view']);
  const customerReads: string[] = [];
  page.on('request', (request) => {
    if (/^\/customers\/customer-one(?:\/jobs)?$/.test(new URL(request.url()).pathname))
      customerReads.push(request.url());
  });
  await propertySignIn(page);
  await expect(page.getByRole('heading', { name: 'Cedar House', exact: true })).toBeVisible();
  await expect(page.getByText('Camille Roy', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Jobs at this property' })).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Edit property' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Archive property' })).toHaveCount(0);
  expect(customerReads).toEqual([]);
});

test('customer permissions never grant property writes, including direct page URLs', async ({
  page,
}) => {
  await mockProperties(page, ['customers.view', 'customers.edit']);
  const propertyReads: string[] = [];
  page.on('request', (request) => {
    if (/^\/customers\/[^/]+\/properties/.test(new URL(request.url()).pathname))
      propertyReads.push(request.url());
  });
  await propertySignIn(page, '/work/customers/customer-one');
  await expect(page.getByRole('link', { name: 'Add property' })).toHaveCount(0);
  for (const path of [
    detailPath,
    detailPath + '/edit',
    '/work/customers/customer-one/properties/new',
  ]) {
    await page.goto(path);
    await expect(page.getByRole('alert')).toContainText('does not have permission');
  }
  expect(propertyReads).toEqual([]);
});

test('uncertain property creation retains the editor and blocks duplicate submissions', async ({
  page,
}) => {
  await mockProperties(page);
  await propertySignIn(page, '/work/customers/customer-one/properties/new');
  await page.getByLabel('Street address', { exact: true }).fill('12 Office Lane');
  await page.getByLabel('City', { exact: true }).fill('Ottawa');
  await page.getByLabel('Province or territory', { exact: true }).selectOption('ON');
  await page.getByLabel('Postal code', { exact: true }).fill('K1P 1J1');
  let attempts = 0;
  await page.route('**/customers/customer-one/properties', (route) => {
    attempts++;
    return route.abort('failed');
  });
  await page.getByRole('button', { name: 'Save property', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('result could not be confirmed');
  await expect(page.getByLabel('Street address', { exact: true })).toHaveValue('12 Office Lane');
  await expect(page.getByRole('button', { name: 'Save property', exact: true })).toBeDisabled();
  expect(attempts).toBe(1);
});

test('deletion follows server eligibility and handles new references before any irreversible removal', async ({
  page,
}) => {
  const fixture = await mockProperties(page);
  await propertySignIn(page);
  await expect(page.getByRole('heading', { name: 'Cedar House', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Permanently delete property' })).toHaveCount(0);
  await expect(
    page.getByText(
      'This property has business references and cannot be permanently deleted. Archive it instead.',
    ),
  ).toBeVisible();
  fixture.change({ canBePermanentlyDeleted: true });
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await page.route('**/customers/customer-one/properties/property-one', (route) =>
    route.request().method() === 'DELETE'
      ? route.fulfill({ status: 409, json: { code: 'PROPERTY_HAS_REFERENCES' } })
      : route.fallback(),
  );
  await page.getByRole('button', { name: 'Permanently delete property' }).click();
  await expect(page.getByRole('dialog')).toContainText('cannot be undone');
  await page.getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('business references');
  fixture.change({ canBePermanentlyDeleted: false });
  await page.getByRole('button', { name: 'Reload current records', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Permanently delete property' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Archive property', exact: true })).toBeEnabled();
});

test('read refusal clears the previous property and sign-out clears protected content', async ({
  page,
}) => {
  await mockProperties(page);
  await propertySignIn(page);
  await expect(page.getByRole('heading', { name: 'Cedar House', exact: true })).toBeVisible();
  await page.route('**/customers/customer-one/properties/property-one', (route) =>
    route.fulfill({ status: 404, json: { code: 'PROPERTY_NOT_FOUND' } }),
  );
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText(
    'property or its customer context is unavailable',
  );
  await expect(page.getByRole('heading', { name: 'Cedar House', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
});
for (const width of [390, 768]) {
  test(`French property forms and details fit ${width}px in both themes`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await mockProperties(page);
    await propertySignIn(page);
    await page.locator('.mobile-menu summary').click();
    await page.getByRole('radio', { name: 'Français', exact: true }).click();
    await page.locator('.mobile-menu summary').click();
    await page.getByRole('link', { name: 'Modifier la propriété', exact: true }).click();
    await expect(page.getByLabel('Province ou territoire', { exact: true })).toHaveValue('QC');
    await expect(page).toHaveTitle('Modifier la propriété | Servora');
    for (const theme of ['dark', 'light']) {
      await page.locator('.mobile-menu summary').click();
      await page
        .getByRole('radio', { name: theme === 'dark' ? 'Sombre' : 'Clair', exact: true })
        .click();
      await page.locator('.mobile-menu summary').click();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
    }
    await page.getByRole('link', { name: 'Annuler', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Cedar House', exact: true })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
  });
}

test('permanent deletion requires a second explicit confirmation after cancellation', async ({
  page,
}) => {
  const fixture = await mockProperties(page);
  fixture.change({ canBePermanentlyDeleted: true });
  await propertySignIn(page);
  await page.getByRole('button', { name: 'Permanently delete property' }).click();
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  expect(fixture.writes).toHaveLength(0);
  await page.getByRole('button', { name: 'Permanently delete property' }).click();
  await page.getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect(page).toHaveURL(/\/work\/customers\/customer-one$/);
  await expect(page.getByText('No active properties for this customer.')).toBeVisible();
  expect(fixture.writes).toMatchObject([
    { method: 'DELETE', path: '/customers/customer-one/properties/property-one' },
  ]);
});

test('property creation does not require customer editing or property read capability', async ({
  page,
}) => {
  const fixture = await mockProperties(page, ['properties.create']);
  const reads: string[] = [];
  page.on('request', (request) => {
    if (request.method() === 'GET' && new URL(request.url()).pathname.startsWith('/customers/'))
      reads.push(request.url());
  });
  await propertySignIn(page, '/work/customers/customer-one/properties/new');
  await page.getByLabel('Street address', { exact: true }).fill('12 Office Lane');
  await page.getByLabel('City', { exact: true }).fill('Ottawa');
  await page.getByLabel('Province or territory', { exact: true }).selectOption('ON');
  await page.getByLabel('Postal code', { exact: true }).fill('K1P 1J1');
  await page.getByRole('button', { name: 'Save property', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Property saved', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Save property', exact: true })).toHaveCount(0);
  expect(reads).toEqual([]);
  expect(fixture.writes).toHaveLength(1);
});

test('credential recovery followed by connection loss preserves the property editor', async ({
  page,
}) => {
  await mockProperties(page);
  await propertySignIn(page, '/work/customers/customer-one/properties/new');
  await page.getByLabel('Street address', { exact: true }).fill('12 Retained Lane');
  await page.getByLabel('City', { exact: true }).fill('Ottawa');
  await page.getByLabel('Province or territory', { exact: true }).selectOption('ON');
  await page.getByLabel('Postal code', { exact: true }).fill('K1P 1J1');
  let sends = 0;
  await page.route('**/customers/customer-one/properties', (route) => {
    if (route.request().method() !== 'POST') return route.fallback();
    sends++;
    return sends === 1
      ? route.fulfill({ status: 401, json: { code: 'UNAUTHENTICATED' } })
      : route.abort('failed');
  });
  await page.getByRole('button', { name: 'Save property', exact: true }).click();
  await expect(
    page.getByRole('alert').filter({ hasText: 'result could not be confirmed' }),
  ).toContainText('result could not be confirmed');
  await expect(page.getByLabel('Street address', { exact: true })).toHaveValue('12 Retained Lane');
  await expect(page.getByLabel('Province or territory', { exact: true })).toHaveValue('ON');
  await expect(page.getByRole('button', { name: 'Save property', exact: true })).toBeDisabled();
  expect(sends).toBe(2); // One definitive 401 refusal, then the established authentication retry.
});
