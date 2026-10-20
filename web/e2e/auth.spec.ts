import { test, expect } from '@playwright/test';
import { mockAuth } from './fixtures/auth';
test.beforeEach(async ({ page }) => {
  await mockAuth(page);
});
test('password sign-in, reload restoration, and sign-out', async ({ page }) => {
  await page.goto('/');
  await page.getByLabel('Email address').fill('office@example.com');
  await page.getByLabel('Password', { exact: true }).fill(' preserve spaces ');
  const request = page.waitForRequest('**/auth/web/sign-in');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  expect((await request).postDataJSON()).toMatchObject({
    password: ' preserve spaces ',
    device: { platform: 'WEB' },
  });
  await expect(page.getByRole('heading', { name: 'Your working day' })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Your working day' })).toBeVisible();
  expect(await page.evaluate(() => JSON.stringify(localStorage))).not.toContain('test-access');
  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
});
test('SMS request, leading-zero verification code, cooldown and number correction', async ({
  page,
}) => {
  await page.goto('/sign-in');
  await page.getByRole('button', { name: 'Phone', exact: true }).click();
  await page.getByLabel('Phone number').fill('+15145551234');
  await page.getByRole('button', { name: 'Send verification code' }).click();
  await expect(
    page.getByText('If this number is eligible, a verification code will be sent.'),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: 'Resend code' })).toBeDisabled();
  await page.getByLabel('Verification code').fill('001234');
  const request = page.waitForRequest('**/auth/web/sms/verify');
  await page.getByRole('button', { name: 'Verify and sign in' }).click();
  expect((await request).postDataJSON().code).toBe('001234');
  await expect(page.getByRole('heading', { name: 'Your working day' })).toBeVisible();
});
test('failed sign-in stays generic and rate limits are localized', async ({ page }) => {
  await page.route('**/auth/web/sign-in', (route) =>
    route.fulfill({ status: 401, json: { code: 'INVALID_CREDENTIALS' } }),
  );
  await page.goto('/sign-in');
  await page.getByLabel('Email address').fill('unknown@example.com');
  await page.getByLabel('Password', { exact: true }).fill('incorrect');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText('The email address or password is incorrect.');
  await page.getByRole('radio', { name: 'Français', exact: true }).click();
  await page.route('**/auth/web/sign-in', (route) =>
    route.fulfill({ status: 429, json: { code: 'TOO_MANY_REQUESTS' } }),
  );
  await page.getByRole('button', { name: 'Se connecter', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText(
    'Trop de tentatives. Patientez avant de réessayer.',
  );
});
test('external return URL stays within the workspace', async ({ page }) => {
  await page.goto('/sign-in?returnUrl=https://evil.example');
  await page.getByLabel('Email address').fill('office@example.com');
  await page.getByLabel('Password', { exact: true }).fill('secret');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page).toHaveURL('/');
});
test('sign-out clears a second tab', async ({ page, context }) => {
  await page.goto('/sign-in');
  await page.getByLabel('Email address').fill('office@example.com');
  await page.getByLabel('Password', { exact: true }).fill('secret');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Your working day' })).toBeVisible();
  const second = await context.newPage();
  await mockAuth(second);
  await second.route('**/auth/web/refresh', (route) =>
    route.fulfill({
      json: {
        accessToken: 'second-access',
        accessTokenExpiresAt: '2099-01-01T00:00:00Z',
        sessionId: 'test-session',
      },
    }),
  );
  await second.goto('/');
  await expect(second.getByRole('heading', { name: 'Your working day' })).toBeVisible();
  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await expect(second.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
});
test('protected deep link preserves the local return URL', async ({ page }) => {
  await page.goto('/forbidden');
  await expect(page).toHaveURL(/sign-in\?returnUrl=/);
  await page.getByLabel('Email address').fill('office@example.com');
  await page.getByLabel('Password', { exact: true }).fill('secret');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page).toHaveURL('/forbidden');
  await expect(page.getByRole('heading', { name: 'Access unavailable' })).toBeVisible();
});
test('changing the phone number clears its verification code', async ({ page }) => {
  await page.goto('/sign-in');
  await page.getByRole('button', { name: 'Phone', exact: true }).click();
  await page.getByLabel('Phone number').fill('+15145551234');
  await page.getByRole('button', { name: 'Send verification code' }).click();
  await page.getByLabel('Verification code').fill('001234');
  await page.getByRole('button', { name: 'Change number' }).click();
  await page.getByLabel('Phone number').fill('+15145554321');
  await page.getByRole('button', { name: 'Send verification code' }).click();
  await expect(page.getByLabel('Verification code')).toHaveValue('');
});
