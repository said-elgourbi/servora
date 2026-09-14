package com.servora.android.ui.customers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.domain.model.Customer
import com.servora.android.domain.model.CustomerDetail
import com.servora.android.domain.model.CustomerJob
import com.servora.android.domain.model.CustomerJobTechnician
import com.servora.android.domain.model.CustomerProperty
import com.servora.android.domain.model.CustomerType
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.PropertyStatus
import com.servora.android.ui.components.InfoCard
import com.servora.android.ui.components.JobStatusPill
import com.servora.android.ui.components.SectionLabel
import com.servora.android.ui.components.addressLine
import com.servora.android.ui.theme.stateColors

const val CustomerDetailTag = "customer-detail"
const val CustomerDetailContentTag = "customer-detail-content"
const val CustomerDetailAllJobsTag = "customer-detail-all-jobs"
const val CustomerDetailCreateJobTag = "customer-detail-create-job"
const val CustomerDetailPropertiesTag = "customer-detail-properties"
const val CustomerDetailJobsTag = "customer-detail-jobs"
const val CustomerDetailSeeAllJobsTag = "customer-detail-see-all-jobs"
const val CustomerDetailAddPropertyTag = "customer-detail-add-property"

/** The disclosure that keeps the customer's archived Properties reachable (`BR-082`). */
const val CustomerDetailArchivedPropertiesTag = "customer-detail-archived-properties"

fun customerDetailPropertyTag(propertyId: String): String =
    "customer-detail-property-$propertyId"

fun customerDetailJobTag(jobId: String): String = "customer-detail-job-$jobId"

/** How many Jobs the detail previews before the Job history takes over, as designed. */
private const val CustomerDetailJobPreviewLimit = 3

/**
 * The customer detail (`BR-081`).
 *
 * Every Property and Job value is the backend's projection: this screen formats the dates, status
 * codes and derived values it is given and does not choose which Visit or Property value to show
 * (`BR-041`).
 *
 * The customer's Jobs open a full history, which the design provides as its own view and which the
 * navigation graph holds as its own destination. Properties are all listed because no Property list
 * screen exists yet to continue to.
 */
@Composable
fun CustomerDetailScreen(
    state: CustomerDetailUiState,
    canViewProperties: Boolean,
    canAddProperty: Boolean,
    onAddProperty: () -> Unit,
    onSeeAllJobs: () -> Unit,
    onRetry: () -> Unit,
    onOpenProperty: (propertyId: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val detail = state.detail

    // The screen draws its content only: this destination's title, its back control and the
    // permission-gated Edit action belong to the app shell's one contextual top bar
    // (`docs/decisions/011-android-contextual-top-bar.md`).
    Box(modifier = modifier.fillMaxSize().testTag(CustomerDetailTag)) {
        when {
            detail != null ->
                CustomerDetailContent(
                    detail = detail,
                    canViewProperties = canViewProperties,
                    canAddProperty = canAddProperty,
                    onAddProperty = onAddProperty,
                    onSeeAllJobs = onSeeAllJobs,
                    onOpenProperty = onOpenProperty,
                )

            state.failureReason != null -> CustomersError(onRetry = onRetry)

            else -> CustomersLoading()
        }

        // Create Job is pinned as a floating action, as the product owner directed; the design
        // draws it in the actions row. The action is not wired yet: the Jobs feature has no
        // create path, so this stays an affordance rather than a fabricated job.
        ExtendedFloatingActionButton(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .testTag(CustomerDetailCreateJobTag),
            onClick = {},
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.customers_create_job))
        }
    }
}

@Composable
private fun CustomerDetailContent(
    detail: CustomerDetail,
    canViewProperties: Boolean,
    canAddProperty: Boolean,
    onAddProperty: () -> Unit,
    onSeeAllJobs: () -> Unit,
    onOpenProperty: (propertyId: String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(CustomerDetailContentTag),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { CustomerDetailIdentity(detail) }
        item { CustomerDetailContactCard(detail.customer) }
        // Properties are their own capability set (`BR-085`), so the section is absent rather than
        // empty for a caller without `properties.view`; the backend independently refuses the read
        // (`BR-007`).
        if (canViewProperties) {
            item {
                CustomerPropertiesSection(
                    properties = detail.properties,
                    archivedProperties = detail.archivedProperties,
                    canAddProperty = canAddProperty,
                    onAddProperty = onAddProperty,
                    onOpenProperty = onOpenProperty,
                )
            }
        }
        detail.customer.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            item { CustomerNotesCard(notes) }
        }
        item { CustomerJobsSection(detail.jobs, onSeeAllJobs = onSeeAllJobs) }
    }
}

