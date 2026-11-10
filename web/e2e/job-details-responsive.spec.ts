import { test, expect } from '@playwright/test';
import { mockJobs, photoEvent } from './fixtures/jobs';
import { propertySignIn } from './fixtures/properties';
import { job, visit } from './fixtures/workspace';

test('job detail timeline groups visits, clarifies destinations and fits compact screens', async ({ page, viewport }, testInfo) => {
  const postedPaths: string[] = [];
  page.on('request', (request) => {
    if (request.method() === 'POST') postedPaths.push(new URL(request.url()).pathname);
  });
  await mockJobs(page, ['customers.view', 'JOB_UPDATE'], {
    ...job,
    visits: job.visits.map((visit) => ({ ...visit, status: 'COMPLETED' })),
    selectedVisit: null,
  });
  await page.route('**/jobs/job-one/activity*', (route) => route.fulfill({ json: {
    jobId: job.id,
    events: [
      { ...photoEvent, id: 'general', kind: 'JOB_NOTE_ADDED', visitSequence: null, photoId: null, photoPhase: null, body: 'General dispatch note.' },
      { ...photoEvent, id: 'visit', kind: 'VISIT_NOTE_ADDED', visitSequence: 1, photoId: null, photoPhase: null, body: 'Visit closeout note.' },
      { ...photoEvent, id: 'outcome', kind: 'VISIT_OUTCOME_RECORDED', visitSequence: 1, photoId: null, photoPhase: null, outcomeCode: 'NEEDS_FOLLOW_UP', outcomeSummary: 'Return visit required.', body: null },
      { ...photoEvent, id: 'assignment', kind: 'VISIT_TECHNICIAN_ASSIGNED', visitSequence: 1, photoId: null, photoPhase: null, body: null, technicianName: 'Alex Technician' },
      { ...photoEvent, id: 'status', kind: 'VISIT_STATUS_CHANGED', visitSequence: 1, photoId: null, photoPhase: null, body: null, toStatus: 'SCHEDULED' },
    ],
  } }));
  await propertySignIn(page, '/work/jobs/job-one?view=office');
  await expect(page.locator('.timeline-tabs [role="tab"]')).toHaveCount(2);
  await expect(page.locator('.main-column > .composer')).toBeVisible();
  if ((viewport?.width ?? 1440) <= 900) {
    const header = page.locator('app-shell .workspace-body > header');
    const headerHeight = (await header.boundingBox())!.height;
    await page.locator('.mobile-menu summary').click();
    await expect(page.locator('.mobile-menu .menu-content')).toBeVisible();
    expect((await header.boundingBox())!.height).toBe(headerHeight);
    await page.screenshot({ path: testInfo.outputPath(`job-mobile-menu-${viewport?.width}.png`) });
    await page.locator('.mobile-menu summary').click();
  }
  await expect(page.locator('.timeline-visit-card')).toHaveCount(job.visits.length);
  await expect(page.locator('.timeline-rail')).toHaveCSS('padding-left', '28px');
  await expect(page.locator('.timeline-rail').evaluate((element) => getComputedStyle(element, '::after').left)).resolves.toBe('8px');
  expect(await page.locator('.timeline-visit-card').first().evaluate((card) => {
    const rail = card.closest('.timeline-rail')!;
    const railLineX = rail.getBoundingClientRect().left + parseFloat(getComputedStyle(rail, '::after').left) + 1;
    const markerStyle = getComputedStyle(card, '::after');
    const markerX = card.getBoundingClientRect().left + parseFloat(markerStyle.left) + parseFloat(markerStyle.width) / 2;
    return Math.abs(railLineX - markerX) < 1;
  })).toBe(true);
  await expect(page.locator('.timeline-visit-card')).toContainText('Visit closeout note.');
  await expect(page.locator('.visit-outcome-card')).toContainText('Follow-up needed');
  await expect(page.locator('.timeline-visit-card')).not.toContainText('Technician assigned');
  await expect(page.locator('.timeline-visit-card')).not.toContainText('Visit status changed');
  await expect(page.getByRole('tab', { name: /Visits/ })).toHaveAttribute('aria-selected', 'true');
  await page.screenshot({ path: testInfo.outputPath(`job-visits-${viewport?.width}.png`), fullPage: true });
  await page.getByRole('button', { name: 'Add update' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByRole('dialog').getByRole('button', { name: 'Post update' })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath(`job-visit-dialog-${viewport?.width}.png`), fullPage: true });
  await page.getByRole('dialog').getByLabel('Write an update…').fill('Follow-up note for the completed visit.');
  await page.getByRole('dialog').getByRole('button', { name: 'Post update' }).click();
  await expect.poll(() => postedPaths.find((path) => path.includes('/visits/visit-one/notes'))).toBeTruthy();
  await page.getByRole('tab', { name: 'General job updates' }).click();
  await expect(page.locator('.main-column > .composer')).toBeVisible();
  await expect(page.locator('.general-group')).toContainText('General dispatch note.');
  await expect(page.locator('.general-group')).toContainText('Technician assigned');
  await expect(page.locator('.general-group')).toContainText('Visit status changed');
  await expect(page.locator('.general-group')).not.toContainText('Visit closeout note.');
  await expect(page.locator('.visit-group')).toHaveCount(0);
  await expect(page.locator('.summary-card').evaluate((element) => element.compareDocumentPosition(document.querySelector('.location-card')!) & Node.DOCUMENT_POSITION_FOLLOWING)).resolves.toBeTruthy();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath(`job-details-${viewport?.width}.png`), fullPage: true });
});

