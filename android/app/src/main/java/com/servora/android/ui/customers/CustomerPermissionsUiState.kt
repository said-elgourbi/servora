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
    // The Customer's contact persons are maintained with their own capability set (`BR-095`), which is
    // independent of the customer capabilities and never inferred from them — `customers.edit` no
    // longer authorizes a contact write. They default to denied, because a caller that does not name one
    // has not been granted it (`BR-007`, `BR-085`).
    val canCreateContact: Boolean = false,
    val canEditContact: Boolean = false,
    val canRemoveContact: Boolean = false,
    // The Job and Visit capabilities the Job Details screen draws its management actions from
    // (`BR-066`). They are denied unless the session names them, and they gate the UI only: the API
    // authorizes every action it receives (`BR-007`).
    val canUpdateJob: Boolean = false,
    val canViewTechnicians: Boolean = false,
    // Creating a Job is its own capability (`BR-008`, `BR-094`), the code the API enforces on
    // `POST /jobs`, so the Create Job affordance is drawn on it and never inferred from `canUpdateJob`
    // (`BR-006`, `BR-011`).
    val canCreateJob: Boolean = false,
    // The evidence capability the photo sources are drawn on (`BR-011`, `BR-015`). It is a separate
    // capability from the Job update one, so a Technician who may record evidence is offered the
    // action even though the default Technician role holds no Job update capability
    // (`docs/decisions/015-evidence-capabilities.md`).
    val canAddEvidencePhoto: Boolean = false,
    // Reading evidence back is the capability the API enforces on the content route, and the one the
    // viewer's two export actions are drawn on: saving a photo on the device and sharing it with
    // another application both read it (`D12`, `D13`, `BR-011`, `BR-015`).
    val canViewEvidence: Boolean = false,
    // Removing accepted evidence is the capability the API enforces on the removal route, and the one
    // the viewer's remove action is drawn on (`BR-089`). It is a Manager-level capability the default
    // Technician role does not hold, and it is never inferred from adding or reading evidence.
    val canRemoveEvidence: Boolean = false,
    // Recording an audio note is its own capability (`ADR-018` A7), so the *Add audio* kind is drawn on
    // it and never on the photo one: a company may grant one kind and withhold the other.
    val canAddEvidenceAudio: Boolean = false,
    // Removing an accepted recording is the audio kind's own Manager-level capability (`BR-089`,
    // `ADR-018` A7): it is never inferred from the photo removal capability, so a session may be offered
    // one kind's removal and not the other's.
    val canRemoveAudioEvidence: Boolean = false,
    // Reading and doing one's own assigned work is the field capability `BR-009` names (`ADR-019`
    // D1, D6). It is what decides which home a signed-in member lands on, and it is never inferred
    // from the office capabilities: a technician holds it and holds no `customers.view` at all.
    val canViewAssignedWork: Boolean = false,
    // The three field capabilities the Visit's own lifecycle is drawn on (`BR-009`, `BR-074`,
    // `BR-077`; `ADR-019` D1). They are never inferred from `canUpdateJob`: the office's capability
    // and the technician's are different codes the API enforces separately, so a Manager without the
    // field capabilities is offered no field action and a technician holding them is offered no Job
    // status control (`BR-066`, `BR-007`). They gate the UI only; the API authorizes every action.
    val canUpdateAssignedVisit: Boolean = false,
    val canAddVisitNote: Boolean = false,
    val canRecordVisitOutcome: Boolean = false,
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
        // The contact capabilities are their own set (`BR-095`): each is read from its own code and
        // never inferred from `customers.edit` or from another contact capability.
        canCreateContact = permissionChecker.has(Permission.CUSTOMERS_CONTACTS_CREATE),
        canEditContact = permissionChecker.has(Permission.CUSTOMERS_CONTACTS_EDIT),
        canRemoveContact = permissionChecker.has(Permission.CUSTOMERS_CONTACTS_REMOVE),
        canUpdateJob = permissionChecker.has(Permission.JOB_UPDATE),
        canCreateJob = permissionChecker.has(Permission.JOB_CREATE),
        canViewTechnicians = permissionChecker.has(Permission.TECHNICIAN_VIEW),
        canAddEvidencePhoto = permissionChecker.has(Permission.EVIDENCE_PHOTO_ADD),
        canViewEvidence = permissionChecker.has(Permission.EVIDENCE_VIEW),
        canRemoveEvidence = permissionChecker.has(Permission.EVIDENCE_PHOTO_REMOVE),
        canAddEvidenceAudio = permissionChecker.has(Permission.EVIDENCE_AUDIO_ADD),
        canRemoveAudioEvidence = permissionChecker.has(Permission.EVIDENCE_AUDIO_REMOVE),
        canViewAssignedWork = permissionChecker.has(Permission.VISIT_VIEW_ASSIGNED),
        // The field capabilities are their own codes (`BR-009`), read from the permission set the API
        // enforces on the Visit routes and never inferred from the office Job capabilities.
        canUpdateAssignedVisit = permissionChecker.has(Permission.VISIT_UPDATE_ASSIGNED_STATUS),
        canAddVisitNote = permissionChecker.has(Permission.VISIT_ADD_NOTE),
        canRecordVisitOutcome = permissionChecker.has(Permission.VISIT_RECORD_OUTCOME),
    )