@Composable
private fun CustomerDetailIdentity(detail: CustomerDetail) {
    val customer = detail.customer
    val isCompany = customer.type == CustomerType.COMPANY
    val since = remember(customer.createdAt) { customerSince(customer.createdAt) }
    // The design shows the company's contact person under its name, which is the primary contact.
    val contactName = if (isCompany) {
        detail.contacts.firstOrNull { it.isPrimary }?.let { contact ->
            listOf(contact.firstName, contact.lastName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        }
    } else {
        null
    }

    Row(verticalAlignment = Alignment.Top) {
        CustomerAvatar(displayName = customer.displayName, isCompany = isCompany)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = customer.displayName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            contactName?.let { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusPill(status = customer.status)
                since?.let { date ->
                    Text(
                        text = stringResource(R.string.customers_since_format, date),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** The customer's phone and email, each a link, as designed. */
@Composable
private fun CustomerDetailContactCard(customer: Customer) {
    val context = LocalContext.current
    val lines = listOfNotNull(customer.phone, customer.email)

    InfoCard {
        if (lines.isEmpty()) {
            Text(
                text = stringResource(R.string.customers_no_contact),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        customer.phone?.let { phone ->
            CustomerContactLine(
                text = phone,
                glyph = R.drawable.ic_phone,
                onClick = { context.startContactIntent(dialIntent(phone)) },
            )
        }
        if (customer.phone != null && customer.email != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        customer.email?.let { email ->
            CustomerContactLine(
                text = email,
                glyph = R.drawable.ic_mail,
                onClick = { context.startContactIntent(mailIntent(email)) },
            )
        }
    }
}

@Composable
private fun CustomerNotesCard(notes: String) {
    InfoCard {
        Text(
            text = stringResource(R.string.customers_detail_notes),
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
 * The customer's Properties, each with the backend's derived job count and last service date
 * (`BR-081`).
 *
 * Every active Property is listed: the design truncates the section and continues to a Property
 * list, which this app does not have yet. Showing everything keeps a Property the backend returned
 * from being unreachable.
 *
 * The customer's archived Properties are excluded from the active projection (`BR-081`), so they sit
 * behind the [ArchivedPropertiesDisclosure] rather than being listed as active work. Without that
 * disclosure an archived Property would be unreachable from Android and could never be restored
 * (`BR-082`).
 */
@Composable
private fun CustomerPropertiesSection(
    properties: List<CustomerProperty>,
    archivedProperties: List<CustomerProperty>,
    canAddProperty: Boolean,
    onAddProperty: () -> Unit,
    onOpenProperty: (propertyId: String) -> Unit,
) {
    val hasArchived = archivedProperties.isNotEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(CustomerDetailPropertiesTag),
    ) {
        SectionLabel(
            label = stringResource(R.string.customers_detail_properties),
            count = properties.size,
        )
        InfoCard {
            // The empty state belongs only to a customer with no Property at all: telling a user
            // with archived Properties that none were added would contradict the rows below it.
            if (properties.isEmpty() && !hasArchived) {
                Text(
                    text = stringResource(R.string.customers_detail_no_properties_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.customers_detail_no_properties_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                properties.forEachIndexed { index, property ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    CustomerPropertyRow(
                        property = property,
                        onClick = { onOpenProperty(property.id) },
                    )
                }
            }
            if (hasArchived) {
                if (properties.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                ArchivedPropertiesDisclosure(
                    properties = archivedProperties,
                    onOpenProperty = onOpenProperty,
                )
            }
            // Adding a Property is part of managing the customer, so the affordance lives with the
            // Properties it adds to and is drawn only when the user may write (`BR-007`, `BR-011`).
            if (canAddProperty) {
                if (properties.isNotEmpty() || hasArchived) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                AddPropertyAction(onClick = onAddProperty)
            }
        }
    }
}

/**
 * The customer's archived Properties, behind an explicit disclosure.
 *
 * The default section stays the active projection (`BR-081`) while an archived Property — which the
 * backend keeps as a real record (`BR-082`) — remains reachable and therefore restorable. The rows
 * open the same Property Detail screen as an active one, where the Restore action lives.
 */
@Composable
private fun ArchivedPropertiesDisclosure(
    properties: List<CustomerProperty>,
    onOpenProperty: (propertyId: String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(CustomerDetailArchivedPropertiesTag),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = pluralStringResource(
                    R.plurals.customers_detail_archived_properties,
                    properties.size,
                    properties.size,
                ),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = stringResource(
                    if (expanded) {
                        R.string.customers_detail_archived_properties_hide
                    } else {
                        R.string.customers_detail_archived_properties_show
                    },
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (expanded) 90f else 0f),
            )
        }
        if (expanded) {
            properties.forEach { property ->
                CustomerPropertyRow(
                    property = property,
                    onClick = { onOpenProperty(property.id) },
                )
            }
        }
    }
}

/** The Add Property affordance under the Properties section, as designed. */
@Composable
private fun AddPropertyAction(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(CustomerDetailAddPropertyTag)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_add),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(R.string.property_add_action),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun CustomerPropertyRow(property: CustomerProperty, onClick: () -> Unit) {
    val separator = stringResource(R.string.customers_counts_separator)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
            .testTag(customerDetailPropertyTag(property.id)),
    ) {
        property.name?.takeIf { it.isNotBlank() }?.let { name ->
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
            text = property.addressLine1,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        property.addressLine2?.takeIf { it.isNotBlank() }?.let { line2 ->
            Text(
                text = line2,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stringResource(
                R.string.customers_property_city_format,
                property.city,
                property.province,
                property.postalCode,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (property.status == PropertyStatus.ARCHIVED) {
            Spacer(Modifier.height(4.dp))
            ArchivedPropertyBadge()
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = listOf(
                pluralStringResource(
                    R.plurals.customers_job_count,
                    property.jobCount,
                    property.jobCount,
                ),
                lastServiceLabel(property.lastServiceAt),
            ).joinToString(" $separator "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The badge an archived Property carries wherever it is listed, so its lifecycle state is visible
 * (`BR-082`). It is the same state the Property Detail screen shows.
 */
@Composable
private fun ArchivedPropertyBadge() {
    Surface(
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
internal fun lastServiceLabel(lastServiceAt: String?): String {
    val date = lastServiceAt?.let { customerSince(it) }
    return if (date == null) {
        stringResource(R.string.customers_detail_never_serviced)
    } else {
        stringResource(R.string.customers_detail_last_service_format, date)
    }
}

/** The customer's Jobs, previewed as designed, with the full history a tap away (`BR-081`). */
@Composable
private fun CustomerJobsSection(jobs: List<CustomerJob>, onSeeAllJobs: () -> Unit) {
    val preview = jobs.take(CustomerDetailJobPreviewLimit)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(CustomerDetailJobsTag),
    ) {
        SectionLabel(
            label = stringResource(R.string.customers_detail_recent_jobs),
            count = jobs.size,
        )
        if (jobs.isEmpty()) {
            InfoCard {
                Text(
                    text = stringResource(R.string.customers_detail_no_jobs),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        } else {
            InfoCard {
                preview.forEachIndexed { index, job ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    CustomerJobRow(job)
                }
            }
            if (jobs.size > preview.size) {
                TextButton(
                    modifier = Modifier.testTag(CustomerDetailSeeAllJobsTag),
                    onClick = onSeeAllJobs,
                ) {
                    Text(
                        text = stringResource(R.string.customers_detail_see_all_jobs, jobs.size),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomerJobRow(job: CustomerJob) {
    val separator = stringResource(R.string.customers_counts_separator)
    val meta = listOfNotNull(
        job.address?.let { addressLine(it) }?.takeIf { it.isNotBlank() },
        job.scheduledStart?.let { customerSince(it) },
        techniciansLabel(job.technicians),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag(customerDetailJobTag(job.id)),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.customers_job_number_format, job.jobNumber),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = job.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = meta.joinToString(" $separator "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        JobStatusPill(job.status)
    }
}

/**
 * The customer's complete Job history.
 *
 * It is a destination of its own (`docs/decisions/010-android-navigation.md`), so it carries the
 * shared secondary header and covers its own loading and failure states: it can be reached from a
 * restored back stack, not only from the detail.
 */
@Composable
internal fun CustomerJobHistoryScreen(
    state: CustomerDetailUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail = state.detail
    // Content only: the Job History title and its back control come from the app shell's one
    // contextual top bar.
    Box(modifier = modifier.fillMaxSize().testTag(CustomerDetailAllJobsTag)) {
        when {
            detail != null -> CustomerJobHistoryList(detail.jobs)
            state.failureReason != null -> CustomersError(onRetry = onRetry)
            else -> CustomersLoading()
        }
    }
}

@Composable
private fun CustomerJobHistoryList(jobs: List<CustomerJob>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
    ) {
        if (jobs.isNotEmpty()) {
            item {
                // The count used to sit in the secondary header. It is informational, not a
                // contextual action, so it belongs with the list rather than the top bar.
                Text(
                    text = pluralStringResource(
                        R.plurals.customers_job_count,
                        jobs.size,
                        jobs.size,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (jobs.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.customers_detail_no_jobs),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            items(jobs, key = { it.id }) { job ->
                CustomerJobRow(job)
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                )
            }
        }
    }
}

/** The selected Visit's technicians, or the localized "unassigned" state (`BR-081`). */
@Composable
private fun techniciansLabel(technicians: List<CustomerJobTechnician>): String {
    val names = technicians.mapNotNull { it.name }.filter { it.isNotBlank() }
    return if (names.isEmpty()) {
        stringResource(R.string.customers_job_unassigned)
    } else {
        names.joinToString(", ")
    }
}
