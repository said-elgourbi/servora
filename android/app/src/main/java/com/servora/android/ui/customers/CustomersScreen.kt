package com.servora.android.ui.customers

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.servora.android.domain.model.CustomerFilters
import com.servora.android.domain.model.CustomerJobFilter
import com.servora.android.domain.model.CustomerStatus
import com.servora.android.domain.model.CustomerStatusFilter
import com.servora.android.R
import com.servora.android.ui.components.ServoraTopBar
import com.servora.android.ui.components.ServoraTopBarState
import com.servora.android.ui.components.initials
import com.servora.android.ui.home.ManagerHomeScreen
import com.servora.android.ui.home.ManagerHomeViewModel
import com.servora.android.ui.home.managerHomeHeader
import com.servora.android.ui.jobs.JobDetailsViewModel
import com.servora.android.ui.navigation.ServoraNavHost
import com.servora.android.ui.navigation.ServoraRoutes
import com.servora.android.ui.navigation.servoraTopBarState
import com.servora.android.ui.theme.stateColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

const val CustomersNavTag = "customers-nav"
const val AddCustomerActionTag = "customers-add-action"
const val EditCustomerActionTag = "customers-edit-action"
const val SettingsSignOutTag = "settings-sign-out"

fun customerRowTag(customerId: String): String = "customer-row-$customerId"

/** Tags for the row's two contact values. The row displays them; it does not dial or compose. */
fun customerPhoneTag(customerId: String): String = "customer-phone-$customerId"
fun customerEmailTag(customerId: String): String = "customer-email-$customerId"

/** Tags for the row's status/"since" line and its trailing open-details chevron. */
fun customerSinceTag(customerId: String): String = "customer-since-$customerId"
fun customerStatusTag(customerId: String): String = "customer-status-$customerId"
fun customerChevronTag(customerId: String): String = "customer-chevron-$customerId"

/** Tags for the customer filter sheet, its controls and the button that opens it. */
const val CustomersFilterSheetTag = "customers-filter-sheet"
const val CustomersFilterButtonTag = "customers-filter-button"
const val CustomersFilterApplyTag = "customers-filter-apply"
const val CustomersFilterClearTag = "customers-filter-clear"

/** Tags for the applied-filter indication: the button's count and the chips under the search field. */
const val CustomersFilterCountTag = "customers-filter-count"
const val CustomersFilterChipsTag = "customers-filter-chips"
const val CustomersFilterStatusChipTag = "customers-filter-chip-status"
const val CustomersFilterStatusChipClearTag = "customers-filter-chip-status-clear"
const val CustomersFilterJobsChipTag = "customers-filter-chip-jobs"
const val CustomersFilterJobsChipClearTag = "customers-filter-chip-jobs-clear"
const val CustomersFilterClearAllTag = "customers-filter-clear-all"

fun customersFilterStatusTag(filter: CustomerStatusFilter): String =
    "customers-filter-status-${filter.name}"

fun customersFilterJobTag(filter: CustomerJobFilter): String =
    "customers-filter-job-${filter.name}"

private const val CustomerContactLogTag = "CustomersContact"
private const val CustomerSinceLogTag = "CustomersSince"

/*
 * Metrics the customers list is built from.
 *
 * The Android values come from `docs/design/android-design-system.md`, which deliberately records
 * larger phone metrics than the web design it mirrors: `Figma/src/screens/Customers.tsx` draws the
 * search field and the filter control at 40 px, while the design system sets a field to 52 dp and
 * a touch target to at least 48 dp.
 */
private val CustomerFieldHeight = 52.dp

/** The row's own inset, which is also the only gutter the list adds (`px-2 -mx-2`). */
private val CustomerRowGutter = 8.dp
private val CustomerSearchIconSize = 18.dp
private val CustomerBusinessIconSize = 14.dp
private val CustomerContactIconSize = 12.dp
private val CustomerCountIconSize = 12.dp
private val CustomerChevronIconSize = 18.dp

private enum class DashboardTab { HOME, SCHEDULE, CUSTOMERS, SETTINGS }

