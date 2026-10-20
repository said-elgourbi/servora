import { test, expect, type Page } from '@playwright/test';

function fixtureValue(key: string): string {
  const value = process.env[`SERVORA_CONNECTED_${key}`];
  if (!value) throw new Error(`Missing connected fixture field: ${key}`);
  return value;
}

for (const audience of ['office', 'field'] as const) {
  test(`${audience}: actual API sign-in, Home, details, restoration and sign-out`, async ({
    page,
  }) => {
    const fixture = {
      email: fixtureValue(audience.toUpperCase()),
      password: fixtureValue('PASSWORD'),
      jobId: fixtureValue('JOB_ID'),
      colleagueJobId: fixtureValue('COLLEAGUE_JOB_ID'),
    };
    const officeRequests: string[] = [];
    page.on('request', (request) => {
      if (new URL(request.url()).pathname === '/home/manager') officeRequests.push(request.url());
    });
    await page.goto('/sign-in');
    await page.getByLabel('Email address').fill(fixture.email);
    await page.getByLabel('Password', { exact: true }).fill(fixture.password);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Your working day' })).toBeVisible();
    const home = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname ===
        `/home/${audience === 'office' ? 'manager' : 'technician'}`,
    );
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    expect((await home).status()).toBe(200);
    if (audience === 'field') {
      await expect(page.getByRole('heading', { name: 'Up next' })).toBeVisible();
      await expect(page.getByText('Colleague private job', { exact: true })).toHaveCount(0);
    }
    await page.locator(`a[href*="/work/jobs/${fixture.jobId}"]`).first().click();
    await expect(
      page.getByRole('heading', { name: 'Connected furnace repair', level: 1, exact: true }),
    ).toBeVisible();
    await expect(page.getByRole('article', { name: 'Visit 1', exact: true })).toHaveClass(
      /selected/,
    );
    await page.reload();
    await expect(
      page.getByRole('heading', { name: 'Connected furnace repair', level: 1, exact: true }),
    ).toBeVisible();
    if (audience === 'field') {
      await expect(page.getByLabel('Visit note', { exact: true })).toBeVisible();
      await page.getByLabel('Visit note', { exact: true }).fill('Connected browser field note');
      await expect(page.getByText('Saved on this browser', { exact: true })).toBeVisible();
      await page.getByRole('button', { name: 'Submit update', exact: true }).click();
      await expect(page.locator('.queue-entry').filter({ hasText: 'Accepted' })).toBeVisible();
      await refreshField(page, fixture.jobId);
      await expect(
        page.getByText('Connected browser field note', { exact: true }).last(),
      ).toBeVisible();
      await expect(page.getByLabel('Select photos')).toBeEnabled();
      await page.getByLabel('Select photos').setInputFiles({
        name: 'evidence.png',
        mimeType: 'image/png',
        buffer: Buffer.from(
          'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=',
          'base64',
        ),
      });
      await expect(page.locator('.attachments article')).toHaveCount(1);
      await expect(page.getByLabel('Select audio')).toBeEnabled();
      await page.getByLabel('Select audio').setInputFiles('e2e/fixtures/field-audio.m4a');
      await expect(page.locator('.attachments article')).toHaveCount(2);
      await page.getByLabel('Visit outcome', { exact: true }).selectOption('NEEDS_FOLLOW_UP');
      await page
        .getByLabel('Outcome summary (required to complete)')
        .fill('Connected outcome with evidence');
      const completionResponse = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          new URL(response.url()).pathname.endsWith('/completion'),
        { timeout: 20000 },
      );
      await page.getByRole('button', { name: 'Complete visit with outcome' }).click();
      const completed = await completionResponse;
      if (completed.status() !== 200) {
        const failure = await completed.json();
        expect({
          status: completed.status(),
          code: failure.code,
          message: failure.message,
        }).toEqual({ status: 200 });
      }
      await expect(page.locator('.queue-entry').filter({ hasText: 'Accepted' })).toHaveCount(4, {
        timeout: 15000,
      });
      await refreshField(page, fixture.jobId);
      await expect(
        page.getByText('Connected outcome with evidence', { exact: true }).last(),
      ).toBeVisible();
      await page.getByRole('button', { name: 'View photo', exact: true }).click();
      await expect(page.getByRole('img', { name: 'Accepted visit evidence' })).toBeVisible();
      await page.getByRole('button', { name: /Play audio/ }).click();
      await expect
        .poll(() =>
          page
            .locator('audio')
            .last()
            .evaluate((audio: HTMLAudioElement) => audio.duration),
        )
        .toBeGreaterThan(1);
      await page.getByLabel('Work start', { exact: true }).fill('2026-10-20T13:00');
      await page.getByLabel('Work end', { exact: true }).fill('2026-10-20T14:00');
      await page.getByLabel('Reason', { exact: true }).fill('Connected follow-up');
      const followResponse = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          new URL(response.url()).pathname.endsWith('/visit-requests'),
      );
      await page.getByRole('button', { name: 'Send request', exact: true }).click();
      const submitted = await followResponse;
      expect(submitted.status()).toBe(201);
      const followRequest: { id: string; version: number } = await submitted.json();
      const officeSignIn = await page.request.post('/auth/sign-in', {
        data: {
          email: fixtureValue('OFFICE'),
          password: fixture.password,
          device: { platform: 'ANDROID', deviceId: 'connected-follow-up-review' },
        },
      });
      expect(officeSignIn.status()).toBe(200);
      const officeTokens: { accessToken: string } = await officeSignIn.json();
      const clarified = await page.request.post(
        `/jobs/${fixture.jobId}/visit-requests/${followRequest.id}/clarification`,
        {
          headers: { Authorization: `Bearer ${officeTokens.accessToken}` },
          data: {
            note: 'Clarify the return visit',
            expectedStatus: 'PENDING',
            expectedVersion: followRequest.version,
          },
        },
      );
      expect(clarified.status()).toBe(200);
      await refreshField(page, fixture.jobId);
      await page
        .getByLabel('Clarification reply', { exact: true })
        .fill('Bring the replacement part');
      const replyResponse = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          new URL(response.url()).pathname.endsWith('/reply'),
      );
      await page.getByRole('button', { name: 'Send reply', exact: true }).click();
      expect((await replyResponse).status()).toBe(200);
      await page.goto('/work/report');
      await page.getByLabel('Customer name as reported').fill('Unscheduled customer');
      await page.getByLabel('Customer phone as reported').fill('+15145551234');
      await page.getByLabel('Work address as reported').fill('24 Field Road');
      await page.getByLabel('Work start', { exact: true }).fill('2026-10-18T13:00');
      await page.getByLabel('Work end', { exact: true }).fill('2026-10-18T14:00');
      await page.getByLabel('Visit outcome', { exact: true }).selectOption('RESOLVED');
      await page.getByLabel('Work summary', { exact: true }).fill('Connected ad-hoc report');
      await page.getByRole('button', { name: 'Submit work report', exact: true }).click();
      await expect(page.getByText('Accepted', { exact: true })).toBeVisible();
      await page.goto(`/work/jobs/${fixture.colleagueJobId}`);
      await expect(page.getByRole('alert')).toContainText('no longer assigned');
      await expect(page.getByText('Colleague private job', { exact: true })).toHaveCount(0);
    }
    await page.getByRole('link', { name: 'Back to Home' }).click();
    await expect(page.getByRole('heading', { name: 'Your working day' })).toBeVisible();
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
    if (audience === 'field') expect(officeRequests).toEqual([]);
  });
}

async function refreshField(page: Page, jobId: string): Promise<void> {
  const activity = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === `/jobs/${jobId}/activity` &&
      response.status() === 200,
  );
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await activity;
  await expect(page.getByRole('button', { name: 'Refresh', exact: true })).toBeEnabled();
}
