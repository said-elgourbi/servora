import {
  optionalText,
  requireEmail,
  requirePassword,
} from '../validation/domain-validation.js';
import type { User, UserProfile, UserStatus } from './user.types.js';

/**
 * Public API shape of a user.
 *
 * SECURITY: the password hash is authentication data and must never cross this
 * boundary. `toUserDto` selects fields explicitly rather than spreading the row.
 */
export interface UserDto {
  id: string;
  email: string;
  phone: string | null;
  status: UserStatus;
  lastLoginAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface UserProfileDto {
  userId: string;
  firstName: string;
  lastName: string;
  displayName: string | null;
  phone: string | null;
  avatarUrl: string | null;
  locale: string;
  timezone: string;
  createdAt: string;
  updatedAt: string;
}

/** Input accepted when creating an authentication identity. */
export interface CreateUserDto {
  email: string;
  password: string;
  phone?: string | null;
}

export function toUserDto(user: User): UserDto {
  return {
    id: user.id,
    email: user.email,
    phone: user.phone,
    status: user.status as UserStatus,
    lastLoginAt: user.lastLoginAt?.toISOString() ?? null,
    createdAt: user.createdAt.toISOString(),
    updatedAt: user.updatedAt.toISOString(),
  };
}

export function toUserProfileDto(profile: UserProfile): UserProfileDto {
  return {
    userId: profile.userId,
    firstName: profile.firstName,
    lastName: profile.lastName,
    displayName: profile.displayName,
    phone: profile.phone,
    avatarUrl: profile.avatarUrl,
    locale: profile.locale,
    timezone: profile.timezone,
    createdAt: profile.createdAt.toISOString(),
    updatedAt: profile.updatedAt.toISOString(),
  };
}

/** Validates untrusted input into a `CreateUserDto`. The password stays plaintext-free. */
export function parseCreateUserDto(input: unknown): CreateUserDto {
  const source = (input ?? {}) as Record<string, unknown>;
  return {
    email: requireEmail(source.email, 'email'),
    password: requirePassword(source.password),
    phone: optionalText(source.phone, 'phone', 50),
  };
}
