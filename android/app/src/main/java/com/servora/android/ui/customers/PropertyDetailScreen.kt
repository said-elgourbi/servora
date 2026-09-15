package com.servora.android.ui.customers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.data.customers.PropertyLifecycleAction
import com.servora.android.data.offline.ReadSource
import com.servora.android.data.customers.QueuedPropertyOperation
import com.servora.android.domain.model.PropertyDetail
import com.servora.android.domain.model.PropertyStatus
import com.servora.android.ui.components.InfoCard
import com.servora.android.ui.components.OfflineNotice
import com.servora.android.ui.components.SectionLabel

const val PropertyDetailTag = "property-detail"
const val PropertyDetailContentTag = "property-detail-content"
const val PropertyDetailArchivedBadgeTag = "property-detail-archived-badge"
const val PropertyDetailArchiveActionTag = "property-detail-archive"
const val PropertyDetailRestoreActionTag = "property-detail-restore"
const val PropertyDetailDeleteActionTag = "property-detail-delete"
const val PropertyDetailActionMessageTag = "property-detail-action-message"
const val PropertyDetailDeletionBlockedTag = "property-detail-deletion-blocked"
const val PropertyDetailArchiveDialogTag = "property-detail-archive-dialog"
const val PropertyDetailRestoreDialogTag = "property-detail-restore-dialog"
const val PropertyDetailDeleteDialogTag = "property-detail-delete-dialog"
const val PropertyDetailArchiveConfirmTag = "property-detail-archive-confirm"
const val PropertyDetailRestoreConfirmTag = "property-detail-restore-confirm"
const val PropertyDetailDeleteConfirmTag = "property-detail-delete-confirm"

/** The app shell's top-bar Edit action on the Property Detail destination. */
const val EditPropertyActionTag = "property-detail-edit-action"

/** Identifies the notice for a lifecycle action the backend has not answered yet (`§7`). */
const val PropertyDetailQueuedOperationTag = "property-detail-queued-operation"

/** Identifies the notice that the values shown are the last the backend reported (`§2`). */
const val PropertyDetailLastReportedTag = "property-detail-last-reported"

/**
 * The Property lifecycle screen (`BR-082` – `BR-086`).
 *
 * It draws content only: the destination's title, its context line and its permission-gated Edit
 * action belong to the app shell's one contextual top bar
 * (`docs/decisions/011-android-contextual-top-bar.md`).
 *
 * Every value shown is the backend's: the open-work counts the archive confirmation displays, the
 * last service date, and whether permanent deletion is currently allowed are the API's answers, so
 * this screen presents them rather than deriving its own (`BR-001`, `BR-041`). Actions are drawn
 * according to the caller's permissions, which is usability only — the backend enforces them
 * (`BR-007`).
 *
 * @param onDeleted invoked once the backend has confirmed the permanent deletion, so the destination
 *   can leave and the Property can be released from local state.
 * @param onLifecycleChanged invoked once the backend has confirmed an archive or restore, so the
 *   destinations that hold a derived projection of the Property can re-read it (`BR-001`).
 */
