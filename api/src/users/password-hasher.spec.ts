import { hashPassword, verifyPassword } from './password-hasher.js';

describe('password hashing', () => {
  it('hashes a password with Argon2id without retaining the plaintext', async () => {
    const hash = await hashPassword('correct horse battery staple');

    expect(hash.startsWith('$argon2id$')).toBe(true);
    expect(hash).not.toContain('correct horse battery staple');
  });

  it('produces a different hash for the same password (unique salts)', async () => {
    const first = await hashPassword('correct horse battery staple');
    const second = await hashPassword('correct horse battery staple');

    expect(first).not.toBe(second);
  });

  it('verifies the correct password', async () => {
    const hash = await hashPassword('correct horse battery staple');

    await expect(verifyPassword(hash, 'correct horse battery staple')).resolves.toBe(true);
  });

  it('rejects the wrong password', async () => {
    const hash = await hashPassword('correct horse battery staple');

    await expect(verifyPassword(hash, 'wrong password')).resolves.toBe(false);
  });

  it('fails closed for a malformed hash', async () => {
    await expect(verifyPassword('not-a-hash', 'anything')).resolves.toBe(false);
  });

  it('refuses to hash a password shorter than the minimum length', async () => {
    await expect(hashPassword('short')).rejects.toThrow(/at least 8 characters/);
  });
});
