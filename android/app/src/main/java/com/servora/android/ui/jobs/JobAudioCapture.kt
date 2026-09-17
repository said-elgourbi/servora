package com.servora.android.ui.jobs

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * The record action, wired to the microphone permission the platform requires (`BR-091`, `BR-012`).
 *
 * Recording is the one update kind that needs a permission, and it is asked for **when the technician
 * asks to record**, never at launch: a Kitchen that only adds notes or photos is never interrupted for a
 * microphone it would not use (`BR-012`). A grant starts the recording; a decline is reported rather
 * than left looking as though nothing happened, and changes nothing else about the Job (`BR-042`).
 *
 * Returns the action to call: it starts recording when the permission is already held, and otherwise
 * asks for it and starts when the answer is yes.
 */
@Composable
internal fun rememberJobAudioRecorder(
    onGranted: () -> Unit,
    onDenied: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) onGranted() else onDenied()
    }

    return {
        val held = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (held) {
            onGranted()
        } else {
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
