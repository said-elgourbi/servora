import type { WorkspaceAddress } from '@servora/shared-types';
export function addressText(address: WorkspaceAddress | null): string {
  return address
    ? [
        address.addressLine1,
        address.addressLine2,
        address.city,
        address.province,
        address.postalCode,
        address.country,
      ]
        .filter(Boolean)
        .join(', ')
    : '';
}
export function directionsUrl(address: WorkspaceAddress | null): string | null {
  const text = addressText(address);
  return text
    ? `https://www.google.com/maps/dir/?api=1&destination=${encodeURIComponent(text)}`
    : null;
}
