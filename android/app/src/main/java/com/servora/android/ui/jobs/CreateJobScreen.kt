package com.servora.android.ui.jobs

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.domain.model.CustomerJobAddress
import com.servora.android.domain.model.CustomerProperty
import com.servora.android.ui.components.addressLine
import com.servora.android.ui.customers.CustomerListItem
import com.servora.android.ui.customers.CustomerSearchField
import com.servora.android.ui.customers.FormAttention
import com.servora.android.ui.customers.PropertyField
import com.servora.android.ui.customers.PropertyFieldHeight
import com.servora.android.ui.customers.PropertyFieldLabel
import com.servora.android.ui.customers.PropertyFormActions
import com.servora.android.ui.customers.PropertyFormFieldSpacing
import com.servora.android.ui.customers.PropertyFormGutter
import com.servora.android.ui.customers.PropertyFormSection
import com.servora.android.ui.customers.PropertyFormSectionSpacing
import com.servora.android.ui.customers.PropertyFormVerticalPadding
import com.servora.android.ui.customers.PropertyNotesField

/** Root of the Create Job form, so a test can assert the screen it is on. */
const val CreateJobTag = "create-job"

const val CreateJobMessageTag = "create-job-message"
const val CreateJobCustomerTag = "create-job-customer"
const val CreateJobCustomerValueTag = "create-job-customer-value"
const val CreateJobCustomerSheetTag = "create-job-customer-sheet"
const val CreateJobCustomerSearchTag = "create-job-customer-search"
const val CreateJobCustomerRetryTag = "create-job-customer-retry"
const val CreateJobPropertyTag = "create-job-property"
const val CreateJobPropertySheetTag = "create-job-property-sheet"
const val CreateJobPropertyRetryTag = "create-job-property-retry"
const val CreateJobNoPropertiesTag = "create-job-no-properties"
const val CreateJobTitleTag = "create-job-title"
const val CreateJobDescriptionTag = "create-job-description"
const val CreateJobSubmitTag = "create-job-submit"
const val CreateJobCancelTag = "create-job-cancel"

fun createJobCustomerOptionTag(customerId: String): String =
    "create-job-customer-option-$customerId"

fun createJobPropertyOptionTag(propertyId: String): String =
    "create-job-property-option-$propertyId"

private val CreateJobChevronSize = 18.dp
private val CreateJobSearchIconSize = 18.dp
private val CreateJobSpinnerSize = 18.dp
private val CreateJobPickerMaxHeight = 360.dp

/**
 * How many Properties make the picker's list "long" enough to carry a search field.
 *
 * A list of a handful is easier to read than to search; a list past this length is easier to search than
 * to scan. It is a presentation threshold, not a business limit.
 */
private const val PropertyPickerSearchThreshold = 8

/**
 * The Create Job form (`BR-094`; `docs/api/job-details.md` §6).
 *
 * The form states the three things a Job is created with — the Customer, the Property and the title —
 * plus the optional description. The Customer and Property selectors are **collapsed controls that open
 * a selection sheet**, because searching a customer and choosing a location both need a keyboard and a
 * list, which a small popup menu cannot give a phone. This version deliberately has no priority, owner,
 * category, schedule, crew or Visit field: none of them is part of creating the work request (`BR-047`,
 * `BR-051`, `BR-053`, `BR-054`, `BR-055`).
 *
 * When the destination was opened for a Customer, that Customer is drawn as a **read-only row** rather
 * than a search control, and its Properties are already being read (`BR-012`).
 *
 * Validation is shown as a form-level message and the API remains the authority: this screen refuses to
 * send an incomplete form, but every business decision — is this Customer active, does the Property
 * belong to it — is the backend's (`BR-001`, `BR-007`).
 *
 * @param onCreated invoked once the backend has created the Job, so the destination can open it.
 */
