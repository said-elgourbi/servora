import { test, expect } from '@playwright/test';
import { mockJobs, jobPermissions } from './fixtures/jobs';
import { propertySignIn } from './fixtures/properties';
test('customer to property to job creation and versioned lifecycle with cancellation confirmation', async ({
  page,
}) => {
  const { writes } = await mockJobs(page);
  await propertySignIn(page);
  await page.getByRole('link', { name: 'New job', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'New job' })).toBeVisible();
  await expect(page.getByLabel('Service property', { exact: true })).toHaveValue('property-one');
  await page.getByRole('button', { name: 'Create job', exact: true }).click();
  expect(writes).toEqual([]);
  await page.getByLabel('Job title', { exact: true }).fill('New heating repair');
  await page.getByLabel('Description (optional)', { exact: true }).fill('Check boiler.');
  await page.getByRole('button', { name: 'Create job', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'New heating repair' })).toBeVisible();
  expect(writes[0]).toEqual({
    path: '/jobs',
    body: {
      customerId: 'customer-one',
      propertyId: 'property-one',
      title: 'New heating repair',
      description: 'Check boiler.',
    },
  });
  await page.getByRole('button', { name: 'Cancel job', exact: true }).click();
  await expect(page.getByRole('dialog')).toContainText('also cancels its open visits');
  await expect(page.getByRole('button', { name: 'Confirm', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  expect(writes).toHaveLength(1);
  await page.getByRole('button', { name: 'Completed', exact: true }).click();
  await page.getByLabel('Explanation (optional)').fill('Verified closure');
  await page.getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Reopen job', exact: true })).toBeVisible();
  expect(writes[1]).toEqual({
    path: '/jobs/created-job/status',
    body: { status: 'COMPLETED', note: 'Verified closure', expectedVersion: 1 },
  });
  await page.reload();
  await expect(page.getByRole('heading', { name: 'New heating repair' })).toBeVisible();
  await page.getByRole('button', { name: 'Reopen job', exact: true }).click();
  await page.getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Completed', exact: true })).toBeVisible();
  expect(writes[2].body).toMatchObject({ status: 'ACTIVE', expectedVersion: 2 });
});
test('a blocked completion retains explanation and prevents resending until explicit review', async ({
  page,
}) => {
  await mockJobs(page);
  let attempts = 0;
  await page.route('**/jobs/job-one/status', (route) => {
    attempts++;
    return route.fulfill({ status: 409, json: { code: 'JOB_COMPLETION_BLOCKED' } });
  });
  await propertySignIn(page, '/work/jobs/job-one?view=office');
  await page.getByRole('button', { name: 'Completed', exact: true }).click();
  await page.getByLabel('Explanation (optional)').fill('Keep my explanation');
  await page.getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('still has an open visit');
  await expect(page.getByRole('alert')).toContainText('Keep my explanation');
  await expect(page.getByRole('button', { name: 'Completed', exact: true })).toBeDisabled();
  expect(attempts).toBe(1);
  await page.getByRole('button', { name: 'Reload current records', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Completed', exact: true })).toBeEnabled();
});
test('lost creation response preserves fields and blocks automatic duplicate creation', async ({
  page,
}) => {
  await mockJobs(page);
  let attempts = 0;
  await page.route(/^http:\/\/[^/]+\/jobs$/, (route) => {
    attempts++;
    return route.abort('failed');
  });
  await propertySignIn(page, '/work/customers/customer-one/jobs/new');
  await page.getByLabel('Service property', { exact: true }).selectOption('property-one');
  await page.getByLabel('Job title', { exact: true }).fill('Preserve this title');
  await page.getByRole('button', { name: 'Create job', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('result could not be confirmed');
  await expect(page.getByLabel('Job title', { exact: true })).toHaveValue('Preserve this title');
  await expect(page.getByRole('button', { name: 'Create job', exact: true })).toBeDisabled();
  expect(attempts).toBe(1);
});
test('activity photo viewing, reasoned removal and authorized audit history', async ({ page }) => {
  const { writes } = await mockJobs(page);
  await propertySignIn(page, '/work/jobs/job-one?view=office');
  await page.getByRole('button', { name: 'View photo', exact: true }).click();
  await expect(page.getByRole('img', { name: 'Accepted visit evidence' })).toBeVisible();
  await page
    .getByRole('dialog')
    .getByRole('button', { name: 'Remove evidence', exact: true })
    .click();
  await expect(page.getByRole('button', { name: 'Confirm', exact: true })).toBeDisabled();
  await page.getByLabel('Removal reason', { exact: true }).fill('Duplicate photo');
  await page.getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect
    .poll(() => writes)
    .toEqual([
      { path: '/jobs/job-one/photos/photo-one/removal', body: { reason: 'Duplicate photo' } },
    ]);
  await expect(page.locator('app-office-job-workflow .activity')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'View photo', exact: true })).toHaveCount(0);
  await expect(page.getByRole('img', { name: 'Accepted visit evidence' })).toHaveCount(0);
  await page.getByRole('radio', { name: 'History', exact: true }).click();
  await page.getByRole('button', { name: 'Include removed evidence history', exact: true }).click();
  await expect(page.getByText('Removal reason: Duplicate photo')).toBeVisible();
  await expect(page.getByRole('button', { name: 'View photo', exact: true })).toHaveCount(0);
});
test('read-only office accounts see no lifecycle or removal actions', async ({ page }) => {
  const { writes } = await mockJobs(page, ['customers.view', 'properties.view', 'evidence.view']);
  await propertySignIn(page, '/work/jobs/job-one?view=office');
  await expect(page.getByRole('heading', { name: 'Furnace repair', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Change job status' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Remove evidence' })).toHaveCount(0);
  await page.getByRole('button', { name: 'More options', exact: true }).click();
  await expect(page.getByRole('menuitem', { name: 'Remove evidence', exact: true })).toHaveCount(0);
  await page.keyboard.press('Escape');
  await expect(page.getByRole('button', { name: 'Include removed evidence history' })).toHaveCount(
    0,
  );
  await page.goto('/work/customers/customer-one/jobs/new');
  await expect(page.getByRole('alert')).toContainText('does not have permission');
  expect(writes).toEqual([]);
});
test('create-only accounts receive a receipt without unauthorized customer or job reads', async ({
  page,
}) => {
  const { reads, writes } = await mockJobs(page, ['JOB_CREATE', 'properties.view']);
  // No Home projection is available, so restore the fixture session via sign-in directly.
  await page.goto('/sign-in');
  await page.getByLabel('Email address').fill('office@example.com');
  await page.getByLabel('Password', { exact: true }).fill('test-password');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page).toHaveURL('/');
  await page.goto('/work/customers/customer-one/jobs/new');
  await page.getByLabel('Service property', { exact: true }).selectOption('property-one');
  await page.getByLabel('Job title', { exact: true }).fill('Accepted write');
  await page.getByRole('button', { name: 'Create job', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('Job created. #1044');
  expect(reads).toEqual([]);
  expect(writes).toHaveLength(1);
});
for (const width of [390, 768]) {
  test(`French job form and lifecycle remain usable at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await mockJobs(page, jobPermissions);
    await propertySignIn(page, '/work/customers/customer-one/jobs/new');
    await page.locator('summary').click();
    await page.getByRole('radio', { name: 'Français', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Nouveau travail', exact: true })).toBeVisible();
    await page.getByLabel('Propriété à desservir', { exact: true }).selectOption('property-one');
    await page.getByLabel('Titre du travail', { exact: true }).fill('Réparation du chauffage');
    await page.getByRole('button', { name: 'Créer le travail', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Réparation du chauffage' })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
  });
}
test('cancel confirmation names working visits and a lost response blocks duplicate requests', async ({
  page,
}) => {
  await mockJobs(page);
  let attempts = 0;
  await page.route('**/jobs/job-one/status', (route) => {
    attempts++;
    return route.abort('failed');
  });
  await propertySignIn(page, '/work/jobs/job-one?view=office');
  await page.getByRole('button', { name: 'Cancel job', exact: true }).click();
  await expect(page.getByRole('dialog')).toContainText('Visit 1');
  await page.getByLabel('Cancellation explanation').fill('Customer canceled');
  await page.getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('result could not be confirmed');
  await expect(page.getByRole('alert')).toContainText('Customer canceled');
  await expect(page.getByRole('button', { name: 'Cancel job', exact: true })).toBeDisabled();
  expect(attempts).toBe(1);
});
test('office audio playback and removal use the independent audio capability', async ({ page }) => {
  const { photoEvent } = await import('./fixtures/jobs');
  const { readFile } = await import('node:fs/promises');
  const { reads, writes } = await mockJobs(page, [
    'customers.view',
    'evidence.view',
    'evidence.audio.remove',
  ]);
  let removed = false;
  await page.route('**/jobs/job-one/activity*', (route) =>
    route.fulfill({
      json: {
        jobId: 'job-one',
        events: removed
          ? []
          : [
              {
                ...photoEvent,
                kind: 'JOB_AUDIO_ADDED',
                photoId: null,
                photoPhase: null,
                audioNoteId: 'audio-one',
                audioPhase: 'DURING',
                audioDurationSeconds: 1,
              },
            ],
      },
    }),
  );
  await page.route('**/jobs/job-one/audio-notes/audio-one/content', async (route) =>
    route.fulfill({
      contentType: 'audio/mp4',
      body: await readFile('e2e/fixtures/field-audio.m4a'),
    }),
  );
  await page.route('**/jobs/job-one/audio-notes/audio-one/removal', async (route) => {
    removed = true;
    await route.fallback();
  });
  await propertySignIn(page, '/work/jobs/job-one?view=office');
  await page.getByRole('button', { name: 'Play audio', exact: true }).click();
  await expect(page.locator('audio')).toBeVisible();
  await expect
    .poll(() => page.locator('audio').evaluate((audio: HTMLAudioElement) => audio.readyState))
    .toBeGreaterThan(0);
  await page.getByRole('button', { name: 'More options', exact: true }).click();
  await page.getByRole('menuitem', { name: 'Remove evidence', exact: true }).click();
  await page.getByLabel('Removal reason', { exact: true }).fill('Wrong recording');
  await page.getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect(page.locator('audio')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Play audio', exact: true })).toHaveCount(0);
  expect(writes[0]).toEqual({
    path: '/jobs/job-one/audio-notes/audio-one/removal',
    body: { reason: 'Wrong recording' },
  });
  expect(reads).not.toContain('/jobs/job-one/photos/audio-one/content');
});
test('a property archived after selection refuses creation without losing the editor', async ({
  page,
}) => {
  await mockJobs(page);
  await page.route(/^http:\/\/[^/]+\/jobs$/, (route) =>
    route.fulfill({ status: 409, json: { code: 'PROPERTY_NOT_AVAILABLE_FOR_NEW_WORK' } }),
  );
  await propertySignIn(page, '/work/customers/customer-one/jobs/new?property=property-one');
  await page.getByLabel('Job title', { exact: true }).fill('Keep this job request');
  await page.getByRole('button', { name: 'Create job', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('This property is archived');
  await expect(page.getByLabel('Job title', { exact: true })).toHaveValue('Keep this job request');
  await expect(page.getByRole('button', { name: 'Create job', exact: true })).toBeDisabled();
});
