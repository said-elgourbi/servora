import { test, expect } from '@playwright/test';
import type { JobActivityEvent } from '@servora/shared-types';
import { jobPermissions, mockJobs, photoEvent } from './fixtures/jobs';
import { propertySignIn } from './fixtures/properties';

test('authors and Managers can edit, review history, and delete comments', async ({ page }, testInfo) => {
  const original: JobActivityEvent = {
    ...photoEvent,
    id: 'note-edit',
    kind: 'JOB_NOTE_ADDED',
    body: 'The customer reported a leak.',
    photoId: null,
    photoPhase: null,
    activityEditedAt: '2026-10-19T13:00:00Z',
  };
  await mockJobs(page, jobPermissions, { visits: [], selectedVisit: null });
  await page.route('**/history', async (route) => {
    return route.fulfill({
      json: [{ id: 'edit-1', previousValue: { body: 'The customer reported a leak.' }, recordedAt: '2026-10-19T13:00:00Z' }],
    });
  });
  await page.route('**/jobs/job-one/activity', async (route) => {
    return route.fulfill({ json: { jobId: 'job-one', events: [original] } });
  });
  await page.route('**/jobs/job-one/notes/note-edit', (route) => {
    if (route.request().method() === 'PATCH') {
      return route.fulfill({ json: { jobId: 'job-one', events: [{ ...original, body: 'The supply line is leaking.', activityEditedAt: '2026-10-19T14:00:00Z' }] } });
    }
    return route.fallback();
  });
  await page.route('**/jobs/job-one/notes/note-edit/removal', (route) =>
    route.fulfill({ status: 201, json: { jobId: 'job-one', events: [] } }),
  );

  await propertySignIn(page, '/work/jobs/job-one?view=office');
  await page.getByRole('tab', { name: 'General job updates' }).click();
  await expect(page.getByRole('button', { name: 'Edit update' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Delete update' })).toBeVisible();
  await page.getByRole('button', { name: 'Edit update' }).click();
  const editor = page.getByRole('dialog');
  await expect(editor.getByLabel('Comment')).toHaveValue('The customer reported a leak.');
  await editor.getByLabel('Comment').fill('The supply line is leaking.');
  await editor.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByText('The supply line is leaking.', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Edited' }).click();
  const history = page.getByRole('dialog', { name: 'Update history' });
  await expect(page.locator('mat-dialog-container')).not.toHaveClass(/mdc-dialog--opening/);
  await expect(history).toContainText('The customer reported a leak.');
  await expect(history).toContainText('The supply line is leaking.');
  await page.screenshot({ path: testInfo.outputPath(`comment-history-${testInfo.project.name}.png`) });
  await history.getByRole('button', { name: 'Close' }).click();
  await page.getByRole('button', { name: 'Delete update' }).click();
  const confirmation = page.getByRole('dialog', { name: 'Delete update' });
  await expect(confirmation).toContainText('Delete this update and its attachments?');
  await confirmation.getByRole('button', { name: 'Delete update' }).click();
  await expect(page.getByText('The supply line is leaking.', { exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width);
});

test('a removed photo shows a clear history entry without an empty preview', async ({ page }, testInfo) => {
  const removedPhoto: JobActivityEvent = {
    ...photoEvent,
    id: 'photo-removal',
    kind: 'JOB_PHOTO_REMOVED',
    body: null,
    canEdit: false,
    canDelete: false,
    photoRemovalReason: 'Duplicate photo',
  };
  await mockJobs(page);
  await page.route('**/jobs/job-one/activity', (route) =>
    route.fulfill({ json: { jobId: 'job-one', events: [removedPhoto] } }),
  );
  await propertySignIn(page, '/work/jobs/job-one?view=office');
  await page.getByRole('tab', { name: /Visits/ }).click();
  const removalLabel = page.getByText('removed a photo');
  await expect(removalLabel).toBeVisible();
  await expect(page.getByText('Duplicate photo')).toBeVisible();
  await expect(page.locator('app-job-photo-preview')).toHaveCount(0);
  await expect(page.locator('.update-body.has-photo')).toHaveCount(0);
  await removalLabel.scrollIntoViewIfNeeded();
  await page.screenshot({ path: testInfo.outputPath(`removed-photo-${testInfo.project.name}.png`) });
});
