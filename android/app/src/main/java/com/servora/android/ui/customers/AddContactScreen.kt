package com.servora.android.ui.customers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.servora.android.R

/** Root of the Add/Edit Contact form, so a test can assert the screen and that it carries no FAB. */
const val AddContactTag = "add-contact"

const val AddContactMessageTag = "add-contact-message"
const val AddContactFirstNameTag = "add-contact-first-name"
const val AddContactLastNameTag = "add-contact-last-name"
const val AddContactPhoneTag = "add-contact-phone"
const val AddContactEmailTag = "add-contact-email"
const val AddContactPrimaryTag = "add-contact-primary"
const val AddContactSaveTag = "add-contact-save"
const val AddContactCancelTag = "add-contact-cancel"

/**
 * The Add/Edit Contact form (`BR-023`, `BR-095`).
 *
 * It draws content only: the destination's title, its back control and the customer it names as
 * context belong to the app shell's one contextual top bar
 * (`docs/decisions/011-android-contextual-top-bar.md`). The form draws no floating action of its own:
 * a form must not have an unrelated primary action hovering over it.
 *
 * The four fields are the ones a contact person owns (`BR-095`), and the primary flag decides whether
 * this person becomes the one the organization calls first. No `role`, billing or job-contact field is
 * drawn: no rule defines what those hold, so no client captures them (`BR-042`, `ADR-022` D5).
 *
 * Add Contact and Edit Contact share the form; only the action label differs.
 *
 * @param onSaved invoked once the backend accepted the write, so the destination can return to the
 *   customer it was opened from.
 * @param saveLabelRes the primary action's label.
 */
@Composable
fun AddContactScreen(
    state: AddContactUiState,
    onFirstNameChange: (String) -> Unit,
    onLastNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPrimaryChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    saveLabelRes: Int = R.string.contact_add_action,
) {
    LaunchedEffect(state.isSaved) {
        if (state.isSaved) {
            onSaved()
        }
    }

    Column(modifier = modifier.fillMaxSize().testTag(AddContactTag)) {
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
                    stringResource(R.string.contact_form_incomplete)

                state.failureReason != null ->
                    stringResource(state.failureReason.contactMessageRes())

                else -> null
            }
            attentionMessage?.let { message ->
                FormAttention(message = message, testTag = AddContactMessageTag)
            }

            PropertyFormSection(
                labelRes = R.string.contact_section_person,
                iconRes = R.drawable.ic_users,
            ) {
                ContactNameFields(
                    state = state,
                    onFirstNameChange = onFirstNameChange,
                    onLastNameChange = onLastNameChange,
                )
                ContactDetailFields(
                    state = state,
                    onPhoneChange = onPhoneChange,
                    onEmailChange = onEmailChange,
                )
                ContactPrimaryField(
                    isPrimary = state.isPrimary,
                    enabled = state.canStatePrimary,
                    onPrimaryChange = onPrimaryChange,
                )
            }
        }

        PropertyFormActions(
            // The edit form waits for the contact it edits before its action can be used, so the wait
            // is reported on the same control a save uses.
            isSaving = state.isSaving || state.isLoading,
            onCancel = onCancel,
            onSave = onSave,
            saveLabelRes = saveLabelRes,
            saveTag = AddContactSaveTag,
            cancelTag = AddContactCancelTag,
        )
    }
}

/** The contact's own name: both parts are required by the API (`BR-095`). */
@Composable
private fun ContactNameFields(
    state: AddContactUiState,
    onFirstNameChange: (String) -> Unit,
    onLastNameChange: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(PropertyFormFieldSpacing)) {
        PropertyField(
            labelRes = R.string.contact_first_name_label,
            value = state.firstName,
            onValueChange = onFirstNameChange,
            placeholderRes = R.string.contact_first_name_placeholder,
            testTag = AddContactFirstNameTag,
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Next,
            ),
        )
        PropertyField(
            labelRes = R.string.contact_last_name_label,
            value = state.lastName,
            onValueChange = onLastNameChange,
            placeholderRes = R.string.contact_last_name_placeholder,
            testTag = AddContactLastNameTag,
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Next,
            ),
        )
    }
}

/**
 * The contact's own phone and email, both optional.
 *
 * They are the person's details and never the customer header's, which the customer's own screen
 * records separately (`BR-095`).
 */
@Composable
private fun ContactDetailFields(
    state: AddContactUiState,
    onPhoneChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(PropertyFormFieldSpacing)) {
        PropertyField(
            labelRes = R.string.contact_phone_label,
            value = state.phone,
            onValueChange = onPhoneChange,
            placeholderRes = R.string.contact_phone_placeholder,
            testTag = AddContactPhoneTag,
            modifier = Modifier.weight(1f),
            optional = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Next,
            ),
        )
        PropertyField(
            labelRes = R.string.contact_email_label,
            value = state.email,
            onValueChange = onEmailChange,
            placeholderRes = R.string.contact_email_placeholder,
            testTag = AddContactEmailTag,
            modifier = Modifier.weight(1f),
            optional = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Done,
            ),
        )
    }
}

/**
 * The primary flag: the customer's primary contact is the person the organization calls first.
 *
 * The whole row is the target and the checkbox reports the choice, as the schedule's filter rows do, so
 * the control is reachable along its whole width (`BR-012`). A contact that already holds the flag
 * cannot be un-promoted by this form: the API documents `isPrimary: true`, which promotes a contact and
 * clears the customer's previous primary in one transaction, and defines no un-promotion, so there is no
 * change for the form to state (`BR-042`, `BR-095`).
 */
@Composable
private fun ContactPrimaryField(
    isPrimary: Boolean,
    enabled: Boolean,
    onPrimaryChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(AddContactPrimaryTag),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondary,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = PropertyFieldHeight)
                .clip(MaterialTheme.shapes.large)
                .toggleable(
                    value = isPrimary,
                    enabled = enabled,
                    role = Role.Checkbox,
                    onValueChange = onPrimaryChange,
                )
                .padding(horizontal = PropertyFormGutter, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = isPrimary,
                onCheckedChange = null,
                enabled = enabled,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.contact_primary_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.contact_primary_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

