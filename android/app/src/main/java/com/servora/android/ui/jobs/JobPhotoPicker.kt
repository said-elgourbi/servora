package com.servora.android.ui.jobs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

/**
 * The library action: the device's own photo picker, for photos the technician already has (`D3`).
 *
 * It is the second of the two sources a photo update may come from (`BR-015`). The system picker is
 * used rather than a gallery Servora reads itself, so **no storage or media-read permission** is
 * requested and nothing outside what the technician selects is read (`docs/tracker/029-photo-evidence-phases.md`
 * D3). It is offered only images, because v1 evidence is photos (`BR-027`, D8).
 *
 * Returns the action to call. What it hands over is each item's own address, in the order the
 * technician chose them: a content URI belongs to whoever serves it, so reading the bytes is the data
 * layer's business ([com.servora.android.data.jobs.JobPhotoPickedItems]), exactly as it is for the
 * camera's file. An empty hand-over is a dismissed picker — nothing was chosen, so nothing is
 * recorded — and a picker no application on the device can show is reported rather than passing as a
 * cancellation (`BR-042`, `dev.md` §1).
 */
@Composable
internal fun rememberJobPhotoPicker(
    onPicked: (List<String>) -> Unit,
    onUnavailable: () -> Unit,
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        onPicked(uris.map { uri -> uri.toString() })
    }

    return {
        val launched = runCatching {
            launcher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
        if (launched.isFailure) {
            onUnavailable()
        }
    }
}
