import type { InferInsertModel, InferSelectModel } from 'drizzle-orm';
import { userProfiles, users } from '../database/schema.js';

// Stable, machine-readable status codes. Never store localized labels here.
export const USER_STATUSES = ['ACTIVE', 'INACTIVE', 'SUSPENDED'] as const;
export type UserStatus = (typeof USER_STATUSES)[number];

/**
 * Authentication identity only: email + password hash. Personal information
 * lives in `UserProfile` and organisational role in `OrganizationMember`.
 */
export type User = InferSelectModel<typeof users>;
export type NewUser = InferInsertModel<typeof users>;

export type UserProfile = InferSelectModel<typeof userProfiles>;
export type NewUserProfile = InferInsertModel<typeof userProfiles>;
