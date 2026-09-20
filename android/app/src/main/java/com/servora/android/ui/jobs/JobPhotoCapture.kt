package com.servora.android.ui.jobs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.servora.android.data.jobs.jobPhotoCaptureUri
import com.servora.android.domain.model.CapturedJobPhoto

/**
 * The camera action, wired to the device's own camera application (`BR-015`, `BR-012`).
 *
 * The photo is captured by `ACTION_IMAGE_CAPTURE` into a file this app owns: the identity and the
 * app-private path are allocated **before** the camera opens, so the bytes, the photo's record and
 * its idempotency key are the same value if the capture is uploaded hours later (offline standard
 * §5, §9). Servora therefore asks for no camera permission and reads no bytes from another
 * application's storage.
 *
 * Returns the action to call: it allocates the target and, when the device has no camera application
 * to answer, reports the cancellation rather than failing silently.
 */
@Composable
internal fun rememberJobPhotoCapture(
    beginCapture: () -> CapturedJobPhoto?,
    onCaptured: (CapturedJobPhoto) -> Unit,
    onCancelled: (CapturedJobPhoto?) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    var target by remember { mutableStateOf<CapturedJobPhoto?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val captured = target
        target = null
        if (success && captured != null) {
            onCaptured(captured)
        } else {
            // The camera was dismissed, or it wrote nothing. Nothing is recorded, so nothing is
            // claimed: a photo that does not exist is never presented as evidence (`BR-042`). The
            // target is handed back so a file the camera may have created is cleaned up (`§9`).
            onCancelled(captured)
        }
    }

    return {
        val captured = beginCapture()
        if (captured == null) {
            // No session can own the photo, so the camera is not opened at all (`§10`).
            onCancelled(null)
        } else {
            target = captured
            val launched = runCatching {
                launcher.launch(jobPhotoCaptureUri(context, captured.localPath))
            }
            if (launched.isFailure) {
                target = null
                onCancelled(captured)
            }
        }
    }
}
