import { EVIDENCE_PERMISSIONS, VISIT_PERMISSIONS } from './permissions.js';

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
    expect(EVIDENCE_PERMISSIONS.PHOTO_ADD).not.toBe(EVIDENCE_PERMISSIONS.AUDIO_ADD);
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
 * The field capability catalogue (`BR-009`; `ADR-019` D1, D3).
 *
 * These four codes are **not** introduced by this slice. Migration `0004_woozy_spitfire` creates them
 * with their bilingual catalogue rows and grants exactly them to the default Technician role, and the
 * development seed mirrors the grants. They are pinned here because the other half of `BR-006` —
 * enforcement — now exists: a route is guarded by them and a client draws its field action from them,
 * so a rename would break the API and every client at once (`BR-041`).
 */
describe('visit permissions', () => {
  it('pins the four codes the Technician role is granted and the routes enforce', () => {
    expect(VISIT_PERMISSIONS).toEqual({
      VIEW_ASSIGNED: 'VISIT_VIEW_ASSIGNED',
      UPDATE_ASSIGNED_STATUS: 'VISIT_UPDATE_ASSIGNED_STATUS',
      ADD_NOTE: 'VISIT_ADD_NOTE',
      RECORD_OUTCOME: 'VISIT_RECORD_OUTCOME',
    });
  });

  it('keeps reading, acting, noting and recording apart', () => {
    // A company may withdraw one of these from a member without withdrawing the others (`BR-006`,
    // `BR-009`), so no two capabilities may collapse into the same code.
    const codes = Object.values(VISIT_PERMISSIONS);
    expect(new Set(codes).size).toBe(codes.length);
  });
});
