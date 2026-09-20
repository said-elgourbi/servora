import {
  CUSTOMER_CONTACT_PERMISSIONS,
  CUSTOMER_PERMISSIONS,
  EVIDENCE_PERMISSIONS,
  VISIT_PERMISSIONS,
} from './permissions.js';

/**
 * The evidence capability catalogue (`BR-006`, `BR-015`, `BR-027`, `BR-091`; tracker 029 D1/D1b,
 * `ADR-018` A7).
 *
 * The codes are a shared vocabulary: the API guards its routes by them, the migration and the
 * development seed insert them, and a client decides whether to draw an action from them
 * (`BR-041`). They are asserted here so a rename is a deliberate contract change rather than a
 * silent one, exactly as the Job lifecycle vocabulary is in `job.types.spec.ts`.
 */
describe('evidence permissions', () => {
  it('pins the per-kind codes the routes, the migration and the clients share', () => {
    expect(EVIDENCE_PERMISSIONS).toEqual({
      VIEW: 'evidence.view',
      PHOTO_ADD: 'evidence.photo.add',
      PHOTO_REMOVE: 'evidence.photo.remove',
      AUDIO_ADD: 'evidence.audio.add',
      AUDIO_REMOVE: 'evidence.audio.remove',
    });
  });

  it('keeps the code ADR-015 reserved for audio rather than inventing a new one', () => {
    // `ADR-015` D2 reserved `evidence.audio.add` and deliberately did not create it until a rule
    // accepted a recording. Audio now has those rules (`BR-091`, `ADR-018` A7), so the capability is
    // created — with the reserved code, in the same per-kind shape as the photo capabilities.
    expect(EVIDENCE_PERMISSIONS.AUDIO_ADD).toBe('evidence.audio.add');
  });

  it('gives each kind its own add and remove capability', () => {
    // The catalogue is per kind (`ADR-018` A7): a company may let a member record or remove one kind
    // of evidence and not the other, so no two capabilities may collapse into one code.
    expect(EVIDENCE_PERMISSIONS.PHOTO_ADD).not.toBe(
      EVIDENCE_PERMISSIONS.AUDIO_ADD,
    );
    expect(EVIDENCE_PERMISSIONS.PHOTO_REMOVE).not.toBe(
      EVIDENCE_PERMISSIONS.AUDIO_REMOVE,
    );
    expect(EVIDENCE_PERMISSIONS.PHOTO_REMOVE).not.toBe(
      EVIDENCE_PERMISSIONS.PHOTO_ADD,
    );
    expect(EVIDENCE_PERMISSIONS.AUDIO_REMOVE).not.toBe(
      EVIDENCE_PERMISSIONS.AUDIO_ADD,
    );
  });
});

/**
 * The Customer capability catalogue (`BR-006`, `BR-023`, `BR-092`; `ADR-021` D1).
 *
 * `customers.view_assigned` is the capability this slice adds: the field read of the Customer behind an
 * assigned Job. It is pinned here for the same reason the evidence and Visit codes are — the migration
 * and the development seed insert it, the Job projection answers its question with it, and the Android
 * screen gates the block on it, so a rename would break the API and the client together (`BR-041`).
 */
describe('customer permissions', () => {
  it('pins the codes the Customer routes and the Job read share', () => {
    expect(CUSTOMER_PERMISSIONS).toEqual({
      VIEW: 'customers.view',
      CREATE: 'customers.create',
      EDIT: 'customers.edit',
      ARCHIVE: 'customers.archive',
      VIEW_ASSIGNED: 'customers.view_assigned',
    });
  });

  it('keeps the assigned read apart from the organization-wide one', () => {
    // `BR-006` requires a capability to name one thing a role can plausibly hold on its own: a
    // technician reads the Customer of an assigned Job, and that must never collapse into the
    // capability that opens the organization's whole customer book (`BR-009`, `BR-092`; `ADR-021` D1).
    expect(CUSTOMER_PERMISSIONS.VIEW_ASSIGNED).not.toBe(
      CUSTOMER_PERMISSIONS.VIEW,
    );
  });
});

/**
 * The contact-person capability catalogue (`BR-095`; `ADR-022` D4).
 *
 * `customers.contacts.create`, `customers.contacts.edit` and `customers.contacts.remove` are the set
 * this slice adds. Migration `0014` inserts their bilingual catalogue rows and grants them to the
 * default Manager role, the API guards the three contact routes by them, and the Android screens
 * draw their contact actions from them — so a rename would break the API and the client together
 * (`BR-041`).
 *
 * `customers.edit` deliberately does not appear here: it no longer authorizes any contact write
 * (`BR-095`), which is the position `BR-085` already took for Properties.
 */
describe('customer contact permissions', () => {
  it('pins the codes the contact routes, the migration and the client share', () => {
    expect(CUSTOMER_CONTACT_PERMISSIONS).toEqual({
      CREATE: 'customers.contacts.create',
      EDIT: 'customers.contacts.edit',
      REMOVE: 'customers.contacts.remove',
    });
  });

  it('keeps creating, editing and removing apart', () => {
    // A role may legitimately add a contact person without being trusted to change or delete the
    // people the organization calls (`BR-006`, `BR-095`), so no two capabilities may collapse into
    // the same code.
    const codes = Object.values(CUSTOMER_CONTACT_PERMISSIONS);
    expect(new Set(codes).size).toBe(codes.length);
  });

  it('does not reuse a capability outside the contact set', () => {
    // The codes are their own set rather than aliases of `customers.edit` or of the organization-wide
    // read (`BR-085`, `BR-095`; `ADR-022` D4).
    const contactCodes = Object.values(
      CUSTOMER_CONTACT_PERMISSIONS,
    ) as string[];
    expect(contactCodes).not.toContain(CUSTOMER_PERMISSIONS.EDIT);
    expect(contactCodes).not.toContain(CUSTOMER_PERMISSIONS.VIEW);
  });
});

/**
 * The field capability catalogue (`BR-009`; `ADR-019` D1, D3).
 *
 * These four codes are **not** introduced by this slice. Migration `0004_woozy_spitfire` creates them
 * with their bilingual catalogue rows and grants exactly them to the default Technician role, and the
 * development seed mirrors the grants. They are pinned here because the other half of `BR-006` —
 * enforcement — now exists: a route is guarded by them and a client draws its field action from them,
 * so a rename would break the API and every client at once (`BR-041`).
 */
describe('visit permissions', () => {
  it('pins the field, scheduling, and follow-up codes shared by routes and clients', () => {
    expect(VISIT_PERMISSIONS).toEqual({
      VIEW_ASSIGNED: 'VISIT_VIEW_ASSIGNED',
      CREATE_SCHEDULE: 'visits.create_schedule',
      UPDATE_SCHEDULE: 'visits.update_schedule',
      ASSIGN_TECHNICIANS: 'visits.assign_technicians',
      REQUEST_FOLLOW_UP: 'visits.request_follow_up',
      REVIEW_REQUESTS: 'visits.review_requests',
      UPDATE_ASSIGNED_STATUS: 'VISIT_UPDATE_ASSIGNED_STATUS',
      ADD_NOTE: 'VISIT_ADD_NOTE',
      RECORD_OUTCOME: 'VISIT_RECORD_OUTCOME',
    });
  });

  it('keeps each Visit capability distinct', () => {
    // A company may withdraw one of these from a member without withdrawing the others (`BR-006`,
    // `BR-009`), so no two capabilities may collapse into the same code.
    const codes = Object.values(VISIT_PERMISSIONS);
    expect(new Set(codes).size).toBe(codes.length);
  });
});