@Composable
fun CreateJobScreen(
    state: CreateJobUiState,
    onCustomerQueryChange: (String) -> Unit,
    onCustomerPickerOpen: () -> Unit,
    onSelectCustomer: (CustomerListItem) -> Unit,
    onRetryCustomers: () -> Unit,
    onSelectProperty: (String) -> Unit,
    onRetryProperties: () -> Unit,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onCreated: (jobId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(state.createdJobId) {
        state.createdJobId?.let(onCreated)
    }

    var customerPickerOpen by rememberSaveable { mutableStateOf(false) }
    var propertyPickerOpen by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().testTag(CreateJobTag)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = PropertyFormGutter,
                    vertical = PropertyFormVerticalPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(PropertyFormSectionSpacing),
        ) {
            val attentionMessage = when {
                state.showsValidationError -> stringResource(R.string.job_create_form_incomplete)
                state.failureReason != null ->
                    stringResource(state.failureReason.messageRes())
                else -> null
            }
            attentionMessage?.let { message -> FormAttention(message) }

            PropertyFormSection(
                labelRes = R.string.job_create_section_customer,
                iconRes = R.drawable.ic_users,
            ) {
                if (state.hasFixedCustomer) {
                    // The Customer is the context the screen was opened from, so it is shown rather than
                    // asked for again: searching for a customer the user already chose would be work for
                    // nothing (`BR-012`).
                    FixedCustomerRow(
                        name = state.fixedCustomerName,
                        placeholderRes = R.string.job_create_customer_unknown,
                    )
                } else {
                    SelectorField(
                        labelRes = R.string.job_create_customer_label,
                        value = state.selectedCustomerName,
                        placeholderRes = R.string.job_create_customer_placeholder,
                        testTag = CreateJobCustomerTag,
                        valueTestTag = CreateJobCustomerValueTag,
                        onClick = {
                            customerPickerOpen = true
                            onCustomerPickerOpen()
                        },
                    )
                }
            }

            PropertyFormSection(
                labelRes = R.string.job_create_section_property,
                iconRes = R.drawable.ic_map_pin,
            ) {
                SelectorField(
                    labelRes = R.string.job_create_property_label,
                    value = state.selectedProperty?.let(::propertyDisplayName),
                    placeholderRes = if (state.canChooseProperty) {
                        R.string.job_create_property_placeholder
                    } else {
                        R.string.job_create_property_needs_customer
                    },
                    testTag = CreateJobPropertyTag,
                    // The selector waits for a Customer: a Property belongs to a Customer, so there is
                    // nothing to choose before one is known (`BR-050`, `BR-094`).
                    enabled = state.canChooseProperty,
                    onClick = { propertyPickerOpen = true },
                )
                when {
                    state.propertiesLoading -> FormLoadingRow()

                    state.propertiesFailure != null -> FormRetryRow(
                        message = stringResource(state.propertiesFailure.readMessageRes()),
                        testTag = CreateJobPropertyRetryTag,
                        onRetry = onRetryProperties,
                    )

                    state.showsNoProperties -> NoPropertiesNotice()

                    else -> Unit
                }
            }

            PropertyFormSection(
                labelRes = R.string.job_create_section_details,
                iconRes = R.drawable.ic_file_text,
            ) {
                PropertyField(
                    labelRes = R.string.job_create_title_label,
                    value = state.title,
                    onValueChange = onTitleChange,
                    placeholderRes = R.string.job_create_title_placeholder,
                    testTag = CreateJobTitleTag,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next,
                    ),
                )
                PropertyNotesField(
                    value = state.description,
                    onValueChange = onDescriptionChange,
                    labelRes = R.string.job_create_description_label,
                    placeholderRes = R.string.job_create_description_placeholder,
                    testTag = CreateJobDescriptionTag,
                )
            }
        }

        PropertyFormActions(
            isSaving = state.isSubmitting,
            onCancel = onCancel,
            onSave = onSubmit,
            saveLabelRes = R.string.job_create_action,
            saveTag = CreateJobSubmitTag,
            cancelTag = CreateJobCancelTag,
        )
    }

    if (customerPickerOpen) {
        CustomerPickerSheet(
            state = state,
            onQueryChange = onCustomerQueryChange,
            onSelect = { customer ->
                onSelectCustomer(customer)
                customerPickerOpen = false
            },
            onRetry = onRetryCustomers,
            onDismiss = { customerPickerOpen = false },
        )
    }

    if (propertyPickerOpen) {
        PropertyPickerSheet(
            properties = state.properties,
            selectedPropertyId = state.selectedPropertyId,
            onSelect = { property ->
                onSelectProperty(property.id)
                propertyPickerOpen = false
            },
            onDismiss = { propertyPickerOpen = false },
        )
    }
}

