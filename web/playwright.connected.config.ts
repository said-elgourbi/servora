import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e/connected',
  workers: 1,
  reporter: 'list',
  preserveOutput: 'never',
  use: {
    baseURL: 'http://127.0.0.1:4300',
    browserName: 'chromium',
    timezoneId: 'UTC',
    serviceWorkers: 'block',
    // Real session credentials must never enter traces, screenshots or videos.
    trace: 'off',
    screenshot: 'off',
    video: 'off',
    launchOptions: process.env['PLAYWRIGHT_CHROMIUM_EXECUTABLE']
      ? { executablePath: process.env['PLAYWRIGHT_CHROMIUM_EXECUTABLE'] }
      : {},
  },
  webServer: {
    command: 'node e2e/fixtures/static-server.mjs',
    url: 'http://127.0.0.1:4300',
    reuseExistingServer: false,
  },
});
