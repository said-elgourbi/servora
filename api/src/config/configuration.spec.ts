import { loadConfig } from './configuration.js';

describe('loadConfig', () => {
  it('returns documented defaults for an empty environment', () => {
    const config = loadConfig({});

    expect(config).toEqual({
      nodeEnv: 'development',
      port: 3000,
      databaseUrl: 'postgres://servora:servora@localhost:5432/servora',
      logLevel: 'info',
    });
  });

  it('honours explicitly provided values', () => {
    const config = loadConfig({
      NODE_ENV: 'test',
      PORT: '4100',
      DATABASE_URL: 'postgresql://db:5432/custom',
      LOG_LEVEL: 'debug',
    });

    expect(config).toEqual({
      nodeEnv: 'test',
      port: 4100,
      databaseUrl: 'postgresql://db:5432/custom',
      logLevel: 'debug',
    });
  });

  it('treats empty values as unset', () => {
    expect(
      loadConfig({ NODE_ENV: '', PORT: '', DATABASE_URL: '', LOG_LEVEL: '' }),
    ).toEqual({
      nodeEnv: 'development',
      port: 3000,
      databaseUrl: 'postgres://servora:servora@localhost:5432/servora',
      logLevel: 'info',
    });
  });

  it.each(['staging', 'PRODUCTION'])(
    'rejects an invalid NODE_ENV (%s)',
    (nodeEnv) => {
      expect(() => loadConfig({ NODE_ENV: nodeEnv })).toThrow(
        /Invalid NODE_ENV/,
      );
    },
  );

  it.each(['0', '-1', '65536', 'abc', '3.5'])(
    'rejects an invalid PORT (%s)',
    (port) => {
      expect(() => loadConfig({ PORT: port })).toThrow(/Invalid PORT/);
    },
  );

  it.each([
    {
      value: 'mysql://servora:servora@localhost:5432/servora',
      note: 'a non-postgres protocol',
    },
    { value: 'not a url', note: 'a malformed URL' },
    { value: 'postgres://', note: 'a URL without a host' },
  ])('rejects DATABASE_URL with $note', ({ value }) => {
    expect(() => loadConfig({ DATABASE_URL: value })).toThrow(
      /Invalid DATABASE_URL/,
    );
  });

  it('rejects an invalid LOG_LEVEL', () => {
    expect(() => loadConfig({ LOG_LEVEL: 'verbose' })).toThrow(
      /Invalid LOG_LEVEL/,
    );
  });
});