@Composable
fun PropertyDetailScreen(
    state: PropertyDetailUiState,
    canArchiveProperty: Boolean,
    canDeleteProperty: Boolean,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onDismissActionFailure: () -> Unit,
    onRetry: () -> Unit,
    onDeleted: () -> Unit,
    onLifecycleChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(state.isDeleted) {
        if (state.isDeleted) {
            onDeleted()
        }
    }

    // A confirmed archive or restore changed what the customer's Property rows and counts describe,
    // so the destination reports it and the shell re-reads them from the backend (`BR-001`).
    LaunchedEffect(state.lifecycleChanged) {
        if (state.lifecycleChanged) {
            onLifecycleChanged()
        }
    }

    var confirmArchive by rememberSaveable { mutableStateOf(false) }
    var confirmRestore by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    val detail = state.detail

    Box(modifier = modifier.fillMaxSize().testTag(PropertyDetailTag)) {
        when {
            detail != null -> PropertyDetailContent(
                detail = detail,
                isWorking = state.isWorking,
                actionFailureRes = state.actionFailure?.messageRes(),
                queuedOperation = state.queuedOperation,
                showingLastReported = state.detailSource == ReadSource.WORKING_SET,
                deletionProhibited = state.deletionProhibited,
                canArchiveProperty = canArchiveProperty,
                canDeleteProperty = canDeleteProperty,
                onRequestArchive = { confirmArchive = true },
                onRequestRestore = { confirmRestore = true },
                onRequestDelete = { confirmDelete = true },
                onDismissActionFailure = onDismissActionFailure,
            )

            state.failureReason != null -> CustomersError(onRetry = onRetry)

            else -> CustomersLoading()
        }
    }

    if (confirmArchive && detail != null) {
        ArchivePropertyDialog(
            detail = detail,
            onConfirm = {
                confirmArchive = false
                onArchive()
            },
            onDismiss = { confirmArchive = false },
        )
    }

    if (confirmRestore) {
        RestorePropertyDialog(
            onConfirm = {
                confirmRestore = false
                onRestore()
            },
            onDismiss = { confirmRestore = false },
        )
    }

    if (confirmDelete) {
        DeletePropertyDialog(
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun PropertyDetailContent(
    detail: PropertyDetail,
    isWorking: Boolean,
    actionFailureRes: Int?,
    queuedOperation: QueuedPropertyOperation?,
    showingLastReported: Boolean,
    deletionProhibited: Boolean,
    canArchiveProperty: Boolean,
    canDeleteProperty: Boolean,
    onRequestArchive: () -> Unit,
    onRequestRestore: () -> Unit,
    onRequestDelete: () -> Unit,
    onDismissActionFailure: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag(PropertyDetailContentTag)
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PropertyAddressCard(detail)

        // What the user asked for and the backend has not answered is stated next to the record
        // rather than drawn as though the Property had changed (`BR-086`, §7).
        if (queuedOperation != null) {
            QueuedOperationNotice(queuedOperation)
        }

        if (showingLastReported) {
            LastReportedNotice()
        }

        detail.notes
            ?.takeIf { it.isNotBlank() }
            ?.let { notes -> PropertyNotesCard(notes) }

        if (actionFailureRes != null) {
            ActionAttention(
                message = stringResource(actionFailureRes),
                onDismiss = onDismissActionFailure,
            )
        }

        // A refused deletion is explained where the user acted, and archiving is offered as the
        // removal this Property actually has (`BR-082`).
        if (deletionProhibited) {
            DeletionBlocked(
                canArchiveProperty = canArchiveProperty,
                onArchive = onRequestArchive,
            )
        }

        if (canArchiveProperty || (canDeleteProperty && detail.canBePermanentlyDeleted)) {
            PropertyLifecycleActions(
                detail = detail,
                isWorking = isWorking,
                canArchiveProperty = canArchiveProperty,
                canDeleteProperty = canDeleteProperty,
                onRequestArchive = onRequestArchive,
                onRequestRestore = onRequestRestore,
                onRequestDelete = onRequestDelete,
            )
        }
    }
}

/** The Property's own values: its label, address, lifecycle state and derived service history. */
@Composable
private fun PropertyAddressCard(detail: PropertyDetail) {
    val separator = stringResource(R.string.customers_counts_separator)
    InfoCard {
        detail.name?.takeIf { it.isNotBlank() }?.let { name ->
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = detail.addressLine1,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        detail.addressLine2?.takeIf { it.isNotBlank() }?.let { line2 ->
            Text(
                text = line2,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(
                R.string.customers_property_city_format,
                detail.city,
                detail.province,
                detail.postalCode,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (detail.status == PropertyStatus.ARCHIVED) {
                ArchivedBadge()
            }
            Text(
                text = listOf(
                    pluralStringResource(
                        R.plurals.customers_job_count,
                        detail.jobCount,
                        detail.jobCount,
                    ),
                    lastServiceLabel(detail.lastServiceAt),
                ).joinToString(" $separator "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The badge an archived Property carries, so its lifecycle state is visible wherever it is shown. */
@Composable
private fun ArchivedBadge() {
    Surface(
        modifier = Modifier.testTag(PropertyDetailArchivedBadgeTag),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
        ),
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            text = stringResource(R.string.property_status_archived),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PropertyNotesCard(notes: String) {
    InfoCard {
        Text(
            text = stringResource(R.string.property_notes_label),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = notes,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The lifecycle actions the caller may perform, according to permission and state (`BR-082`).
 *
 * Archiving is offered while the Property is active, restoring while it is archived, because those
 * are the two states the API accepts. Permanent deletion is offered only when the API said the
 * Property is currently deletable; the API still decides, and a refusal is explained on screen.
 */
@Composable
private fun PropertyLifecycleActions(
    detail: PropertyDetail,
    isWorking: Boolean,
    canArchiveProperty: Boolean,
    canDeleteProperty: Boolean,
    onRequestArchive: () -> Unit,
    onRequestRestore: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(
            label = stringResource(R.string.property_actions_section),
            count = null,
        )
        InfoCard {
            if (canArchiveProperty) {
                val archived = detail.status == PropertyStatus.ARCHIVED
                LifecycleAction(
                    labelRes = if (archived) {
                        R.string.property_restore_action
                    } else {
                        R.string.property_archive_action
                    },
                    glyphRes = if (archived) {
                        R.drawable.ic_check_circle
                    } else {
                        R.drawable.ic_file_text
                    },
                    descriptionRes = if (archived) {
                        R.string.property_restore_description
                    } else {
                        R.string.property_archive_description
                    },
                    testTag = if (archived) {
                        PropertyDetailRestoreActionTag
                    } else {
                        PropertyDetailArchiveActionTag
                    },
                    enabled = !isWorking,
                    destructive = false,
                    onClick = if (archived) onRequestRestore else onRequestArchive,
                )
            }
            if (canDeleteProperty && detail.canBePermanentlyDeleted) {
                if (canArchiveProperty) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                LifecycleAction(
                    labelRes = R.string.property_delete_action,
                    glyphRes = R.drawable.ic_alert_circle,
                    descriptionRes = R.string.property_delete_description,
                    testTag = PropertyDetailDeleteActionTag,
                    enabled = !isWorking,
                    destructive = true,
                    onClick = onRequestDelete,
                )
            }
        }
    }
}

/** One lifecycle action row: a glyph, its label, and the consequence it has. */
@Composable
private fun LifecycleAction(
    labelRes: Int,
    glyphRes: Int,
    descriptionRes: Int,
    testTag: String,
    enabled: Boolean,
    destructive: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            painter = painterResource(glyphRes),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
            Text(
                text = stringResource(descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The lifecycle action the backend has not answered yet (`BR-086`, §7).
 *
 * A queued action is presented as waiting, and a refused one names the reason: neither is drawn as
 * though the Property had changed, because nothing has been applied (`BR-086`).
 */
@Composable
private fun QueuedOperationNotice(operation: QueuedPropertyOperation) {
    val awaitingSync = operation.isAwaitingSync
    val message = if (awaitingSync) {
        stringResource(
            when (operation.action) {
                PropertyLifecycleAction.ARCHIVE -> R.string.property_sync_queued_archive
                PropertyLifecycleAction.RESTORE -> R.string.property_sync_queued_restore
            },
        )
    } else {
        val reason = stringResource(
            operation.failure?.messageRes() ?: R.string.property_error_server,
        )
        stringResource(
            when (operation.action) {
                PropertyLifecycleAction.ARCHIVE ->
                    R.string.property_sync_rejected_archive

                PropertyLifecycleAction.RESTORE ->
                    R.string.property_sync_rejected_restore
            },
            reason,
        )
    }

    OfflineNotice(
        message = message,
        isRefusal = !awaitingSync,
        glyphRes = if (awaitingSync) null else R.drawable.ic_alert_circle,
        tag = PropertyDetailQueuedOperationTag,
    )
}

/** States that the values on screen are the last the backend reported (`§2`, §7). */
@Composable
private fun LastReportedNotice() {
    OfflineNotice(
        message = stringResource(R.string.offline_last_reported),
        tag = PropertyDetailLastReportedTag,
    )
}

/** A refusal the user needs to see, with an explicit way to clear it. */
@Composable
private fun ActionAttention(message: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PropertyDetailActionMessageTag)
            .clickable(onClick = onDismiss),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_alert_circle),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
            Text(text = message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * The answer to a refused permanent deletion (`BR-082`).
 *
 * The Property is not deleted and the user stays on the screen; archiving is the removal this
 * Property has, and it is offered when the caller may perform it.
 */
@Composable
private fun DeletionBlocked(canArchiveProperty: Boolean, onArchive: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PropertyDetailDeletionBlockedTag),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.property_delete_blocked_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.property_delete_blocked_message),
                style = MaterialTheme.typography.bodySmall,
            )
            if (canArchiveProperty) {
                TextButton(onClick = onArchive) {
                    Text(stringResource(R.string.property_archive_action))
                }
            }
        }
    }
}

/**
 * The archive confirmation (`BR-083`).
 *
 * It states what archiving does — the Property stops being available for new jobs — and, when
 * current work exists, shows the counts the API reported and says explicitly that the work
 * continues. Nothing is cancelled or rescheduled by this action.
 */
@Composable
private fun ArchivePropertyDialog(
    detail: PropertyDetail,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag(PropertyDetailArchiveDialogTag),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.property_archive_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.property_archive_confirm_message))
                if (detail.archiveImpact.hasOpenWork) {
                    Text(stringResource(R.string.property_archive_open_work_lead))
                    Text(
                        text = pluralStringResource(
                            R.plurals.property_archive_open_jobs,
                            detail.archiveImpact.activeJobCount,
                            detail.archiveImpact.activeJobCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = pluralStringResource(
                            R.plurals.property_archive_open_visits,
                            detail.archiveImpact.activeVisitCount,
                            detail.archiveImpact.activeVisitCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(stringResource(R.string.property_archive_open_work_continue))
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag(PropertyDetailArchiveConfirmTag),
                onClick = onConfirm,
            ) {
                Text(stringResource(R.string.property_archive_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.property_cancel))
            }
        },
    )
}

/** The restore confirmation: the Property returns to active use, and nothing else changes. */
@Composable
private fun RestorePropertyDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.testTag(PropertyDetailRestoreDialogTag),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.property_restore_confirm_title)) },
        text = { Text(stringResource(R.string.property_restore_confirm_message)) },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag(PropertyDetailRestoreConfirmTag),
                onClick = onConfirm,
            ) {
                Text(stringResource(R.string.property_restore_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.property_cancel))
            }
        },
    )
}

/** The destructive confirmation permanent deletion uses, separate from archiving. */
@Composable
private fun DeletePropertyDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.testTag(PropertyDetailDeleteDialogTag),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.property_delete_confirm_title)) },
        text = { Text(stringResource(R.string.property_delete_confirm_message)) },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag(PropertyDetailDeleteConfirmTag),
                onClick = onConfirm,
            ) {
                Text(
                    text = stringResource(R.string.property_delete_confirm_action),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.property_cancel))
            }
        },
    )
}