test('dense job overview, split activity, and responsive themes work together', async ({
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
  const visitsTimeline = page.locator('.visits-timeline');
  await expect(visitsTimeline.locator('.timeline-visit-card').filter({ hasText: 'Visit 1 · Work' })).toContainText('Replacement valve ordered.');
  await expect(visitsTimeline).not.toContainText('Technician assigned');
  await expect(visitsTimeline).not.toContainText('Visit status changed');
  await expect(page.locator('.visit-update-card')).toHaveCount(25);
  await expect(visitsTimeline).toContainText('Furnace before repair');
  await expect(visitsTimeline).toContainText('Before work');
  await expect(page.getByText('Historical service note 22', { exact: true })).toBeVisible();
  await page.getByRole('tab', { name: 'General job updates' }).click();
  await expect(page.locator('.general-group')).toContainText('Technician assigned');
  await expect(page.locator('.general-group')).toContainText('Visit status changed');
  await expect(page.locator('.general-group')).not.toContainText('Replacement valve ordered.');
  await expect(page.locator('app-job-photo-preview img')).toHaveCount(0);
  // Reload restores the default Visits tab before the visual review.
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Restore heating — Cedar House' })).toBeVisible();
  await page.getByRole('tab', { name: 'Visits' }).click();
  await expect(page.locator('app-job-photo-preview img')).toBeVisible();
  const compact = (viewport?.width ?? 1440) <= 900;
  for (const theme of ['Light', 'Dark']) {
    if (compact) await page.locator('.mobile-menu summary').click();
    await page.getByRole('radio', { name: theme, exact: true }).click();
    if (compact) await page.locator('.mobile-menu summary').click();
    await page.evaluate(() => window.scrollTo(0, 0));
    if ((viewport?.width ?? 1440) <= 500) {
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
  await expect(page.getByRole('heading', { name: 'Furnace repair' })).toBeVisible();
  await expect(page.locator('app-job-photo-preview')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'View photo' })).toHaveCount(0);
  expect(reads.filter((path) => path.endsWith('/content'))).toEqual([]);
});

test('authorized office users can complete the selected visit with an outcome at every width', async ({
  page,
  viewport,
}, testInfo) => {
  const completionRequests: Record<string, unknown>[] = [];
  const selectedVisit = {
    id: 'visit-one', status: 'IN_PROGRESS', purposeCode: 'WORK',
    scheduledStart: visit.scheduledStart, scheduledEnd: visit.scheduledEnd,
    version: 4, reschedulable: false, requestFollowUpAllowed: false,
    allowedStatusTransitions: [], completionAllowed: true, addUpdateAllowed: true,
    fieldActionable: true,
  };
  await mockJobs(page, ['customers.view', 'JOB_UPDATE', 'VISIT_RECORD_OUTCOME'], {
    ...job,
    selectedVisit,
    visits: [{ ...job.visits[0], status: 'IN_PROGRESS', version: 4 }],
  });
  await page.route('**/jobs/job-one/visits/visit-one/completion', async (route) => {
    completionRequests.push(route.request().postDataJSON() as Record<string, unknown>);
    await route.fulfill({ json: job });
  });
  await propertySignIn(page, '/work/jobs/job-one?view=office');
  const complete = page.getByRole('button', { name: 'Complete this visit' });
  await expect(complete).toBeVisible();
  await complete.click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath(`job-complete-visit-${viewport?.width}.png`), fullPage: true, animations: 'disabled' });
  await dialog.getByLabel('Outcome').selectOption('NEEDS_PARTS');
  await dialog.getByLabel('Describe what happened').fill('Replacement motor is required.');
  await dialog.getByRole('button', { name: 'Complete this visit' }).click();
  await expect.poll(() => completionRequests.length).toBe(1);
  expect(completionRequests[0]).toMatchObject({
    outcomeCode: 'NEEDS_PARTS',
    outcomeSummary: 'Replacement motor is required.',
    expectedVersion: 4,
  });
  expect(completionRequests[0]['clientOperationId']).toBeTruthy();
});

test('resolved outcomes are green and parts attention includes the recorded part at every width', async ({ page, viewport }, testInfo) => {
  await mockJobs(page, ['customers.view', 'JOB_UPDATE'], {
    ...job,
    selectedVisit: null,
    attention: [{ code: 'PARTS_REQUIRED', relevantVisitId: 'visit-one', reasonCode: null }],
    visits: [
      { ...job.visits[0], status: 'COMPLETED', outcomeCode: 'NEEDS_PARTS' },
      { ...job.visits[0], id: 'visit-two', sequence: 2, status: 'COMPLETED', outcomeCode: 'RESOLVED' },
    ],
  });
  await page.route('**/jobs/job-one/activity*', (route) => route.fulfill({ json: {
    jobId: job.id,
    events: [
      { ...photoEvent, id: 'parts-result', kind: 'VISIT_OUTCOME_RECORDED', visitSequence: 1, photoId: null, photoPhase: null, outcomeCode: 'NEEDS_PARTS', outcomeSummary: 'They need a valve.', body: null },
      { ...photoEvent, id: 'resolved-result', kind: 'VISIT_OUTCOME_RECORDED', visitSequence: 2, photoId: null, photoPhase: null, outcomeCode: 'RESOLVED', outcomeSummary: 'Work is completed.', body: null },
    ],
  } }));
  await propertySignIn(page, '/work/jobs/job-one?view=office');
  await expect(page.locator('.attention-banner')).toContainText('They need a valve.');
  const resolved = page.locator('.visit-outcome-resolved');
  await expect(resolved).toContainText('Resolved');
  await expect(resolved).toHaveCSS('background-color', 'rgb(237, 255, 241)');
  await expect(resolved.locator('.resolved-outcome .outcome-title')).toHaveCSS('color', 'rgb(23, 107, 53)');
  await page.screenshot({ path: testInfo.outputPath(`job-outcome-styling-${viewport?.width}.png`), fullPage: true });
});
