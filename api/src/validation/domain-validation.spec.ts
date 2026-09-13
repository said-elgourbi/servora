import {
  DomainValidationError,
  optionalEmail,
  optionalText,
  requireEmail,
  requireEnum,
  requirePassword,
  requireText,
} from './domain-validation.js';

describe('domain validation', () => {
  describe('requireText', () => {
    it('returns the trimmed value', () => {
      expect(requireText('  Servora  ', 'name', 200)).toBe('Servora');
    });

    it.each([undefined, null, '', '   ', 42])(
      'rejects a missing or non-string value (%s)',
      (value) => {
        expect(() => requireText(value, 'name', 200)).toThrow(
          DomainValidationError,
        );
      },
    );

    it('rejects a value longer than the maximum', () => {
      expect(() => requireText('abcd', 'name', 3)).toThrow(
        /at most 3 characters/,
      );
    });
  });

  describe('optionalText', () => {
    it.each([undefined, null, '', '   '])('maps %s to null', (value) => {
      expect(optionalText(value, 'phone', 50)).toBeNull();
    });

    it('trims a provided value', () => {
      expect(optionalText(' 555-0100 ', 'phone', 50)).toBe('555-0100');
    });
  });

  describe('requireEmail', () => {
    it('accepts a valid address', () => {
      expect(requireEmail('Manager@Example.COM', 'email')).toBe(
        'Manager@Example.COM',
      );
    });

    it.each(['not-an-email', 'missing@tld', 'a b@example.com'])(
      'rejects %s',
      (value) => {
        expect(() => requireEmail(value, 'email')).toThrow(
          /valid email address/,
        );
      },
    );

    it('maps blank optional input to null', () => {
      expect(optionalEmail('  ', 'email')).toBeNull();
    });
  });

  describe('requireEnum', () => {
    it('accepts an allowed value', () => {
      expect(requireEnum('MANAGER', ['MANAGER', 'TECHNICIAN'], 'role')).toBe(
        'MANAGER',
      );
    });

    it('rejects an unknown value', () => {
      expect(() =>
        requireEnum('DISPATCHER', ['MANAGER', 'TECHNICIAN'], 'role'),
      ).toThrow(/must be one of: MANAGER, TECHNICIAN/);
    });
  });

  describe('requirePassword', () => {
    it('accepts a password of sufficient length', () => {
      expect(requirePassword('correct horse')).toBe('correct horse');
    });

    it('rejects a short password', () => {
      expect(() => requirePassword('short')).toThrow(/at least 8 characters/);
    });
  });

  it('exposes the collected issues on the error', () => {
    const error = new DomainValidationError(['name is required']);
    expect(error.issues).toEqual(['name is required']);
    expect(error.name).toBe('DomainValidationError');
  });
});
