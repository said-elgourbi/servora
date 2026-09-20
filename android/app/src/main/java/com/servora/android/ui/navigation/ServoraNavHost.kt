package com.servora.android.ui.navigation

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.servora.android.R
import com.servora.android.ui.components.ServoraTopBarState
import com.servora.android.ui.components.openAddressInMaps
import com.servora.android.ui.customers.AddContactScreen
import com.servora.android.ui.customers.AddContactViewModel
import com.servora.android.ui.customers.AddCustomerScreen
import com.servora.android.ui.customers.AddCustomerViewModel
import com.servora.android.ui.customers.AddPropertyScreen
import com.servora.android.ui.customers.AddPropertyViewModel
import com.servora.android.ui.customers.CustomerDetailScreen
import com.servora.android.ui.customers.CustomerDetailUiState
import com.servora.android.ui.customers.CustomerJobHistoryScreen
import com.servora.android.ui.customers.CustomerPermissionsUiState
import com.servora.android.ui.customers.CustomersScreen
import com.servora.android.ui.customers.CustomersViewModel
import com.servora.android.ui.customers.EditContactViewModel
import com.servora.android.ui.customers.EditCustomerActionTag
import com.servora.android.ui.customers.EditCustomerScreen
import com.servora.android.ui.customers.EditCustomerViewModel
import com.servora.android.ui.customers.EditPropertyActionTag
import com.servora.android.ui.customers.EditPropertyViewModel
import com.servora.android.ui.customers.PropertyDetailScreen
import com.servora.android.ui.customers.PropertyDetailViewModel
import com.servora.android.ui.customers.RemoveContactViewModel
import com.servora.android.ui.jobs.CreateJobScreen
import com.servora.android.ui.jobs.CreateJobViewModel
// The destination draws the redesigned Job Details screen, which presents the Job and its Visits
// apart. `JobDetailsScreen` remains in the package as the screen it replaces: restoring the previous
// presentation is this import and the call below (`docs/tracker/044-job-details-job-visit-separation.md`).
import com.servora.android.ui.jobs.JobDetailsOverviewScreen
import com.servora.android.ui.jobs.JobDetailsViewModel
import com.servora.android.ui.jobs.rememberJobPhotoCapture
import com.servora.android.ui.jobs.rememberJobPhotoPicker

/**
 * Every destination the signed-in application can be at.
 *
 * Routes are plain strings because the navigation graph is declared with the Compose builder. Each
 * secondary route has its own path shape, so no route can swallow another's argument.
 */
object ServoraRoutes {
    /** Everything that is not a drill-down screen: the bottom-navigation tabs and their area. */
    const val ROOT = "root"

    const val CUSTOMER_ID = "customerId"

    const val PROPERTY_ID = "propertyId"

    const val CONTACT_ID = "contactId"

    const val JOB_ID = "jobId"

    const val CUSTOMER_DETAIL = "customer/detail/{customerId}"
    const val CUSTOMER_EDIT = "customer/edit/{customerId}"
    const val CUSTOMER_JOBS = "customer/jobs/{customerId}"
    const val CUSTOMER_CREATE = "customer/create"
    const val CUSTOMER_ADD_PROPERTY = "customer/properties/new/{customerId}"
    const val CUSTOMER_ADD_CONTACT = "customer/contacts/new/{customerId}"
    const val CUSTOMER_EDIT_CONTACT = "customer/contacts/edit/{customerId}/{contactId}"
    const val PROPERTY_DETAIL = "customer/properties/detail/{customerId}/{propertyId}"
    const val PROPERTY_EDIT = "customer/properties/edit/{customerId}/{propertyId}"
    const val JOB_DETAIL = "job/detail/{jobId}"

    /**
     * Create Job, with the Customer as an **optional** argument.
     *
     * Launching it from a customer passes that customer's id, and the form then treats the Customer as
     * fixed context rather than asking for one again (`BR-012`). Launching it without an id is the same
     * destination with the searchable Customer selector, which is what a Jobs entry point added later
     * uses — so the route is one screen, not two.
     */
    const val JOB_CREATE = "job/create?$CUSTOMER_ID={$CUSTOMER_ID}"

    fun customerDetail(customerId: String): String = "customer/detail/${Uri.encode(customerId)}"

    fun customerEdit(customerId: String): String = "customer/edit/${Uri.encode(customerId)}"

    fun customerJobs(customerId: String): String = "customer/jobs/${Uri.encode(customerId)}"

    fun customerAddProperty(customerId: String): String =
        "customer/properties/new/${Uri.encode(customerId)}"

    fun customerAddContact(customerId: String): String =
        "customer/contacts/new/${Uri.encode(customerId)}"

    fun customerEditContact(customerId: String, contactId: String): String =
        "customer/contacts/edit/${Uri.encode(customerId)}/${Uri.encode(contactId)}"

    fun propertyDetail(customerId: String, propertyId: String): String =
        "customer/properties/detail/${Uri.encode(customerId)}/${Uri.encode(propertyId)}"

