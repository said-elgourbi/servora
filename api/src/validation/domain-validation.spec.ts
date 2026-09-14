import {
  DomainValidationError,
  optionalEmail,
  optionalInstant,
  optionalPositiveInteger,
  optionalText,
  optionalUuid,
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

  describe('optionalUuid', () => {
    it('accepts a canonical UUID and treats blank input as absent', () => {
      const id = '3f1a1a2e-0f83-4a4c-9c0e-2a1f0f4a5b6c';
      expect(optionalUuid(id, 'clientOperationId')).toBe(id);
      expect(optionalUuid(undefined, 'clientOperationId')).toBeNull();
      expect(optionalUuid('  ', 'clientOperationId')).toBeNull();
    });

    it('rejects a value that is not a UUID', () => {
      expect(() => optionalUuid('not-a-uuid', 'clientOperationId')).toThrow(
        /must be a UUID/,
      );
    });
  });

  describe('optionalInstant', () => {
    it('accepts an ISO-8601 instant with a zone', () => {
      expect(optionalInstant('2026-09-13T10:15:00.000Z', 'capturedAt')).toBe(
        '2026-09-13T10:15:00.000Z',
      );
      expect(optionalInstant('2026-09-13T10:15:00Z', 'capturedAt')).toBe(
        '2026-09-13T10:15:00Z',
      );
      expect(optionalInstant('2026-09-13T10:15:00-04:00', 'capturedAt')).toBe(
        '2026-09-13T10:15:00-04:00',
      );
      expect(optionalInstant(null, 'capturedAt')).toBeNull();
    });

    it('accepts the sub-millisecond precision a device clock reports', () => {
      // Kotlin's `Instant.toString()` emits 0, 3, 6 or 9 fractional digits; Android reports
      // microseconds, which a 1-3 digit pattern rejected as a validation failure.
      expect(optionalInstant('2026-09-13T10:15:00.123456Z', 'capturedAt')).toBe(
        '2026-09-13T10:15:00.123456Z',
      );
      expect(
        optionalInstant('2026-09-13T10:15:00.123456789Z', 'capturedAt'),
      ).toBe('2026-09-13T10:15:00.123456789Z');
    });

    it('rejects a local wall-clock string', () => {
      expect(() => optionalInstant('2026-09-13 10:15', 'capturedAt')).toThrow(
        /must be an ISO-8601 instant/,
      );
    });

    it('rejects a fractional second finer than a nanosecond', () => {
      expect(() =>
        optionalInstant('2026-09-13T10:15:00.1234567890Z', 'capturedAt'),
      ).toThrow(/must be an ISO-8601 instant/);
    });
  });

  describe('optionalPositiveInteger', () => {
    it('accepts a positive integer and treats absent input as null', () => {
      expect(optionalPositiveInteger(3, 'expectedVersion')).toBe(3);
      expect(optionalPositiveInteger(undefined, 'expectedVersion')).toBeNull();
    });

    it.each([0, -1, 1.5, '2'])(
      'rejects a value that is not a positive integer (%s)',
      (value) => {
        expect(() => optionalPositiveInteger(value, 'expectedVersion')).toThrow(
          /must be a positive integer/,
        );
      },
    );
  });
});