/**
 * The Customer the form is fixed to, drawn as a read-only value.
 *
 * It is a field-shaped surface without a chevron and without a click: the Customer is context, so the
 * screen offers no way to change it and does not pretend to (`BR-012`).
 */
@Composable
private fun FixedCustomerRow(name: String?, placeholderRes: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        PropertyFieldLabel(labelRes = R.string.job_create_customer_label, optional = false)
        Spacer(Modifier.height(6.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(PropertyFieldHeight)
                .testTag(CreateJobCustomerValueTag),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondary,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = name ?: stringResource(placeholderRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (name == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A collapsed selection control: it looks like a field, shows the chosen value or its placeholder, and
 * opens a selection sheet when it is tapped.
 *
 * It is deliberately not a popup menu: choosing a Customer needs a search field and a list, which a menu
 * cannot give on a phone with the keyboard open.
 */
@Composable
private fun SelectorField(
    labelRes: Int,
    value: String?,
    placeholderRes: Int,
    testTag: String,
    onClick: () -> Unit,
    valueTestTag: String? = null,
    enabled: Boolean = true,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        PropertyFieldLabel(labelRes = labelRes, optional = false)
        Spacer(Modifier.height(6.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(PropertyFieldHeight)
                .testTag(testTag),
            onClick = onClick,
            enabled = enabled,
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondary,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = value ?: stringResource(placeholderRes),
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (valueTestTag == null) {
                                Modifier
                            } else {
                                Modifier.testTag(valueTestTag)
                            },
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (value == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_down),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(CreateJobChevronSize),
                )
            }
        }
    }
}

/** The row the Property selector shows while the Customer's Properties are being read. */
@Composable
private fun FormLoadingRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PropertyFormFieldSpacing),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(CreateJobSpinnerSize),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.job_create_properties_loading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A read that failed, with the way to try it again — the form itself is left untouched. */
@Composable
private fun FormRetryRow(message: String, testTag: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PropertyFormFieldSpacing),
    ) {
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.job_create_retry))
        }
    }
}

/**
 * The inline state a Customer with no `ACTIVE` Property shows.
 *
 * It says what is missing and why a Job cannot be created yet, rather than offering a create action this
 * screen does not have (`BR-094` Notes).
 */
