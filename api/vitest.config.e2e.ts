import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    globals: true,
    root: './',
    // The API refuses to start without `JWT_SECRET` (`loadAuthConfig`), so the e2e run
    // supplies its own test-only value instead of requiring a developer's `.env`.
    // This is never a production default: a real deployment must provide its own.
    env: {
      JWT_SECRET: 'servora-e2e-jwt-secret-not-for-production',
    },

    include: ['**/*.e2e-spec.ts'],
    testTimeout: 20000,
    // Apply pending migrations once per run before any test file executes.
    globalSetup: ['./test/global-setup.ts'],
  },
});
