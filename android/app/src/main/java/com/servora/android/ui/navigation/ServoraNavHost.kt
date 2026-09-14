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
import com.servora.android.ui.customers.EditCustomerActionTag
import com.servora.android.ui.customers.EditCustomerScreen
import com.servora.android.ui.customers.EditCustomerViewModel
import com.servora.android.ui.customers.EditPropertyActionTag
import com.servora.android.ui.customers.EditPropertyViewModel
import com.servora.android.ui.customers.PropertyDetailScreen
import com.servora.android.ui.customers.PropertyDetailViewModel
import com.servora.android.ui.jobs.JobDetailsScreen
import com.servora.android.ui.jobs.JobDetailsViewModel

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

    const val JOB_ID = "jobId"

    const val CUSTOMER_DETAIL = "customer/detail/{customerId}"
    const val CUSTOMER_EDIT = "customer/edit/{customerId}"
    const val CUSTOMER_JOBS = "customer/jobs/{customerId}"
    const val CUSTOMER_CREATE = "customer/create"
    const val CUSTOMER_ADD_PROPERTY = "customer/properties/new/{customerId}"
    const val PROPERTY_DETAIL = "customer/properties/detail/{customerId}/{propertyId}"
    const val PROPERTY_EDIT = "customer/properties/edit/{customerId}/{propertyId}"
    const val JOB_DETAIL = "job/detail/{jobId}"

    fun customerDetail(customerId: String): String = "customer/detail/${Uri.encode(customerId)}"

    fun customerEdit(customerId: String): String = "customer/edit/${Uri.encode(customerId)}"

    fun customerJobs(customerId: String): String = "customer/jobs/${Uri.encode(customerId)}"

    fun customerAddProperty(customerId: String): String =
        "customer/properties/new/${Uri.encode(customerId)}"

    fun propertyDetail(customerId: String, propertyId: String): String =
        "customer/properties/detail/${Uri.encode(customerId)}/${Uri.encode(propertyId)}"

    fun propertyEdit(customerId: String, propertyId: String): String =
        "customer/properties/edit/${Uri.encode(customerId)}/${Uri.encode(propertyId)}"

    fun jobDetail(jobId: String): String = "job/detail/${Uri.encode(jobId)}"
}

private fun NavBackStackEntry.customerId(): String =
    arguments?.getString(ServoraRoutes.CUSTOMER_ID).orEmpty()

private fun NavBackStackEntry.propertyId(): String =
    arguments?.getString(ServoraRoutes.PROPERTY_ID).orEmpty()

private fun NavBackStackEntry.jobId(): String =
    arguments?.getString(ServoraRoutes.JOB_ID).orEmpty()

private fun customerIdArgument() =
    listOf(navArgument(ServoraRoutes.CUSTOMER_ID) { type = NavType.StringType })

private fun jobIdArgument() =
    listOf(navArgument(ServoraRoutes.JOB_ID) { type = NavType.StringType })

private fun propertyArguments() = listOf(
    navArgument(ServoraRoutes.CUSTOMER_ID) { type = NavType.StringType },
    navArgument(ServoraRoutes.PROPERTY_ID) { type = NavType.StringType },
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
    jobDetailsViewModel: JobDetailsViewModel,
    permissions: CustomerPermissionsUiState,
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
            CustomerDetailScreen(
                state = state.customerDetail ?: CustomerDetailUiState(customerId, isLoading = true),
                canViewProperties = permissions.canViewProperties,
                canAddProperty = permissions.canCreateProperty,
                onAddProperty = {
                    // The Add Property destination begins its own form session before it composes,
                    // so nothing has to be reset here and a previous attempt's terminal state cannot
                    // make the new screen report itself saved on entry.
                    navController.push(ServoraRoutes.customerAddProperty(customerId))
                },
                onSeeAllJobs = { navController.push(ServoraRoutes.customerJobs(customerId)) },
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
            val context = LocalContext.current
            JobDetailsScreen(
                state = state,
                canUpdateJob = permissions.canUpdateJob,
                canViewTechnicians = permissions.canViewTechnicians,
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
                onAddActivityText = jobDetailsViewModel::addActivityText,
                onRescheduleVisit = jobDetailsViewModel::rescheduleVisit,
                onAssignTechnicians = jobDetailsViewModel::assignVisitTechnicians,
                onConfirmPendingAction = jobDetailsViewModel::confirmPendingAction,
                onDismissPendingAction = jobDetailsViewModel::dismissPendingAction,
                onDismissActionMessage = jobDetailsViewModel::dismissActionMessage,
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

        ServoraRoutes.CUSTOMER_ADD_PROPERTY -> ServoraTopBarState(
            title = stringResource(R.string.property_add_title),
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
