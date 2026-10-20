import { test, expect, type Page } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import type { JobActivityEvent, OfficeJobDetails } from '@servora/shared-types';
import { mockJobs, photoEvent } from './fixtures/jobs';
import { job } from './fixtures/workspace';
import { propertySignIn } from './fixtures/properties';

function event(
  id: string,
  kind: string,
  minute: number,
  values: Partial<JobActivityEvent> = {},
): JobActivityEvent {
  return {
    ...photoEvent,
    id,
    kind,
    photoId: null,
    photoPhase: null,
    body: null,
    recordedAt: new Date(Date.parse('2026-10-19T10:00:00Z') + minute * 60000).toISOString(),
    ...values,
  };
}
async function history(
  page: Page,
  events: JobActivityEvent[],
  details?: Partial<OfficeJobDetails>,
) {
  await mockJobs(page);
  if (details)
    await page.route(/^http:\/\/[^/]+\/jobs\/job-one$/, (route) =>
      route.fulfill({ json: { ...job, ...details } }),
    );
  await page.route('**/jobs/job-one/activity*', (route) =>
    route.fulfill({ json: { jobId: 'job-one', events } }),
  );
  await page.route('**/jobs/job-one/photos/*/content', async (route) =>
    route.fulfill({
      contentType: 'image/svg+xml',
      body: await readFile('e2e/fixtures/job-evidence.svg'),
    }),
  );
  await propertySignIn(page, '/work/jobs/job-one?view=office');
  await expect(page.getByRole('heading', { name: 'Job activity', exact: true })).toBeVisible();
  return page.locator('.feed-panel');
}