    fun propertyEdit(customerId: String, propertyId: String): String =
        "customer/properties/edit/${Uri.encode(customerId)}/${Uri.encode(propertyId)}"

    fun jobDetail(jobId: String): String = "job/detail/${Uri.encode(jobId)}"

    /**
     * Create Job, optionally for [customerId].
     *
     * A `null` customer produces the route with the argument absent, which is how the destination's
     * optional argument is declared: the user then searches for the Customer.
     */
    fun jobCreate(customerId: String? = null): String =
        if (customerId == null) {
            "job/create"
        } else {
            "job/create?${ServoraRoutes.CUSTOMER_ID}=${Uri.encode(customerId)}"
        }
}

private fun NavBackStackEntry.customerId(): String =
    arguments?.getString(ServoraRoutes.CUSTOMER_ID).orEmpty()

private fun NavBackStackEntry.propertyId(): String =
    arguments?.getString(ServoraRoutes.PROPERTY_ID).orEmpty()

private fun NavBackStackEntry.contactId(): String =
    arguments?.getString(ServoraRoutes.CONTACT_ID).orEmpty()

private fun NavBackStackEntry.jobId(): String =
    arguments?.getString(ServoraRoutes.JOB_ID).orEmpty()

private fun customerIdArgument() =
    listOf(navArgument(ServoraRoutes.CUSTOMER_ID) { type = NavType.StringType })

/**
 * The Create Job destination's optional Customer argument.
 *
 * `nullable` with no default is what makes the argument **absent** when the route is opened without one:
 * a route that supplied an empty default would make "no customer" and "the empty id" the same thing
 * (`BR-042`).
 */
private fun optionalCustomerIdArgument() = listOf(
    navArgument(ServoraRoutes.CUSTOMER_ID) {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    },
)

private fun jobIdArgument() =
    listOf(navArgument(ServoraRoutes.JOB_ID) { type = NavType.StringType })

private fun propertyArguments() = listOf(
    navArgument(ServoraRoutes.CUSTOMER_ID) { type = NavType.StringType },
    navArgument(ServoraRoutes.PROPERTY_ID) { type = NavType.StringType },
)

private fun contactArguments() = listOf(
    navArgument(ServoraRoutes.CUSTOMER_ID) { type = NavType.StringType },
    navArgument(ServoraRoutes.CONTACT_ID) { type = NavType.StringType },
)

/**
 * The back stack for the signed-in area.
 *
 * The root destination holds the bottom-navigation area; every other destination is a drill-down
 * screen that is *pushed* on top of it, so system Back and the top bar's back control both pop
 * exactly one screen and the app is left only from the root
 * (`docs/decisions/010-android-navigation.md`).
 *
 * Business state stays where it already lives: a screen's data is read through
 * [CustomersViewModel] and the backend remains the authority (`BR-001`). Navigation owns only which
 * screen is on top.
 *
 * @param rootContent the bottom-navigation area, which the shell owns and passes in.
 */
