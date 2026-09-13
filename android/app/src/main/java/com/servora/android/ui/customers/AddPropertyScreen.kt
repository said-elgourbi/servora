package com.servora.android.ui.customers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servora.android.R
import com.servora.android.domain.model.PropertyProvince

/** Root of the Add Property form, so a test can assert the screen and that it carries no FAB. */
const val AddPropertyTag = "add-property"

const val AddPropertyMessageTag = "add-property-message"
const val AddPropertyStreetTag = "add-property-street"
const val AddPropertyUnitTag = "add-property-unit"
const val AddPropertyCityTag = "add-property-city"
const val AddPropertyProvinceTag = "add-property-province"
const val AddPropertyPostalCodeTag = "add-property-postal-code"
const val AddPropertyNameTag = "add-property-name"
const val AddPropertyNotesTag = "add-property-notes"
const val AddPropertySaveTag = "add-property-save"
const val AddPropertyCancelTag = "add-property-cancel"
const val AddPropertyProvinceSheetTag = "add-property-province-sheet"

fun addPropertyProvinceOptionTag(code: String): String = "add-property-province-$code"

/** The design system's control height, shared with the customer screens' fields. */
private val PropertyFieldHeight = 52.dp

/** The design gives the notes control two rows plus its padding. */
private val PropertyNotesHeight = 104.dp

private val PropertyFieldLabelSpacing = 6.dp
private val PropertyFormSectionSpacing = 24.dp
private val PropertyFormFieldSpacing = 12.dp
private val PropertyFormGutter = 16.dp
private val PropertyFormVerticalPadding = 20.dp
private val PropertyFieldHorizontalPadding = 14.dp
private val PropertySectionIconSize = 12.dp
private val PropertyProvinceChevronSize = 16.dp
private val PropertyProvinceCodeWidth = 28.dp
private val PropertyPickerMaxHeight = 480.dp
private val PropertySaveSpinnerSize = 18.dp
private val PropertyAlertIconSize = 16.dp

/**
 * The Add Property form (`BR-049`, `BR-050`).
 *
 * It draws content only: the destination's title, its back control and the customer it names as
 * context belong to the app shell's one contextual top bar
 * (`docs/decisions/011-android-contextual-top-bar.md`). The form draws no floating action of its
 * own: a create form must not have an unrelated primary action hovering over it.
 *
 * The fields are exactly the authoritative Property fields. Province is a picker over the stable
 * Canadian codes (`BR-041`); the country is never asked for because the backend stores the value the
 * address model already carries.
 *
 * @param onSaved invoked once the backend has created the Property, so the destination can return to
 *   the customer it was opened from.
 * @param saveLabelRes the primary action's label. Add Property and Edit Property share the form, and
 *   only the label differs.
 */
