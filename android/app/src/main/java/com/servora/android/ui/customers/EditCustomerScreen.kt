package com.servora.android.ui.customers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.domain.model.CustomerContact
import com.servora.android.domain.model.CustomerStatus
import com.servora.android.domain.model.CustomerType
import com.servora.android.ui.components.InfoCard
import com.servora.android.ui.theme.stateColors

/** Root of the Edit Customer form. */
const val EditCustomerTag = "edit-customer"

const val EditCustomerMessageTag = "edit-customer-message"
const val EditCustomerTypeSectionTag = "edit-customer-type-section"
const val EditCustomerIndividualTag = "edit-customer-type-individual"
const val EditCustomerBusinessTag = "edit-customer-type-business"
const val EditCustomerConversionNoticeTag = "edit-customer-conversion-notice"
const val EditCustomerCompanyNameTag = "edit-customer-company-name"
const val EditCustomerFirstNameTag = "edit-customer-first-name"
const val EditCustomerLastNameTag = "edit-customer-last-name"
const val EditCustomerPhoneTag = "edit-customer-phone"
const val EditCustomerEmailTag = "edit-customer-email"
const val EditCustomerNotesTag = "edit-customer-notes"
const val EditCustomerStatusActiveTag = "edit-customer-status-active"
const val EditCustomerStatusInactiveTag = "edit-customer-status-inactive"
const val EditCustomerPropertiesHintTag = "edit-customer-properties-hint"

/** The customer's contact persons, shown read-only, and the hint that says where they are managed. */
const val EditCustomerContactsTag = "edit-customer-contacts"
const val EditCustomerContactsHintTag = "edit-customer-contacts-hint"
const val EditCustomerSaveTag = "edit-customer-save"
const val EditCustomerCancelTag = "edit-customer-cancel"

/**
 * The air the form keeps above the action bar, so its last control — the status options — does not
 * sit against the Cancel and Save row.
 */
private val EditCustomerFormFooterSpacing = 12.dp

/**
 * The Edit Customer form (`BR-023`, `BR-087`).
 *
 * It draws content only: the destination's title and back control belong to the app shell's one
 * contextual top bar (`docs/decisions/011-android-contextual-top-bar.md`).
 *
 * The form states the customer's type, so the same edit converts the customer between individual and
 * company. When the stated type is not the customer's current one the form says so before the edit
 * is applied, because a conversion replaces the current type's details and nothing is carried over
 * (`BR-067`, `BR-087`).
 *
 * The customer is read before the form can be used: until the read answers there is nothing to edit,
 * and a read that failed is reported with a retry rather than shown as an empty customer.
 *
 * @param onSaved invoked once the backend accepted the edit.
 */
@Composable
fun EditCustomerScreen(
    state: EditCustomerUiState,
    onTypeChange: (CustomerType) -> Unit,
    onCompanyNameChange: (String) -> Unit,
    onFirstNameChange: (String) -> Unit,
    onLastNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onStatusChange: (CustomerStatus) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(state.isSaved) {
        if (state.isSaved) {
            onSaved()
        }
    }

    Box(modifier = modifier.fillMaxSize().testTag(EditCustomerTag)) {
        when {
            state.isLoaded -> CustomerEditForm(
                state = state,
                onTypeChange = onTypeChange,
                onCompanyNameChange = onCompanyNameChange,
                onFirstNameChange = onFirstNameChange,
                onLastNameChange = onLastNameChange,
                onPhoneChange = onPhoneChange,
                onEmailChange = onEmailChange,
                onNotesChange = onNotesChange,
                onStatusChange = onStatusChange,
                onSave = onSave,
                onCancel = onCancel,
            )

            state.failureReason != null -> CustomersError(onRetry = onRetry)

            else -> CustomersLoading()
        }
    }
}

