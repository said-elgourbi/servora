package com.servora.android.ui.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.data.customers.*
import com.servora.android.data.schedule.ResolveAdHocLocationRequestDto
import com.servora.android.domain.model.AdHocWorkReport
import com.servora.android.domain.model.PropertyProvince
import com.servora.android.ui.customers.ProvinceField
import com.servora.android.ui.customers.CustomerPermissionsUiState
import com.servora.android.ui.customers.ProvincePickerSheet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

@Composable
internal fun AdHocLocationDialog(
    report: AdHocWorkReport,
    state: ScheduleUiState,
    permissions: CustomerPermissionsUiState,
    onSearch: (String) -> Unit,
    onProperties: (String) -> Unit,
    onConfirm: (ResolveAdHocLocationRequestDto) -> Unit,
    onDismiss: () -> Unit,
) {
    val sending = state.reviewingAdHocReportId == report.id
    var newCustomer by rememberSaveable { mutableStateOf(report.customerId == null && permissions.canCreateCustomer && permissions.canCreateProperty) }
    var company by rememberSaveable { mutableStateOf(false) }
    var customerId by rememberSaveable { mutableStateOf(report.customerId) }
    var query by rememberSaveable { mutableStateOf(report.reportedCustomerName.orEmpty()) }
    var name by rememberSaveable { mutableStateOf(report.reportedCustomerName.orEmpty()) }
    var firstName by rememberSaveable { mutableStateOf(report.reportedCustomerName.orEmpty().substringBefore(' ')) }
    var lastName by rememberSaveable { mutableStateOf(report.reportedCustomerName.orEmpty().substringAfter(' ', "")) }
    var phone by rememberSaveable { mutableStateOf(report.reportedCustomerPhone.orEmpty()) }
    var newProperty by rememberSaveable { mutableStateOf(permissions.canCreateProperty) }
    var propertyId by rememberSaveable { mutableStateOf<String?>(null) }
    var street by rememberSaveable { mutableStateOf(report.reportedCustomerAddress.orEmpty()) }
    var unit by rememberSaveable { mutableStateOf("") }
    var city by rememberSaveable { mutableStateOf("") }
    var provinceCode by rememberSaveable { mutableStateOf<String?>(null) }
    var postalCode by rememberSaveable { mutableStateOf("") }
    var provincePicker by remember { mutableStateOf(false) }
    val province = PropertyProvince.entries.firstOrNull { it.code == provinceCode }
    LaunchedEffect(newCustomer, query) {
        if (!newCustomer && permissions.canOpenCustomers) onSearch(query)
    }
    LaunchedEffect(newCustomer, customerId) {
        if (!newCustomer && permissions.canViewProperties) customerId?.let(onProperties)
    }
    LaunchedEffect(report.version) {
        // A refresh after a lost response can reveal the successful resolution.
        if (report.customerId != null && report.propertyId != null) onDismiss()
    }
    val validCustomer = if (newCustomer) permissions.canCreateCustomer && name.isNotBlank() &&
        (company || (firstName.isNotBlank() && lastName.isNotBlank())) else customerId != null && permissions.canOpenCustomers
    val validProperty = if (newProperty) permissions.canCreateProperty && street.isNotBlank() && city.isNotBlank() &&
        province != null && postalCode.isNotBlank() else !newCustomer && propertyId != null && permissions.canViewProperties
    AlertDialog(
        modifier = Modifier.testTag("ad-hoc-location-dialog"),
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(stringResource(R.string.ad_hoc_resolve_location)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (report.customerId == null) {
                    if (permissions.canCreateCustomer && permissions.canCreateProperty) LocationChoice(R.string.ad_hoc_new_customer, newCustomer, !sending) { newCustomer = true; newProperty = true; propertyId = null }
                    if (permissions.canOpenCustomers) LocationChoice(R.string.ad_hoc_existing_customer, !newCustomer, !sending) { newCustomer = false; propertyId = null }
                } else Text(report.customerName.orEmpty())
                if (newCustomer) {
                    LocationChoice(R.string.ad_hoc_individual, !company, !sending) { company = false }
                    LocationChoice(R.string.ad_hoc_company, company, !sending) { company = true }
                    LocationText(name, { name = it }, R.string.ad_hoc_customer_name, !sending)
                    if (!company) {
                        LocationText(firstName, { firstName = it }, R.string.ad_hoc_first_name, !sending)
                        LocationText(lastName, { lastName = it }, R.string.ad_hoc_last_name, !sending)
                    }
                    LocationText(phone, { phone = it }, R.string.ad_hoc_report_reported_phone_label, !sending)
                } else if (report.customerId == null) {
                    LocationText(query, { query = it; customerId = null; propertyId = null }, R.string.ad_hoc_customer_search, !sending)
                    state.adHocLocationCustomers.forEach { customer ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !sending) { customerId = customer.id; propertyId = null }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(customerId == customer.id, onClick = { customerId = customer.id; propertyId = null }, enabled = !sending)
                            Text(listOfNotNull(customer.displayName, customer.phone).joinToString("\n"), modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (!newCustomer && permissions.canViewProperties) LocationChoice(R.string.ad_hoc_existing_property, !newProperty, !sending) { newProperty = false }
                if (permissions.canCreateProperty) LocationChoice(R.string.ad_hoc_new_property, newProperty, !sending) { newProperty = true }
                if (newProperty) {
                    LocationText(street, { street = it }, R.string.ad_hoc_street, !sending)
                    LocationText(unit, { unit = it }, R.string.ad_hoc_unit, !sending)
                    LocationText(city, { city = it }, R.string.ad_hoc_city, !sending)
                    ProvinceField(province, onClick = { if (!sending) provincePicker = true })
                    LocationText(postalCode, { postalCode = it }, R.string.ad_hoc_postal_code, !sending)
                } else if (customerId != null) {
                    state.adHocLocationProperties.forEach { property ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !sending) { propertyId = property.id }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(propertyId == property.id, onClick = { propertyId = property.id }, enabled = !sending)
                            Text(listOfNotNull(property.name, property.addressLine1, property.city, property.postalCode).joinToString(", "), modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (state.adHocLocationLoading || sending) CircularProgressIndicator()
                if (state.adHocLocationFailure != null) {
                    Text(stringResource(R.string.ad_hoc_report_review_failed), color = MaterialTheme.colorScheme.error)
                    if (!newCustomer) TextButton(enabled = !sending, onClick = {
                        if (!newCustomer && customerId != null) onProperties(customerId!!)
                        else if (!newCustomer) onSearch(query)
                    }) { Text(stringResource(R.string.ad_hoc_location_retry)) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = validCustomer && validProperty && !sending && !state.adHocLocationLoading,
                modifier = Modifier.testTag("ad-hoc-location-confirm"), onClick = {
                    val customer = if (!newCustomer) null else if (company) Json.encodeToJsonElement(
                        CreateCompanyCustomerRequest("COMPANY", name.trim(), phone = phone.trim().takeIf { it.isNotEmpty() }, company = CompanyCustomerPayload(name.trim())),
                    ).jsonObject else Json.encodeToJsonElement(
                        CreateIndividualCustomerRequest("INDIVIDUAL", name.trim(), phone = phone.trim().takeIf { it.isNotEmpty() }, individual = IndividualCustomerPayload(firstName.trim(), lastName.trim())),
                    ).jsonObject
                    onConfirm(ResolveAdHocLocationRequestDto(
                        existingCustomerId = if (newCustomer) null else customerId, newCustomer = customer,
                        existingPropertyId = if (newProperty) null else propertyId,
                        newProperty = if (!newProperty) null else CreatePropertyRequest(addressLine1 = street.trim(), addressLine2 = unit.trim().takeIf { it.isNotEmpty() }, city = city.trim(), province = province!!.code, postalCode = postalCode.trim()),
                        expectedVersion = report.version,
                    ))
                }) { Text(stringResource(R.string.ad_hoc_resolve_location)) }
        },
        dismissButton = { TextButton(enabled = !sending, onClick = onDismiss) { Text(stringResource(R.string.ad_hoc_location_cancel)) } },
    )
    if (provincePicker) ProvincePickerSheet(province, onSelect = { provinceCode = it.code; provincePicker = false }, onDismiss = { provincePicker = false })
}

@Composable
private fun LocationText(value: String, onChange: (String) -> Unit, label: Int, enabled: Boolean) {
    OutlinedTextField(value, onChange, label = { Text(stringResource(label)) }, enabled = enabled, modifier = Modifier.fillMaxWidth(), singleLine = true)
}

@Composable
private fun LocationChoice(label: Int, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick, enabled = enabled)
        Text(stringResource(label), modifier = Modifier.weight(1f))
    }
}
