import { EVIDENCE_PERMISSIONS } from './permissions.js';

/**
 * The evidence capability catalogue (`BR-006`, `BR-015`, `BR-027`; tracker 029 D1/D1b).
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
    });
  });

  it('keeps the reserved audio capability out of the catalogue until audio exists', () => {
    // Audio is agreed as a future kind but no rule accepts it yet (`BR-027`, tracker 029 D8), so
    // creating `evidence.audio.add` now would grant a capability nothing can exercise (`BR-042`).
    expect(Object.values(EVIDENCE_PERMISSIONS)).not.toContain(
      'evidence.audio.add',
    );
  });
});
