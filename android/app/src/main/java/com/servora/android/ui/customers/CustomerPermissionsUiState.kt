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
    // The Job and Visit capabilities the Job Details screen draws its management actions from
    // (`BR-066`). They are denied unless the session names them, and they gate the UI only: the API
    // authorizes every action it receives (`BR-007`).
    val canUpdateJob: Boolean = false,
    val canViewTechnicians: Boolean = false,
    // The evidence capability the photo sources are drawn on (`BR-011`, `BR-015`). It is a separate
    // capability from the Job update one, so a Technician who may record evidence is offered the
    // action even though the default Technician role holds no Job update capability
    // (`docs/decisions/015-evidence-capabilities.md`).
    val canAddEvidencePhoto: Boolean = false,
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
        canUpdateJob = permissionChecker.has(Permission.JOB_UPDATE),
        canViewTechnicians = permissionChecker.has(Permission.TECHNICIAN_VIEW),
        canAddEvidencePhoto = permissionChecker.has(Permission.EVIDENCE_PHOTO_ADD),
    )
