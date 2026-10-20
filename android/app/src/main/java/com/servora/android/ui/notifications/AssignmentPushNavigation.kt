package com.servora.android.ui.notifications

import androidx.compose.runtime.staticCompositionLocalOf
import com.servora.android.data.push.AssignmentNotifications

/** The authenticated navigation host owns routing; previews and isolated UI tests need no receiver. */
val LocalAssignmentNotifications = staticCompositionLocalOf<AssignmentNotifications?> { null }
