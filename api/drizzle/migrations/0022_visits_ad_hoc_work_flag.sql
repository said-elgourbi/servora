-- Mark Visits created from a reconciled ad-hoc work report (`BR-AH-003`, `BR-AH-006`).
--
-- The Visit sequence stays derived from creation order (`visit-assignment.ts`), but a Visit that the
-- office inserted after the fact as historical work must be recognisable in the read model so a screen
-- can label it "ad-hoc completed work" rather than implying it happened after a later-numbered Visit.
-- The flag is `false` for every Visit created through the normal scheduling path.
ALTER TABLE "visits" ADD COLUMN "is_ad_hoc_work" boolean DEFAULT false NOT NULL;
