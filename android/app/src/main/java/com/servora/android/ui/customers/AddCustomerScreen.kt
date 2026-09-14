package com.servora.android.ui.customers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.domain.model.CustomerType
import com.servora.android.domain.model.PropertyProvince

/** Root of the New Customer form. */
const val AddCustomerTag = "add-customer"

const val AddCustomerMessageTag = "add-customer-message"
const val AddCustomerCreatedTag = "add-customer-created"
const val AddCustomerIndividualTag = "add-customer-type-individual"
const val AddCustomerBusinessTag = "add-customer-type-business"
const val AddCustomerCompanyNameTag = "add-customer-company-name"
const val AddCustomerFirstNameTag = "add-customer-first-name"
const val AddCustomerLastNameTag = "add-customer-last-name"
const val AddCustomerContactFirstNameTag = "add-customer-contact-first-name"
const val AddCustomerContactLastNameTag = "add-customer-contact-last-name"
const val AddCustomerPhoneTag = "add-customer-phone"
const val AddCustomerEmailTag = "add-customer-email"
const val AddCustomerNotesTag = "add-customer-notes"
const val AddCustomerPropertySectionTag = "add-customer-property-section"
const val AddCustomerPropertyStreetTag = "add-customer-property-street"
const val AddCustomerPropertyUnitTag = "add-customer-property-unit"
const val AddCustomerPropertyCityTag = "add-customer-property-city"
const val AddCustomerPropertyPostalCodeTag = "add-customer-property-postal-code"
const val AddCustomerPropertyNameTag = "add-customer-property-name"
const val AddCustomerPropertyNotesTag = "add-customer-property-notes"
const val AddCustomerSaveTag = "add-customer-save"
const val AddCustomerCancelTag = "add-customer-cancel"

/**
 * The New Customer form (`BR-023`, `BR-049`, `BR-050`).
 *
 * It draws content only: the destination's title and back control belong to the app shell's one
 * contextual top bar (`docs/decisions/011-android-contextual-top-bar.md`).
 *
 * The form draws the customer's own fields plus two optional sections, each gated by the capability
 * that would perform its write: the first service Property needs `properties.create`, and the
 * business customer's primary contact needs `customers.edit`. A section the caller cannot write is
 * not drawn, and the backend remains the authority either way (`BR-007`, `BR-085`).
 *
 * The first Property section is optional end to end. If the customer is created but one of the
 * optional writes fails, the customer still exists on the backend, so the screen states exactly what
 * did not save and replaces its primary action with opening the customer that was created — where the
 * Property or contact can be added later (`BR-001`, `BR-067`).
 *
 * @param onSaved invoked once every part the form submitted was accepted, with the created
 *   customer's id.
 * @param onOpenCreatedCustomer invoked when the customer exists but an optional part did not save.
 */