@Composable
fun ServoraHomeScreen(
    permissions: CustomerPermissionsUiState,
    customersViewModel: CustomersViewModel,
    addCustomerViewModel: AddCustomerViewModel,
    editCustomerViewModel: EditCustomerViewModel,
    addPropertyViewModel: AddPropertyViewModel,
    propertyDetailViewModel: PropertyDetailViewModel,
    editPropertyViewModel: EditPropertyViewModel,
    managerHomeViewModel: ManagerHomeViewModel,
    jobDetailsViewModel: JobDetailsViewModel,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val customers by customersViewModel.uiState.collectAsState()
    val managerHome by managerHomeViewModel.uiState.collectAsState()
    // The list is read once the user is allowed to see customers (`BR-007`); the backend still
    // decides whether the read succeeds.
    LaunchedEffect(permissions.canOpenCustomers) {
        if (permissions.canOpenCustomers) {
            customersViewModel.load()
        }
    }
    var selected by rememberSaveable {
        mutableStateOf(if (permissions.canOpenCustomers) DashboardTab.CUSTOMERS.name else DashboardTab.HOME.name)
    }
    val selectedTab = DashboardTab.valueOf(selected)
    val navController = rememberNavController()

    // The home asks the backend for today's operation in the device's own zone, so the day the
    // backend resolves is the day the manager is working in (`BR-001`).
    val timeZoneId = remember { ZoneId.systemDefault().id }
    LaunchedEffect(selectedTab) {
        if (selectedTab == DashboardTab.HOME) {
            managerHomeViewModel.load(timeZoneId)
        }
    }

    // The one contextual top bar. A root destination shows the title of the tab the user is on and
    // no back control; a pushed screen replaces this with its own title, a back control and its
    // contextual actions (`docs/decisions/011-android-contextual-top-bar.md`).
    //
    // Home is the exception the design calls for: the manager's home opens with a greeting and the
    // current date rather than the tab's name. It is the same one top bar, so the home still does
    // not stack a second header above its content.
    val homeHeader = managerHomeHeader(managerHome.home?.displayName)
    val rootTitle = when (selectedTab) {
        DashboardTab.HOME -> homeHeader.title
        DashboardTab.SCHEDULE -> stringResource(R.string.nav_schedule)
        DashboardTab.CUSTOMERS -> stringResource(R.string.nav_customers)
        DashboardTab.SETTINGS -> stringResource(R.string.nav_settings)
    }
    val topBarState = servoraTopBarState(
        navController = navController,
        rootState = ServoraTopBarState(
            title = rootTitle,
            isRoot = true,
            subtitle = if (selectedTab == DashboardTab.HOME) homeHeader.subtitle else null,
        ),
        permissions = permissions,
        // The customer a destination belongs to, for the destinations that show it as context. The
        // detail is read by the destination itself, so this lookup answers as soon as it has.
        customerName = { customerId ->
            customers.customerDetail
                ?.takeIf { it.customerId == customerId }
                ?.detail
                ?.customer
                ?.displayName
        },
    )

    Scaffold(
        // The window draws edge-to-edge (enforced from Android 15 for this target SDK), so the
        // keyboard is an inset the app has to apply rather than a window resize. Without it the
        // soft keyboard simply covers the bottom of the shell, which leaves a form's last field
        // and its actions underneath the keyboard while it is being typed in.
        //
        // The inset belongs to the shell rather than to each form: insetting here shrinks what the
        // destination's scrollable content is measured against, which is the resize Compose answers
        // by bringing a focused field back into view, and it lifts the bottom navigation with the
        // content instead of leaving a bottom-bar-sized gap above the keyboard.
        modifier = modifier.fillMaxSize().imePadding(),
        topBar = { ServoraTopBar(state = topBarState) },
        bottomBar = {
            DashboardNavigation(
                selected = selectedTab,
                canOpenCustomers = permissions.canOpenCustomers,
                onSelect = { tab ->
                    selected = tab.name
                    // Choosing a tab leaves any drill-down screen behind, so the back stack is never
                    // left pointing at a screen the tab no longer shows.
                    navController.popBackStack(ServoraRoutes.ROOT, inclusive = false)
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        ServoraNavHost(
            navController = navController,
            customersViewModel = customersViewModel,
            addCustomerViewModel = addCustomerViewModel,
            editCustomerViewModel = editCustomerViewModel,
            addPropertyViewModel = addPropertyViewModel,
            propertyDetailViewModel = propertyDetailViewModel,
            editPropertyViewModel = editPropertyViewModel,
            jobDetailsViewModel = jobDetailsViewModel,
            permissions = permissions,
            // The bottom-navigation area is the graph's root destination; the drill-down screens are
            // pushed on top of it.
            rootContent = {
                // Re-entering the bottom-navigation area means a drill-down screen was left behind,
                // so the customer it showed is released rather than kept alive behind the list.
                LaunchedEffect(Unit) { customersViewModel.closeCustomerDetail() }
                when (selectedTab) {
                    DashboardTab.CUSTOMERS ->
                        CustomersScreen(
                            permissions = permissions,
                            state = customers,
                            onCreate = {
                                navController.navigate(ServoraRoutes.CUSTOMER_CREATE)
                            },
                            onOpenCustomer = { customerId ->
                                navController.navigate(ServoraRoutes.customerDetail(customerId))
                            },
                            onRetry = customersViewModel::retry,
                            onApplyFilters = customersViewModel::applyFilters,
                        )

                    DashboardTab.HOME ->
                        ManagerHomeScreen(
                            state = managerHome,
                            // A condition or a schedule row opens the Job it is about: the Job
                            // Details destination is where the manager acts on it (`BR-012`).
                            onOpenJob = { jobId ->
                                navController.navigate(ServoraRoutes.jobDetail(jobId))
                            },
                            // "See all" and the tab are the same destination: the schedule area is
                            // where today can be worked on, and it already exists in the bottom
                            // navigation.
                            onOpenSchedule = { selected = DashboardTab.SCHEDULE.name },
                            onRetry = { managerHomeViewModel.retry(timeZoneId) },
                        )

                    DashboardTab.SCHEDULE ->
                        PlaceholderTab(
                            title = stringResource(R.string.nav_schedule),
                            modifier = Modifier,
                        )

                    DashboardTab.SETTINGS ->
                        SettingsTab(onSignOut = onSignOut, modifier = Modifier)
                }
            },
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun DashboardNavigation(
    selected: DashboardTab,
    canOpenCustomers: Boolean,
    onSelect: (DashboardTab) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            DashboardNavItem(
                label = stringResource(R.string.nav_home),
                iconRes = R.drawable.ic_home,
                selected = selected == DashboardTab.HOME,
                onClick = { onSelect(DashboardTab.HOME) },
            )
            DashboardNavItem(
                label = stringResource(R.string.nav_schedule),
                iconRes = R.drawable.ic_calendar,
                selected = selected == DashboardTab.SCHEDULE,
                onClick = { onSelect(DashboardTab.SCHEDULE) },
            )
            if (canOpenCustomers) {
                DashboardNavItem(
                    label = stringResource(R.string.nav_customers),
                    iconRes = R.drawable.ic_users,
                    selected = selected == DashboardTab.CUSTOMERS,
                    modifier = Modifier.testTag(CustomersNavTag),
                    onClick = { onSelect(DashboardTab.CUSTOMERS) },
                )
            }
            DashboardNavItem(
                label = stringResource(R.string.nav_settings),
                iconRes = R.drawable.ic_settings,
                selected = selected == DashboardTab.SETTINGS,
                onClick = { onSelect(DashboardTab.SETTINGS) },
            )
        }
    }
}

/**
 * One bottom-navigation destination: the design pairs its glyph with the label, and the selected
 * destination is the only one drawn in the brand colour (`Figma/src/screens/Dashboard.tsx`).
 */
@Composable
private fun DashboardNavItem(
    label: String,
    iconRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = contentColor,
        )
    }
}

/**
 * The Customers destination: the root list the customer screens are opened from.
 *
 * The screen draws the list and nothing else. Which customer is open, and whether the create or edit
 * screen is on top, belongs to the navigation back stack
 * (`docs/decisions/010-android-navigation.md`), so system Back and a secondary header's arrow both
 * come back here.
 */
@Composable
fun CustomersScreen(
    permissions: CustomerPermissionsUiState,
    state: CustomersUiState,
    onCreate: () -> Unit,
    onOpenCustomer: (String) -> Unit,
    onRetry: () -> Unit,
    onApplyFilters: (CustomerFilters) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!permissions.canOpenCustomers) {
        NoCustomerAccess(modifier)
        return
    }

    CustomerListScreen(
        state = state,
        permissions = permissions,
        modifier = modifier,
        onCreate = onCreate,
        onRetry = onRetry,
        onApplyFilters = onApplyFilters,
        onSelect = { onOpenCustomer(it.id) },
    )
}

@Composable
private fun CustomerListScreen(
    state: CustomersUiState,
    permissions: CustomerPermissionsUiState,
    modifier: Modifier,
    onCreate: () -> Unit,
    onRetry: () -> Unit,
    onApplyFilters: (CustomerFilters) -> Unit,
    onSelect: (CustomerListItem) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val filtered = remember(state.customers, query) {
        state.customers.filter { customer ->
            query.isBlank() ||
                customer.displayName.contains(query, ignoreCase = true) ||
                customer.email?.contains(query, ignoreCase = true) == true ||
                customer.phone?.contains(query, ignoreCase = true) == true
        }
    }
    // Clearing restores the unconstrained list on the backend as well as the local search.
    val clearSearchAndFilters: () -> Unit = {
        query = ""
        onApplyFilters(CustomerFilters.Unconstrained)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.customers_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CustomerSearchField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                )
                // Opens the filter sheet the design defines (`Figma/src/screens/Customers.tsx`).
                // The list itself is narrowed by the backend, which stays the authority (`BR-001`).
                // The control is marked while any dimension is applied, including the default
                // active-customer filter.
                CustomerFilterButton(
                    appliedCount = state.filters.appliedCount,
                    onClick = { showFilters = true },
                )
            }
            // The applied filters sit under the search field, each removable, as designed.
            CustomerAppliedFilterChips(
                filters = state.filters,
                onApplyFilters = onApplyFilters,
            )
            Spacer(Modifier.height(8.dp))
            // The count describes what the backend returned, so it is withheld until a read has
            // produced a list.
            if (state.failureReason == null) {
                Text(
                    text = stringResource(
                        R.string.customers_count_format,
                        filtered.size,
                        if (filtered.size == 1) {
                            stringResource(R.string.customers_count_singular)
                        } else {
                            stringResource(R.string.customers_count_plural)
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // The rows carry the design's own 8 dp inset (`px-2 -mx-2`), so the list adds
                // only that gutter and every row's content lands on the 16 dp margin the header
                // uses.
                contentPadding = PaddingValues(
                    start = CustomerRowGutter,
                    end = CustomerRowGutter,
                    bottom = 96.dp,
                ),
            ) {
                when {
                    state.isLoading && state.customers.isEmpty() -> item { CustomersLoading() }

                    state.failureReason != null && state.customers.isEmpty() ->
                        item { CustomersError(onRetry = onRetry) }

                    // An empty list is "no match" when a filter is active, because the account
                    // may still have customers that the filter excludes (`BR-042`). Only an
                    // unconstrained empty list means the account has no customers yet.
                    state.customers.isEmpty() && state.filters.isActive ->
                        item { NoMatchingCustomers(onClear = clearSearchAndFilters) }

                    state.customers.isEmpty() ->
                        item {
                            EmptyCustomers(
                                onCreate = onCreate,
                                canCreateCustomer = permissions.canCreateCustomer,
                            )
                        }

                    filtered.isEmpty() ->
                        item { NoMatchingCustomers(onClear = clearSearchAndFilters) }

                    else -> items(filtered, key = { it.id }) { customer ->
                        CustomerRow(customer = customer, onClick = { onSelect(customer) })
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = CustomerRowGutter),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                        )
                    }
                }
            }

            // New Customer is pinned to the bottom of the list as a floating action so the primary
            // action stays reachable while the list scrolls. The design draws it in the header;
            // product moved it to the bottom, which is the only deviation from
            // `Figma/src/screens/Customers.tsx`.
            if (permissions.canCreateCustomer) {
                ExtendedFloatingActionButton(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .testTag(AddCustomerActionTag),
                    onClick = onCreate,
                    shape = MaterialTheme.shapes.large,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.customers_new))
                }
            }
        }
    }

    if (showFilters) {
        CustomersFilterSheet(
            filters = state.filters,
            onDismiss = { showFilters = false },
            onApply = { applied ->
                showFilters = false
                onApplyFilters(applied)
            },
            onClear = { onApplyFilters(CustomerFilters.Unconstrained) },
        )
    }
}

