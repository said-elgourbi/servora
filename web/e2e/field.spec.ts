import { test, expect, type BrowserContext, type Page } from '@playwright/test';
import { job as baseJob, fieldHome } from './fixtures/workspace';
import { tokens } from './fixtures/auth';
const scope = {
  userId: '11111111-1111-4111-8111-111111111111',
  organizationId: '22222222-2222-4222-8222-222222222222',
  membershipId: '33333333-3333-4333-8333-333333333333',
};
const permissions = [
  'VISIT_VIEW_ASSIGNED',
  'VISIT_UPDATE_ASSIGNED_STATUS',
  'VISIT_RECORD_OUTCOME',
  'VISIT_ADD_NOTE',
  'evidence.photo.add',
  'evidence.audio.add',
  'visits.request_follow_up',
  'visits.report_ad_hoc_work',
];
const details = {
  ...baseJob,
  selectedVisit: {
    id: 'visit-one',
    status: 'IN_PROGRESS',
    purposeCode: 'WORK',
    version: 1,
    scheduledStart: '2026-10-19T13:00:00Z',
    scheduledEnd: '2026-10-19T14:00:00Z',
    fieldActionable: true,
    allowedStatusTransitions: ['ON_SITE'],
    completionAllowed: true,
    addUpdateAllowed: true,
    requestFollowUpAllowed: false,
  },
};
async function records(page: Page) {
  return page.evaluate(async () => {
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const open = indexedDB.open('servora-field-v1', 1);
      open.onsuccess = () => resolve(open.result);
      open.onerror = () => reject(open.error);
    });
    try {
      return await new Promise<
        {
          kind: string;
          state?: string;
          payload?: Record<string, unknown>;
          dependencies?: string[];
          ambiguous?: boolean;
          data?: unknown;
          operation?: string;
        }[]
      >((resolve, reject) => {
        const request = db.transaction('records').objectStore('records').getAll();
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
      });
    } finally {
      db.close();
    }
  });
}
async function mockField(context: BrowserContext) {
  const state = {
    authenticated: false,
    offline: false,
    refused: false,
    lostStatus: false,
    conflict: false,
    detailsVersion: 1,
    sentBodies: [] as Record<string, unknown>[],
    statusCalls: 0,
    noteCalls: 0,
    uploadRefused: false,
    sent: [] as string[],
    changedAccount: false,
  };
  await context.route('**/auth/**', (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/auth/web/sign-out') {
      state.authenticated = false;
      return route.fulfill({ status: 204 });
    }
    if (path === '/auth/web/sign-in') {
      state.authenticated = true;
      return route.fulfill({ json: tokens });
    }
    if (path === '/auth/web/refresh' && state.offline) return route.abort();
    if (path === '/auth/web/refresh')
      return route.fulfill({
        status: state.authenticated ? 200 : 401,
        json: state.authenticated ? tokens : { code: 'UNAUTHENTICATED' },
      });
    if (path === '/auth/me')
      return route.fulfill({
        json: {
          userId: state.changedAccount ? '44444444-4444-4444-8444-444444444444' : scope.userId,
          permissions,
        },
      });
    if (path === '/auth/field-context')
      return state.offline
        ? route.abort()
        : route.fulfill({
            json: state.changedAccount
              ? { ...scope, userId: '44444444-4444-4444-8444-444444444444' }
              : scope,
          });
    return route.abort();
  });
  await context.route('**/home/**', (route) =>
    state.offline ? route.abort() : route.fulfill({ json: fieldHome }),
  );
  await context.route('**/jobs/**', async (route) => {
    if (route.request().isNavigationRequest()) return route.fallback();
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (state.offline) return route.abort();
    if (state.refused) return route.fulfill({ status: 404, json: { code: 'JOB_NOT_FOUND' } });
    if (!request.headers()['x-servora-field-membership'])
      return route.fulfill({ status: 403, json: { code: 'FIELD_SCOPE_INVALID' } });
    if (request.method() === 'GET')
      return route.fulfill({
        json: path.endsWith('/activity')
          ? { jobId: 'job-one', events: [] }
          : path.endsWith('/visit-requests')
            ? []
            : {
                ...details,
                selectedVisit: { ...details.selectedVisit, version: state.detailsVersion },
              },
      });
    state.sent.push(path);
    if (request.headers()['content-type']?.includes('application/json'))
      state.sentBodies.push(request.postDataJSON());
    if (path.endsWith('/status')) {
      state.statusCalls++;
      if (state.conflict) return route.fulfill({ status: 409, json: { code: 'VERSION_CONFLICT' } });
      if (state.lostStatus && state.statusCalls === 1) return route.abort();
    }
    if (path.endsWith('/notes')) state.noteCalls++;
    if (state.uploadRefused && path.endsWith('/photos'))
      return route.fulfill({ status: 415, json: { code: 'UNSUPPORTED_MEDIA' } });
    return route.fulfill({
      status: path.endsWith('/notes') || path.endsWith('/photos') ? 201 : 200,
      json:
        path.endsWith('/status') || path.endsWith('/completion')
          ? details
          : { jobId: 'job-one', events: [] },
    });
  });
  return state;
}
async function signIn(page: Page) {
  await page.goto('/sign-in');
  await page.getByLabel('Email address').fill('field@example.com');
  await page.getByLabel('Password', { exact: true }).fill('test-password');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Your working day' })).toBeVisible();
}
async function openJob(page: Page) {
  await page.goto('/work/jobs/job-one?view=field&visit=visit-one');
  await expect(page.getByLabel('Visit note', { exact: true })).toBeVisible();
}
test('durable offline editor survives reload, submits immutably, and replays once across tabs', async ({
  page,
  context,
}) => {
  const state = await mockField(context);
  await signIn(page);
  await openJob(page);
  state.offline = true;
  await page.getByLabel('Visit note', { exact: true }).fill('Durable field note');
  await expect(page.getByText('Saved on this browser', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Submit update', exact: true }).click();
  await expect
    .poll(async () => (await records(page)).filter((row) => row.kind === 'command').length)
    .toBe(1);
  await expect(page.getByLabel('Visit note', { exact: true })).toHaveValue('');
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(page.getByText(/Offline snapshot from/)).toBeVisible();
  state.offline = false;
  const other = await context.newPage();
  await openJob(other);
  await Promise.all([
    page.getByRole('button', { name: 'Try again', exact: true }).click(),
    other.getByRole('button', { name: 'Try again', exact: true }).click(),
  ]);
  await expect.poll(() => state.noteCalls).toBe(1);
  await expect
    .poll(async () => (await records(page)).find((row) => row.kind === 'command')?.state)
    .toBe('accepted');
  await other.getByRole('button', { name: 'Sign out', exact: true }).click();
  await expect(page.getByLabel('Visit note', { exact: true })).toHaveCount(0);
});
test('response loss keeps status dependencies blocked and requires review of the original result', async ({
  page,
  context,
}) => {
  const state = await mockField(context);
  await signIn(page);
  await openJob(page);
  state.lostStatus = true;
  await page.getByRole('button', { name: 'On site', exact: true }).click();
  await expect.poll(() => state.statusCalls).toBe(1);
  state.detailsVersion = 9;
  await page.getByLabel('Visit note', { exact: true }).fill('Must wait for status reconciliation');
  await page.getByRole('button', { name: 'Submit update', exact: true }).click();
  await expect
    .poll(async () => (await records(page)).find((row) => row.operation === 'status')?.state, {
      timeout: 10000,
    })
    .toBe('attention');
  expect(state.noteCalls).toBe(0);
  await expect(page.getByText(/original resulting version is unknown/)).toBeVisible();
});
test('evidence refusal retains original bytes and blocks completion', async ({ page, context }) => {
  const state = await mockField(context);
  await signIn(page);
  await openJob(page);
  state.uploadRefused = true;
  await page.getByLabel('Select photos').setInputFiles({
    name: 'photo.png',
    mimeType: 'image/png',
    buffer: Buffer.from('89504e470d0a1a0a', 'hex'),
  });
  await page.getByLabel('Visit outcome', { exact: true }).selectOption('RESOLVED');
  await page.getByLabel('Outcome summary (required to complete)').fill('Finished repair');
  await page.getByRole('button', { name: 'Complete visit with outcome' }).click();
  await expect
    .poll(async () => (await records(page)).find((row) => row.operation === 'photo')?.state)
    .toBe('attention');
  expect(state.sent.some((path) => path.endsWith('/completion'))).toBe(false);
  await expect(page.getByText(/server refused this work/)).toBeVisible();
  state.refused = true;
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Furnace repair', exact: true })).toHaveCount(0);
  await expect(page.locator('.queue-entry')).toHaveCount(2);
  await expect(page.getByAltText('Photo in your unsent work')).toBeVisible();
});
test('quota failure keeps editor unsaved and explicit access refusal never serves snapshots', async ({
  page,
  context,
}) => {
  const state = await mockField(context);
  await signIn(page);
  await openJob(page);
  await page.evaluate(() => {
    const original = IDBObjectStore.prototype.put;
    Object.defineProperty(window, 'restoreFieldWrites', {
      value: () => {
        IDBObjectStore.prototype.put = original;
      },
    });
    IDBObjectStore.prototype.put = function (...args: Parameters<IDBObjectStore['put']>) {
      if ((args[0] as { kind?: string }).kind === 'draft')
        throw new DOMException('Quota full', 'QuotaExceededError');
      return original.apply(this, args);
    };
  });
  await page.getByLabel('Visit note', { exact: true }).fill('Keep this editor');
  await expect(page.getByText(/Work could not be saved/).first()).toBeVisible();
  await expect(page.getByLabel('Visit note', { exact: true })).toHaveValue('Keep this editor');
  await page.evaluate(() => Reflect.get(window, 'restoreFieldWrites')());
  state.refused = true;
  await page.getByRole('button', { name: 'Refresh', exact: true }).first().click();
  await expect(page.getByRole('heading', { name: 'Furnace repair' })).toHaveCount(0);
});
test('retained drafts unlock only after online verification of their original account', async ({
  page,
  context,
}) => {
  const state = await mockField(context);
  await signIn(page);
  await openJob(page);
  await page.getByLabel('Visit note', { exact: true }).fill('Original account draft');
  await expect(page.getByText('Saved on this browser', { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByLabel('Visit note', { exact: true })).toHaveValue(
    'Original account draft',
  );
  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  state.changedAccount = true;
  await signIn(page);
  await openJob(page);
  await expect(page.getByLabel('Visit note', { exact: true })).toHaveValue('');
  expect((await records(page)).some((row) => row.kind === 'draft')).toBe(true);
});

test('cold offline reload keeps retained work locked until online context verification', async ({
  page,
  context,
}) => {
  const state = await mockField(context);
  await signIn(page);
  await openJob(page);
  await page.getByLabel('Visit note', { exact: true }).fill('Retained through offline reload');
  await expect(page.getByText('Saved on this browser', { exact: true })).toBeVisible();
  state.offline = true;
  await page.reload();
  await expect(page.getByLabel('Visit note', { exact: true })).toHaveCount(0);
  state.offline = false;
  await page.reload();
  await expect(page.getByLabel('Visit note', { exact: true })).toHaveValue(
    'Retained through offline reload',
  );
});
test('an incompatible database schema pauses storage without deleting retained drafts', async ({
  page,
  context,
}) => {
  await mockField(context);
  await signIn(page);
  await openJob(page);
  await page.getByLabel('Visit note', { exact: true }).fill('Must survive schema mismatch');
  await expect(page.getByText('Saved on this browser', { exact: true })).toBeVisible();
  await page.evaluate(
    () =>
      new Promise<void>((resolve, reject) => {
        const request = indexedDB.open('servora-field-v1', 2);
        request.onsuccess = () => {
          request.result.close();
          resolve();
        };
        request.onerror = () => reject(request.error);
      }),
  );
  await page.reload();
  await expect(page.getByText(/Work could not be saved/).first()).toBeVisible();
  expect(
    await page.evaluate(
      () =>
        new Promise<number>((resolve, reject) => {
          const request = indexedDB.open('servora-field-v1', 2);
          request.onsuccess = () => {
            const db = request.result;
            const rows = db.transaction('records').objectStore('records').getAll();
            rows.onsuccess = () => {
              resolve(rows.result.filter((row: { kind: string }) => row.kind === 'draft').length);
              db.close();
            };
          };
          request.onerror = () => reject(request.error);
        }),
    ),
  ).toBe(1);
});

test('a version conflict requires online review and new intent gets a new operation ID', async ({
  page,
  context,
}) => {
  const state = await mockField(context);
  await signIn(page);
  await openJob(page);
  state.conflict = true;
  await page.getByRole('button', { name: 'On site', exact: true }).click();
  await expect
    .poll(async () => (await records(page)).find((row) => row.operation === 'status')?.state)
    .toBe('attention');
  state.conflict = false;
  state.detailsVersion = 2;
  await page.getByRole('button', { name: 'Review current details', exact: true }).click();
  await expect(page.getByText('Reviewed — original work retained', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: 'On site', exact: true }).click();
  await expect.poll(() => state.statusCalls).toBe(2);
  expect(state.sentBodies[0]?.['clientOperationId']).not.toBe(
    state.sentBodies[1]?.['clientOperationId'],
  );
  expect(state.sentBodies[0]?.['expectedVersion']).toBe(1);
  expect(state.sentBodies[1]?.['expectedVersion']).toBe(2);
});

test('field editors fit phone, tablet and desktop and translate their controls', async ({
  page,
  context,
}) => {
  await mockField(context);
  await signIn(page);
  await openJob(page);
  for (const width of [390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    await expect(page.getByLabel('Visit note', { exact: true })).toBeVisible();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
  }
  await page.getByRole('radio', { name: 'Français', exact: true }).click();
  await expect(page.getByLabel('Note de visite', { exact: true })).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Soumettre la mise à jour', exact: true }),
  ).toBeVisible();
});