@Composable
private fun CustomerEditForm(
    state: EditCustomerUiState,
    onTypeChange: (CustomerType) -> Unit,
    onCompanyNameChange: (String) -> Unit,
    onFirstNameChange: (String) -> Unit,
    onLastNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onStatusChange: (CustomerStatus) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
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
            when {
                state.showsValidationError -> FormAttention(
                    message = stringResource(R.string.customers_edit_incomplete),
                    testTag = EditCustomerMessageTag,
                )

                state.failureReason != null -> FormAttention(
                    message = stringResource(state.failureReason.messageRes()),
                    testTag = EditCustomerMessageTag,
                )
            }

            CustomerTypeSection(
                type = state.type,
                onTypeChange = onTypeChange,
                individualTag = EditCustomerIndividualTag,
                businessTag = EditCustomerBusinessTag,
                modifier = Modifier.testTag(EditCustomerTypeSectionTag),
            )

            if (state.isConversion) {
                CustomerConversionNotice()
            }

            PropertyFormSection(
                labelRes = R.string.customers_create_section_info,
                iconRes = R.drawable.ic_business,
            ) {
                EditCustomerNameFields(
                    state = state,
                    onCompanyNameChange = onCompanyNameChange,
                    onFirstNameChange = onFirstNameChange,
                    onLastNameChange = onLastNameChange,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(PropertyFormFieldSpacing)) {
                    PropertyField(
                        labelRes = R.string.customers_create_phone_label,
                        value = state.phone,
                        onValueChange = onPhoneChange,
                        placeholderRes = R.string.customers_create_phone_placeholder,
                        testTag = EditCustomerPhoneTag,
                        modifier = Modifier.weight(1f),
                        optional = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Phone,
                            imeAction = ImeAction.Next,
                        ),
                    )
                    PropertyField(
                        labelRes = R.string.customers_create_email_label,
                        value = state.email,
                        onValueChange = onEmailChange,
                        placeholderRes = R.string.customers_create_email_placeholder,
                        testTag = EditCustomerEmailTag,
                        modifier = Modifier.weight(1f),
                        optional = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                    )
                }

                PropertyNotesField(
                    value = state.notes,
                    onValueChange = onNotesChange,
                    labelRes = R.string.customers_create_notes_label,
                    placeholderRes = R.string.customers_create_notes_placeholder,
                    testTag = EditCustomerNotesTag,
                )
            }

            CustomerStatusSection(
                status = state.status,
                onStatusChange = onStatusChange,
            )

            if (state.contacts.isNotEmpty()) {
                CustomerContactsReadOnly(contacts = state.contacts)
            }

            CustomerPropertiesHint()
            CustomerContactsHint()

            Spacer(Modifier.height(EditCustomerFormFooterSpacing))
        }

        PropertyFormActions(
            // The form waits for the customer it edits before its action can be used, so the wait is
            // reported on the same control a save uses.
            isSaving = state.isSaving || state.isLoading,
            onCancel = onCancel,
            onSave = onSave,
            saveLabelRes = R.string.customers_save,
            saveTag = EditCustomerSaveTag,
            cancelTag = EditCustomerCancelTag,
        )
    }
}

/**
 * The name the stated type stores (`BR-087`): the company's legal name, or the individual's first
 * and last name. Exactly one is drawn, and it follows the type the form states rather than the type
 * the customer had.
 */
@Composable
private fun EditCustomerNameFields(
    state: EditCustomerUiState,
    onCompanyNameChange: (String) -> Unit,
    onFirstNameChange: (String) -> Unit,
    onLastNameChange: (String) -> Unit,
) {
    if (state.isCompany) {
        PropertyField(
            labelRes = R.string.customers_create_company_label,
            value = state.companyName,
            onValueChange = onCompanyNameChange,
            placeholderRes = R.string.customers_create_company_placeholder,
            testTag = EditCustomerCompanyNameTag,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Next,
            ),
        )
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(PropertyFormFieldSpacing)) {
        EditCustomerNameField(
            labelRes = R.string.customers_create_first_name_label,
            placeholderRes = R.string.customers_create_first_name_placeholder,
            value = state.firstName,
            onValueChange = onFirstNameChange,
            testTag = EditCustomerFirstNameTag,
        )
        EditCustomerNameField(
            labelRes = R.string.customers_create_last_name_label,
            placeholderRes = R.string.customers_create_last_name_placeholder,
            value = state.lastName,
            onValueChange = onLastNameChange,
            testTag = EditCustomerLastNameTag,
        )
    }
}

/** One of a two-column name pair; both columns share the row equally. */
@Composable
private fun RowScope.EditCustomerNameField(
    labelRes: Int,
    placeholderRes: Int,
    value: String,
    onValueChange: (String) -> Unit,
    testTag: String,
) {
    PropertyField(
        labelRes = labelRes,
        value = value,
        onValueChange = onValueChange,
        placeholderRes = placeholderRes,
        testTag = testTag,
        modifier = Modifier.weight(1f),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Next,
        ),
    )
}

/**
 * The customer's status, as the design's two-option control.
 *
 * The options wear the status colour language the rest of the app paints a customer's state with
 * (`StatusPill`, `JobStatusPill`) rather than the brand-filled appearance of the type control: a
 * state reads as a state, and the selected option no longer repeats the Save action's fill directly
 * above it.
 */
