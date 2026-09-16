import { EVIDENCE_PERMISSIONS } from './permissions.js';

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
