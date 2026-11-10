import { expect, test, type Page } from '@playwright/test';
import { mockAuth } from './fixtures/auth';
import { officeHome } from './fixtures/workspace';

function attentionItems(count: number) {
  return Array.from({ length: count }, (_, index) => ({
    kind: index % 2 ? 'JOB_NEEDS_SCHEDULING' : 'VISIT_OVERDUE',
    jobId: `attention-job-${index}`,
    jobNumber: 2000 + index,
    jobTitle: `Attention job ${index}`,
    jobStatus: 'ACTIVE',
    customerId: 'attention-customer',
    customerName: `Customer ${index}`,
    visitId: `attention-visit-${index}`,
    visitNumber: index + 1,
    purposeCode: 'WORK',
    visitStatus: 'SCHEDULED',
    technicians: [{ membershipId: `tech-${index}`, name: `Technician ${index}`, roleCode: 'LEAD' }],
    scheduledStart: `2026-10-${String(20 + (index % 10)).padStart(2, '0')}T14:00:00Z`,
    scheduledEnd: `2026-10-${String(20 + (index % 10)).padStart(2, '0')}T15:00:00Z`,
  }));
}

async function signIn(page: Page, count: number) {
  const items = attentionItems(count);
  await mockAuth(page, ['customers.view', 'schedule.view_org']);
  await page.route('**/home/manager**', (route) => {
    const offset = Number(new URL(route.request().url()).searchParams.get('attentionOffset') || 0);
    return route.fulfill({ json: { ...officeHome, attention: { total: count, countsByKind: {}, items: items.slice(offset, offset + 20) } } });
  });
  await page.goto('/sign-in');
  await page.getByLabel('Email address').fill('member@example.com');
  await page.getByLabel('Password', { exact: true }).fill('test-password');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.locator('.attention-section')).toBeVisible();
  return items;
}

test('zero attention items show the compact empty state without expansion controls', async ({ page }) => {
  await signIn(page, 0);
  await expect(page.getByText('No items need attention', { exact: true })).toBeVisible();
  await expect(page.locator('.attention-toggle')).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'View all 0' })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Today’s visits summary' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Today’s schedule' })).toBeVisible();
});

for (const count of [1, 3]) {
  test(`${count} attention item${count === 1 ? '' : 's'} fit the initial preview`, async ({ page }) => {
    await signIn(page, count);
    await expect(page.locator('.attention-item')).toHaveCount(0);
    await expect(page.locator('.attention-row')).toHaveCount(count);
    await expect(page.locator('.attention-toggle')).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByRole('button', { name: 'Show more' })).toHaveCount(0);
    await expect(page.getByRole('link', { name: `View all ${count}` })).toBeVisible();
  });
}

test('four items expand to the 10 item cap, collapse by keyboard, and retain the full count link', async ({ page }) => {
  await signIn(page, 4);
  const toggle = page.locator('.attention-toggle');
  await expect(page.locator('.attention-row')).toHaveCount(3);
  await page.getByRole('button', { name: 'Show more' }).click();
  await expect(page.locator('.attention-row')).toHaveCount(4);
  await expect(page.getByRole('button', { name: 'Show less' })).toBeVisible();
  await page.getByRole('button', { name: 'Show less' }).click();
  await expect(page.locator('.attention-row')).toHaveCount(3);
  await toggle.focus();
  await page.keyboard.press('Space');
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  const controlledList = page.locator('#manager-attention-items');
  await expect(controlledList).toHaveAttribute('aria-hidden', 'true');
  await expect(controlledList).toHaveCSS('grid-template-rows', '0px');
  await expect(page.getByRole('button', { name: 'Show more' })).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'View all 4' })).toBeVisible();
  await page.keyboard.press('Enter');
  await expect(toggle).toHaveAttribute('aria-expanded', 'true');
});

test('125 items stay capped on Home and the full list paginates in groups of 20', async ({ page }) => {
  const items = await signIn(page, 125);
  await expect(page.locator('.attention-row')).toHaveCount(3);
  await page.getByRole('button', { name: 'Show more' }).click();
  await expect(page.locator('.attention-row')).toHaveCount(10);
  await expect(page.getByRole('link', { name: 'View all 125' })).toHaveAttribute('href', '/work/attention');
  await page.getByRole('link', { name: 'View all 125' }).click();
  await expect(page.getByRole('heading', { name: 'Needs attention (125)' })).toBeVisible();
  await expect(page.locator('.attention-item')).toHaveCount(20);
  await expect(page.getByText('Showing 1–20 of 125')).toBeVisible();
  await page.getByRole('button', { name: 'Next' }).click();
  await expect(page.locator('.attention-item').first()).toContainText(items[20]!.jobTitle);
  await expect(page.getByText('Showing 21–40 of 125')).toBeVisible();
});

test('attention details and controls remain usable on mobile', async ({ page }) => {
  await signIn(page, 4);
  await page.setViewportSize({ width: 390, height: 844 });
  const row = page.locator('.attention-row').first();
  await expect(row).toContainText('Attention job 0');
  await expect(row).toContainText('Customer 0');
  await expect(row).toContainText('Technician 0');
  await expect(row.getByRole('link', { name: 'Open job' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Show more' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
