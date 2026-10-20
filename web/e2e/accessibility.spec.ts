import { test, expect } from '@playwright/test';
import { mockAuth } from './fixtures/auth';

for (const language of ['en', 'fr'] as const) {
  test(`${language}: skip link moves focus into sign-in and validation describes invalid fields`, async ({
    page,
  }) => {
    await mockAuth(page);
    await page.goto('/sign-in');
    if (language === 'fr') await page.getByRole('radio', { name: 'Français', exact: true }).click();
    const skip = page.getByRole('link', {
      name: language === 'fr' ? 'Aller au contenu' : 'Skip to content',
    });
    // Return to the start of the document through the actual keyboard order.
    for (let step = 0; step < 15; step++) {
      await page.keyboard.press('Shift+Tab');
      if (await skip.evaluate((element) => element === document.activeElement)) break;
    }
    await expect(skip).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('main')).toBeFocused();
    await expect(page).toHaveURL(/\/sign-in$/);
    await expect(page.getByRole('main')).toHaveCount(1);
    await expect(page).toHaveTitle(`${language === 'fr' ? 'Bienvenue' : 'Welcome back'} | Servora`);
    await page
      .getByRole('button', { name: language === 'fr' ? 'Se connecter' : 'Sign in', exact: true })
      .click();
    for (const input of await page.locator('input').all()) {
      await expect(input).toHaveAttribute('aria-required', 'true');
      await expect(input).toHaveAccessibleDescription(
        language === 'fr' ? 'Ce champ est obligatoire.' : 'This field is required.',
      );
    }
    const email = page.getByLabel(language === 'fr' ? 'Adresse courriel' : 'Email address');
    await email.fill('invalid');
    await email.press('Tab');
    await expect(email).toHaveAttribute('aria-invalid', 'true');
  });
}

test('navigation identifies Home only on Home and moves focus into the new page', async ({
  page,
}) => {
  await mockAuth(page, ['customers.view', 'VISIT_VIEW_ASSIGNED']);
  await page.goto('/sign-in');
  await page.getByLabel('Email address').fill('member@example.com');
  await page.getByLabel('Password', { exact: true }).fill('test-password');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Your working day' })).toBeVisible();
  const home = page
    .getByRole('navigation', { name: 'Main navigation', exact: true })
    .getByRole('link', { name: 'Workspace', exact: true });
  await expect(home).toHaveAttribute('aria-current', 'page');
  const field = page.getByRole('link', { name: 'My work', exact: true });
  await field.click();
  await expect(field).toHaveAttribute('aria-current', 'page');
  await expect(field).toBeFocused();
  await expect(home).toHaveAttribute('aria-current', 'page');
  const work = page
    .getByRole('link')
    .filter({ has: page.getByRole('heading', { name: 'Furnace repair' }) })
    .first();
  await work.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: 'Furnace repair', level: 1 })).toBeVisible();
  await expect(home).not.toHaveAttribute('aria-current', 'page');
  await expect(page).toHaveTitle('Job details | Servora');
  await expect(page.getByRole('main')).toBeFocused();
  await page.getByRole('link', { name: 'Back to Home' }).click();
  await expect(page.getByRole('heading', { name: 'Your working day' })).toBeVisible();
  await expect(page.getByRole('main')).toBeFocused();
  await expect(home).toHaveAttribute('aria-current', 'page');
  await expect(page).toHaveTitle('Your working day | Servora');
});

for (const width of [390, 768, 1440]) {
  test(`skip link works at ${width}px without leaving sign-in`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await mockAuth(page);
    await page.goto('/sign-in');
    await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
    const skip = page.getByRole('link', { name: 'Skip to content' });
    for (let step = 0; step < 20; step++) {
      await page.keyboard.press('Shift+Tab');
      if (await skip.evaluate((element) => element === document.activeElement)) break;
    }
    await expect(page.getByRole('link', { name: 'Skip to content' })).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('main')).toBeFocused();
    await expect(page).toHaveURL(/\/sign-in$/);
    await page.keyboard.press('Tab');
    await expect(page.getByRole('button', { name: 'Email', exact: true })).toBeFocused();
  });
}
