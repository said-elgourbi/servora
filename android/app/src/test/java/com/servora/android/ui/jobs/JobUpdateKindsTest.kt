package com.servora.android.ui.jobs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which kinds of update the Add update sheet offers, and in what order (`BR-012`, `BR-042`).
 *
 * The kinds a session may add are the part of the sheet that decides what can be reached at all, and it
 * is a rule rather than a drawing: a note is the everyday case and comes first, a kind is offered only
 * where the capability for it exists, and audio is a first-class kind that no call may offer while the
 * API has no capability to accept a recording (`docs/decisions/015-evidence-capabilities.md` D2).
 */
class JobUpdateKindsTest {

    @Test
    fun offersTheEverydayKindFirst() {
        assertEquals(
            listOf(JobUpdateKind.NOTE, JobUpdateKind.PHOTO),
            offeredUpdateKinds(canWriteNote = true, canAddPhoto = true, canAddAudio = false),
        )
    }

    @Test
    fun offersOnlyTheKindsTheSessionMayAdd() {
        // A Job with no represented Visit can take no note, and only the kinds it can take are stated
        // (`BR-015`, `BR-051`).
        assertEquals(
            listOf(JobUpdateKind.PHOTO),
            offeredUpdateKinds(canWriteNote = false, canAddPhoto = true, canAddAudio = false),
        )
        assertEquals(
            listOf(JobUpdateKind.NOTE),
            offeredUpdateKinds(canWriteNote = true, canAddPhoto = false, canAddAudio = false),
        )
    }

    @Test
    fun offersAudioAsAFirstClassKindWhenTheSessionMayAddIt() {
        // Audio is a peer of the other two rather than a property of the photo kind: when it exists it is
        // offered alongside them, and the sheet's layout then grows by one segment (`BR-027`).
        assertEquals(
            listOf(JobUpdateKind.NOTE, JobUpdateKind.PHOTO, JobUpdateKind.AUDIO),
            offeredUpdateKinds(canWriteNote = true, canAddPhoto = true, canAddAudio = true),
        )
        assertEquals(
            listOf(JobUpdateKind.AUDIO),
            offeredUpdateKinds(canWriteNote = false, canAddPhoto = false, canAddAudio = true),
        )
    }

    @Test
    fun offersNoKindWhenTheSessionMayAddNothing() {
        // An action nobody may perform is not offered, and with no kind offered the sheet states no
        // kind and draws no kind's controls (`BR-042`).
        assertEquals(
            emptyList<JobUpdateKind>(),
            offeredUpdateKinds(canWriteNote = false, canAddPhoto = false, canAddAudio = false),
        )
    }
}
