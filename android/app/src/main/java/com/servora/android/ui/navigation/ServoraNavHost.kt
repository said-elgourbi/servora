package com.servora.android.ui.navigation

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
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
import com.servora.android.ui.customers.AddPropertyScreen
import com.servora.android.ui.customers.AddPropertyViewModel
import com.servora.android.ui.customers.CustomerDetailScreen
import com.servora.android.ui.customers.CustomerDetailUiState
import com.servora.android.ui.customers.CustomerFormScreen
import com.servora.android.ui.customers.CustomerJobHistoryScreen
import com.servora.android.ui.customers.CustomerPermissionsUiState
import com.servora.android.ui.customers.CustomersScreen
import com.servora.android.ui.customers.CustomersViewModel
import com.servora.android.ui.customers.EditCustomerActionTag
import com.servora.android.ui.customers.EditPropertyActionTag
import com.servora.android.ui.customers.EditPropertyViewModel
import com.servora.android.ui.customers.PropertyDetailScreen
import com.servora.android.ui.customers.PropertyDetailViewModel

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

    const val CUSTOMER_DETAIL = "customer/detail/{customerId}"
    const val CUSTOMER_EDIT = "customer/edit/{customerId}"
    const val CUSTOMER_JOBS = "customer/jobs/{customerId}"
    const val CUSTOMER_CREATE = "customer/create"
    const val CUSTOMER_ADD_PROPERTY = "customer/properties/new/{customerId}"
    const val PROPERTY_DETAIL = "customer/properties/detail/{customerId}/{propertyId}"
    const val PROPERTY_EDIT = "customer/properties/edit/{customerId}/{propertyId}"

    fun customerDetail(customerId: String): String = "customer/detail/${Uri.encode(customerId)}"

    fun customerEdit(customerId: String): String = "customer/edit/${Uri.encode(customerId)}"

    fun customerJobs(customerId: String): String = "customer/jobs/${Uri.encode(customerId)}"

    fun customerAddProperty(customerId: String): String =
        "customer/properties/new/${Uri.encode(customerId)}"

    fun propertyDetail(customerId: String, propertyId: String): String =
        "customer/properties/detail/${Uri.encode(customerId)}/${Uri.encode(propertyId)}"

    fun propertyEdit(customerId: String, propertyId: String): String =
        "customer/properties/edit/${Uri.encode(customerId)}/${Uri.encode(propertyId)}"
}

private fun NavBackStackEntry.customerId(): String =
    arguments?.getString(ServoraRoutes.CUSTOMER_ID).orEmpty()

private fun NavBackStackEntry.propertyId(): String =
    arguments?.getString(ServoraRoutes.PROPERTY_ID).orEmpty()

private fun customerIdArgument() =
    listOf(navArgument(ServoraRoutes.CUSTOMER_ID) { type = NavType.StringType })

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
    addPropertyViewModel: AddPropertyViewModel,
    propertyDetailViewModel: PropertyDetailViewModel,
    editPropertyViewModel: EditPropertyViewModel,
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
                    // The form is reset before the destination composes, so a previous save's
                    // terminal state cannot make the new screen report itself saved on entry.
                    addPropertyViewModel.start(customerId)
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
            LaunchedEffect(customerId) { addPropertyViewModel.start(customerId) }
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
            LaunchedEffect(customerId, propertyId) {
                propertyDetailViewModel.start(customerId, propertyId)
            }
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
            LaunchedEffect(customerId, propertyId) {
                editPropertyViewModel.start(customerId, propertyId)
            }
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

        composable(ServoraRoutes.CUSTOMER_EDIT, customerIdArgument()) { entry ->
            // The destination carries the customer it edits, so the form is scoped to one customer
            // rather than being a single shared screen.
            key(entry.customerId()) {
                CustomerFormScreen(
                    title = stringResource(R.string.customers_edit_title),
                    action = stringResource(R.string.customers_save),
                )
            }
        }

        composable(ServoraRoutes.CUSTOMER_CREATE) {
            CustomerFormScreen(
                title = stringResource(R.string.customers_create_title),
                action = stringResource(R.string.customers_create_action),
            )
        }
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
