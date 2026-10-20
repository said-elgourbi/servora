import type { Page } from '@playwright/test';
import type {
  CustomerDetails,
  CustomerContact,
  CustomerRecord,
  CustomerSummary,
} from '@servora/shared-types';
import { mockAuth } from './auth';
export const customerPermissions = [
  'customers.view',
  'customers.create',
  'customers.edit',
  'customers.archive',
  'customers.contacts.create',
  'customers.contacts.edit',
  'customers.contacts.remove',
  'properties.view',
];
export const customer: CustomerDetails = {
  customer: {
    id: 'customer-one',
    organizationId: 'org-one',
    type: 'INDIVIDUAL',
    displayName: 'Camille Roy',
    email: 'camille@example.com',
    phone: '+15145551234',
    billingEmail: 'billing@example.com',
    billingPhone: null,
    notes: 'Side entrance',
    preferredContactMethod: 'PHONE',
    language: 'fr-CA',
    status: 'ACTIVE',
    deletedAt: null,
    deletedByMembershipId: null,
    deleteReason: null,
    createdAt: '2026-10-01T12:00:00Z',
    updatedAt: '2026-10-01T12:00:00Z',
    propertyCount: 1,
    jobCount: 0,
  },
  individual: {
    customerId: 'customer-one',
    firstName: 'Camille',
    lastName: 'Roy',
    dateOfBirth: null,
  },
  company: null,
  contacts: [],
};
export async function mockCustomers(page: Page, permissions = customerPermissions) {
  await mockAuth(page, permissions);
  let details = structuredClone(customer);
  let archived = false;
  const writes: { method: string; path: string; body: Record<string, unknown> }[] = [];
  await page.route(/^https?:\/\/[^/]+\/customers(?:\/|\?|$)/, async (route) => {
    const request = route.request(),
      path = new URL(request.url()).pathname,
      method = request.method();
    const body = request.postDataJSON() as Record<string, unknown> | null;
    if (method !== 'GET') writes.push({ method, path, body: body ?? {} });
    if (path.endsWith('/archive')) {
      archived = true;
      return route.fulfill({ json: details.customer });
    }
    if (path.endsWith('/properties'))
      return route.fulfill({
        json: [
          {
            id: 'property-one',
            name: 'Cedar House',
            addressLine1: '987 Cedar Lane',
            addressLine2: null,
            city: 'Montreal',
            province: 'QC',
            postalCode: 'H3A 2T6',
            country: 'Canada',
            status: 'ACTIVE',
            jobCount: 0,
            lastServiceAt: null,
          },
        ],
      });
    if (path.endsWith('/jobs')) return route.fulfill({ json: [] });
    if (path.includes('/contacts')) {
      if (method === 'DELETE') {
        details.contacts = [];
        return route.fulfill({ status: 204 });
      }
      const contact = {
        id: 'contact-one',
        customerId: details.customer.id,
        role: 'Site manager',
        isBillingContact: false,
        isJobContact: false,
        version: method === 'PATCH' ? 2 : 1,
        createdAt: '2026-10-01T12:00:00Z',
        updatedAt: '2026-10-01T12:00:00Z',
        ...body,
      } as CustomerContact;
      details.contacts = [contact];
      return route.fulfill({ status: method === 'POST' ? 201 : 200, json: contact });
    }
    if (method === 'POST' || method === 'PATCH') {
      const { individual, company, ...header } = body ?? {};
      details = {
        ...details,
        customer: { ...details.customer, ...header } as CustomerSummary,
        individual: individual
          ? ({
              customerId: 'customer-one',
              ...(individual as object),
            } as CustomerDetails['individual'])
          : null,
        company: company
          ? ({ customerId: 'customer-one', ...(company as object) } as CustomerDetails['company'])
          : null,
      };
      return route.fulfill({ status: method === 'POST' ? 201 : 200, json: details });
    }
    if (path === '/customers') {
      const inactive: CustomerRecord = {
        ...details.customer,
        id: 'customer-two',
        displayName: 'Inactive Company',
        type: 'COMPANY',
        email: 'office@example.com',
        status: 'INACTIVE',
      };
      return route.fulfill({
        json: archived
          ? []
          : [
              { ...details.customer, jobCount: 5, openJobCount: 2, overdueVisitCount: 1 },
              { ...inactive, propertyCount: 0, jobCount: 0, openJobCount: 0, overdueVisitCount: 0 },
            ],
      });
    }
    return route.fulfill({ json: details });
  });
  return { writes };
}
export async function signInCustomers(page: Page) {
  await page.goto('/sign-in');
  await page.getByLabel('Email address').fill('office@example.com');
  await page.getByLabel('Password', { exact: true }).fill('test-password');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.getByRole('heading', { name: 'Your working day' }).waitFor();
  await page.goto('/work/customers');
  await expectCustomerPage(page);
}
async function expectCustomerPage(page: Page) {
  // Waiting for the first record also waits for restoration and the customer read.
  await page.getByRole('link', { name: 'Camille Roy', exact: true }).waitFor();
}