@Composable
fun AddPropertyScreen(
    state: AddPropertyUiState,
    onStreetAddressChange: (String) -> Unit,
    onUnitChange: (String) -> Unit,
    onCityChange: (String) -> Unit,
    onProvinceChange: (PropertyProvince) -> Unit,
    onPostalCodeChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    saveLabelRes: Int = R.string.property_save,
) {
    LaunchedEffect(state.isSaved) {
        if (state.isSaved) {
            onSaved()
        }
    }

    var provincePickerOpen by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().testTag(AddPropertyTag)) {
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
                state.showsValidationError ->
                    stringResource(R.string.property_form_incomplete)

                state.failureReason != null ->
                    stringResource(state.failureReason.messageRes())

                else -> null
            }
            attentionMessage?.let { message -> FormAttention(message) }

            PropertyFormSection(
                labelRes = R.string.property_section_service_address,
                iconRes = R.drawable.ic_map_pin,
            ) {
                PropertyField(
                    labelRes = R.string.property_street_label,
                    value = state.addressLine1,
                    onValueChange = onStreetAddressChange,
                    placeholderRes = R.string.property_street_placeholder,
                    testTag = AddPropertyStreetTag,
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
                    testTag = AddPropertyUnitTag,
                )
                PropertyField(
                    labelRes = R.string.property_city_label,
                    value = state.city,
                    onValueChange = onCityChange,
                    placeholderRes = R.string.property_city_placeholder,
                    testTag = AddPropertyCityTag,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next,
                    ),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(PropertyFormFieldSpacing),
                ) {
                    ProvinceField(
                        province = state.province,
                        onClick = { provincePickerOpen = true },
                        modifier = Modifier.weight(1f),
                    )
                    PropertyField(
                        labelRes = R.string.property_postal_code_label,
                        value = state.postalCode,
                        onValueChange = onPostalCodeChange,
                        placeholderRes = R.string.property_postal_code_placeholder,
                        testTag = AddPropertyPostalCodeTag,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Next,
                        ),
                    )
                }
            }

            PropertyFormSection(
                labelRes = R.string.property_section_details,
                iconRes = R.drawable.ic_file_text,
            ) {
                PropertyField(
                    labelRes = R.string.property_name_label,
                    value = state.name,
                    onValueChange = onNameChange,
                    placeholderRes = R.string.property_name_placeholder,
                    optional = true,
                    testTag = AddPropertyNameTag,
                )
                PropertyNotesField(
                    value = state.notes,
                    onValueChange = onNotesChange,
                )
            }
        }

        PropertyFormActions(
            // An edit waits for the Property it edits before its action can be used, so the form
            // reports that wait on the same control a save uses. The create form never loads.
            isSaving = state.isSaving || state.isLoading,
            onCancel = onCancel,
            onSave = onSave,
            saveLabelRes = saveLabelRes,
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
 * One labelled section of the form: a small glyph beside the section's uppercase name, then its
 * fields, as the design draws it.
 */
@Composable
private fun PropertyFormSection(
    labelRes: Int,
    iconRes: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(PropertySectionIconSize),
            )
            Text(
                text = stringResource(labelRes).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(PropertyFormFieldSpacing))
        Column(
            verticalArrangement = Arrangement.spacedBy(PropertyFormFieldSpacing),
            content = content,
        )
    }
}

/**
 * A field's label, with the design's optional marker.
 *
 * The label is uppercase and the marker stays in normal case, so the marker reads as an aside rather
 * than as part of the field's name (`BR-028`).
 */
@Composable
private fun PropertyFieldLabel(labelRes: Int, optional: Boolean) {
    val label = stringResource(labelRes)
    val optionalMarker = stringResource(R.string.property_field_optional)
    val text = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
            append(label.uppercase())
        }
        if (optional) {
            append(" ")
            withStyle(SpanStyle(fontWeight = FontWeight.Normal)) {
                append(optionalMarker)
            }
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 0.4.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One single-line field: the design's quiet fill, outline and 52 dp control height. */
@Composable
private fun PropertyField(
    labelRes: Int,
    value: String,
    onValueChange: (String) -> Unit,
    placeholderRes: Int,
    testTag: String,
    modifier: Modifier = Modifier,
    optional: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        PropertyFieldLabel(labelRes = labelRes, optional = optional)
        Spacer(Modifier.height(PropertyFieldLabelSpacing))
        Surface(
            modifier = Modifier.fillMaxWidth().height(PropertyFieldHeight),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondary,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = PropertyFieldHorizontalPadding),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth().testTag(testTag),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = keyboardOptions,
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty()) {
                                Text(
                                    text = stringResource(placeholderRes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
            }
        }
    }
}

/**
 * The optional notes field.
 *
 * It is the same quiet fill as the other fields but taller and multi-line, as the design draws the
 * notes control.
 */
@Composable
private fun PropertyNotesField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        PropertyFieldLabel(labelRes = R.string.property_notes_label, optional = true)
        Spacer(Modifier.height(PropertyFieldLabelSpacing))
        Surface(
            modifier = Modifier.fillMaxWidth().height(PropertyNotesHeight),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondary,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(PropertyFieldHorizontalPadding),
                contentAlignment = Alignment.TopStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth().testTag(AddPropertyNotesTag),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Default,
                    ),
                    maxLines = 4,
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.TopStart) {
                            if (value.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.property_notes_placeholder),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
            }
        }
    }
}

