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
    // The Property lifecycle capabilities default to denied, because a caller that does not name
    // one has not been granted it (`BR-007`, `BR-085`).
    val canEditProperty: Boolean = false,
    val canArchiveProperty: Boolean = false,
    val canDeleteProperty: Boolean = false,
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
        canEditProperty = permissionChecker.has(Permission.PROPERTIES_EDIT),
        canArchiveProperty = permissionChecker.has(Permission.PROPERTIES_ARCHIVE),
        canDeleteProperty = permissionChecker.has(Permission.PROPERTIES_DELETE),
    )
