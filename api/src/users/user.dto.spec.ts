import { parseCreateUserDto, toUserDto } from './user.dto.js';
import type { User } from './user.types.js';

const row: User = {
  id: '00000000-0000-0000-0000-000000000001',
  email: 'manager@example.com',
  // A realistic Argon2id hash; must never leave the backend boundary.
  passwordHash: '$argon2id$v=19$m=19456,t=2,p=1$c2FsdHNhbHQ$hashhashhash',
  phone: '+1 555 0100',
  status: 'ACTIVE',
  lastLoginAt: null,
  createdAt: new Date('2026-01-01T00:00:00.000Z'),
  updatedAt: new Date('2026-01-02T00:00:00.000Z'),
};

describe('toUserDto', () => {
  it('exposes exactly the public fields', () => {
    expect(Object.keys(toUserDto(row)).sort()).toEqual([
      'createdAt',
      'email',
      'id',
      'lastLoginAt',
      'phone',
      'status',
      'updatedAt',
    ]);
  });

  it('never exposes the password hash', () => {
    const serialized = JSON.stringify(toUserDto(row));

    expect(serialized).not.toContain('passwordHash');
    expect(serialized).not.toContain('argon2');
    expect(serialized).not.toContain('hashhashhash');
  });

  it('serializes timestamps as ISO-8601 UTC', () => {
    expect(toUserDto(row).createdAt).toBe('2026-01-01T00:00:00.000Z');
  });
});

describe('parseCreateUserDto', () => {
  it('accepts a valid payload and keeps the plaintext password out of the DTO shape', () => {
    const dto = parseCreateUserDto({
      email: 'tech@example.com',
      password: 'supersecret',
      phone: '555-0101',
    });

    expect(dto).toEqual({
      email: 'tech@example.com',
      password: 'supersecret',
      phone: '555-0101',
    });
  });

  it('rejects an invalid email', () => {
    expect(() =>
      parseCreateUserDto({ email: 'nope', password: 'supersecret' }),
    ).toThrow(/valid email address/);
  });

  it('rejects a short password', () => {
    expect(() =>
      parseCreateUserDto({ email: 'tech@example.com', password: 'short' }),
    ).toThrow(/at least 8 characters/);
  });
});
