import { test, expect } from '@playwright/test';
for (const width of [390, 1440]) {
  test(`actual customer → property → Job lifecycle and assigned-only denial at ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 900 });
    const office = process.env['SERVORA_CONNECTED_OFFICE'],
      field = process.env['SERVORA_CONNECTED_FIELD'],
      password = process.env['SERVORA_CONNECTED_PASSWORD'];
    if (!office || !field || !password) throw new Error('Missing connected property fixture');
    await page.goto('/sign-in');
    await page.getByLabel('Email address').fill(office);
    await page.getByLabel('Password', { exact: true }).fill(password);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Your working day' })).toBeVisible();
    if (width < 900) await page.locator('summary').click();
    await page.getByRole('link', { name: 'Customers', exact: true }).click();
    if (width < 900) await page.locator('summary').click();
    await page.getByRole('link', { name: 'New customer', exact: true }).click();
    await page.getByLabel('Display name').fill('Connected property owner');
    await page.getByLabel('First name', { exact: true }).fill('Camille');
    await page.getByLabel('Last name', { exact: true }).fill('Roy');
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(
      page.getByRole('heading', { name: 'Connected property owner', exact: true }),
    ).toBeVisible();
    const customerPath = new URL(page.url()).pathname;
    await page.getByRole('link', { name: 'Add property', exact: true }).click();
    await page.getByLabel('Property name (optional)').fill('Connected service location');
    await page.getByLabel('Street address', { exact: true }).fill('12 Test Lane');
    await page.getByLabel('City', { exact: true }).fill('Montreal');
    await page.getByLabel('Province or territory', { exact: true }).selectOption('QC');
    await page.getByLabel('Postal code', { exact: true }).fill('H3A 2T6');
    await page.getByRole('button', { name: 'Save property', exact: true }).click();
    await expect(
      page.getByRole('heading', { name: 'Connected service location', exact: true }),
    ).toBeVisible();
    const propertyPath = new URL(page.url()).pathname;
    await expect(page.getByText('Never serviced', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Permanently delete property' })).toHaveCount(0);
    await page.getByRole('button', { name: 'Archive property', exact: true }).click();
    await expect(page.getByRole('dialog')).toContainText('0 active jobs · 0 active visits');
    await page.getByRole('button', { name: 'Confirm', exact: true }).click();
    await page.getByRole('link', { name: 'Edit property', exact: true }).click();
    await expect(page.getByText(/Editing it does not restore it/)).toBeVisible();
    await page.getByLabel('Street address', { exact: true }).fill('14 Corrected Lane');
    await page.getByRole('button', { name: 'Save property', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Restore property', exact: true })).toBeVisible();
    await page.reload();
    await expect(page.getByText(/14 Corrected Lane/)).toBeVisible();
    await page.getByRole('link', { name: 'Back to customer' }).click();
    await expect(page.getByLabel('Property status', { exact: true })).toHaveValue('ARCHIVED');
    await expect(
      page.getByRole('link', { name: 'Connected service location', exact: true }),
    ).toBeVisible();
    await page.getByLabel('Property status', { exact: true }).selectOption('ACTIVE');
    await expect(page.getByText('No active properties for this customer.')).toBeVisible();
    await page.getByLabel('Property status', { exact: true }).selectOption('ALL');
    await page.getByRole('link', { name: 'Connected service location', exact: true }).click();
    await page.getByRole('button', { name: 'Restore property', exact: true }).click();
    await page.getByRole('button', { name: 'Confirm', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Archive property', exact: true })).toBeVisible();
    await page.goto(customerPath);
    await expect(
      page.getByRole('link', { name: 'Connected service location', exact: true }),
    ).toBeVisible();
    await page.getByRole('link', { name: 'New job', exact: true }).click();
    await page
      .getByLabel('Service property', { exact: true })
      .selectOption({ label: 'Connected service location · 14 Corrected Lane, Montreal' });
    await page.getByLabel('Job title', { exact: true }).fill('Connected new heating job');
    await page
      .getByLabel('Description (optional)', { exact: true })
      .fill('Real customer to property to job');
    const created = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === '/jobs' && response.request().method() === 'POST',
    );
    await page.getByRole('button', { name: 'Create job', exact: true }).click();
    expect((await created).status()).toBe(201);
    await expect(
      page.getByRole('heading', { name: 'Connected new heating job', exact: true }),
    ).toBeVisible();
    const jobPath = new URL(page.url()).pathname;
    await expect(page.getByText('No visits have been created.')).toBeVisible();
    await page.getByRole('button', { name: 'Completed', exact: true }).click();
    await page.getByLabel('Explanation (optional)').fill('Closed without a visit');
    await page.getByRole('button', { name: 'Confirm', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Reopen job', exact: true })).toBeVisible();
    await page.reload();
    await expect(page.getByRole('button', { name: 'Reopen job', exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Reopen job', exact: true }).click();
    await page.getByRole('button', { name: 'Confirm', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Cancel job', exact: true })).toBeVisible();
    await page.goto(customerPath);
    await expect(page.getByRole('link', { name: /Connected new heating job/ })).toBeVisible();
    await page.getByLabel('Find a job by number or text').fill('missing title');
    await expect(page.getByRole('link', { name: /Connected new heating job/ })).toHaveCount(0);
    await page.getByLabel('Find a job by number or text').fill('heating');
    await page.getByRole('link', { name: /Connected new heating job/ }).click();
    await page.getByRole('button', { name: 'Cancel job', exact: true }).click();
    await page.getByLabel('Cancellation explanation').fill('Customer no longer needs the work');
    await page.getByRole('button', { name: 'Confirm', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Reopen job', exact: true })).toBeVisible();
    if (width < 900) await page.locator('summary').click();
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await page.getByLabel('Email address').fill(field);
    await page.getByLabel('Password', { exact: true }).fill(password);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Up next' })).toBeVisible();
    const reads: string[] = [];
    page.on('request', (request) => {
      if (/^\/customers\/.+\/properties/.test(new URL(request.url()).pathname))
        reads.push(request.url());
    });
    await page.goto(propertyPath);
    await expect(page.getByRole('alert')).toContainText('does not have permission');
    expect(reads).toEqual([]);
    await page.goto(jobPath + '?view=office');
    await expect(
      page.getByRole('heading', { name: 'Connected new heating job', exact: true }),
    ).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'Change job status' })).toHaveCount(0);
    const denied = await page.evaluate(async (jobPath) => {
      const response = await fetch('/auth/web/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Servora-Web': '1' },
        body: '{}',
      });
      if (!response.ok) throw new Error('Could not authenticate denied-action probe');
      const tokens = (await response.json()) as { accessToken: string };
      const status = await fetch('/jobs/' + jobPath.split('/').pop() + '/status', {
        method: 'PATCH',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${tokens.accessToken}`,
        },
        body: JSON.stringify({ status: 'ACTIVE' }),
      });
      return status.status;
    }, jobPath);
    expect(denied).toBe(403);
  });
}
