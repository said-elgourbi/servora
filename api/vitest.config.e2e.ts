import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    globals: true,
    root: './',
    // The API refuses to start without `JWT_SECRET` (`loadAuthConfig`), so the e2e run
    // supplies its own test-only value instead of requiring a developer's `.env`.
    // This is never a production default: a real deployment must provide its own.
    //
    // `STORAGE_PROVIDER=noop` is supplied for the same reason (`loadStorageConfig`): a test
    // must not depend on the machine's object-storage configuration, and the evidence tests
    // replace the provider with an in-memory fake anyway. A `noop` provider refuses an
    // upload rather than reporting one it did not store, so no test can silently pass by
    // pretending the bytes were kept (`BR-014`, `BR-015`).
    env: {
      JWT_SECRET: 'servora-e2e-jwt-secret-not-for-production',
      STORAGE_PROVIDER: 'noop',
    },

    include: ['**/*.e2e-spec.ts'],
    testTimeout: 20000,
    // Apply pending migrations once per run before any test file executes.
    globalSetup: ['./test/global-setup.ts'],
  },
});
