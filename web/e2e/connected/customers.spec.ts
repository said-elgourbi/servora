import { test, expect } from '@playwright/test';

test('office customer and contact lifecycle through the actual API on a phone viewport', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const email = process.env['SERVORA_CONNECTED_OFFICE'];
  const password = process.env['SERVORA_CONNECTED_PASSWORD'];
  if (!email || !password) throw new Error('Missing connected customer fixture');
  await page.goto('/sign-in');
  await page.getByLabel('Email address').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Your working day' })).toBeVisible();
  await page.locator('.mobile-menu summary').click();
  await page.getByRole('link', { name: 'Customers', exact: true }).click();
  await page.locator('.mobile-menu summary').click();
  await page.getByRole('link', { name: 'New customer', exact: true }).click();
  await page.getByLabel('Customer type').selectOption('COMPANY');
  await page.getByLabel('Display name').fill('Connected web company');
  await page.getByLabel('Legal name').fill('Connected Web Ltd.');
  await page.getByLabel('Email address').fill('office@example.com');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'Connected web company', exact: true }),
  ).toBeVisible();
  const customerPath = new URL(page.url()).pathname;
  await expect(page.getByText('No active properties for this customer.')).toBeVisible();
  await page.getByRole('button', { name: 'Add contact', exact: true }).click();
  await page.getByLabel('First name', { exact: true }).fill('Jo');
  await page.getByLabel('Last name', { exact: true }).fill('Roy');
  await page.getByLabel('Primary contact', { exact: true }).check();
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await page.getByRole('button', { name: 'Edit contact · Jo Roy', exact: true }).click();
  await page.getByLabel('Phone number', { exact: true }).fill('+15145559876');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('link', { name: '+15145559876', exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('link', { name: '+15145559876', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Remove contact · Jo Roy' }).click();
  await page.getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect(page.getByText('No contact people recorded.')).toBeVisible();
  await page.getByRole('link', { name: 'Edit customer', exact: true }).click();
  await page.getByLabel('Customer type').selectOption('INDIVIDUAL');
  await page.getByLabel('First name', { exact: true }).fill('Camille');
  await page.getByLabel('Last name', { exact: true }).fill('Roy');
  await page.getByLabel('Display name').fill('Connected individual');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await page.getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'Connected individual', exact: true }),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Archive customer', exact: true }).click();
  await page.getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect(page).toHaveURL(/\/work\/customers$/);
  await expect(page.getByRole('link', { name: 'Connected individual', exact: true })).toHaveCount(
    0,
  );
  await page.goto(customerPath);
  await expect(page.getByRole('alert')).toContainText('unavailable or has been archived');
});
