import { test, expect } from '@playwright/test';
import { mockAuth } from './fixtures/auth';
import { job } from './fixtures/workspace';
test('EN/FR, appearance persistence, keyboard access, and responsive sign-in', async ({
  page,
}, testInfo) => {
  await mockAuth(page);
  await page.goto('/sign-in');
  await page.getByRole('radio', { name: 'Français', exact: true }).click();
  await page.getByRole('radio', { name: 'Sombre', exact: true }).click();
  await expect(page.locator('html')).toHaveAttribute('lang', 'fr');
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Bienvenue' })).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  await page.screenshot({ path: testInfo.outputPath('sign-in.png'), fullPage: true });
  await page.keyboard.press('Tab');
  await expect(page.getByRole('link', { name: 'Aller au contenu' })).toBeFocused();
  await page.getByRole('radio', { name: 'English', exact: true }).click();
  await page.getByRole('radio', { name: 'Light', exact: true }).click();
  await page.getByRole('button', { name: 'Show password' }).click();
  await expect(page.getByLabel('Password', { exact: true })).toHaveAttribute('type', 'text');
  await page.getByRole('button', { name: 'Hide password' }).click();
  await expect(page.getByLabel('Password', { exact: true })).toHaveAttribute('type', 'password');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByText('This field is required.').first()).toBeVisible();
});

test('workspace and job details fit phone, tablet and desktop in light and dark themes', async ({
  page,
  viewport,
}, testInfo) => {
  const compact = (viewport?.width ?? 1280) <= 900;
  await mockAuth(page, ['customers.view', 'VISIT_VIEW_ASSIGNED']);
  const title = 'Replace entrance HVAC thermostat — Cedar House';
  await page.route(/^http:\/\/[^/]+\/jobs\/[^/]+$/, (route) =>
    route.fulfill({
      json: {
        ...job,
        title,
        description:
          'The entrance thermostat stopped responding. Diagnose and replace the unit, and confirm that the building temperature controls are working before leaving.',
        customerContactDetails: {
          ...job.customerContactDetails,
          notes:
            'Please coordinate access with the property manager. Parking is available at the rear entrance.',
          contacts: [
            ...job.customerContactDetails.contacts,
            {
              firstName: 'Morgan',
              lastName: 'Campbell',
              phone: null,
              email: 'building.operations@cedar-properties.example.com',
              isPrimary: false,
            },
          ],
        },
      },
    }),
  );
  await page.goto('/sign-in');
  await page.getByLabel('Email address').fill('member@example.com');
  await page.getByLabel('Password', { exact: true }).fill('test-password');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Your working day' })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('home-light.png'), fullPage: true });
  if (compact) await page.locator('.mobile-menu summary').click();
  await page.getByRole('radio', { name: 'Français', exact: true }).click();
  await page.getByRole('radio', { name: 'Sombre', exact: true }).click();
  if (compact) await page.locator('.mobile-menu summary').click();
  await expect(page.getByRole('heading', { name: 'Votre journée de travail' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath('home.png'), fullPage: true });
  await page
    .getByRole('link')
    .filter({ has: page.getByRole('heading', { name: 'Furnace repair' }) })
    .click();
  await expect(page.getByRole('heading', { name: title })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath('job.png'), fullPage: true });
  if (compact) await page.locator('.mobile-menu summary').click();
  await page.getByRole('radio', { name: 'English', exact: true }).click();
  await page.getByRole('radio', { name: 'Light', exact: true }).click();
  if (compact) await page.locator('.mobile-menu summary').click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await expect(page.getByRole('heading', { name: 'Customer contact' })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('job-light.png'), fullPage: true });
});

test('keyboard reaches account controls and skips the shell at every viewport', async ({
  page,
  viewport,
}) => {
  const compact = (viewport?.width ?? 1280) <= 900;
  await mockAuth(page);
  await page.goto('/sign-in');
  await page.getByLabel('Email address').fill('member@example.com');
  await page.getByLabel('Password', { exact: true }).fill('test-password');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Your working day' })).toBeVisible();
  const skip = page.getByRole('link', { name: 'Skip to content' });
  for (let step = 0; step < 30; step++) {
    await page.keyboard.press('Shift+Tab');
    if (await skip.evaluate((element) => element === document.activeElement)) break;
  }
  await expect(skip).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('main')).toBeFocused();
  const account = page.locator('.mobile-menu summary');
  const language = page.getByRole('radio', { name: 'English', exact: true });
  const target = compact ? account : language;
  for (let step = 0; step < 30; step++) {
    await page.keyboard.press('Shift+Tab');
    if (await target.evaluate((element) => element === document.activeElement)) break;
  }
  await expect(target).toBeFocused();
  if (compact) {
    await page.keyboard.press('Enter');
    await expect(page.locator('.mobile-menu')).toHaveAttribute('open', '');
    await page.keyboard.press('Tab');
    await expect(page.getByRole('link', { name: 'Customers', exact: true })).toBeFocused();
    await page.keyboard.press('Tab');
  }
  await expect(language).toBeFocused();
  // Segmented controls support arrow navigation without using a pointer.
  await page.keyboard.press('ArrowDown');
  await expect(page.locator('html')).toHaveAttribute('lang', 'fr');
  await expect(page.getByRole('heading', { name: 'Votre journée de travail' })).toBeVisible();
  await page.keyboard.press('Tab');
  await expect(page.getByRole('radio', { name: 'Système', exact: true })).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(page.getByRole('button', { name: 'Se déconnecter', exact: true })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: 'Bienvenue' })).toBeVisible();
});
