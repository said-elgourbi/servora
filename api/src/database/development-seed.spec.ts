import { describe, expect, it } from 'vitest';
import { SYSTEM_ROLE_CODES } from '../members/organization-member.types.js';
import {
  DEFAULT_SEED_MANAGER_EMAIL,
  DEFAULT_SEED_ORGANIZATION_NAME,
  DEFAULT_SEED_TECHNICIAN_EMAIL,
  resolveDevelopmentSeed,
} from './development-seed.js';

describe('resolveDevelopmentSeed', () => {
  it('seeds one account per default system role template', () => {
    const seed = resolveDevelopmentSeed({});

    expect(seed.accounts.map((account) => account.role)).toEqual([
      'MANAGER',
      'TECHNICIAN',
    ]);
    expect(
      seed.accounts.every((account) =>
        SYSTEM_ROLE_CODES.includes(account.role),
      ),
    ).toBe(true);
  });

  it('defaults to reserved addresses and the development organization', () => {
    const seed = resolveDevelopmentSeed({});

    expect(seed.organizationName).toBe(DEFAULT_SEED_ORGANIZATION_NAME);
    expect(seed.accounts.map((account) => account.email)).toEqual([
      DEFAULT_SEED_MANAGER_EMAIL,
      DEFAULT_SEED_TECHNICIAN_EMAIL,
    ]);
  });

  it('generates a distinct, usable password per account when none is configured', () => {
    const seed = resolveDevelopmentSeed({});
    const [manager, technician] = seed.accounts;

    expect(manager?.generatedPassword).toBe(true);
    expect(technician?.generatedPassword).toBe(true);
    expect(manager?.password).not.toBe(technician?.password);
    expect((manager?.password ?? '').length).toBeGreaterThanOrEqual(8);
    expect((technician?.password ?? '').length).toBeGreaterThanOrEqual(8);
  });

  it('uses configured passwords and reports them as not generated', () => {
    const seed = resolveDevelopmentSeed({
      SEED_MANAGER_PASSWORD: 'manager-dev-password',
      SEED_TECHNICIAN_PASSWORD: 'technician-dev-password',
    });
    const [manager, technician] = seed.accounts;

    expect(manager?.password).toBe('manager-dev-password');
    expect(manager?.generatedPassword).toBe(false);
    expect(technician?.password).toBe('technician-dev-password');
    expect(technician?.generatedPassword).toBe(false);
  });

  it('treats a blank password variable as unset', () => {
    const seed = resolveDevelopmentSeed({ SEED_MANAGER_PASSWORD: '   ' });

    expect(seed.accounts[0]?.generatedPassword).toBe(true);
  });

  it('normalizes a configured email to lower case', () => {
    const seed = resolveDevelopmentSeed({
      SEED_MANAGER_EMAIL: ' Manager@Servora.Test ',
    });

    expect(seed.accounts[0]?.email).toBe('manager@servora.test');
  });

  it('honours a configured organization name', () => {
    const seed = resolveDevelopmentSeed({
      SEED_ORGANIZATION_NAME: 'Acme Field Service',
    });

    expect(seed.organizationName).toBe('Acme Field Service');
  });

  it('rejects a configured address that is not an email, naming the variable', () => {
    expect(() =>
      resolveDevelopmentSeed({ SEED_MANAGER_EMAIL: 'not-an-address' }),
    ).toThrow(/SEED_MANAGER_EMAIL/);
  });

  it('rejects a configured password below the domain minimum, naming the variable', () => {
    expect(() =>
      resolveDevelopmentSeed({ SEED_TECHNICIAN_PASSWORD: 'short' }),
    ).toThrow(/SEED_TECHNICIAN_PASSWORD/);
  });

  it('names the environment variable a configured password is read from', () => {
    const seed = resolveDevelopmentSeed({});

    expect(seed.accounts.map((account) => account.passwordVariable)).toEqual([
      'SEED_MANAGER_PASSWORD',
      'SEED_TECHNICIAN_PASSWORD',
    ]);
  });
});