@Composable
fun ServoraNavHost(
    navController: NavHostController,
    customersViewModel: CustomersViewModel,
    addCustomerViewModel: AddCustomerViewModel,
    editCustomerViewModel: EditCustomerViewModel,
    addPropertyViewModel: AddPropertyViewModel,
    propertyDetailViewModel: PropertyDetailViewModel,
    editPropertyViewModel: EditPropertyViewModel,
    addContactViewModel: AddContactViewModel,
    editContactViewModel: EditContactViewModel,
    removeContactViewModel: RemoveContactViewModel,
    jobDetailsViewModel: JobDetailsViewModel,
    createJobViewModel: CreateJobViewModel,
    permissions: CustomerPermissionsUiState,
    /**
     * Resolves the display name of the customer a destination belongs to, or `null` while it is not
     * known. The Create Job destination uses it to draw the customer it was opened for as context.
     */
    customerName: (customerId: String) -> String?,
    rootContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = ServoraRoutes.ROOT,
        modifier = modifier,
    ) {
        composable(ServoraRoutes.ROOT) { rootContent() }

        composable(ServoraRoutes.CUSTOMER_DETAIL, customerIdArgument()) { entry ->
            val customerId = entry.customerId()
            val state by customersViewModel.uiState.collectAsState()
            // The detail is a backend read, so the destination asks for it. The ViewModel skips a
            // customer it already holds, so a recomposition cannot start a second request
            // (`BR-001`).
            LaunchedEffect(customerId) { customersViewModel.openCustomerDetail(customerId) }
            // Removing a contact person acts on this customer, so the removal's session is this
            // destination instance: leaving the screen and returning starts without the previous
            // attempt's answer, which is what keeps a refusal the user walked away from out of the
            // next visit (`BR-042`).
            removeContactViewModel.start(customerId, entry.id)
            val contactRemoval by removeContactViewModel.uiState.collectAsState()
            CustomerDetailScreen(
                state = state.customerDetail ?: CustomerDetailUiState(customerId, isLoading = true),
                canViewProperties = permissions.canViewProperties,
                canAddProperty = permissions.canCreateProperty,
                canCreateJob = permissions.canCreateJob,
                // The contact capabilities are their own set (`BR-095`), so each action is drawn on
                // the code that would perform it and never on `customers.edit` (`BR-007`, `BR-011`).
                canCreateContact = permissions.canCreateContact,
                canEditContact = permissions.canEditContact,
                canRemoveContact = permissions.canRemoveContact,
                contactRemoval = contactRemoval,
                onAddProperty = {
                    // The Add Property destination begins its own form session before it composes,
                    // so nothing has to be reset here and a previous attempt's terminal state cannot
                    // make the new screen report itself saved on entry.
                    navController.push(ServoraRoutes.customerAddProperty(customerId))
                },
                onCreateJob = {
                    // The Job form is opened for this customer, so the customer is its context rather
                    // than something to search for again (`BR-012`).
                    navController.push(ServoraRoutes.jobCreate(customerId))
                },
                onSeeAllJobs = { navController.push(ServoraRoutes.customerJobs(customerId)) },
                onAddContact = {
                    navController.push(ServoraRoutes.customerAddContact(customerId))
                },
                onEditContact = { contactId ->
                    navController.push(ServoraRoutes.customerEditContact(customerId, contactId))
                },
                onRemoveContact = removeContactViewModel::remove,
                onDismissContactRemovalFailure = removeContactViewModel::dismissFailure,
                onContactRemoved = {
                    // The contact left every ordinary view on the backend, so the customer's detail is
                    // re-read rather than patched locally, and the contacts are what the API now
                    // reports (`BR-001`, `BR-033`, `BR-095`). Acknowledging the signal keeps
                    // re-entering this screen from reading again.
                    customersViewModel.reloadCustomerDetail(customerId)
                    removeContactViewModel.acknowledgeRemoved()
                },
                onRetry = customersViewModel::retryCustomerDetail,
                onOpenProperty = { propertyId ->
                    navController.push(
                        ServoraRoutes.propertyDetail(customerId, propertyId),
                    )
                },
            )
        }

        composable(ServoraRoutes.CUSTOMER_ADD_PROPERTY, customerIdArgument()) { entry ->
            val customerId = entry.customerId()
            // Reaching this screen directly, such as from a restored back stack, must still read the
            // customer whose name the destination's context line shows, and must scope the form to
            // that customer.
            LaunchedEffect(customerId) { customersViewModel.openCustomerDetail(customerId) }
            // The destination instance is the form session. Beginning it before the screen composes
            // is what keeps a previous session's validation or submission error out of this one;
            // recomposing this same instance passes the same id, which the ViewModel ignores, so a
            // configuration change still keeps what the user typed.
            addPropertyViewModel.start(customerId, entry.id)
            val state by addPropertyViewModel.uiState.collectAsState()
            AddPropertyScreen(
                state = state,
                onStreetAddressChange = addPropertyViewModel::onStreetAddressChange,
                onUnitChange = addPropertyViewModel::onUnitChange,
                onCityChange = addPropertyViewModel::onCityChange,
                onProvinceChange = addPropertyViewModel::onProvinceChange,
                onPostalCodeChange = addPropertyViewModel::onPostalCodeChange,
                onNameChange = addPropertyViewModel::onNameChange,
                onNotesChange = addPropertyViewModel::onNotesChange,
                onSave = addPropertyViewModel::save,
                onCancel = { navController.navigateUp() },
                onSaved = {
                    // The Property now exists on the backend, so the customer's detail and the
                    // list's Property count are re-read rather than patched locally (`BR-001`).
                    customersViewModel.reloadCustomerDetail(customerId)
                    customersViewModel.reload()
                    navController.popBackStack()
                },
            )
        }

        composable(ServoraRoutes.CUSTOMER_ADD_CONTACT, customerIdArgument()) { entry ->
            val customerId = entry.customerId()
            // Reaching this screen directly, such as from a restored back stack, must still read the
            // customer whose name the destination's context line shows, and must scope the form to
            // that customer.
            LaunchedEffect(customerId) { customersViewModel.openCustomerDetail(customerId) }
            // The destination instance is the form session. Beginning it before the screen composes
            // is what keeps a previous session's validation or submission error out of this one;
            // recomposing this same instance passes the same id, which the ViewModel ignores, so a
            // configuration change still keeps what the user typed.
            addContactViewModel.start(customerId, entry.id)
            val state by addContactViewModel.uiState.collectAsState()
            AddContactScreen(
                state = state,
                onFirstNameChange = addContactViewModel::onFirstNameChange,
                onLastNameChange = addContactViewModel::onLastNameChange,
                onPhoneChange = addContactViewModel::onPhoneChange,
                onEmailChange = addContactViewModel::onEmailChange,
                onPrimaryChange = addContactViewModel::onPrimaryChange,
                onSave = addContactViewModel::save,
                onCancel = { navController.navigateUp() },
                onSaved = {
                    // The contact exists on the backend now, so the customer's detail — and with it
                    // the contacts it reports — is re-read rather than patched locally (`BR-001`).
                    customersViewModel.reloadCustomerDetail(customerId)
                    navController.popBackStack()
                },
            )
        }

        composable(ServoraRoutes.CUSTOMER_EDIT_CONTACT, contactArguments()) { entry ->
            val customerId = entry.customerId()
            val contactId = entry.contactId()
            // The customer's name is the destination's context line; the contact itself is read by the
            // form, through the customer detail projection (`BR-095`).
            LaunchedEffect(customerId) { customersViewModel.openCustomerDetail(customerId) }
            // The destination instance is the form session: the form reads the contact it edits, and
            // re-entering the same instance keeps what the user entered. A new instance reads the
            // contact again, so no value or message from the previous session is carried over.
            editContactViewModel.start(customerId, contactId, entry.id)
            val state by editContactViewModel.uiState.collectAsState()
            AddContactScreen(
                state = state,
                onFirstNameChange = editContactViewModel::onFirstNameChange,
                onLastNameChange = editContactViewModel::onLastNameChange,
                onPhoneChange = editContactViewModel::onPhoneChange,
                onEmailChange = editContactViewModel::onEmailChange,
                onPrimaryChange = editContactViewModel::onPrimaryChange,
                onSave = editContactViewModel::save,
                onCancel = { navController.navigateUp() },
                onSaved = {
                    // The edit is on the backend now, so the customer's detail is re-read rather than
                    // patched locally (`BR-001`).
                    customersViewModel.reloadCustomerDetail(customerId)
                    navController.popBackStack()
                },
                saveLabelRes = R.string.contact_edit_action,
            )
        }

        composable(ServoraRoutes.PROPERTY_DETAIL, propertyArguments()) { entry ->
            val customerId = entry.customerId()
            val propertyId = entry.propertyId()
            // Reaching this screen directly, such as from a restored back stack, must still read the
            // Property it shows and the customer whose name the destination's context line carries.
            LaunchedEffect(customerId) { customersViewModel.openCustomerDetail(customerId) }
            // The destination instance is the session: a new instance starts without the previous
            // instance's action outcome, while recomposing this one keeps what it already read.
            propertyDetailViewModel.start(customerId, propertyId, entry.id)
            val state by propertyDetailViewModel.uiState.collectAsState()
            PropertyDetailScreen(
                state = state,
                canArchiveProperty = permissions.canArchiveProperty,
                canDeleteProperty = permissions.canDeleteProperty,
                onArchive = propertyDetailViewModel::archive,
                onRestore = propertyDetailViewModel::restore,
                onDelete = propertyDetailViewModel::delete,
                onDismissActionFailure = propertyDetailViewModel::dismissActionFailure,
                onRetry = propertyDetailViewModel::retry,
                onDeleted = {
                    // The Property is gone on the backend, so the customer's detail and the list's
                    // Property count are re-read rather than patched locally (`BR-001`), and the
                    // destination leaves.
                    customersViewModel.reloadCustomerDetail(customerId)
                    customersViewModel.reload()
                    navController.popBackStack()
                },
                onLifecycleChanged = {
                    // The archive or restore is on the backend now, so the customer's Property
                    // rows, its counts and the list's count are re-read rather than patched
                    // locally (`BR-001`). Acknowledging the signal keeps re-entering this screen
                    // from reading again.
                    customersViewModel.reloadCustomerDetail(customerId)
                    customersViewModel.reload()
                    propertyDetailViewModel.acknowledgeLifecycleChange()
                },
            )
        }

        composable(ServoraRoutes.PROPERTY_EDIT, propertyArguments()) { entry ->
            val customerId = entry.customerId()
            val propertyId = entry.propertyId()
            // The destination carries the Property it edits, so the form is scoped to one Property
            // rather than being a single shared screen. The customer's detail is read as well, for
            // the destination's context line.
            LaunchedEffect(customerId) { customersViewModel.openCustomerDetail(customerId) }
            // The destination instance is the form session, so a new instance starts from a fresh
            // read and never from the previous session's values, validation message or save error.
            editPropertyViewModel.start(customerId, propertyId, entry.id)
            val state by editPropertyViewModel.uiState.collectAsState()
            AddPropertyScreen(
                state = state,
                onStreetAddressChange = editPropertyViewModel::onStreetAddressChange,
                onUnitChange = editPropertyViewModel::onUnitChange,
                onCityChange = editPropertyViewModel::onCityChange,
                onProvinceChange = editPropertyViewModel::onProvinceChange,
                onPostalCodeChange = editPropertyViewModel::onPostalCodeChange,
                onNameChange = editPropertyViewModel::onNameChange,
                onNotesChange = editPropertyViewModel::onNotesChange,
                onSave = editPropertyViewModel::save,
                onCancel = { navController.navigateUp() },
                onSaved = {
                    // The edit is on the backend now, so both the customer's Property rows and the
                    // Property's own screen are re-read rather than patched locally (`BR-001`).
                    customersViewModel.reloadCustomerDetail(customerId)
                    propertyDetailViewModel.reload(customerId, propertyId)
                    navController.popBackStack()
                },
                saveLabelRes = R.string.property_edit_action,
            )
        }

        composable(ServoraRoutes.CUSTOMER_JOBS, customerIdArgument()) { entry ->
            val customerId = entry.customerId()
            val state by customersViewModel.uiState.collectAsState()
            // Reaching this screen directly, such as from a restored back stack, must still read the
            // customer it belongs to.
            LaunchedEffect(customerId) { customersViewModel.openCustomerDetail(customerId) }
            CustomerJobHistoryScreen(
                state = state.customerDetail?.takeIf { it.customerId == customerId }
                    ?: CustomerDetailUiState(customerId, isLoading = true),
                onRetry = customersViewModel::retryCustomerDetail,
            )
        }

        composable(ServoraRoutes.JOB_DETAIL, jobIdArgument()) { entry ->
            val jobId = entry.jobId()
            // Reaching this screen directly, such as from a restored back stack, must still read the
            // Job it shows. The Job and its crew are the backend's, so the destination asks for them
            // and holds nothing of its own (`BR-001`).
            LaunchedEffect(jobId) { jobDetailsViewModel.start(jobId) }
            val state by jobDetailsViewModel.uiState.collectAsState()
            // The player's moving answer is collected **without** being read here: it is passed down as a
            // state, so this destination, the screen and the timeline are not recomposed ten times a second
            // while a recording plays — only the playhead and the elapsed seconds that read it are
            // (`ADR-018` A11, `BR-012`).
            val audioProgress = jobDetailsViewModel.audioPlaybackProgress.collectAsState()
            val context = LocalContext.current
            // Capturing evidence opens the device's camera into a file this app owns, so no storage
            // access and no camera permission are needed (`BR-015`, offline standard §9).
            val capturePhoto = rememberJobPhotoCapture(
                beginCapture = jobDetailsViewModel::beginPhotoCapture,
                onCaptured = jobDetailsViewModel::photoCaptured,
                // A dismissed or unavailable camera captured nothing, so nothing is recorded and
                // nothing is claimed (`BR-042`); any file it created is cleaned up (`§9`).
                onCancelled = jobDetailsViewModel::photoCaptureCancelled,
            )
            // The second source is the device's own photo picker, which the technician chooses from
            // in the update sheet (`D3`): it hands over only what they select, and Servora asks for no
            // storage or media-read permission (`BR-015`).
            val choosePhotos = rememberJobPhotoPicker(
                onPicked = jobDetailsViewModel::photosPicked,
                // A device whose picker no application can show is reported rather than looking like
                // a cancellation (`BR-042`).
                onUnavailable = jobDetailsViewModel::photoPickerUnavailable,
            )
            JobDetailsOverviewScreen(
                state = state,
                canUpdateJob = permissions.canUpdateJob,
                // The evidence capability is its own, so a technician who records field evidence is
                // offered the photo sources without holding the Job update capability (`BR-009`).
                canAddEvidencePhoto = permissions.canAddEvidencePhoto,
                canViewTechnicians = permissions.canViewTechnicians,
                // The Customer destination is the office read (`customers.view`). A session holding only
                // the field capabilities is not offered a tap the API would refuse, and reads the
                // customer's contact details in place instead (`BR-011`, `BR-092`).
                canOpenCustomer = permissions.canOpenCustomers,
                // The Visit's field lifecycle is the technician's own capability set (`BR-009`,
                // `BR-066`), so the field action is drawn on it and never on the office's Job update:
                // a Manager without them is offered no field action (`BR-007`, `BR-011`).
                canUpdateAssignedVisit = permissions.canUpdateAssignedVisit,
                canRecordVisitOutcome = permissions.canRecordVisitOutcome,
                canAddVisitNote = permissions.canAddVisitNote,
                onRetry = jobDetailsViewModel::retry,
                onRetryActivity = jobDetailsViewModel::retryActivity,
                // The customer the Job belongs to is one this organization owns, so the row opens the
                // customer destination the customer list already provides (`BR-048`).
                onOpenCustomer = { customerId ->
                    navController.push(ServoraRoutes.customerDetail(customerId))
                },
                // The address is handed to the device so it opens in the user's preferred map
                // application; Servora names no provider (`BR-038`, `BR-042`).
                onOpenInMaps = { address -> openAddressInMaps(context, address) },
                onLoadAssignableTechnicians = jobDetailsViewModel::loadAssignableTechnicians,
                onChangeJobStatus = { status -> jobDetailsViewModel.changeJobStatus(status) },
                onChangeVisitStatus = { status, outcome, summary ->
                    jobDetailsViewModel.changeVisitStatus(status, outcome, summary)
                },
                onDiscardQueuedVisitAction = jobDetailsViewModel::discardQueuedVisitAction,
                onDiscardQueuedVisitNote = jobDetailsViewModel::discardQueuedVisitNote,
                onAddActivityText = jobDetailsViewModel::addActivityText,
                onRescheduleVisit = jobDetailsViewModel::rescheduleVisit,
                onAssignTechnicians = jobDetailsViewModel::assignVisitTechnicians,
                onConfirmPendingAction = jobDetailsViewModel::confirmPendingAction,
                onDismissPendingAction = jobDetailsViewModel::dismissPendingAction,
                onDismissActionMessage = jobDetailsViewModel::dismissActionMessage,
                onCapturePhoto = capturePhoto,
                onChoosePhotos = choosePhotos,
                onConfirmCapturedPhoto = jobDetailsViewModel::confirmCapturedPhoto,
                onDiscardCapturedPhoto = jobDetailsViewModel::discardCapturedPhoto,
                onKeepCapturedPhoto = jobDetailsViewModel::keepCapturedPhoto,
                onRemovePendingPhoto = jobDetailsViewModel::removePendingPhoto,
                onSubmitPendingPhotos = jobDetailsViewModel::submitPendingPhotos,
                onSavePhoto = jobDetailsViewModel::savePhotoToDevice,
                onSharePhoto = jobDetailsViewModel::sharePhoto,
                onRemoveEvidencePhoto = jobDetailsViewModel::removeEvidencePhoto,
                onSavePermissionResult = jobDetailsViewModel::onSavePermissionResult,
                // Reading evidence is its own capability, so the viewer's save and share actions are
                // drawn only for a session that may read the photo back (`BR-006`, `BR-011`), while
                // removing recorded evidence is a Manager-level capability of its own (`BR-089`).
                canViewEvidence = permissions.canViewEvidence,
                canRemoveEvidence = permissions.canRemoveEvidence,
                // Recording an audio note is its own capability, so the *Add audio* kind is offered only
                // to a session the API would accept a recording from (`BR-006`, `BR-007`).
                canAddAudio = permissions.canAddEvidenceAudio,
                // Removing an accepted recording is the audio kind's own Manager-level capability, never
                // inferred from the photo one (`BR-089`, `ADR-018` A7).
                canRemoveAudioEvidence = permissions.canRemoveAudioEvidence,
                onSelectAudioPhase = jobDetailsViewModel::selectAudioPhase,
                onStartAudioRecording = jobDetailsViewModel::startAudioRecording,
                onStopAudioRecording = jobDetailsViewModel::stopAudioRecording,
                onCancelAudioRecording = jobDetailsViewModel::cancelAudioRecording,
                onToggleAudioPlayback = jobDetailsViewModel::toggleAudioPlayback,
                // Moving through a recording is the player's own operation, not a write to the Job
                // (`BR-001`, `ADR-018` A11).
                onSeekAudioPlayback = jobDetailsViewModel::seekAudioPlayback,
                onAttachAudioNote = jobDetailsViewModel::attachAudioNote,
                onRemovePendingAudioNote = jobDetailsViewModel::removePendingAudioNote,
                onRemoveEvidenceAudioNote = jobDetailsViewModel::removeEvidenceAudioNote,
                onMicrophoneDenied = jobDetailsViewModel::microphoneDenied,
                onDismissAudioMessage = jobDetailsViewModel::dismissAudioMessage,
                onDismissPhotoMessage = jobDetailsViewModel::dismissPhotoMessage,
                photoImages = jobDetailsViewModel.photoImages,
                audioProgress = audioProgress,
            )
        }

        composable(ServoraRoutes.CUSTOMER_EDIT, customerIdArgument()) { entry ->
            // The destination instance is the form session: the form reads the customer it edits,
            // and re-entering the same instance keeps what the user entered. A new instance reads
            // the customer again, so no value or message from the previous session is carried over.
            editCustomerViewModel.start(entry.customerId(), entry.id)
            val state by editCustomerViewModel.uiState.collectAsState()
            EditCustomerScreen(
                state = state,
                onTypeChange = editCustomerViewModel::onTypeChange,
                onCompanyNameChange = editCustomerViewModel::onCompanyNameChange,
                onFirstNameChange = editCustomerViewModel::onFirstNameChange,
                onLastNameChange = editCustomerViewModel::onLastNameChange,
                onPhoneChange = editCustomerViewModel::onPhoneChange,
                onEmailChange = editCustomerViewModel::onEmailChange,
                onNotesChange = editCustomerViewModel::onNotesChange,
                onStatusChange = editCustomerViewModel::onStatusChange,
                onSave = editCustomerViewModel::save,
                onCancel = { navController.navigateUp() },
                onRetry = editCustomerViewModel::retry,
                onSaved = {
                    // The edit is on the backend now, so the customer's detail and the list are
                    // re-read rather than patched locally (`BR-001`); a conversion changes the type
                    // the list row shows and may replace the subtype values the detail is built on.
                    customersViewModel.reloadCustomerDetail(entry.customerId())
                    customersViewModel.reload()
                    navController.popBackStack()
                },
            )
        }

        composable(ServoraRoutes.CUSTOMER_CREATE) { entry ->
            // The destination instance is the form session, begun before the screen composes so a
            // previous attempt's validation or submission error cannot be drawn again. Re-entering
            // this same instance passes the same id, which the ViewModel ignores.
            addCustomerViewModel.start(entry.id)
            val state by addCustomerViewModel.uiState.collectAsState()
            AddCustomerScreen(
                state = state,
                canCreateProperty = permissions.canCreateProperty,
                canCreateContact = permissions.canEditCustomer,
                onTypeChange = addCustomerViewModel::onTypeChange,
                onCompanyNameChange = addCustomerViewModel::onCompanyNameChange,
                onFirstNameChange = addCustomerViewModel::onFirstNameChange,
                onLastNameChange = addCustomerViewModel::onLastNameChange,
                onPhoneChange = addCustomerViewModel::onPhoneChange,
                onEmailChange = addCustomerViewModel::onEmailChange,
                onNotesChange = addCustomerViewModel::onNotesChange,
                onStreetAddressChange = addCustomerViewModel::onStreetAddressChange,
                onUnitChange = addCustomerViewModel::onUnitChange,
                onCityChange = addCustomerViewModel::onCityChange,
                onProvinceChange = addCustomerViewModel::onProvinceChange,
                onPostalCodeChange = addCustomerViewModel::onPostalCodeChange,
                onPropertyNameChange = addCustomerViewModel::onPropertyNameChange,
                onPropertyNotesChange = addCustomerViewModel::onPropertyNotesChange,
                onContactFirstNameChange = addCustomerViewModel::onContactFirstNameChange,
                onContactLastNameChange = addCustomerViewModel::onContactLastNameChange,
                onSave = addCustomerViewModel::save,
                onCancel = { navController.navigateUp() },
                onSaved = { customerId -> openCreatedCustomer(navController, customersViewModel, customerId) },
                onOpenCreatedCustomer = { customerId ->
                    openCreatedCustomer(navController, customersViewModel, customerId)
                },
            )
        }

        // Create Job, with the Customer optional (`BR-094`). The destination instance is the form
        // session, begun before the screen composes so a previous attempt's validation or submission
        // error cannot be drawn again.
        composable(ServoraRoutes.JOB_CREATE, optionalCustomerIdArgument()) { entry ->
            val customerId = entry.arguments?.getString(ServoraRoutes.CUSTOMER_ID)
            // The fixed Customer's name is the one its detail destination already read; without it the
            // form asks the user to search instead (`BR-012`).
            val customerName = customerId?.let(customerName)
            createJobViewModel.start(customerId, customerName, entry.id)
            val state by createJobViewModel.uiState.collectAsState()
            CreateJobScreen(
                state = state,
                onCustomerQueryChange = createJobViewModel::onCustomerQueryChange,
                onCustomerPickerOpen = createJobViewModel::onCustomerPickerOpen,
                onSelectCustomer = createJobViewModel::selectCustomer,
                onRetryCustomers = createJobViewModel::retryCustomers,
                onSelectProperty = createJobViewModel::selectProperty,
                onRetryProperties = createJobViewModel::retryProperties,
                onTitleChange = createJobViewModel::onTitleChange,
                onDescriptionChange = createJobViewModel::onDescriptionChange,
                onSubmit = createJobViewModel::submit,
                onCancel = { navController.navigateUp() },
                onCreated = { jobId ->
                    // The Job exists on the backend, so the customer's Job rows and counts are re-read
                    // rather than patched locally (`BR-001`), and the form leaves the back stack so Back
                    // from the new Job returns to where the user started rather than to the form.
                    customerId?.let { createdFor ->
                        customersViewModel.reloadCustomerDetail(createdFor)
                    }
                    customersViewModel.reload()
                    navController.navigate(ServoraRoutes.jobDetail(jobId)) {
                        popUpTo(ServoraRoutes.JOB_CREATE) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
    }
}

/**
 * Leaves the New Customer form for the customer it just created.
 *
 * The list is re-read rather than patched locally, because the new customer is the backend's row
 * (`BR-001`), and the form is removed from the back stack so Back from the customer returns to the
 * list instead of the form.
 */
private fun openCreatedCustomer(
    navController: NavHostController,
    customersViewModel: CustomersViewModel,
    customerId: String,
) {
    customersViewModel.reload()
    navController.navigate(ServoraRoutes.customerDetail(customerId)) {
        popUpTo(ServoraRoutes.CUSTOMER_CREATE) { inclusive = true }
        launchSingleTop = true
    }
}

/**
 * The contextual top bar for whichever destination is currently on top.
 *
 * This is the single place that maps a destination to its header, so no screen draws a second top
 * bar and no route comparison is repeated elsewhere. Root destinations use the [rootState] the shell
 * builds from the selected bottom-navigation tab; every pushed screen supplies its own localized
 * title, gets a back control that pops the same entry Android's system Back pops, and adds only the
 * actions its permissions allow (`docs/decisions/011-android-contextual-top-bar.md`).
 *
 * [customerName] resolves the customer a destination belongs to, so a screen whose meaning depends on
 * its customer can show that customer as its context line. It stays a lookup rather than a value
 * because the header is built before a destination's read has necessarily answered.
 */
@Composable
fun servoraTopBarState(
    navController: NavHostController,
    rootState: ServoraTopBarState,
    permissions: CustomerPermissionsUiState,
    customerName: (customerId: String) -> String?,
): ServoraTopBarState {
    val entry by navController.currentBackStackEntryAsState()
    return when (entry?.destination?.route) {
        ServoraRoutes.CUSTOMER_DETAIL -> {
            val customerId = entry?.arguments?.getString(ServoraRoutes.CUSTOMER_ID).orEmpty()
            ServoraTopBarState(
                title = stringResource(R.string.customers_view_title),
                isRoot = false,
                onBack = { navController.navigateUp() },
                actions = {
                    if (permissions.canEditCustomer) {
                        TextButton(
                            modifier = Modifier.testTag(EditCustomerActionTag),
                            onClick = { navController.push(ServoraRoutes.customerEdit(customerId)) },
                        ) {
                            Text(stringResource(R.string.customers_edit_short))
                        }
                    }
                },
            )
        }

        ServoraRoutes.CUSTOMER_JOBS -> pushedScreenTopBar(
            title = stringResource(R.string.customers_all_jobs_title),
            navController = navController,
        )

        ServoraRoutes.JOB_DETAIL -> pushedScreenTopBar(
            title = stringResource(R.string.job_details_title),
            navController = navController,
        )

        ServoraRoutes.JOB_CREATE -> pushedScreenTopBar(
            title = stringResource(R.string.job_create_title),
            navController = navController,
        )

        ServoraRoutes.CUSTOMER_ADD_PROPERTY -> ServoraTopBarState(
            title = stringResource(R.string.property_add_title),
            isRoot = false,
            onBack = { navController.navigateUp() },
            subtitle = customerName(
                entry?.arguments?.getString(ServoraRoutes.CUSTOMER_ID).orEmpty(),
            ),
        )

        ServoraRoutes.CUSTOMER_ADD_CONTACT -> ServoraTopBarState(
            title = stringResource(R.string.contact_add_title),
            isRoot = false,
            onBack = { navController.navigateUp() },
            subtitle = customerName(
                entry?.arguments?.getString(ServoraRoutes.CUSTOMER_ID).orEmpty(),
            ),
        )

        ServoraRoutes.CUSTOMER_EDIT_CONTACT -> ServoraTopBarState(
            title = stringResource(R.string.contact_edit_title),
            isRoot = false,
            onBack = { navController.navigateUp() },
            subtitle = customerName(
                entry?.arguments?.getString(ServoraRoutes.CUSTOMER_ID).orEmpty(),
            ),
        )

        ServoraRoutes.PROPERTY_DETAIL -> {
            val customerId = entry?.arguments?.getString(ServoraRoutes.CUSTOMER_ID).orEmpty()
            val propertyId = entry?.arguments?.getString(ServoraRoutes.PROPERTY_ID).orEmpty()
            ServoraTopBarState(
                title = stringResource(R.string.property_detail_title),
                isRoot = false,
                onBack = { navController.navigateUp() },
                subtitle = customerName(customerId),
                actions = {
                    // Editing is its own Property capability (`BR-085`), so the action is drawn only
                    // when the user holds it; the backend remains the authority (`BR-007`).
                    if (permissions.canEditProperty) {
                        TextButton(
                            modifier = Modifier.testTag(EditPropertyActionTag),
                            onClick = {
                                navController.push(
                                    ServoraRoutes.propertyEdit(customerId, propertyId),
                                )
                            },
                        ) {
                            Text(stringResource(R.string.customers_edit_short))
                        }
                    }
                },
            )
        }

        ServoraRoutes.PROPERTY_EDIT -> ServoraTopBarState(
            title = stringResource(R.string.property_edit_title),
            isRoot = false,
            onBack = { navController.navigateUp() },
            subtitle = customerName(
                entry?.arguments?.getString(ServoraRoutes.CUSTOMER_ID).orEmpty(),
            ),
        )

        ServoraRoutes.CUSTOMER_EDIT -> pushedScreenTopBar(
            title = stringResource(R.string.customers_edit_title),
            navController = navController,
        )

        ServoraRoutes.CUSTOMER_CREATE -> pushedScreenTopBar(
            title = stringResource(R.string.customers_create_title),
            navController = navController,
        )

        else -> rootState
    }
}

/** A pushed screen's header: its own title and a back control, with no contextual action. */
private fun pushedScreenTopBar(
    title: String,
    navController: NavHostController,
) = ServoraTopBarState(
    title = title,
    isRoot = false,
    onBack = { navController.navigateUp() },
)

/** Pushes [route] without disturbing or duplicating what is already on the stack. */
private fun NavHostController.push(route: String) {
    navigate(route) { launchSingleTop = true }
}
