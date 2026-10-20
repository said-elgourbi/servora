import { test, expect } from '@playwright/test';
test('production worker caches only the shell and supports an offline reload', async ({ page }) => {
  await page.goto('/sign-in');
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
  await page.evaluate(async () => {
    await navigator.serviceWorker.ready;
  });
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
  const paths = await page.evaluate(async () => {
    const result: string[] = [];
    for (const key of await caches.keys())
      for (const request of await (await caches.open(key)).keys())
        result.push(new URL(request.url).pathname);
    return result;
  });
  expect(paths.some((path) => path.endsWith('.js'))).toBe(true);
  expect(
    paths.filter((path) => /^\/(auth|jobs|customers|properties|home|schedule)(\/|$)/.test(path)),
  ).toEqual([]);
  await page.context().setOffline(true);
  await page.reload();
  await expect(
    page.getByRole('heading', { name: 'Your session could not be checked.' }),
  ).toBeVisible();
});
