import { test, expect } from '@playwright/test';
import { mockJobs, photoEvent } from './fixtures/jobs';
import { propertySignIn } from './fixtures/properties';
import { job } from './fixtures/workspace';

test('dense job overview, grouped history and photo viewer work in both themes', async ({
  page,
  viewport,
}, testInfo) => {
  await mockJobs(page);
  await page.route(/^http:\/\/[^/]+\/jobs\/job-one$/, (route) =>
    route.fulfill({
      json: {
        ...job,
        title: 'Restore heating — Cedar House',
        attention: [
          { code: 'FOLLOW_UP_NEEDS_SCHEDULING', relevantVisitId: 'visit-two', reasonCode: null },
        ],
        visits: [
          { ...job.visits[0], status: 'COMPLETED', outcomeCode: 'NEEDS_FOLLOW_UP' },
          {
            ...job.visits[0],
            id: 'visit-two',
            sequence: 2,
            purposeCode: 'WORK',
            status: 'DRAFT',
            scheduledStart: null,
            scheduledEnd: null,
            technicians: [],
          },
        ],
      },
    }),
  );
  const events = [
    {
      ...photoEvent,
      id: 'note',
      kind: 'VISIT_NOTE_ADDED',
      photoId: null,
      photoPhase: null,
      recordedAt: '2026-10-19T14:00:00Z',
      body: 'Replacement valve ordered. Coordinate the return visit with Jo before dispatch.',
    },
    {
      ...photoEvent,
      id: 'status',
      kind: 'VISIT_STATUS_CHANGED',
      photoId: null,
      photoPhase: null,
      recordedAt: '2026-10-19T13:30:00Z',
      body: null,
      fromStatus: 'IN_PROGRESS',
      toStatus: 'COMPLETED',
    },
    {
      ...photoEvent,
      id: 'assignment',
      kind: 'VISIT_TECHNICIAN_ASSIGNED',
      technicianName: 'Alex Tremblay',
      photoId: null,
      photoPhase: null,
      recordedAt: '2026-10-19T13:00:00Z',
      body: null,
    },
    photoEvent,
    ...Array.from({ length: 23 }, (_, index) => ({
      ...photoEvent,
      id: `older-${index}`,
      photoId: null,
      photoPhase: null,
      kind: 'VISIT_NOTE_ADDED',
      body: `Historical service note ${index}`,
      recordedAt: `2026-10-18T10:${String(59 - index).padStart(2, '0')}:00Z`,
    })),
  ];
  await page.route('**/jobs/job-one/activity*', (route) =>
    route.fulfill({ json: { jobId: job.id, events } }),
  );
  await propertySignIn(page, '/work/jobs/job-one?view=office');
  await expect(page.getByRole('heading', { name: 'Restore heating — Cedar House' })).toBeVisible();
  await expect(page.getByText('Replacement valve ordered.', { exact: false })).toBeVisible();
  await expect(page.locator('.system-group')).toHaveCount(0);
  await page.getByRole('radio', { name: 'All activity', exact: true }).click();
  await expect(page.locator('.system-group')).toHaveCount(2);
  const system = page.locator('.system-group').last();
  await expect(system).not.toHaveAttribute('open');
  await expect(system.locator('.system-event')).toHaveCount(1);
  await system.locator('summary').focus();
  await page.keyboard.press('Enter');
  await expect(system).toHaveAttribute('open', '');
  await expect(system.getByText('Alex Tremblay', { exact: true }).last()).toBeVisible();
  await page.keyboard.press('Enter');
  await expect(system).not.toHaveAttribute('open');
  await expect(page.getByText('Historical service note 22', { exact: true })).toHaveCount(0);
  await expect(page.locator('.feed-panel .activity')).toHaveCount(12);
  for (const count of [24, 27]) {
    await page.getByRole('button', { name: 'Show older activity' }).click();
    await expect(page.locator('.feed-panel .activity')).toHaveCount(count);
  }
  await expect(page.getByText('Historical service note 22', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'View photo', exact: true }).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByRole('img', { name: 'Accepted visit evidence' })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'View photo', exact: true })).toBeFocused();
  // Reload restores the bounded initial feed for the visual review.
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Restore heating — Cedar House' })).toBeVisible();
  await expect(page.locator('app-job-photo-preview img')).toBeVisible();
  const compact = (viewport?.width ?? 1440) <= 900;
  for (const theme of ['Light', 'Dark']) {
    if (compact) await page.locator('.mobile-menu summary').click();
    await page.getByRole('radio', { name: theme, exact: true }).click();
    if (compact) await page.locator('.mobile-menu summary').click();
    await page.evaluate(() => window.scrollTo(0, 0));
    if ((viewport?.width ?? 1440) < 1100) {
      const heading = await page.locator('h1').boundingBox();
      expect(heading?.width).toBeGreaterThan((viewport?.width ?? 390) * 0.65);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({
      path: testInfo.outputPath(`job-${theme.toLowerCase()}.png`),
      fullPage: true,
    });
  }
});

test('evidence content is never requested without evidence viewing permission', async ({
  page,
}) => {
  const { reads } = await mockJobs(page, ['customers.view']);
  await propertySignIn(page, '/work/jobs/job-one?view=office');
  await expect(page.getByText('Furnace before repair', { exact: true })).toBeVisible();
  await expect(page.locator('app-job-photo-preview')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'View photo' })).toHaveCount(0);
  expect(reads.filter((path) => path.endsWith('/content'))).toEqual([]);
});
