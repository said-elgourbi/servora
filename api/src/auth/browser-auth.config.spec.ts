import { describe, expect, it } from 'vitest';
import { loadBrowserAuthConfig } from './browser-auth.config.js';
describe('browser origin configuration', () => {
  it('defaults only local development and disables unconfigured production browser auth', () => {
    expect(loadBrowserAuthConfig({}).origin).toBe('http://localhost:4200');
    expect(loadBrowserAuthConfig({ NODE_ENV: 'production' })).toEqual({
      origin: null,
      secure: true,
    });
  });
  it.each([
    'https://office.example/',
    'https://office.example/path',
    'https://office.example?x=1',
    'https://u:p@office.example',
    'null',
    '*',
    'http://office.example',
  ])('rejects unsafe production origin %s', (WEB_ORIGIN) => {
    expect(() =>
      loadBrowserAuthConfig({ NODE_ENV: 'production', WEB_ORIGIN }),
    ).toThrow(/WEB_ORIGIN/);
  });
});