test('operational feed separates meaningful decisions from crew churn and exact history', async ({
  page,
  viewport,
}, testInfo) => {
  const detailedNote =
    'The circulation pump runs, but the return line remains cold. The isolation valve is seized and must be replaced.\n\n' +
    'The customer approved isolating the affected zone while the remaining floors stay heated. Photograph the original fitting before removal and check the pipe size against the replacement assembly.\n\n' +
    'The replacement part is ordered. Coordinate access with Jo and confirm the delivery before dispatching the return visit. Do not mark the work resolved until flow and temperature are verified on every floor.\n\n' +
    'Final instruction: call Jo before the return visit.';
  const crew = Array.from({ length: 5 }, (_, index) =>
    event(
      `crew-${index}`,
      index % 2 ? 'VISIT_TECHNICIAN_ROLE_CHANGED' : 'VISIT_TECHNICIAN_ASSIGNED',
      50 - index,
      {
        actorName: index % 2 ? 'Morgan Lee' : 'Jo Tremblay',
        technicianName: index % 2 ? 'Alex Tremblay' : 'Sam Chen',
        roleCode: index % 2 ? 'LEAD' : 'TECHNICIAN',
        previousRoleCode: index % 2 ? 'TECHNICIAN' : null,
      },
    ),
  );
  const older = Array.from({ length: 35 }, (_, index) =>
    event(`older-${index}`, 'VISIT_NOTE_ADDED', -1440 - index, {
      body: `Earlier service update ${index}.`,
      visitSequence: 2,
    }),
  );
  const records = [
    event('comment', 'VISIT_NOTE_ADDED', 100, { actorName: 'Morgan Lee', body: detailedNote }),
    event('outcome', 'VISIT_OUTCOME_RECORDED', 95, {
      outcomeCode: 'NEEDS_PARTS',
      outcomeSummary:
        'Replacement valve required. Current zone remains isolated; return after the part arrives.',
      visitSequence: 2,
    }),
    event('schedule', 'VISIT_RESCHEDULED', 90, { actorName: 'Jo Tremblay', visitSequence: 2 }),
    event('photo', 'JOB_PHOTO_ADDED', 85, {
      photoId: 'photo-one',
      photoPhase: 'BEFORE',
      body: 'Seized valve before replacement.',
    }),
    event('audio', 'JOB_AUDIO_ADDED', 80, {
      audioNoteId: 'audio-one',
      audioPhase: 'DURING',
      audioDurationSeconds: 42,
      body: 'Pump noise and handover for the return crew.',
    }),
    ...crew,
    event('status-1', 'VISIT_STATUS_CHANGED', 40, {
      visitSequence: 2,
      fromStatus: 'EN_ROUTE',
      toStatus: 'ON_SITE',
    }),
    event('status-2', 'VISIT_STATUS_CHANGED', 39, {
      visitSequence: 2,
      fromStatus: 'SCHEDULED',
      toStatus: 'EN_ROUTE',
    }),
    ...older,
  ];
  const feed = await history(page, records);
  await expect(feed.getByRole('radio', { name: 'Updates', exact: true })).toBeChecked();
  await expect(feed.locator('.system-group')).toHaveCount(0);
  await feed.getByRole('radio', { name: 'All activity', exact: true }).click();
  await expect(feed.getByRole('heading', { name: 'Parts required', exact: true })).toBeVisible();
  await expect(feed.getByRole('heading', { name: 'Visit rescheduled', exact: true })).toBeVisible();
  await expect(feed.getByText('5 crew changes', { exact: true })).toBeVisible();
  await expect(feed.getByText('2 status changes', { exact: true })).toBeVisible();
  await expect(feed.locator('.system-event').first()).not.toBeVisible();
  await expect(feed.locator('details details')).toHaveCount(0);
  const note = feed.locator('.update-card').filter({ hasText: 'The circulation pump runs' });
  await expect(note.locator('.comment')).toHaveClass(/clamped/);
  await note.getByRole('button', { name: 'Read full update', exact: true }).click();
  await expect(note.locator('.comment')).not.toHaveClass(/clamped/);
  await expect(
    note.getByText('Final instruction: call Jo before the return visit.', { exact: false }),
  ).toBeVisible();
  await note.getByRole('button', { name: 'Show less', exact: true }).click();
  const summary = feed.locator('.system-group').filter({ hasText: '5 crew changes' });
  await summary.locator('summary').focus();
  await page.keyboard.press('Enter');
  await expect(summary.locator('.system-event')).toHaveCount(5);
  await expect(summary.getByText('crew-4', { exact: false })).toBeVisible();
  await expect(summary.getByText('TECHNICIAN', { exact: true })).toHaveCount(0);
  await expect(summary.getByText('Technician → Lead', { exact: true }).first()).toBeVisible();
  await expect(summary.getByText('Morgan Lee', { exact: true }).first()).toBeVisible();
  await page.keyboard.press('Enter');
  await feed.getByRole('radio', { name: 'Updates', exact: true }).click();
  await expect(feed.locator('.system-group')).toHaveCount(0);
  await expect(feed.locator('.system-event')).toHaveCount(0);
  await expect(feed.getByRole('heading', { name: 'Visit rescheduled', exact: true })).toBeVisible();
  // Arrow navigation uses Material's existing accessible radio-group interaction.
  await feed.getByRole('radio', { name: 'All activity', exact: true }).focus();
  await page.keyboard.press('ArrowRight');
  await expect(feed.getByRole('radio', { name: 'History', exact: true })).toBeChecked();
  await expect(feed.locator('.update-card')).toHaveCount(0);
  await expect(feed.locator('.system-event')).toHaveCount(12);
  await expect(feed.locator('details')).toHaveCount(0);
  await expect(feed.getByText('2026-10-19T10:50:00.000Z', { exact: true })).toBeVisible();
  await feed.screenshot({ path: testInfo.outputPath('activity-system-history.png') });
  await feed.getByRole('radio', { name: 'Updates', exact: true }).click();
  await expect(feed.locator('.update-card')).toHaveCount(12);
  for (const count of [24, 36, 40]) {
    await feed.getByRole('button', { name: 'Show older activity' }).click();
    await expect(feed.locator('.update-card')).toHaveCount(count);
  }
  await expect(feed.getByRole('button', { name: 'Show older activity' })).toHaveCount(0);
  await expect(feed.getByText('Earlier service update 34.', { exact: true })).toBeVisible();
  // Reset pagination before capturing the default operational experience.
  await feed.getByRole('radio', { name: 'All activity', exact: true }).click();
  await feed.getByRole('radio', { name: 'Updates', exact: true }).click();
  await expect(feed.locator('.update-card')).toHaveCount(12);
  await expect(feed.locator('app-job-photo-preview img')).toBeVisible();
  const compact = (viewport?.width ?? 1440) <= 900;
  for (const theme of ['Light', 'Dark']) {
    if (compact) await page.locator('.mobile-menu summary').click();
    await page.getByRole('radio', { name: theme, exact: true }).click();
    if (compact) await page.locator('.mobile-menu summary').click();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await feed.screenshot({ path: testInfo.outputPath(`activity-${theme.toLowerCase()}.png`) });
  }
  if (compact) await page.locator('.mobile-menu summary').click();
  await page.getByRole('radio', { name: 'Français', exact: true }).click();
  if (compact) await page.locator('.mobile-menu summary').click();
  await expect(
    page.getByRole('heading', { name: 'Activité du travail', exact: true }),
  ).toBeVisible();
  await expect(page.getByRole('radio', { name: 'Historique', exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await feed.screenshot({ path: testInfo.outputPath('activity-french-dark.png') });
});

for (const scenario of ['empty', 'only-system', 'single-update'] as const) {
  test(`${scenario} history has clear empty states and no unnecessary collapsed levels`, async ({
    page,
  }) => {
    const records =
      scenario === 'empty'
        ? []
        : scenario === 'only-system'
          ? [event('assigned', 'VISIT_TECHNICIAN_ASSIGNED', 0, { technicianName: 'Sam Chen' })]
          : [
              event('note', 'VISIT_NOTE_ADDED', 0, {
                body: 'Heating restored. Customer confirmed normal temperature.',
              }),
            ];
    const feed = await history(page, records);
    await expect(feed.locator('details details')).toHaveCount(0);
    await feed.getByRole('radio', { name: 'Updates', exact: true }).click();
    if (scenario === 'single-update')
      await expect(
        feed.getByText('Heating restored. Customer confirmed normal temperature.', { exact: true }),
      ).toBeVisible();
    else
      await expect(
        feed.getByText('No notes, photos or visit results yet.', { exact: false }),
      ).toBeVisible();
    await feed.getByRole('radio', { name: 'History', exact: true }).click();
    if (scenario === 'only-system')
      await expect(feed.getByText('Sam Chen', { exact: true })).toBeVisible();
    else if (scenario === 'empty')
      await expect(feed.getByText('No history recorded yet.', { exact: false })).toBeVisible();
    else
      await expect(
        feed.getByText('Heating restored. Customer confirmed normal temperature.', { exact: true }),
      ).toBeVisible();
  });
}

test('completed job reads as six human updates without audit noise or empty media labels', async ({
  page,
  viewport,
}, testInfo) => {
  const records = [
    event('visit-completed', 'VISIT_STATUS_CHANGED', 0, {
      recordedAt: '2026-10-13T17:55:46Z',
      fromStatus: 'IN_PROGRESS',
      toStatus: 'COMPLETED',
    }),
    event('result', 'VISIT_OUTCOME_RECORDED', 0, {
      recordedAt: '2026-10-13T17:55:46Z',
      actorName: 'Dev Technician',
      outcomeCode: 'RESOLVED',
      outcomeSummary: 'Work is done. Furnace tested and running normally.',
    }),
    event('job-completed', 'JOB_STATUS_CHANGED', 0, {
      recordedAt: '2026-10-13T17:55:46Z',
      fromStatus: 'ACTIVE',
      toStatus: 'COMPLETED',
      visitSequence: null,
    }),
    event('note', 'VISIT_NOTE_ADDED', 0, {
      recordedAt: '2026-10-07T19:45:00Z',
      actorName: 'Sarah Moreau',
      body: 'The valve needs replacing. The part has been ordered.',
    }),
    event('voice', 'JOB_AUDIO_ADDED', 0, {
      recordedAt: '2026-10-03T16:14:00Z',
      actorName: 'Dev Technician',
      audioNoteId: 'voice-one',
      audioPhase: 'UNSPECIFIED',
      audioDurationSeconds: 6,
    }),
    event('window-photo', 'JOB_PHOTO_ADDED', 0, {
      recordedAt: '2026-10-01T15:05:00Z',
      actorName: 'Dev Technician',
      photoId: 'photo-one',
      photoPhase: 'UNSPECIFIED',
    }),
    event('door-photo', 'JOB_PHOTO_ADDED', 0, {
      recordedAt: '2026-09-30T15:29:00Z',
      actorName: 'Dev Manager',
      photoId: 'photo-two',
      photoPhase: 'UNSPECIFIED',
      body: 'The door is damaged.',
    }),
    event('access-note', 'VISIT_NOTE_ADDED', 0, {
      recordedAt: '2026-09-30T14:00:00Z',
      actorName: 'Dev Manager',
      body: 'Use the side entrance. The customer will be home.',
    }),
    ...Array.from({ length: 10 }, (_, index) =>
      event(
        `routine-${index}`,
        index % 2 ? 'VISIT_TECHNICIAN_ASSIGNED' : 'VISIT_STATUS_CHANGED',
        -30000 - index,
      ),
    ),
  ];
  const feed = await history(page, records, {
    title: 'Furnace service call',
    status: 'COMPLETED',
    allowedStatusTransitions: ['ACTIVE'],
    visits: job.visits.map((visit) => ({ ...visit, status: 'COMPLETED', outcomeCode: 'RESOLVED' })),
  });
  await expect(feed.getByRole('radio', { name: 'Updates', exact: true })).toBeChecked();
  await expect(feed.locator('.update-card')).toHaveCount(6);
  await expect(
    feed.locator('.system-group, .system-event, .record-reference, .event-details'),
  ).toHaveCount(0);
  await expect(feed.getByText('Status unavailable', { exact: true })).toHaveCount(0);
  await expect(feed.getByRole('button', { name: 'Include removed evidence history' })).toHaveCount(
    0,
  );
  await expect(feed.getByRole('heading', { name: 'Resolved', exact: true })).toBeVisible();
  await expect(feed.getByText('Sarah Moreau added a note', { exact: true })).toBeVisible();
  await expect(feed.getByText('Dev Technician added a voice note', { exact: true })).toBeVisible();
  const note = feed.locator('.update-card').filter({ hasText: 'The valve needs replacing.' });
  await note.getByRole('button', { name: 'More options', exact: true }).click();
  await page.getByRole('menuitem', { name: 'Event details', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Event details', exact: true });
  await expect(dialog.getByText('2026-10-07T19:45:00Z', { exact: true })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  await expect(note.getByRole('button', { name: 'More options' })).toBeFocused();
  const compact = (viewport?.width ?? 1440) <= 900;
  for (const theme of ['Light', 'Dark']) {
    if (compact) await page.locator('.mobile-menu summary').click();
    await page.getByRole('radio', { name: theme, exact: true }).click();
    if (compact) await page.locator('.mobile-menu summary').click();
    await expect(feed.locator('app-job-photo-preview img')).toHaveCount(2);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    const size = await feed.boundingBox();
    expect(size?.height).toBeLessThan(compact ? 1500 : 1150);
    await feed.screenshot({
      path: testInfo.outputPath(`activity-realistic-${theme.toLowerCase()}.png`),
    });
    await page.screenshot({
      path: testInfo.outputPath(`job-realistic-${theme.toLowerCase()}.png`),
      fullPage: true,
    });
  }
  await feed.getByRole('radio', { name: 'History', exact: true }).click();
  await expect(feed.locator('.system-event')).toHaveCount(12);
  await expect(feed.getByText('Visit status changed', { exact: true }).first()).toBeVisible();
  await expect(
    feed.getByText('Work is done. Furnace tested and running normally.', { exact: true }),
  ).toBeVisible();
});