@Composable
fun AddCustomerScreen(
    state: AddCustomerUiState,
    canCreateProperty: Boolean,
    canCreateContact: Boolean,
    onTypeChange: (CustomerType) -> Unit,
    onCompanyNameChange: (String) -> Unit,
    onFirstNameChange: (String) -> Unit,
    onLastNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onStreetAddressChange: (String) -> Unit,
    onUnitChange: (String) -> Unit,
    onCityChange: (String) -> Unit,
    onProvinceChange: (PropertyProvince) -> Unit,
    onPostalCodeChange: (String) -> Unit,
    onPropertyNameChange: (String) -> Unit,
    onPropertyNotesChange: (String) -> Unit,
    onContactFirstNameChange: (String) -> Unit,
    onContactLastNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onSaved: (String) -> Unit,
    onOpenCreatedCustomer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val createdCustomerId = state.createdCustomerId
    LaunchedEffect(state.isSaved, createdCustomerId) {
        if (state.isSaved && createdCustomerId != null) {
            onSaved(createdCustomerId)
        }
    }

    var provincePickerOpen by rememberSaveable { mutableStateOf(false) }
    val showPropertySection = canCreateProperty
    val showContactSection = state.isCompany && canCreateContact

    Column(modifier = modifier.fillMaxSize().testTag(AddCustomerTag)) {
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
                state.hasUnfinishedStep -> CustomerCreatedNotice(state)
                state.showsValidationError ->
                    FormAttention(
                        message = stringResource(R.string.customers_create_incomplete),
                        testTag = AddCustomerMessageTag,
                    )
                state.failureReason != null ->
                    FormAttention(
                        message = stringResource(state.failureReason.messageRes()),
                        testTag = AddCustomerMessageTag,
                    )
            }

            CustomerTypeSection(
                type = state.type,
                onTypeChange = onTypeChange,
                individualTag = AddCustomerIndividualTag,
                businessTag = AddCustomerBusinessTag,
            )

            CustomerInformationSection(
                state = state,
                showContactSection = showContactSection,
                onCompanyNameChange = onCompanyNameChange,
                onFirstNameChange = onFirstNameChange,
                onLastNameChange = onLastNameChange,
                onPhoneChange = onPhoneChange,
                onEmailChange = onEmailChange,
                onNotesChange = onNotesChange,
                onContactFirstNameChange = onContactFirstNameChange,
                onContactLastNameChange = onContactLastNameChange,
            )

            if (showPropertySection) {
                FirstPropertySection(
                    state = state,
                    onStreetAddressChange = onStreetAddressChange,
                    onUnitChange = onUnitChange,
                    onCityChange = onCityChange,
                    onPostalCodeChange = onPostalCodeChange,
                    onPropertyNameChange = onPropertyNameChange,
                    onPropertyNotesChange = onPropertyNotesChange,
                    onOpenProvincePicker = { provincePickerOpen = true },
                )
            }
        }

        PropertyFormActions(
            isSaving = state.isSaving,
            onCancel = onCancel,
            onSave = {
                if (state.hasUnfinishedStep) {
                    createdCustomerId?.let(onOpenCreatedCustomer)
                } else {
                    onSave()
                }
            },
            saveLabelRes = if (state.hasUnfinishedStep) {
                R.string.customers_create_open_action
            } else {
                R.string.customers_create_action
            },
        )
    }

    if (provincePickerOpen) {
        ProvincePickerSheet(
            selected = state.province,
            onSelect = { province ->
                onProvinceChange(province)
                provincePickerOpen = false
            },
            onDismiss = { provincePickerOpen = false },
        )
    }
}

/**
 * The customer type, as the design's two-option segmented control.
 *
 * Add Customer and Edit Customer draw the same control; only the test tags differ, because each
 * screen's control is asserted separately.
 */
