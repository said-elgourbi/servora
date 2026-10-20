import { test, expect, type Page } from '@playwright/test';
import { mockAuth } from './fixtures/auth';
import { dispatch } from './fixtures/dispatch';
async function open(page: Page) {
  await mockAuth(page, ['customers.view', 'schedule.view_org']);
  await page.route('**/schedule?**', (route) => route.fulfill({ json: dispatch }));
  await page.goto('/sign-in');
  await page.getByLabel('Email address').fill('member@example.com');
  await page.getByLabel('Password', { exact: true }).fill('test-password');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Your working day' })).toBeVisible();
  await page.goto('/work/schedule');
  await expect(page.getByRole('heading', { name: 'Office schedule' })).toBeVisible();
}
test('desktop grid places shared and overlapping visits, list preserves unique rows and keyboard navigation', async ({
  page,
}) => {
  test.skip(
    (page.viewportSize()?.width ?? 0) < 1100,
    'Time grid is a desktop interaction; compact agenda is validated separately.',
  );
  await open(page);
  const board = page.getByRole('region', { name: /Technician schedule/ });
  await expect(board).toBeVisible();
  const shared = page.locator('.appointment[data-visit="1"]');
  await expect(shared).toHaveCount(2);
  await expect(shared.first()).toContainText('Shared visit');
  const geometry = await shared.first().evaluate((el) => ({
    top: parseFloat((el as HTMLElement).style.top),
    height: parseFloat((el as HTMLElement).style.height),
    width: (el as HTMLElement).style.width,
  }));
  expect(geometry.top).toBeCloseTo(72);
  expect(geometry.height).toBeCloseTo(108);
  expect(geometry.width).toBe('50%');
  await expect(page.locator('.appointment[data-visit="2"]')).toHaveClass(/overlap/);
  await expect(page.locator('.column-heading').filter({ hasText: 'Jo Martin' })).toContainText(
    '0 visits',
  );
  await shared.first().focus();
  await expect(shared.first()).toBeFocused();
  await expect(page.locator('.appointment[data-visit="1"].related')).toHaveCount(2);
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/work\/jobs\/job-one\?visit=1&view=office/);
  await page.goto('/work/schedule');
  await page.getByRole('button', { name: 'List', exact: true }).click();
  await expect(page.getByRole('table')).toBeVisible();
  await expect(page.locator('tbody tr')).toHaveCount(6);
  await expect(page.locator('tbody tr').first()).toContainText('Furnace repair');
  await expect(page.locator('tbody tr').first()).toContainText('Sarah Chen');
  await page.getByRole('button', { name: 'Unassigned (3)', exact: true }).click();
  await expect(page.locator('tbody tr')).toHaveCount(2);
  await expect(page.getByText('Not scheduled', { exact: true })).toBeVisible();
  await expect(page.getByText(/Showing the first visits/)).toBeVisible();
});
for (const theme of ['light', 'dark'] as const) {
  test(`dispatch ${theme} theme with English and French layouts`, async ({ page }, testInfo) => {
    await page.addInitScript((value) => localStorage.setItem('servora.appearance', value), theme);
    await open(page);
    const compact = (page.viewportSize()?.width ?? 0) <= 900;
    if (compact) await page.locator('.mobile-menu summary').click();
    await page
      .getByRole('radio', { name: theme === 'dark' ? 'Dark' : 'Light', exact: true })
      .click();
    if (compact) await page.locator('.mobile-menu summary').click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
    if ((page.viewportSize()?.width ?? 0) < 1100) {
      await expect(page.locator('app-dispatch-grid')).toHaveCount(0);
      await expect(page.locator('.agenda-row')).toHaveCount(6);
    } else await expect(page.locator('app-dispatch-grid')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({
      path: testInfo.outputPath(`dispatch-${theme}-en.png`),
      fullPage: true,
    });
    if ((page.viewportSize()?.width ?? 0) >= 1100) {
      await page.getByRole('button', { name: 'List', exact: true }).click();
      await expect(page.locator('tbody tr')).toHaveCount(6);
      await page.screenshot({
        path: testInfo.outputPath(`dispatch-list-${theme}-en.png`),
        fullPage: true,
      });
      await page.getByRole('button', { name: 'Schedule', exact: true }).click();
    }
    if (compact) await page.locator('.mobile-menu summary').click();
    await page.getByRole('radio', { name: 'Français', exact: true }).click();
    if (compact) await page.locator('.mobile-menu summary').click();
    await expect(page.getByRole('heading', { name: 'Planning du bureau' })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({
      path: testInfo.outputPath(`dispatch-${theme}-fr.png`),
      fullPage: true,
    });
    if ((page.viewportSize()?.width ?? 0) >= 1100) {
      await page.getByRole('button', { name: 'Liste', exact: true }).click();
      await expect(page.getByRole('columnheader', { name: 'État', exact: true })).toBeVisible();
      await page.screenshot({
        path: testInfo.outputPath(`dispatch-list-${theme}-fr.png`),
        fullPage: true,
      });
    }
  });
}
test('overnight and midnight appointments retain true times and continuation', async ({ page }) => {
  test.skip((page.viewportSize()?.width ?? 0) < 1100, 'Geometry is desktop-only.');
  await open(page);
  await page.route('**/schedule?**', (route) =>
    route.fulfill({
      json: {
        ...dispatch,
        visits: [
          {
            ...dispatch.visits[0],
            visitId: 'midnight',
            scheduledStart: '2026-10-20T04:00:00Z',
            scheduledEnd: '2026-10-20T04:30:00Z',
          },
          {
            ...dispatch.visits[1],
            visitId: 'night',
            scheduledStart: '2026-10-21T03:00:00Z',
            scheduledEnd: '2026-10-21T06:00:00Z',
          },
        ],
      },
    }),
  );
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  const early = page.locator('.appointment[data-visit="midnight"]').first();
  expect(await early.evaluate((el) => parseFloat((el as HTMLElement).style.top))).toBe(0);
  const night = page.locator('.appointment[data-visit="night"]');
  await expect(night.getByLabel('Continues next day')).toBeVisible();
  await expect(night).toHaveAttribute('aria-label', /Oct 21.*02:00/);
  expect(await night.evaluate((el) => parseFloat((el as HTMLElement).style.height))).toBe(72);
  await page.route('**/schedule?**', (route) =>
    route.fulfill({
      json: {
        ...dispatch,
        day: {
          localDate: '2026-11-01',
          timeZone: 'America/Toronto',
          start: '2026-11-01T04:00:00Z',
          end: '2026-11-02T05:00:00Z',
        },
        visits: [
          {
            ...dispatch.visits[0],
            visitId: 'repeated-hour',
            scheduledStart: '2026-11-01T05:30:00Z',
            scheduledEnd: '2026-11-01T06:30:00Z',
          },
        ],
      },
    }),
  );
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  const repeated = page.locator('.appointment[data-visit="repeated-hour"]').first();
  await expect(repeated.locator('.event-time')).toContainText('GMT-4');
  await expect(repeated.locator('.event-time')).toContainText('GMT-5');
  expect(await repeated.evaluate((el) => parseFloat((el as HTMLElement).style.height))).toBe(72);
});

test('wide crews scroll within the grid and brief appointments retain readable details', async ({
  page,
}) => {
  test.skip((page.viewportSize()?.width ?? 0) < 1100, 'Grid scrolling applies to desktop.');
  await open(page);
  await page.route('**/schedule?**', (route) =>
    route.fulfill({
      json: {
        ...dispatch,
        technicians: [
          ...dispatch.technicians,
          ...Array.from({ length: 8 }, (_, i) => ({
            membershipId: `extra-${i}`,
            name: `Technician ${i + 1}`,
          })),
        ],
        visits: [
          {
            ...dispatch.visits[0],
            visitId: 'brief',
            jobTitle: 'Safety check',
            scheduledStart: '2026-10-20T12:05:00Z',
            scheduledEnd: '2026-10-20T12:20:00Z',
          },
        ],
      },
    }),
  );
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  const board = page.getByRole('region', { name: /Technician schedule/ });
  await expect(page.getByRole('table')).toBeVisible();
  await expect(page.locator('tbody tr')).toHaveCount(1);
  await expect(page.locator('tbody tr')).toContainText('Safety check');
  await expect(page.locator('tbody tr')).toContainText('08:05');
  expect(
    await page
      .locator('.appointment[data-visit="brief"]')
      .first()
      .evaluate((el) => parseFloat((el as HTMLElement).style.height)),
  ).toBe(18);
  expect(await board.evaluate((el) => el.scrollWidth > el.clientWidth)).toBe(true);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await board.focus();
  await page.keyboard.press('ArrowRight');
  await expect.poll(() => board.evaluate((el) => el.scrollLeft)).toBeGreaterThan(0);
});
