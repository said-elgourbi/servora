import { test, expect } from '@playwright/test';
import { jobPermissions, mockJobs } from './fixtures/jobs';
import { propertySignIn } from './fixtures/properties';

test('posts an update to the general job feed or a past visit', async ({ page }, testInfo) => {
  const { writes } = await mockJobs(page, [...jobPermissions, 'evidence.photo.add'], {
    visits: [
      { id: 'visit-one', sequence: 1, status: 'COMPLETED', purposeCode: 'WORK', outcomeCode: 'RESOLVED', scheduledStart: '2026-10-18T13:00:00Z', scheduledEnd: '2026-10-18T14:00:00Z', technicians: [] },
      { id: 'visit-two', sequence: 2, status: 'COMPLETED', purposeCode: 'WORK', outcomeCode: 'RESOLVED', scheduledStart: '2026-10-19T13:00:00Z', scheduledEnd: '2026-10-19T14:00:00Z', technicians: [] },
    ],
  });
  await propertySignIn(page, '/work/jobs/job-one?view=office');
  await expect(page.getByRole('heading', { name: 'Furnace repair', exact: true })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('job-update.png'), fullPage: true });

  await page.getByLabel('Write an update…').fill('General follow-up');
  await page.getByRole('button', { name: 'Post update', exact: true }).click();
  await expect.poll(() => writes.some((write) => write.path === '/jobs/job-one/notes' && write.body['body'] === 'General follow-up')).toBe(true);

  await page.getByLabel('Write an update…').fill('Photo was missed in the report');
  await page.getByLabel('Post to').selectOption('visit-two');
  await page.getByRole('button', { name: 'Post update', exact: true }).click();
  await expect.poll(() => writes.some((write) => write.path === '/jobs/job-one/visits/visit-two/notes' && write.body['body'] === 'Photo was missed in the report')).toBe(true);

  let photoUpload = '';
  await page.route('**/jobs/job-one/photos', async (route) => {
    photoUpload = route.request().postData() ?? '';
    return route.fulfill({ status: 201, json: { jobId: 'job-one', events: [] } });
  });
  await page.getByLabel('Post to').selectOption('visit-two');
  await page.locator('input[type="file"]').first().setInputFiles({
    name: 'missed-photo.png', mimeType: 'image/png', buffer: Buffer.from('fake-image'),
  });
  await page.getByRole('button', { name: 'Post update', exact: true }).click();
  await expect.poll(() => photoUpload).toContain('visit-two');
  await expect(page.locator('html')).toHaveJSProperty('scrollWidth', await page.evaluate(() => window.innerWidth));
});