/**
 * The province control.
 *
 * It is a picker, never a free-text field, and it shows the stable code with its localized name so
 * the stored value stays visible (`BR-041`, `BR-028`). It carries the same label and height as the
 * fields beside it.
 */
@Composable
private fun ProvinceField(
    province: PropertyProvince?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        PropertyFieldLabel(labelRes = R.string.property_province_label, optional = false)
        Spacer(Modifier.height(PropertyFieldLabelSpacing))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(PropertyFieldHeight)
                .testTag(AddPropertyProvinceTag),
            onClick = onClick,
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondary,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = PropertyFieldHorizontalPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val label = if (province == null) {
                    stringResource(R.string.property_province_placeholder)
                } else {
                    stringResource(
                        R.string.property_province_value_format,
                        province.code,
                        stringResource(provinceLabelRes(province)),
                    )
                }
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (province == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_down),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(PropertyProvinceChevronSize),
                )
            }
        }
    }
}

/**
 * The province picker, drawn as the design's bottom sheet.
 *
 * The rows are the stable Canadian codes, so the control never becomes a free-text field and the
 * chosen value is the one the API stores (`BR-041`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProvincePickerSheet(
    selected: PropertyProvince?,
    onSelect: (PropertyProvince) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().testTag(AddPropertyProvinceSheetTag)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = PropertyFormGutter, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.property_province_label),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.property_province_close),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier
                    .heightIn(max = PropertyPickerMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                PropertyProvince.entries.forEach { province ->
                    ProvinceOption(
                        province = province,
                        selected = province == selected,
                        onClick = { onSelect(province) },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One province row: the code, its localized name, and the check that marks the selection. */
@Composable
private fun ProvinceOption(
    province: PropertyProvince,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(addPropertyProvinceOptionTag(province.code))
            .clickable(onClick = onClick)
            .padding(horizontal = PropertyFormGutter, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PropertyFormFieldSpacing),
    ) {
        Text(
            text = province.code,
            modifier = Modifier.width(PropertyProvinceCodeWidth),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = contentColor,
        )
        Text(
            text = stringResource(provinceLabelRes(province)),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
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

/** The form's two actions: the design's outlined Cancel and the brand-filled save. */
@Composable
private fun PropertyFormActions(
    isSaving: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    saveLabelRes: Int,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PropertyFormGutter, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(PropertyFormFieldSpacing),
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .height(PropertyFieldHeight)
                    .testTag(AddPropertyCancelTag),
                shape = MaterialTheme.shapes.large,
            ) {
                Text(stringResource(R.string.property_cancel))
            }
            Button(
                onClick = onSave,
                enabled = !isSaving,
                modifier = Modifier
                    .weight(1f)
                    .height(PropertyFieldHeight)
                    .testTag(AddPropertySaveTag),
                shape = MaterialTheme.shapes.large,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(PropertySaveSpinnerSize),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(saveLabelRes))
                }
            }
        }
    }
}

/** A form-level message: what is missing, or why the backend did not accept the Property. */
@Composable
private fun FormAttention(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(AddPropertyMessageTag),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = PropertyFieldHorizontalPadding,
                vertical = 12.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_alert_circle),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(PropertyAlertIconSize),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The localized name of one Canadian province or territory (`BR-028`). */
private fun provinceLabelRes(province: PropertyProvince): Int =
    when (province) {
        PropertyProvince.AB -> R.string.property_province_ab
        PropertyProvince.BC -> R.string.property_province_bc
        PropertyProvince.MB -> R.string.property_province_mb
        PropertyProvince.NB -> R.string.property_province_nb
        PropertyProvince.NL -> R.string.property_province_nl
        PropertyProvince.NS -> R.string.property_province_ns
        PropertyProvince.NT -> R.string.property_province_nt
        PropertyProvince.NU -> R.string.property_province_nu
        PropertyProvince.ON -> R.string.property_province_on
        PropertyProvince.PE -> R.string.property_province_pe
        PropertyProvince.QC -> R.string.property_province_qc
        PropertyProvince.SK -> R.string.property_province_sk
        PropertyProvince.YT -> R.string.property_province_yt
    }


