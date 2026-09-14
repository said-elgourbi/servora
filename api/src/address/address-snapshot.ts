/**
 * Reading a stored address snapshot.
 *
 * A Job and a Visit each preserve the address that was relevant when they were created
 * (`BR-056`, `BR-057`): `jobs.property_address_snapshot` and `visits.location_address_snapshot`.
 * The column is `jsonb`, so what a read gets back is untyped JSON — never a value a projection can
 * trust to have the fields it expects.
 *
 * The shape is defined once here because more than one projection resolves it (the customer-detail
 * Job row and the manager-home Visit row). A second, separately written parser would let the two
 * answer differently for the same stored snapshot (`BR-041`).
 */

/** The parts of an address a Job or a Visit snapshot preserves. Every part may be absent. */
export interface AddressSnapshot {
  propertyName: string | null;
  addressLine1: string | null;
  addressLine2: string | null;
  city: string | null;
  province: string | null;
  postalCode: string | null;
  country: string | null;
}

/**
 * Reads a snapshot, reporting `null` when it is absent or not an object.
 *
 * A snapshot that is present but partly empty keeps its absent parts as `null` rather than being
 * reported as no address: the record says an address was preserved, so the projection says which
 * parts of it are known.
 */
export function readAddressSnapshot(snapshot: unknown): AddressSnapshot | null {
  if (typeof snapshot !== 'object' || snapshot === null) {
    return null;
  }
  const source = snapshot as Record<string, unknown>;
  return {
    propertyName: textValue(source.propertyName),
    addressLine1: textValue(source.addressLine1),
    addressLine2: textValue(source.addressLine2),
    city: textValue(source.city),
    province: textValue(source.province),
    postalCode: textValue(source.postalCode),
    country: textValue(source.country),
  };
}

function textValue(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null;
}
