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

/**
 * The Property values a snapshot is written from (`BR-056`).
 *
 * `name` is the Property's own name and becomes the snapshot's `propertyName`; the remaining parts
 * are the structured address the Property stores (`BR-049`). `addressLine2` is optional so a Property
 * row read from the database and the development seed's own literal are both accepted.
 */
export interface AddressSnapshotSource {
  readonly name: string | null;
  readonly addressLine1: string;
  readonly addressLine2?: string | null;
  readonly city: string;
  readonly province: string;
  readonly postalCode: string;
  readonly country: string;
}

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

/**
 * Writes the snapshot a Job or a Visit preserves from the Property it is created at (`BR-056`).
 *
 * The parts are the Property's own, under exactly the names `readAddressSnapshot` reads back, so the
 * writer and the reader are one definition of the snapshot shape rather than two that could drift
 * (`BR-041`). The result is a frozen copy: a later edit of the Property never rewrites it
 * (`BR-057`, `BR-084`).
 */
export function addressSnapshotOf(
  property: AddressSnapshotSource,
): AddressSnapshot {
  return {
    propertyName: property.name,
    addressLine1: property.addressLine1,
    addressLine2: property.addressLine2 ?? null,
    city: property.city,
    province: property.province,
    postalCode: property.postalCode,
    country: property.country,
  };
}
