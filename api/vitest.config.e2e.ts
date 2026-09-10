import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    globals: true,
    root: './',
    include: ['**/*.e2e-spec.ts'],
    testTimeout: 20000,
    // Apply pending migrations once per run before any test file executes.
    globalSetup: ['./test/global-setup.ts'],
  },
});

