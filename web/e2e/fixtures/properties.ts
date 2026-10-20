import type { Page } from '@playwright/test';
import type { CustomerJob, PropertyDetails } from '@servora/shared-types';
import { mockCustomers, customer, customerPermissions } from './customers';
export const propertyPermissions = [
  ...customerPermissions,
  'properties.create',
  'properties.edit',
  'properties.archive',
  'properties.delete',
];
export const property: PropertyDetails = {
  id: 'property-one',
  name: 'Cedar House',
  addressLine1: '987 Cedar Lane',
  addressLine2: null,
  city: 'Montreal',
  province: 'QC',
  postalCode: 'H3A 2T6',
  country: 'Canada',
  notes: 'Mechanical room B1',
  status: 'ACTIVE',
  version: 4,
  archivedAt: null,
  jobCount: 2,
  lastServiceAt: '2026-10-01T12:00:00Z',
  archiveImpact: { activeJobCount: 2, activeVisitCount: 3 },
  canBePermanentlyDeleted: false,
};
const history: CustomerJob[] = [
  {
    id: 'job-one',
    jobNumber: 1042,
    title: 'Historic furnace service',
    description: null,
    typeCode: null,
    status: 'ACTIVE',
    propertyId: 'property-one',
    propertyAddress: {
      propertyName: 'Old label',
      addressLine1: '123 Original Street',
      addressLine2: null,
      city: 'Montreal',
      province: 'QC',
      postalCode: 'H3A 2T6',
      country: 'Canada',
    },
    scheduledStart: '2026-10-01T12:00:00Z',
    technicians: [],
  },
  {
    id: 'job-other',
    jobNumber: 1043,
    title: 'Other property private job',
    description: null,
    typeCode: null,
    status: 'NEW',
    propertyId: 'property-two',
    propertyAddress: null,
    scheduledStart: null,
    technicians: [],
  },
];
export async function mockProperties(page: Page, permissions = propertyPermissions) {
  await mockCustomers(page, permissions);
  let current = structuredClone(property),
    deleted = false;
  const writes: { method: string; path: string; body: Record<string, unknown> | null }[] = [];
  await page.route(/^https?:\/\/[^/]+\/customers\/customer-one(?:\/|\?|$)/, async (route) => {
    const request = route.request(),
      path = new URL(request.url()).pathname,
      method = request.method();
    const body = request.postDataJSON() as Record<string, unknown> | null;
    if (method !== 'GET') writes.push({ method, path, body });
    if (path === '/customers/customer-one')
      return route.fulfill({
        json: {
          ...customer,
          customer: {
            ...customer.customer,
            propertyCount: deleted || current.status === 'ARCHIVED' ? 0 : 1,
          },
        },
      });
    if (path.endsWith('/jobs')) return route.fulfill({ json: history });
    if (!path.includes('/properties')) return route.fallback();
    if (method === 'DELETE') {
      deleted = true;
      return route.fulfill({ status: 204 });
    }
    if (method === 'POST' && path.endsWith('/properties')) {
      current = {
        ...current,
        ...body,
        id: 'property-one',
        version: 1,
        status: 'ACTIVE',
        jobCount: 0,
        lastServiceAt: null,
      } as PropertyDetails;
      const {
        version: _version,
        notes: _notes,
        archiveImpact: _impact,
        canBePermanentlyDeleted: _delete,
        archivedAt: _archived,
        ...summary
      } = current;
      return route.fulfill({ status: 201, json: summary });
    }
    if (method === 'PATCH') {
      current = { ...current, ...body, version: current.version + 1 } as PropertyDetails;
      return route.fulfill({ json: current });
    }
    if (method === 'POST') {
      current.status = path.endsWith('/archive') ? 'ARCHIVED' : 'ACTIVE';
      current.archivedAt = current.status === 'ARCHIVED' ? '2026-10-19T12:00:00Z' : null;
      current.version++;
      return route.fulfill({ json: current });
    }
    if (path.endsWith('/properties')) {
      const filter = new URL(request.url()).searchParams.get('status') ?? 'ACTIVE';
      return route.fulfill({
        json: deleted || (filter !== 'ALL' && filter !== current.status) ? [] : [current],
      });
    }
    return deleted
      ? route.fulfill({ status: 404, json: { code: 'PROPERTY_NOT_FOUND' } })
      : route.fulfill({ json: current });
  });
  return {
    writes,
    change: (update: Partial<PropertyDetails>) => {
      current = { ...current, ...update };
    },
  };
}
export async function propertySignIn(
  page: Page,
  path = '/work/customers/customer-one/properties/property-one',
) {
  await page.goto('/sign-in');
  await page.getByLabel('Email address').fill('office@example.com');
  await page.getByLabel('Password', { exact: true }).fill('test-password');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.getByRole('heading', { name: 'Your working day' }).waitFor();
  await page.goto(path);
}
