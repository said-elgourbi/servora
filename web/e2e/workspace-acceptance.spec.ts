import { test, expect, type Locator, type Page } from '@playwright/test';
import { mockAuth } from './fixtures/auth';
import { fieldHome, job } from './fixtures/workspace';

async function signIn(page: Page) {
  await page.goto('/sign-in');
  await page.getByLabel('Email address').fill('member@example.com');
  await page.getByLabel('Password', { exact: true }).fill('test-password');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Your working day' })).toBeVisible();
}

// Reach controls through the real tab order; never move focus using DOM APIs.
async function tabTo(page: Page, target: Locator) {
  // Browser chrome does not wrap focus consistently in headless Firefox.
  for (const key of ['Tab', 'Shift+Tab']) {
    for (let step = 0; step < 40; step++) {
      if (await target.evaluate((element) => element === document.activeElement)) return;
      await page.keyboard.press(key);
    }
  }
  await expect(target).toBeFocused();
}

test('keyboard can skip navigation, switch Home, open work, return and sign out', async ({
  page,
}) => {
  await mockAuth(page, ['customers.view', 'VISIT_VIEW_ASSIGNED']);
  await signIn(page);
  await tabTo(page, page.getByRole('link', { name: 'Skip to content' }));
  await page.keyboard.press('Enter');
  await expect(page.getByRole('main')).toBeFocused();
  await tabTo(page, page.getByRole('link', { name: 'My work', exact: true }));
  await page.keyboard.press('Enter');
  await expect(page.getByRole('link', { name: 'My work', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  );
  const work = page
    .getByRole('link')
    .filter({ has: page.getByRole('heading', { name: 'Furnace repair' }) })
    .first();
  await tabTo(page, work);
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: 'Furnace repair', level: 1 })).toBeVisible();
  await tabTo(page, page.getByRole('link', { name: 'Back to Home' }));
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: 'Up next' })).toBeVisible();
  await tabTo(page, page.getByRole('button', { name: 'Sign out', exact: true }));
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
});

test('an account without Home capabilities makes no business request', async ({ page }) => {
  await mockAuth(page, []);
  const requests: string[] = [];
  page.on('request', (request) => {
    if (/\/(home|jobs)\//.test(new URL(request.url()).pathname)) requests.push(request.url());
  });
  await signIn(page);
  await expect(page.getByText('No permissions have been granted to this account.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Refresh' })).toHaveCount(0);
  expect(requests).toEqual([]);
});

test('field empty states translate without inventing work or attention', async ({ page }) => {
  await mockAuth(page, ['VISIT_VIEW_ASSIGNED']);
  await page.route('**/home/technician**', (route) =>
    route.fulfill({
      json: {
        ...fieldHome,
        nextVisit: null,
        visits: [],
        upcoming: [],
        upcomingTotal: 0,
      },
    }),
  );
  await signIn(page);
  await expect(page.getByText('No assigned work left to do.')).toBeVisible();
  await expect(page.getByText('No visits today.')).toBeVisible();
  await expect(page.getByText('No upcoming visits.')).toBeVisible();
  await expect(page.getByRole('heading', { name: /Needs attention/ })).toHaveCount(0);
  await page.getByRole('radio', { name: 'Français', exact: true }).click();
  await expect(page.getByText('Aucun travail assigné restant.')).toBeVisible();
  await expect(page.getByText('Aucune visite aujourd’hui.')).toBeVisible();
  await expect(page.getByText('Aucune visite à venir.')).toBeVisible();
});

for (const failure of ['network', 'server', 'forbidden'] as const) {
  test(`Home ${failure} failure clears prior work, announces the error and recovers`, async ({
    page,
  }) => {
    await mockAuth(page);
    await signIn(page);
    let release!: () => void;
    const pending = new Promise<void>((resolve) => {
      release = resolve;
    });
    await page.route('**/home/manager**', async (route) => {
      await pending;
      if (failure === 'network') await route.abort('failed');
      else await route.fulfill({ status: failure === 'forbidden' ? 403 : 500, json: {} });
    });
    try {
      await page.getByRole('button', { name: 'Refresh' }).click();
      await expect(page.getByRole('status')).toHaveText('Please wait…');
      await expect(page.getByRole('button', { name: 'Refresh' })).toBeDisabled();
      await expect(page.getByText('Furnace repair')).toHaveCount(0);
    } finally {
      release();
    }
    await expect(page.getByRole('alert')).toContainText(
      failure === 'network'
        ? 'Unable to connect'
        : failure === 'forbidden'
          ? 'does not have permission'
          : 'Something went wrong',
    );
    await expect(page.getByRole('status')).toHaveCount(0);
    await page.unroute('**/home/manager**');
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(page.getByRole('heading', { name: 'Furnace repair' })).toBeVisible();
    await expect(page.getByRole('alert')).toHaveCount(0);
  });
}

test('detail failure removes contacts and work, retries, and translates empty details', async ({
  page,
}) => {
  await mockAuth(page, ['VISIT_VIEW_ASSIGNED']);
  await signIn(page);
  await page.goto('/work/jobs/job-one?visit=visit-one&view=field');
  await expect(page.getByRole('heading', { name: 'Customer contact' })).toBeVisible();
  await page.route(/^http:\/\/[^/]+\/jobs\/[^/]+$/, (route) => route.abort('failed'));
  await page.getByRole('button', { name: 'Refresh' }).click();
  await expect(page.getByRole('alert')).toContainText('Unable to connect');
  await expect(page.getByRole('link', { name: /Call ·/ })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Furnace repair' })).toHaveCount(0);
  await page.route(/^http:\/\/[^/]+\/jobs\/[^/]+$/, (route) =>
    route.fulfill({
      json: {
        ...job,
        visits: [],
        address: null,
        customerContactDetails: null,
      },
    }),
  );
  await page.getByRole('button', { name: 'Try again' }).click();
  await expect(page.getByText('No visits have been created.')).toBeVisible();
  await expect(page.getByText('No address provided')).toBeVisible();
  await page.getByRole('radio', { name: 'Français', exact: true }).click();
  await expect(page.getByText('Aucune visite créée.')).toBeVisible();
  await expect(page.getByText('Aucune adresse fournie')).toBeVisible();
});

test('system appearance follows the browser while an explicit choice survives reload', async ({
  page,
}) => {
  await mockAuth(page);
  await page.emulateMedia({ colorScheme: 'dark', reducedMotion: 'reduce' });
  await signIn(page);
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await page.emulateMedia({ colorScheme: 'light' });
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  await page.getByRole('radio', { name: 'Dark', exact: true }).click();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Your working day' })).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await page.getByRole('radio', { name: 'System', exact: true }).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
});
