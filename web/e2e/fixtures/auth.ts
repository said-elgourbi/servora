import { officeHome, fieldHome, job } from './workspace';
import type { Page } from '@playwright/test';
export const tokens = {
  accessToken: 'test-access',
  accessTokenExpiresAt: '2099-01-01T00:00:00Z',
  sessionId: 'test-session',
};
export async function mockAuth(page: Page, permissions: string[] = ['customers.view']) {
  let authenticated = false;
  await page.route('**/home/**', (route) =>
    route.fulfill({
      json: new URL(route.request().url()).pathname === '/home/manager' ? officeHome : fieldHome,
    }),
  );
  await page.route(/^http:\/\/[^/]+\/jobs\/[^/]+$/, (route) => route.fulfill({ json: job }));
  await page.route('**/jobs/*/activity*', (route) =>
    route.fulfill({ json: { jobId: 'job-one', events: [] } }),
  );
  await page.route('**/auth/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/auth/web/sign-out') {
      authenticated = false;
      return route.fulfill({ status: 204 });
    }
    if (path === '/auth/web/refresh')
      return route.fulfill({
        status: authenticated ? 200 : 401,
        json: authenticated ? tokens : { code: 'REFRESH_TOKEN_INVALID' },
      });
    if (path === '/auth/web/sign-in' || path === '/auth/web/sms/verify') {
      authenticated = true;
      return route.fulfill({ json: tokens });
    }
    if (path === '/auth/web/sms/request') return route.fulfill({ status: 202 });
    if (path === '/auth/me') return route.fulfill({ json: { userId: 'test-user', permissions } });
    return route.abort();
  });
}
