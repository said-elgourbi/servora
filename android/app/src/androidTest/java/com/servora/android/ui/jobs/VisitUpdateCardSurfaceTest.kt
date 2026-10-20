package com.servora.android.ui.jobs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.servora.android.data.jobs.JobPhotoImages
import com.servora.android.domain.model.EvidencePhase
import com.servora.android.domain.model.JobActivityEvent
import com.servora.android.domain.model.JobActivityKind
import com.servora.android.ui.theme.ServoraTheme
import kotlin.math.abs
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val VisitUpdateCardPageSwatchTag = "visit-update-card-page-swatch"

@RunWith(AndroidJUnit4::class)
class VisitUpdateCardSurfaceTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun updateCardAndAudioAttachmentHaveDistinctLightSurfaces() {
        val colors = renderAndCapture(darkTheme = false)

        assertTrue(
            "Light update card should be visibly lighter than the page background: $colors",
            colors.card.luminance() > colors.page.luminance() + 0.015f,
        )
        assertTrue(
            "Light system card should be distinct from the user update card: $colors",
            colors.system.distanceTo(colors.card) > 0.015f,
        )
        assertTrue(
            "Light system card should stay separated from the page background: $colors",
            colors.system.distanceTo(colors.page) > 0.015f,
        )
        assertTrue(
            "Light audio attachment should be distinct from the update card: $colors",
            colors.card.distanceTo(colors.media) > 0.025f,
        )
    }

    @Test
    fun updateCardAndAudioAttachmentHaveDistinctDarkSurfaces() {
        val colors = renderAndCapture(darkTheme = true)

        assertTrue(
            "Dark update card should be visually distinct from the page background: $colors",
            colors.card.distanceTo(colors.page) > 0.08f,
        )
        assertTrue(
            "Dark system card should be subtly elevated from the user update card: $colors",
            colors.system.luminance() > colors.card.luminance() + 0.005f,
        )
        assertTrue(
            "Dark system card should stay separated from the page background: $colors",
            colors.system.distanceTo(colors.page) > 0.08f,
        )
        assertTrue(
            "Dark audio attachment should be subtly lighter than the update card: $colors",
            colors.media.luminance() > colors.card.luminance() + 0.005f,
        )
    }

    private fun renderAndCapture(darkTheme: Boolean): SurfaceColors {
        composeTestRule.setContent {
            ServoraTheme(darkTheme = darkTheme) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
                        .padding(16.dp),
                ) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
                            .testTag(VisitUpdateCardPageSwatchTag),
                    )
                    ActivityTimeline(
                        events = listOf(noteEvent(), systemEvent(), audioEvent()),
                        jobId = "job-1",
                        photoImages = JobPhotoImages.None,
                        onOpenPhoto = {},
                        canManageVisitNotes = false,
                        onEditVisitNote = { _, _ -> },
                        onRemoveVisitNote = { _, _ -> },
                        audio = JobActivityAudio(),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }

        return SurfaceColors(
            page = composeTestRule.onNodeWithTag(VisitUpdateCardPageSwatchTag).dominantColor(),
            card = composeTestRule.onNodeWithTag(jobActivityEventTag("note-event")).dominantColor(),
            system = composeTestRule.onNodeWithTag(jobActivityEventTag("system-event")).dominantColor(),
            media = composeTestRule.onNodeWithTag(jobAudioActivityAttachmentTag("audio-1")).dominantColor(),
        )
    }

    private fun noteEvent(): JobActivityEvent = baseEvent(
        id = "note-event",
        kind = JobActivityKind.VISIT_NOTE_ADDED,
        body = "Checked the filter and reset the thermostat.",
    )

    private fun audioEvent(): JobActivityEvent = baseEvent(
        id = "audio-event",
        kind = JobActivityKind.JOB_AUDIO_ADDED,
        body = "Compressor noise after startup.",
        audioNoteId = "audio-1",
        audioPhase = EvidencePhase.DURING_WORK.name,
        audioDurationSeconds = 42,
    )

    private fun systemEvent(): JobActivityEvent = baseEvent(
        id = "system-event",
        kind = JobActivityKind.VISIT_STATUS_CHANGED,
        body = null,
        toStatus = "COMPLETED",
    )

    private fun baseEvent(
        id: String,
        kind: JobActivityKind,
        body: String?,
        toStatus: String? = null,
        audioNoteId: String? = null,
        audioPhase: String? = null,
        audioDurationSeconds: Int? = null,
    ): JobActivityEvent = JobActivityEvent(
        id = id,
        kind = kind,
        recordedAt = "2026-09-30T14:00:00Z",
        actorName = "Sam Rivera",
        visitSequence = 1,
        fromStatus = null,
        toStatus = toStatus,
        technicianName = null,
        roleCode = null,
        previousRoleCode = null,
        outcomeCode = null,
        outcomeSummary = null,
        body = body,
        audioNoteId = audioNoteId,
        audioPhase = audioPhase,
        audioDurationSeconds = audioDurationSeconds,
    )
}

private data class SurfaceColors(
    val page: Color,
    val card: Color,
    val system: Color,
    val media: Color,
)

private fun androidx.compose.ui.test.SemanticsNodeInteraction.dominantColor(): Color {
    val pixels = captureToImage().toPixelMap()
    val counts = mutableMapOf<Int, Int>()
    for (y in 0 until pixels.height) {
        for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            if (color.alpha < 0.95f) continue
            val key =
                (color.red.toByteChannel() shl 16) or
                    (color.green.toByteChannel() shl 8) or
                    color.blue.toByteChannel()
            counts[key] = counts.getOrDefault(key, 0) + 1
        }
    }
    val key = counts.maxBy { it.value }.key
    return Color(
        red = ((key shr 16) and 0xff) / 255f,
        green = ((key shr 8) and 0xff) / 255f,
        blue = (key and 0xff) / 255f,
    )
}

private fun Float.toByteChannel(): Int = (coerceIn(0f, 1f) * 255f).toInt()

private fun Color.distanceTo(other: Color): Float =
    abs(red - other.red) + abs(green - other.green) + abs(blue - other.blue)

private fun Color.luminance(): Float =
    0.2126f * red.linearize() + 0.7152f * green.linearize() + 0.0722f * blue.linearize()

private fun Float.linearize(): Float =
    if (this <= 0.03928f) this / 12.92f else ((this + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
