package com.servora.android.ui.auth

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager

/**
 * Drops focus from the focused text field when the soft keyboard goes away.
 *
 * Why this exists: when the keyboard is dismissed by something other than the field itself — the
 * system back gesture, the IME's own Done/dismiss affordance, a window resize — the field stays
 * focused. Material renders the label and placeholder from the focus state, so the field keeps its
 * floated label and visible placeholder even though the layout has already returned to its resting
 * position. The fix belongs to the focus state, not to the layout: no padding or offset is involved
 * anywhere.
 *
 * Only a visible → hidden transition is acted on, because that is what a dismissal produces. The
 * transition is tracked explicitly rather than reacting to "the IME is not visible": clearing focus
 * whenever the IME is absent would also wipe a deliberate focus in the moment between a field
 * requesting focus and the keyboard appearing.
 *
 * The dismissals the authentication screens currently have are the system back gesture and the
 * IME's own Done/dismiss action; tapping outside a field does not dismiss the keyboard on these
 * screens at all (a pre-existing gap, not a state this composable can observe).
 *
 * [imeVisible] is a parameter so the transition can be driven deterministically from tests; the
 * production value is the window's IME inset visibility.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ClearFocusWhenImeHidden(imeVisible: Boolean = WindowInsets.isImeVisible) {
    val focusManager = LocalFocusManager.current
    var wasVisible by remember { mutableStateOf(false) }

    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            wasVisible = true
        } else if (wasVisible) {
            wasVisible = false
            focusManager.clearFocus()
        }
    }
}
