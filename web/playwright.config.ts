import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './e2e',
  testIgnore: /connected\//,
  fullyParallel: true,
  forbidOnly: !!process.env['CI'],
  retries: 0,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:4300',
    trace: 'retain-on-failure',
    launchOptions: process.env['PLAYWRIGHT_CHROMIUM_EXECUTABLE']
      ? { executablePath: process.env['PLAYWRIGHT_CHROMIUM_EXECUTABLE'] }
      : {},
  },
  projects: [
    {
      name: 'desktop',
      testIgnore: [/pwa\.spec\.ts/, /connected\//],
      use: {
        browserName: 'chromium',
        viewport: { width: 1440, height: 900 },
        serviceWorkers: 'block',
      },
    },
    {
      name: 'phone',
      testMatch: /responsive\.spec\.ts/,
      use: {
        browserName: 'chromium',
        viewport: { width: 390, height: 844 },
        serviceWorkers: 'block',
      },
    },
    {
      name: 'tablet',
      testMatch: /responsive\.spec\.ts/,
      use: {
        browserName: 'chromium',
        viewport: { width: 768, height: 1024 },
        serviceWorkers: 'block',
      },
    },
    {
      name: 'pwa',
      testMatch: /pwa\.spec\.ts/,
      use: { browserName: 'chromium', serviceWorkers: 'allow' },
    },
  ],
  webServer: {
    command: 'node e2e/fixtures/static-server.mjs',
    url: 'http://127.0.0.1:4300',
    reuseExistingServer: false,
  },
});
