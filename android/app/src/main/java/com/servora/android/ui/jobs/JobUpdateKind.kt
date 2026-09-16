package com.servora.android.ui.jobs

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.servora.android.R

/*
 * The kinds of update the Add update sheet adds, and what each one is called and drawn with.
 *
 * No Compose lives here: which kinds a session may add is the one part of the sheet that is a rule
 * rather than a drawing, so it is stated where it can be verified without a device (`qa.md` §6.1).
 */

/**
 * One kind of update a technician may add to a Job's Activity (`BR-015`, `BR-027`).
 *
 * The kinds are **peers**: the sheet states which one is in effect and then draws only that kind's own
 * controls, so a new kind is a new mode rather than another action in a growing list. Audio is declared
 * as a peer here and is not offered yet (`offeredUpdateKinds`).
 */
internal enum class JobUpdateKind { NOTE, PHOTO, AUDIO }

/**
 * The kinds a session may add, in the order the sheet offers them: the everyday case first (`BR-012`).
 *
 * A kind is offered only where the session may actually record it, because an update nobody can add is
 * not presented (`BR-042`). Audio is a first-class kind, but no product rule accepts an audio recording
 * yet — the API's capability for it is reserved and deliberately not created (`evidence.audio.add`,
 * `docs/decisions/015-evidence-capabilities.md` D2) — so no caller offers it today, and its controls are
 * the seam the recorder lands in (`JobUpdateAudioContent`).
 */
internal fun offeredUpdateKinds(
    canWriteNote: Boolean,
    canAddPhoto: Boolean,
    canAddAudio: Boolean,
): List<JobUpdateKind> = buildList {
    if (canWriteNote) add(JobUpdateKind.NOTE)
    if (canAddPhoto) add(JobUpdateKind.PHOTO)
    if (canAddAudio) add(JobUpdateKind.AUDIO)
}

/** What one kind is called. Only the label is localized; what it writes is the API's (`BR-028`). */
@StringRes
internal fun updateKindLabelRes(kind: JobUpdateKind): Int = when (kind) {
    JobUpdateKind.NOTE -> R.string.job_activity_update_note
    JobUpdateKind.PHOTO -> R.string.job_photo_add_action
    JobUpdateKind.AUDIO -> R.string.job_activity_update_audio
}

/** The glyph one kind is drawn with, from the icon set the design uses. */
@DrawableRes
internal fun updateKindIconRes(kind: JobUpdateKind): Int = when (kind) {
    JobUpdateKind.NOTE -> R.drawable.ic_file_text
    JobUpdateKind.PHOTO -> R.drawable.ic_camera
    JobUpdateKind.AUDIO -> R.drawable.ic_mic
}
