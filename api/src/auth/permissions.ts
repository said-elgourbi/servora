export const CUSTOMER_PERMISSIONS = {
  VIEW: 'customers.view',
  CREATE: 'customers.create',
  EDIT: 'customers.edit',
  ARCHIVE: 'customers.archive',
} as const;

export type CustomerPermission =
  (typeof CUSTOMER_PERMISSIONS)[keyof typeof CUSTOMER_PERMISSIONS];

/**
 * The Property capability catalogue (`BR-085`).
 *
 * Property capabilities are independent of `customers.*`: a Property capability is never implied by
 * a Customer capability, and vice versa. The codes follow the resource-prefixed `resource.action`
 * convention shared by every permission (`BR-006`, `BR-041`).
 *
 * `VIEW`, `CREATE`, `EDIT`, `ARCHIVE` and `DELETE` are all enforced by the Property routes in
 * `PropertiesController` and `CustomersController`.
 */
export const PROPERTY_PERMISSIONS = {
  VIEW: 'properties.view',
  CREATE: 'properties.create',
  EDIT: 'properties.edit',
  ARCHIVE: 'properties.archive',
  DELETE: 'properties.delete',
} as const;

export type PropertyPermission =
  (typeof PROPERTY_PERMISSIONS)[keyof typeof PROPERTY_PERMISSIONS];

export type PermissionCode =
  CustomerPermission | PropertyPermission | (string & {});