@Composable
internal fun CustomerTypeSection(
    type: CustomerType,
    onTypeChange: (CustomerType) -> Unit,
    individualTag: String,
    businessTag: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        CustomerFieldGroupLabel(labelRes = R.string.customers_create_section_type)
        Spacer(Modifier.height(PropertyFormFieldSpacing))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PropertyFormFieldSpacing),
        ) {
            CustomerSegmentOption(
                labelRes = R.string.customers_create_type_individual,
                iconRes = R.drawable.ic_users,
                selected = type == CustomerType.INDIVIDUAL,
                testTag = individualTag,
                onClick = { onTypeChange(CustomerType.INDIVIDUAL) },
                modifier = Modifier.weight(1f),
            )
            CustomerSegmentOption(
                labelRes = R.string.customers_create_type_business,
                iconRes = R.drawable.ic_business,
                selected = type == CustomerType.COMPANY,
                testTag = businessTag,
                onClick = { onTypeChange(CustomerType.COMPANY) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The small uppercase name a group of customer-form controls sits under. */
@Composable
internal fun CustomerFieldGroupLabel(labelRes: Int) {
    Text(
        text = stringResource(labelRes).uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One option of a customer form's segmented control. The selected one is the brand-filled one. */
@Composable
internal fun CustomerSegmentOption(
    labelRes: Int,
    iconRes: Int?,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(PropertyFieldHeight).testTag(testTag),
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.secondary
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = if (selected) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = PropertyFormFieldSpacing),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** The customer's own fields: the name its subtype stores, plus the optional header contact details. */
@Composable
private fun CustomerInformationSection(
    state: AddCustomerUiState,
    showContactSection: Boolean,
    onCompanyNameChange: (String) -> Unit,
    onFirstNameChange: (String) -> Unit,
    onLastNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onContactFirstNameChange: (String) -> Unit,
    onContactLastNameChange: (String) -> Unit,
) {
    PropertyFormSection(
        labelRes = R.string.customers_create_section_info,
        iconRes = R.drawable.ic_business,
    ) {
        CustomerNameFields(
            state = state,
            showContactSection = showContactSection,
            onCompanyNameChange = onCompanyNameChange,
            onFirstNameChange = onFirstNameChange,
            onLastNameChange = onLastNameChange,
            onContactFirstNameChange = onContactFirstNameChange,
            onContactLastNameChange = onContactLastNameChange,
        )
        CustomerHeaderContactFields(
            state = state,
            onPhoneChange = onPhoneChange,
            onEmailChange = onEmailChange,
        )
        PropertyNotesField(
            value = state.notes,
            onValueChange = onNotesChange,
            labelRes = R.string.customers_create_notes_label,
            placeholderRes = R.string.customers_create_notes_placeholder,
            testTag = AddCustomerNotesTag,
        )
    }
}

/**
 * The name a customer's subtype requires.
 *
 * A business customer is named by its company, and its contact person is the separate primary
 * contact the API records on `customer_contacts`; an individual customer is named by the first and
 * last name its own subtype stores (`BR-023`).
 */
@Composable
private fun CustomerNameFields(
    state: AddCustomerUiState,
    showContactSection: Boolean,
    onCompanyNameChange: (String) -> Unit,
    onFirstNameChange: (String) -> Unit,
    onLastNameChange: (String) -> Unit,
    onContactFirstNameChange: (String) -> Unit,
    onContactLastNameChange: (String) -> Unit,
) {
    if (state.isCompany) {
        PropertyField(
            labelRes = R.string.customers_create_company_label,
            value = state.companyName,
            onValueChange = onCompanyNameChange,
            placeholderRes = R.string.customers_create_company_placeholder,
            testTag = AddCustomerCompanyNameTag,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Next,
            ),
        )
        if (showContactSection) {
            Text(
                text = stringResource(R.string.customers_create_contact_label),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(PropertyFormFieldSpacing)) {
                CustomerNameField(
                    labelRes = R.string.customers_create_first_name_label,
                    placeholderRes = R.string.customers_create_first_name_placeholder,
                    value = state.contactFirstName,
                    onValueChange = onContactFirstNameChange,
                    testTag = AddCustomerContactFirstNameTag,
                )
                CustomerNameField(
                    labelRes = R.string.customers_create_last_name_label,
                    placeholderRes = R.string.customers_create_last_name_placeholder,
                    value = state.contactLastName,
                    onValueChange = onContactLastNameChange,
                    testTag = AddCustomerContactLastNameTag,
                )
            }
        }
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(PropertyFormFieldSpacing)) {
        CustomerNameField(
            labelRes = R.string.customers_create_first_name_label,
            placeholderRes = R.string.customers_create_first_name_placeholder,
            value = state.firstName,
            onValueChange = onFirstNameChange,
            testTag = AddCustomerFirstNameTag,
        )
        CustomerNameField(
            labelRes = R.string.customers_create_last_name_label,
            placeholderRes = R.string.customers_create_last_name_placeholder,
            value = state.lastName,
            onValueChange = onLastNameChange,
            testTag = AddCustomerLastNameTag,
        )
    }
}


/** One of a two-column name pair; both columns share the row equally. */
@Composable
private fun RowScope.CustomerNameField(
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

/** The customer header's optional phone and email; neither is required by `BR-023`. */
@Composable
private fun CustomerHeaderContactFields(
    state: AddCustomerUiState,
    onPhoneChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(PropertyFormFieldSpacing)) {
        PropertyField(
            labelRes = R.string.customers_create_phone_label,
            value = state.phone,
            onValueChange = onPhoneChange,
            placeholderRes = R.string.customers_create_phone_placeholder,
            testTag = AddCustomerPhoneTag,
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
            testTag = AddCustomerEmailTag,
            modifier = Modifier.weight(1f),
            optional = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
        )
    }
}



/**
 * The optional first service Property.
 *
 * It is a bordered card rather than a plain section, as the design draws it, so the address reads as
 * a service location rather than as the customer's own contact information (`BR-047`, `BR-049`). Its
 * fields are exactly the authoritative Property fields the Add Property form uses, including the
 * stable province code and no country (the backend stores it).
 */
@Composable
private fun FirstPropertySection(
    state: AddCustomerUiState,
    onStreetAddressChange: (String) -> Unit,
    onUnitChange: (String) -> Unit,
    onCityChange: (String) -> Unit,
    onPostalCodeChange: (String) -> Unit,
    onPropertyNameChange: (String) -> Unit,
    onPropertyNotesChange: (String) -> Unit,
    onOpenProvincePicker: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(AddCustomerPropertySectionTag),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(PropertyFormGutter),
            verticalArrangement = Arrangement.spacedBy(PropertyFormFieldSpacing),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_home),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = stringResource(R.string.customers_create_section_property),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.customers_create_property_optional),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.customers_create_property_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PropertyField(
                labelRes = R.string.property_street_label,
                value = state.addressLine1,
                onValueChange = onStreetAddressChange,
                placeholderRes = R.string.property_street_placeholder,
                testTag = AddCustomerPropertyStreetTag,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            )
            PropertyField(
                labelRes = R.string.property_unit_label,
                value = state.addressLine2,
                onValueChange = onUnitChange,
                placeholderRes = R.string.property_unit_placeholder,
                optional = true,
                testTag = AddCustomerPropertyUnitTag,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            )
            PropertyField(
                labelRes = R.string.property_city_label,
                value = state.city,
                onValueChange = onCityChange,
                placeholderRes = R.string.property_city_placeholder,
                testTag = AddCustomerPropertyCityTag,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(PropertyFormFieldSpacing)) {
                ProvinceField(
                    province = state.province,
                    onClick = onOpenProvincePicker,
                    modifier = Modifier.weight(1f),
                )
                PropertyField(
                    labelRes = R.string.property_postal_code_label,
                    value = state.postalCode,
                    onValueChange = onPostalCodeChange,
                    placeholderRes = R.string.property_postal_code_placeholder,
                    testTag = AddCustomerPropertyPostalCodeTag,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Next,
                    ),
                )
            }
            PropertyField(
                labelRes = R.string.property_name_label,
                value = state.propertyName,
                onValueChange = onPropertyNameChange,
                placeholderRes = R.string.property_name_placeholder,
                optional = true,
                testTag = AddCustomerPropertyNameTag,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            )
            PropertyNotesField(
                value = state.propertyNotes,
                onValueChange = onPropertyNotesChange,
                labelRes = R.string.property_notes_label,
                placeholderRes = R.string.property_notes_placeholder,
                testTag = AddCustomerPropertyNotesTag,
            )
        }
    }
}

/**
 * What the screen says once the customer exists but an optional part did not save.
 *
 * The customer is real on the backend, so this is not a failed create: it names the part that did not
 * save, and the primary action becomes opening the customer, where it can be added later
 * (`BR-001`, `BR-067`).
 */
@Composable
private fun CustomerCreatedNotice(state: AddCustomerUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(AddCustomerCreatedTag),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(PropertyFormGutter),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.customers_create_created_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(
                    when (state.unfinishedStep) {
                        CustomerSetupStep.PRIMARY_CONTACT ->
                            R.string.customers_create_contact_unfinished

                        else -> R.string.customers_create_property_unfinished
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            state.failureReason?.let { reason ->
                Text(
                    text = stringResource(reason.messageRes()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