@Composable
private fun CustomerStatusSection(
    status: CustomerStatus,
    onStatusChange: (CustomerStatus) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        CustomerFieldGroupLabel(labelRes = R.string.customers_edit_section_status)
        Spacer(Modifier.height(PropertyFormFieldSpacing))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PropertyFormFieldSpacing),
        ) {
            CustomerStatusOption(
                status = CustomerStatus.ACTIVE,
                selected = status == CustomerStatus.ACTIVE,
                testTag = EditCustomerStatusActiveTag,
                onClick = { onStatusChange(CustomerStatus.ACTIVE) },
                modifier = Modifier.weight(1f),
            )
            CustomerStatusOption(
                status = CustomerStatus.INACTIVE,
                selected = status == CustomerStatus.INACTIVE,
                testTag = EditCustomerStatusInactiveTag,
                onClick = { onStatusChange(CustomerStatus.INACTIVE) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * One option of the status control.
 *
 * `Active` is painted with the success tint the app gives a live customer, `Inactive` with the quiet
 * neutral one; the option that is not selected carries no border and the muted text colour. The two
 * options keep the field height and shape of every other control on the form, so the pair still
 * reads as two choices.
 */
@Composable
private fun CustomerStatusOption(
    status: CustomerStatus,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = status == CustomerStatus.ACTIVE
    val colors = MaterialTheme.stateColors
    Surface(
        modifier = modifier.height(PropertyFieldHeight).testTag(testTag),
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (selected && active) {
            colors.successContainer.copy(alpha = 0.28f)
        } else {
            MaterialTheme.colorScheme.secondary
        },
        contentColor = when {
            !selected -> MaterialTheme.colorScheme.onSurfaceVariant
            active -> colors.success
            else -> MaterialTheme.colorScheme.onSurface
        },
        border = when {
            !selected -> null
            active -> BorderStroke(1.dp, colors.success.copy(alpha = 0.25f))
            else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = PropertyFormFieldSpacing),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(status.labelRes()),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** The design's note under the status options: Properties belong to the customer's own screen. */
@Composable
private fun CustomerPropertiesHint() {
    Text(
        modifier = Modifier.fillMaxWidth().testTag(EditCustomerPropertiesHintTag),
        text = stringResource(R.string.customers_edit_properties_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * The customer's contact persons, shown read-only (`BR-095`, `ADR-022` D5).
 *
 * The edit form writes the customer's own fields and nothing else: a contact person is maintained from
 * the customer detail screen, where the capability that would perform the write is checked. The values
 * are the backend's, drawn so the user can see who is recorded without this form pretending to own
 * them (`BR-001`, `BR-042`).
 */
@Composable
private fun CustomerContactsReadOnly(contacts: List<CustomerContact>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(EditCustomerContactsTag),
    ) {
        CustomerFieldGroupLabel(labelRes = R.string.customers_detail_contacts)
        Spacer(Modifier.height(PropertyFormFieldSpacing))
        InfoCard {
            contacts.forEachIndexed { index, contact ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            modifier = Modifier.weight(1f),
                            text = listOf(contact.firstName, contact.lastName)
                                .filter { it.isNotBlank() }
                                .joinToString(" "),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (contact.isPrimary) {
                            Text(
                                text = stringResource(R.string.customers_contacts_primary),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    contact.phone?.takeIf { it.isNotBlank() }?.let { phone ->
                        Text(
                            text = phone,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    contact.email?.takeIf { it.isNotBlank() }?.let { email ->
                        Text(
                            text = email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** The note that says contacts are maintained from the customer's own screen (`ADR-022` D5). */
@Composable
private fun CustomerContactsHint() {
    Text(
        modifier = Modifier.fillMaxWidth().testTag(EditCustomerContactsHintTag),
        text = stringResource(R.string.customers_edit_contacts_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

private fun CustomerStatus.labelRes(): Int = when (this) {
    CustomerStatus.ACTIVE -> R.string.customers_status_active
    CustomerStatus.INACTIVE -> R.string.customers_status_inactive
}

/**
 * What applying this edit does to the customer's type (`BR-087`).
 *
 * A conversion replaces the current type's details with the ones the form now states and nothing is
 * carried over, so the screen says so while the user is still deciding (`BR-067`).
 */
@Composable
private fun CustomerConversionNotice() {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(EditCustomerConversionNoticeTag),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            modifier = Modifier.padding(
                horizontal = PropertyFormGutter,
                vertical = 12.dp,
            ),
            text = stringResource(R.string.customers_edit_conversion_notice),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