/**
 * The customers search field: a quiet filled control with a leading search glyph and a trailing
 * clear affordance, as designed (`Figma/src/screens/Customers.tsx`).
 *
 * [BasicTextField] is used rather than an `OutlinedTextField` because the design's field is a
 * filled, label-less surface with its own icon inset, which the outlined field's box and floating
 * label cannot express.
 */
@Composable
private fun CustomerSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(CustomerFieldHeight),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondary,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(CustomerSearchIconSize),
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                text = stringResource(R.string.customers_search_placeholder),
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
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.customers_search_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(CustomerSearchIconSize),
                    )
                }
            }
        }
    }
}

/**
 * The customers filter control. It is drawn as designed and keeps the search field's height, so
 * the two controls line up on the same row.
 *
 * While any filter dimension is applied the control takes the brand fill and its label becomes the
 * number of applied filters, so the applied state is visible without opening the sheet. The default
 * active-customer filter counts, as designed (`Figma/src/screens/Customers.tsx`).
 */
@Composable
private fun CustomerFilterButton(
    appliedCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasAppliedFilters = appliedCount > 0
    val appliedDescription = pluralStringResource(
        R.plurals.customers_filter_applied_count,
        appliedCount,
        appliedCount,
    )
    Surface(
        modifier = modifier
            .height(CustomerFieldHeight)
            .testTag(CustomersFilterButtonTag)
            .then(
                // The numeric label does not say what the number means; the spoken label does.
                if (hasAppliedFilters) {
                    Modifier.semantics { contentDescription = appliedDescription }
                } else {
                    Modifier
                },
            ),
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (hasAppliedFilters) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.secondary
        },
        contentColor = if (hasAppliedFilters) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        border = BorderStroke(
            1.dp,
            if (hasAppliedFilters) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_filter),
                contentDescription = null,
                tint = if (hasAppliedFilters) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(CustomerSearchIconSize),
            )
            if (hasAppliedFilters) {
                Text(
                    modifier = Modifier.testTag(CustomersFilterCountTag),
                    text = appliedCount.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Text(
                    text = stringResource(R.string.customers_filter),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * The filters currently applied, shown under the search field, as designed
 * (`Figma/src/screens/Customers.tsx`).
 *
 * Each chip removes its own dimension; Clear all removes every dimension. Both re-read the list
 * from the backend, which stays the authority for which customers match (`BR-001`). The row is
 * absent while nothing constrains the list.
 */
@Composable
private fun CustomerAppliedFilterChips(
    filters: CustomerFilters,
    onApplyFilters: (CustomerFilters) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!filters.isActive) {
        return
    }
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag(CustomersFilterChipsTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (filters.status != CustomerStatusFilter.ALL) {
            AppliedFilterChip(
                label = statusFilterLabel(filters.status),
                labelTag = CustomersFilterStatusChipTag,
                clearTag = CustomersFilterStatusChipClearTag,
                onClear = { onApplyFilters(filters.copy(status = CustomerStatusFilter.ALL)) },
            )
        }
        if (filters.jobs != CustomerJobFilter.ALL) {
            AppliedFilterChip(
                label = jobFilterLabel(filters.jobs),
                labelTag = CustomersFilterJobsChipTag,
                clearTag = CustomersFilterJobsChipClearTag,
                onClear = { onApplyFilters(filters.copy(jobs = CustomerJobFilter.ALL)) },
            )
        }
        TextButton(
            modifier = Modifier.testTag(CustomersFilterClearAllTag),
            onClick = { onApplyFilters(CustomerFilters.Unconstrained) },
        ) {
            Text(
                text = stringResource(R.string.customers_filter_clear_all),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** One applied-filter chip: the filter's label and an affordance that removes it. */
@Composable
private fun AppliedFilterChip(
    label: String,
    labelTag: String,
    clearTag: String,
    onClear: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                modifier = Modifier.testTag(labelTag),
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            IconButton(
                modifier = Modifier
                    .size(28.dp)
                    .testTag(clearTag),
                onClick = onClear,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(
                        R.string.customers_filter_remove_format,
                        label,
                    ),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/**
 * The customer filter sheet, as designed (`Figma/src/screens/Customers.tsx`).
 *
 * The user edits a local draft; nothing reaches the backend until it is committed. Clear resets the
 * draft and immediately asks for the unconstrained list, staying open so a different filter can be
 * chosen; Apply commits the draft and closes. Both routes re-read the list from the backend, which
 * remains the authority for which customers match (`BR-001`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomersFilterSheet(
    filters: CustomerFilters,
    onDismiss: () -> Unit,
    onApply: (CustomerFilters) -> Unit,
    onClear: () -> Unit,
) {
    var draft by remember { mutableStateOf(filters) }
    // Opening skips the partially expanded stop so the sheet is fully visible the first time it is
    // shown, which keeps the Commit actions reachable without dragging. The content scrolls when it
    // is taller than the space the sheet can occupy (for example on a short landscape phone).
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        modifier = Modifier.testTag(CustomersFilterSheetTag),
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.customers_filter_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.customers_filter_close),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(CustomerSearchIconSize),
                    )
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
            )

            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                FilterGroupLabel(text = stringResource(R.string.customers_filter_status_label))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CustomerStatusFilter.entries.forEach { option ->
                        FilterStatusOption(
                            label = statusFilterLabel(option),
                            selected = draft.status == option,
                            modifier = Modifier
                                .weight(1f)
                                .testTag(customersFilterStatusTag(option)),
                            onClick = { draft = draft.copy(status = option) },
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                FilterGroupLabel(text = stringResource(R.string.customers_filter_jobs_label))
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CustomerJobFilter.entries.forEach { option ->
                        FilterJobOption(
                            label = jobFilterLabel(option),
                            selected = draft.jobs == option,
                            modifier = Modifier.testTag(customersFilterJobTag(option)),
                            onClick = { draft = draft.copy(jobs = option) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (draft.isActive) {
                    OutlinedButton(
                        modifier = Modifier
                            .weight(1f)
                            .height(CustomerFieldHeight)
                            .testTag(CustomersFilterClearTag),
                        onClick = {
                            draft = CustomerFilters.Unconstrained
                            onClear()
                        },
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Text(stringResource(R.string.customers_filter_clear))
                    }
                }
                Button(
                    modifier = Modifier
                        .weight(1f)
                        .height(CustomerFieldHeight)
                        .testTag(CustomersFilterApplyTag),
                    onClick = { onApply(draft) },
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(stringResource(R.string.customers_filter_apply))
                }
            }
        }
    }
}

@Composable
private fun FilterGroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One segment of the Customer Status control: the selected segment uses the brand fill. */
@Composable
private fun FilterStatusOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(40.dp),
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.secondary
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** One row of the Jobs control: the selected row is outlined and carries the check glyph. */
@Composable
private fun FilterJobOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().height(48.dp),
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondary
        },
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
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
}

@Composable
private fun statusFilterLabel(filter: CustomerStatusFilter): String =
    when (filter) {
        CustomerStatusFilter.ALL -> stringResource(R.string.customers_filter_status_all)
        CustomerStatusFilter.ACTIVE -> stringResource(R.string.customers_status_active)
        CustomerStatusFilter.INACTIVE -> stringResource(R.string.customers_status_inactive)
    }

@Composable
private fun jobFilterLabel(filter: CustomerJobFilter): String =
    when (filter) {
        CustomerJobFilter.ALL -> stringResource(R.string.customers_filter_jobs_all)
        CustomerJobFilter.HAS_OPEN_JOBS -> stringResource(R.string.customers_filter_jobs_has_open)
        CustomerJobFilter.NO_OPEN_JOBS -> stringResource(R.string.customers_filter_jobs_no_open)
        CustomerJobFilter.HAS_OVERDUE_VISITS ->
            stringResource(R.string.customers_filter_jobs_has_overdue)
        CustomerJobFilter.NO_JOBS -> stringResource(R.string.customers_filter_jobs_none)
    }

@Composable
private fun CustomerRow(customer: CustomerListItem, onClick: () -> Unit) {
    val since = remember(customer.createdAt) { customerSince(customer.createdAt) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(customerRowTag(customer.id))
            // The whole row opens Customer Details, so it carries Material's standard pressed
            // feedback (the ripple); the shape clip bounds it to the row. The phone and email are
            // drawn as ordinary row content rather than nested links, so a tap that lands on one
            // opens the customer instead of dialling or composing.
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(horizontal = CustomerRowGutter, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        CustomerAvatar(customer)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = customer.displayName,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (customer.isCompany) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        painter = painterResource(R.drawable.ic_business),
                        contentDescription = stringResource(R.string.customers_business_mark),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(CustomerBusinessIconSize),
                    )
                }
            }
            // Status sits directly under the name, followed by the customer's "since" date when
            // the created timestamp can be read (`Figma/src/screens/Customers.tsx`).
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusPill(
                    status = customer.status,
                    modifier = Modifier.testTag(customerStatusTag(customer.id)),
                )
                since?.let { date ->
                    Text(
                        modifier = Modifier.testTag(customerSinceTag(customer.id)),
                        text = stringResource(R.string.customers_since_format, date),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Phone then email, each on its own line under the name with a deliberate gap. They are
            // values, not links: the row's only action is opening the customer, so a tap that lands
            // on a contact value opens the customer rather than dialling or composing. Customer
            // Details keeps both as `tel:`/`mailto:` links.
            customer.phone?.let { phone ->
                Spacer(Modifier.height(4.dp))
                CustomerContactLine(
                    modifier = Modifier.testTag(customerPhoneTag(customer.id)),
                    text = phone,
                    glyph = R.drawable.ic_phone,
                )
            }
            customer.email?.let { email ->
                Spacer(Modifier.height(2.dp))
                CustomerContactLine(
                    modifier = Modifier.testTag(customerEmailTag(customer.id)),
                    text = email,
                    glyph = R.drawable.ic_mail,
                )
            }

            Spacer(Modifier.height(6.dp))
            CustomerCountsRow(
                propertyCount = customer.propertyCount,
                jobCount = customer.jobCount,
            )
        }
        Spacer(Modifier.width(10.dp))
        // The chevron is the affordance: the whole row is the target that opens the customer.
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(CustomerChevronIconSize)
                .testTag(customerChevronTag(customer.id)),
        )
    }
}

/**
 * One contact line under a customer's name: the leading glyph and the value.
 *
 * The line is a link only when [onClick] is supplied — Customer Details passes the `tel:`/`mailto:`
 * action for the phone and the email, while the customers list passes none so that a tap on a
 * contact value opens the customer rather than dialling or composing
 * (`docs/tracker/007-android-customers-list.md`).
 */
@Composable
internal fun CustomerContactLine(
    text: String,
    glyph: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.small
    val lineModifier = if (onClick == null) {
        modifier.clip(shape)
    } else {
        modifier.clip(shape).clickable(onClick = onClick)
    }
    Row(
        modifier = lineModifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painter = painterResource(glyph),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(CustomerContactIconSize),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The derived "N properties · N jobs" the design shows under the contacts. */
@Composable
private fun CustomerCountsRow(propertyCount: Int, jobCount: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CustomerCount(
            glyph = R.drawable.ic_home,
            label = pluralStringResource(
                R.plurals.customers_property_count,
                propertyCount,
                propertyCount,
            ),
        )
        Text(
            text = stringResource(R.string.customers_counts_separator),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CustomerCount(
            glyph = R.drawable.ic_briefcase,
            label = pluralStringResource(
                R.plurals.customers_job_count,
                jobCount,
                jobCount,
            ),
        )
    }
}

@Composable
private fun CustomerCount(glyph: Int, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            painter = painterResource(glyph),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(CustomerCountIconSize),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Opens the dialer with [phone]. `ACTION_DIAL` needs no permission and never places the call. */
internal fun dialIntent(phone: String): Intent =
    Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null))

/** Opens the user's email client addressed to [email]. */
internal fun mailIntent(email: String): Intent =
    Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", email, null))

/**
 * Starts an outbound contact intent.
 *
 * A device with no dialer or email client keeps the value visible instead of crashing the screen.
 * The failure is logged rather than rethrown because nothing in the app can recover from a missing
 * platform app; the contact value itself is never logged.
 */
internal fun Context.startContactIntent(intent: Intent) {
    try {
        startActivity(intent)
    } catch (missing: ActivityNotFoundException) {
        Log.d(CustomerContactLogTag, "No activity handled an outbound contact intent.", missing)
    }
}

@Composable
internal fun CustomerAvatar(customer: CustomerListItem) {
    CustomerAvatar(displayName = customer.displayName, isCompany = customer.isCompany)
}

/** The customer's initials avatar, for a screen that holds the customer rather than a list row. */
@Composable
internal fun CustomerAvatar(
    displayName: String,
    isCompany: Boolean,
    modifier: Modifier = Modifier,
) {
    val isBusiness = isCompany
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
                if (isBusiness) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondary
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials(displayName),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (isBusiness) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSecondary
            },
        )
    }
}

/**
 * Formats the customer's created timestamp as the row's "since" date in the device locale.
 *
 * Returns `null` when the value is not an instant this build can read, so the row shows the status
 * alone rather than a fabricated date (`BR-042`).
 */
internal fun customerSince(createdAt: String): String? =
    try {
        Instant.parse(createdAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                    .withLocale(Locale.getDefault()),
            )
    } catch (unreadable: DateTimeParseException) {
        Log.d(CustomerSinceLogTag, "Customer created timestamp could not be read.", unreadable)
        null
    }

@Composable
internal fun StatusPill(status: CustomerStatus, modifier: Modifier = Modifier) {
    val active = status == CustomerStatus.ACTIVE
    val colors = MaterialTheme.stateColors
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = if (active) colors.successContainer.copy(alpha = 0.28f) else MaterialTheme.colorScheme.secondary,
        border = BorderStroke(
            1.dp,
            if (active) colors.success.copy(alpha = 0.25f) else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            text = stringResource(
                if (active) R.string.customers_status_active else R.string.customers_status_inactive,
            ),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (active) colors.success else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlaceholderTab(title: String, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The Settings tab.
 *
 * Only sign-out is implemented here. The other settings surfaces the design defines are separate
 * features and are not invented by this slice (`BR-042`); they are added when their own rules are
 * decided. Sign-out is the one item this slice needs, because persisting a session across restarts
 * makes ending one from the device necessary (`BR-014`).
 */
@Composable
private fun SettingsTab(onSignOut: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OutlinedButton(
            onClick = onSignOut,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag(SettingsSignOutTag),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                text = stringResource(R.string.settings_sign_out),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun EmptyCustomers(onCreate: () -> Unit, canCreateCustomer: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_business),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.customers_empty_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.customers_empty_message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (canCreateCustomer) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onCreate, shape = MaterialTheme.shapes.medium) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.customers_new))
            }
        }
    }
}

/** Shown when the read succeeded but nothing matches the current search, as designed. */
@Composable
private fun NoMatchingCustomers(onClear: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.customers_no_match_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onClear) {
            Text(
                text = stringResource(R.string.customers_no_match_action),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
internal fun CustomersLoading() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.customers_loading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun CustomersError(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.customers_error_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.customers_error_message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry, shape = MaterialTheme.shapes.medium) {
            Text(stringResource(R.string.customers_retry))
        }
    }
}

@Composable
private fun NoCustomerAccess(modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.customers_no_access_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.customers_no_access_message),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