@Composable
private fun NoPropertiesNotice() {
    Text(
        text = stringResource(R.string.job_create_no_properties),
        modifier = Modifier.fillMaxWidth().testTag(CreateJobNoPropertiesTag),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The Customer picker: a search field over the organization's active customers.
 *
 * The narrowing is local because the API exposes no text query on `GET /customers` (`BR-001`), so typing
 * costs no request. Loading, empty, failed and selected states are all drawn, because each of them is a
 * different answer the user has to be able to tell apart (`BR-042`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomerPickerSheet(
    state: CreateJobUiState,
    onQueryChange: (String) -> Unit,
    onSelect: (CustomerListItem) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().testTag(CreateJobCustomerSheetTag)) {
            PickerHeader(
                titleRes = R.string.job_create_customer_picker_title,
                onDismiss = onDismiss,
            )
            CustomerSearchField(
                value = state.customerQuery,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PropertyFormGutter),
                placeholderRes = R.string.job_create_customer_search_placeholder,
                testTag = CreateJobCustomerSearchTag,
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier
                    .heightIn(max = CreateJobPickerMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    state.customersLoading -> FormLoadingRow()

                    state.customersFailure != null -> FormRetryRow(
                        message = stringResource(state.customersFailure.readMessageRes()),
                        testTag = CreateJobCustomerRetryTag,
                        onRetry = onRetry,
                    )

                    state.visibleCustomers.isEmpty() ->
                        PickerEmptyRow(messageRes = R.string.job_create_customer_empty)

                    else -> state.visibleCustomers.forEach { customer ->
                        CustomerPickerRow(
                            customer = customer,
                            selected = customer.id == state.selectedCustomerId,
                            onClick = { onSelect(customer) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One Customer row: the name a user recognises, what else identifies them, and the selection mark. */
@Composable
private fun CustomerPickerRow(
    customer: CustomerListItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val secondary = customer.email ?: customer.phone
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(createJobCustomerOptionTag(customer.id))
            .clickable(onClick = onClick)
            .padding(horizontal = PropertyFormGutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PropertyFormFieldSpacing),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = customer.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                // A long customer name wraps rather than being cut off, so two customers whose names
                // start alike stay distinguishable.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            secondary?.takeIf { it.isNotBlank() }?.let { value ->
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_check_circle),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * The Property picker: the Customer's active Properties, with the address each one is.
 *
 * A Customer may hold many Properties, so the list carries a search field **when it is long** and stays a
 * plain list when it is short — the same reasoning the customer list applies to its own search.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PropertyPickerSheet(
    properties: List<CustomerProperty>,
    selectedPropertyId: String?,
    onSelect: (CustomerProperty) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val searchable = properties.size > PropertyPickerSearchThreshold
    val visible = if (!searchable) {
        properties
    } else {
        properties.filter { property ->
            propertyDisplayName(property).contains(query, ignoreCase = true) ||
                propertyAddressLine(property).contains(query, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().testTag(CreateJobPropertySheetTag)) {
            PickerHeader(
                titleRes = R.string.job_create_property_picker_title,
                onDismiss = onDismiss,
            )
            if (searchable) {
                CustomerSearchField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PropertyFormGutter),
                    placeholderRes = R.string.job_create_property_search_placeholder,
                )
                Spacer(Modifier.height(12.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier
                    .heightIn(max = CreateJobPickerMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (visible.isEmpty()) {
                    PickerEmptyRow(messageRes = R.string.job_create_property_empty)
                } else {
                    visible.forEach { property ->
                        PropertyPickerRow(
                            property = property,
                            selected = property.id == selectedPropertyId,
                            onClick = { onSelect(property) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One Property row: its name when it has one, and the address it is. */
@Composable
private fun PropertyPickerRow(
    property: CustomerProperty,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(createJobPropertyOptionTag(property.id))
            .clickable(onClick = onClick)
            .padding(horizontal = PropertyFormGutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PropertyFormFieldSpacing),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val name = property.name?.takeIf { it.isNotBlank() }
            name?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = propertyAddressLine(property),
                style = MaterialTheme.typography.bodySmall,
                color = if (name == null && selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                // A long address wraps to two lines rather than hiding which location it is.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_check_circle),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** A sheet's title and its close control. */
@Composable
private fun PickerHeader(titleRes: Int, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = PropertyFormGutter, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(titleRes),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        IconButton(onClick = onDismiss) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.job_create_picker_close),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** A sheet's empty state: nothing matched, or nothing is there to choose. */
@Composable
private fun PickerEmptyRow(messageRes: Int) {
    Text(
        text = stringResource(messageRes),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PropertyFormGutter, vertical = 16.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * What the Property selector shows for a Property: its name when it has one, and the address otherwise.
 *
 * A Property name is optional (`BR-049`), so a Property without one is still identified by where it is
 * rather than being presented as nameless.
 */
private fun propertyDisplayName(property: CustomerProperty): String =
    property.name?.takeIf { it.isNotBlank() } ?: property.addressLine1

/**
 * A Property's address as one line.
 *
 * The formatting is the one already shared for an address (`BR-041`); the snapshot type is only the shape
 * that formatter takes, so a Property's address is projected into it rather than formatted a second way.
 */
private fun propertyAddressLine(property: CustomerProperty): String =
    addressLine(
        CustomerJobAddress(
            propertyName = property.name,
            addressLine1 = property.addressLine1,
            addressLine2 = property.addressLine2,
            city = property.city,
            province = property.province,
            postalCode = property.postalCode,
            country = property.country,
        ),
    )
