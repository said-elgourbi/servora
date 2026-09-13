package com.servora.android.ui.customers

import androidx.compose.runtime.Immutable
import com.servora.android.domain.auth.Permission
import com.servora.android.domain.auth.PermissionChecker

@Immutable
data class CustomerPermissionsUiState(
    val canOpenCustomers: Boolean,
    val canCreateCustomer: Boolean,
    val canEditCustomer: Boolean,
    val canArchiveCustomer: Boolean,
    val canViewProperties: Boolean,
    val canCreateProperty: Boolean,
)

fun customerPermissionsUiState(
    permissionChecker: PermissionChecker,
): CustomerPermissionsUiState =
    CustomerPermissionsUiState(
        canOpenCustomers = permissionChecker.has(Permission.CUSTOMERS_VIEW),
        canCreateCustomer = permissionChecker.has(Permission.CUSTOMERS_CREATE),
        canEditCustomer = permissionChecker.has(Permission.CUSTOMERS_EDIT),
        canArchiveCustomer = permissionChecker.has(Permission.CUSTOMERS_ARCHIVE),
        // Property capabilities are their own set (`BR-085`): they are read from the Property
        // permissions and never inferred from the customer ones.
        canViewProperties = permissionChecker.has(Permission.PROPERTIES_VIEW),
        canCreateProperty = permissionChecker.has(Permission.PROPERTIES_CREATE),
    )
