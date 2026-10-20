package com.servora.android.ui.jobs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

/** The device's own document picker, constrained to quote PDFs. */
@Composable
internal fun rememberJobQuoteDocumentPicker(
    onPicked: (String) -> Unit,
    onUnavailable: () -> Unit,
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onPicked(it.toString()) }
    }
    return {
        val launched = runCatching { launcher.launch(QUOTE_PDF_MIME_TYPE) }
        if (launched.isFailure) {
            onUnavailable()
        }
    }
}

private const val QUOTE_PDF_MIME_TYPE = "application/pdf"
