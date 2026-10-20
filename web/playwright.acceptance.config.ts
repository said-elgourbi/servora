import { defineConfig } from '@playwright/test';
import base from './playwright.config';

// Explicit opt-in: the normal Chromium run does not require additional browsers.
export default defineConfig({
  ...base,
  workers: 2,
  use: { ...base.use, launchOptions: {} },
  projects: ['firefox', 'edge'].flatMap((browser) =>
    [
      { name: 'desktop', width: 1440, height: 900 },
      { name: 'phone', width: 390, height: 844 },
      { name: 'tablet', width: 768, height: 1024 },
    ].map(({ name, width, height }) => ({
      name: `${browser}-${name}`,
      testIgnore: [/pwa\.spec\.ts/, /connected\//],
      ...(name === 'desktop' ? {} : { testMatch: /responsive\.spec\.ts/ }),
      use: {
        browserName: browser === 'firefox' ? ('firefox' as const) : ('chromium' as const),
        ...(browser === 'edge'
          ? {
              channel: 'msedge',
              launchOptions: process.env['PLAYWRIGHT_EDGE_EXECUTABLE']
                ? { executablePath: process.env['PLAYWRIGHT_EDGE_EXECUTABLE'] }
                : {},
            }
          : {}),
        viewport: { width, height },
        serviceWorkers: 'block' as const,
      },
    })),
  ),
});
