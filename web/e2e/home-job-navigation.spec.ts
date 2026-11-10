import { expect, test } from '@playwright/test';
import { mockAuth } from './fixtures/auth';

test('Home job links keep visit as a query parameter and open the job', async ({ page }) => {
  await mockAuth(page, ['customers.view', 'schedule.view_org']);
  await page.goto('/sign-in');
  await page.getByLabel('Email address').fill('member@example.com');
  await page.getByLabel('Password', { exact: true }).fill('test-password');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  const jobLink = page.locator('a[href="/work/jobs/job-one?visit=visit-one"]').first();
  await expect(jobLink).toBeVisible();
  await jobLink.click();
  await expect(page).toHaveURL(/\/work\/jobs\/job-one\?visit=visit-one$/);
  await expect(page.getByRole('heading', { name: 'Furnace repair' })).toBeVisible();
});
