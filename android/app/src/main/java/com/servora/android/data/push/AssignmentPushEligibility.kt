package com.servora.android.data.push

import java.time.Instant

/** A push is a hint, never a credential or an authorization grant. */
internal fun assignmentBindingMatches(
    message: AssignmentPushMessage,
    installation: PushInstallation?,
    sessionId: String?,
    now: Instant,
): Boolean = sessionId != null && installation != null &&
    installation.sessionId == sessionId && installation.bindingId == message.bindingId &&
    installation.organizationId == message.organizationId && !installation.needsTargetReset &&
    message.expiresAt > now
