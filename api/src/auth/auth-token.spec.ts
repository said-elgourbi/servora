import {
  generateOpaqueToken,
  hashOpaqueToken,
  tokenHashesEqual,
} from './auth-token.js';

describe('opaque authentication tokens', () => {
  it('generates a URL-safe token carrying 256 bits of entropy', () => {
    const token = generateOpaqueToken();

    expect(token).toMatch(/^[A-Za-z0-9_-]+$/);
    expect(token).toHaveLength(43);
    expect(Buffer.from(token, 'base64url')).toHaveLength(32);
  });

  it('never repeats a generated token', () => {
    const tokens = new Set(
      Array.from({ length: 64 }, () => generateOpaqueToken()),
    );

    expect(tokens.size).toBe(64);
  });

  it('hashes deterministically with SHA-256 and never stores the raw token', () => {
    const token = generateOpaqueToken();
    const hash = hashOpaqueToken(token);

    expect(hash).toMatch(/^[0-9a-f]{64}$/);
    expect(hash).toBe(hashOpaqueToken(token));
    expect(hash).not.toContain(token);
  });

  it('produces a different hash for a different token', () => {
    expect(hashOpaqueToken('first')).not.toBe(hashOpaqueToken('second'));
  });

  describe('tokenHashesEqual', () => {
    it('accepts identical hashes', () => {
      const hash = hashOpaqueToken('token');

      expect(tokenHashesEqual(hash, hash)).toBe(true);
    });

    it('rejects a different hash', () => {
      expect(
        tokenHashesEqual(hashOpaqueToken('token'), hashOpaqueToken('other')),
      ).toBe(false);
    });

    it('rejects a length mismatch without throwing', () => {
      expect(tokenHashesEqual(hashOpaqueToken('token'), 'short')).toBe(false);
    });
  });
});
