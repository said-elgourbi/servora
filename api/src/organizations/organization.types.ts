import type { InferInsertModel, InferSelectModel } from 'drizzle-orm';
import { organizations } from '../database/schema.js';

// Stable, machine-readable status codes. Never store localized labels here.
export const ORGANIZATION_STATUSES = ['ACTIVE', 'INACTIVE'] as const;
export type OrganizationStatus = (typeof ORGANIZATION_STATUSES)[number];

export type Organization = InferSelectModel<typeof organizations>;
export type NewOrganization = InferInsertModel<typeof organizations>;
