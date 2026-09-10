import { describe, expect, it } from 'vitest';
import { readClientContext } from './client-context.js';

const MAX_USER_AGENT_LENGTH = 500;

describe('readClientContext', () => {
  it('records the address and user agent observed by the server', () => {
    const context = readClientContext({
      ip: '::ffff:127.0.0.1',
      headers: { 'user-agent': 'Servora-Android/1.0' },
    });

    expect(context).toEqual({
      ipAddress: '::ffff:127.0.0.1',
      userAgent: 'Servora-Android/1.0',
    });
  });

  it('accepts an IPv6 address', () => {
    const context = readClientContext({ ip: '2001:db8::1', headers: {} });

    expect(context.ipAddress).toBe('2001:db8::1');
  });

  it('stores an unusable address as null instead of failing the sign-in', () => {
    const unusable = [
      undefined,
      '',
      '   ',
      'not-an-ip',
      '1.2.3.4, 5.6.7.8',
      'a'.repeat(46),
    ];

    for (const ip of unusable) {
      expect(readClientContext({ ip, headers: {} }).ipAddress).toBeNull();
    }
  });

  it('stores a missing user agent as null', () => {
    const missing = [
      {},
      { 'user-agent': '' },
      { 'user-agent': [] as string[] },
    ];

    for (const headers of missing) {
      expect(
        readClientContext({ ip: '10.0.0.1', headers }).userAgent,
      ).toBeNull();
    }
  });

  it('truncates a user agent to the stored column length', () => {
    const context = readClientContext({
      ip: '10.0.0.1',
      headers: { 'user-agent': 'u'.repeat(600) },
    });

    expect(context.userAgent).toHaveLength(MAX_USER_AGENT_LENGTH);
  });

  it('uses the first value when a header is repeated', () => {
    const context = readClientContext({
      ip: '10.0.0.1',
      headers: { 'user-agent': ['first', 'second'] },
    });

    expect(context.userAgent).toBe('first');
  });
});
