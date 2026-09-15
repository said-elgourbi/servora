package com.servora.android.ui.customers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.servora.android.data.customers.CustomerDetailResult
import com.servora.android.data.customers.CustomersRepository
import com.servora.android.data.customers.CustomersResult
import com.servora.android.data.offline.ReadSource
import com.servora.android.domain.model.CustomerFilters
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the customer list.
 *
 * The ViewModel decides nothing about customers: it asks [CustomersRepository] for them and
 * reports what came back. Whether the caller may see them at all is the backend's decision
 * (`BR-007`), surfaced here only as a failure the screen can explain.
 *
 * The filter is applied by the backend, not here: the ViewModel records the filter it asked for
 * and re-reads when it changes (`BR-001`).
 */
@HiltViewModel
class CustomersViewModel @Inject constructor(
    private val customersRepository: CustomersRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CustomersUiState())
    val uiState: StateFlow<CustomersUiState> = _uiState.asStateFlow()

    private var loadRequested = false

    /** Loads the list once, so returning to the tab does not re-read the backend. */
    fun load() {
        if (loadRequested) {
            return
        }
        loadRequested = true
        refresh()
    }

    /** Re-runs the read after a failure. */
    fun retry() {
        refresh()
    }

    /**
     * Re-reads the list from the backend even when it is already loaded.
     *
     * Called when a confirmed mutation can change a derived value the list shows — a customer's
     * Property count after a Property is created, archived or deleted, for example — so the list is
     * not left describing an earlier read (`BR-001`). The backend remains the authority for the
     * values the rows show.
     */
    fun reload() {
        loadRequested = true
        refresh()
    }

    /**
     * Forgets the loaded list and allows it to load again.
     *
     * Called when the session ends, so a user who signs in next does not see the previous user's
     * rows. The list is scoped to a session by the backend (`BR-001`, `BR-007`), so it must not
     * outlive that session in the UI either.
     */
    fun reset() {
        loadRequested = false
        _uiState.value = CustomersUiState()
    }

    /**
     * Applies [filters] and re-reads the list.
     *
     * Re-reading rather than filtering locally keeps the backend the authority for which customers
     * the list shows (`BR-001`). A read is skipped when the filter is unchanged, so re-applying the
     * same filter is not a second request.
     */
    fun applyFilters(filters: CustomerFilters) {
        if (filters == _uiState.value.filters) {
            return
        }
        _uiState.update { it.copy(filters = filters) }
        refresh()
    }

    /**
     * Reads [customerId]'s detail for the screen, unless it is already loaded or being read.
     *
     * The screen drives this from its selected customer, so a recomposition must not start a second
     * read of the same customer (`BR-001`).
     */
    fun openCustomerDetail(customerId: String) {
        val current = _uiState.value.customerDetail
        if (current?.customerId == customerId) {
            val settled = current.detail != null && current.failureReason == null
            if (current.isLoading || settled) {
                return
            }
        }
        loadCustomerDetail(customerId)
    }

    /** Re-runs the detail read after a failure. */
    fun retryCustomerDetail() {
        val customerId = _uiState.value.customerDetail?.customerId ?: return
        loadCustomerDetail(customerId)
    }

    /**
     * Re-reads [customerId]'s detail even when it is already loaded.
     *
     * Used after a write that changed what the detail shows. The backend is the authority for what
     * the customer now has, so the screen is refreshed from it rather than patched locally
     * (`BR-001`).
     */
    fun reloadCustomerDetail(customerId: String) {
        loadCustomerDetail(customerId)
    }

    /** Drops the loaded detail, so returning to the list releases the previous customer's data. */
    fun closeCustomerDetail() {
        _uiState.update { it.copy(customerDetail = null) }
    }

    private fun loadCustomerDetail(customerId: String) {
        _uiState.update {
            it.copy(
                customerDetail = CustomerDetailUiState(
                    customerId = customerId,
                    isLoading = true,
                ),
            )
        }
        viewModelScope.launch {
            // The read happens outside the state mutator: `update` may re-run its lambda on
            // contention, and a re-run must never issue a second request.
            val result = customersRepository.loadCustomerDetail(customerId)
            _uiState.update { state ->
                val current = state.customerDetail
                if (current?.customerId != customerId) {
                    // Another customer replaced this one while the read was in flight.
                    state
                } else {
                    when (result) {
                        is CustomerDetailResult.Success ->
                            state.copy(
                                customerDetail = current.copy(
                                    isLoading = false,
                                    detail = result.detail,
                                    // The values may be the last the backend reported rather than a
                                    // fresh answer, and the screen says so (`§2`, §7).
                                    showingLastReported =
                                        result.source == ReadSource.WORKING_SET,
                                    failureReason = null,
                                ),
                            )

                        is CustomerDetailResult.Failure ->
                            state.copy(
                                customerDetail = current.copy(
                                    isLoading = false,
                                    // A failed read must not leave the previous detail on
                                    // screen: it described different data (`BR-001`).
                                    detail = null,
                                    showingLastReported = false,
                                    failureReason = result.reason,
                                ),
                            )
                    }
                }
            }
        }
    }

    /**
     * Identifies the newest list read.
     *
     * A read only writes state while it is still the newest one requested, so an answer that arrives
     * after a newer read was asked for cannot replace it with rows read for the filter that read was
     * asked to apply (`BR-001`).
     */
    private var listReadGeneration = 0

    private fun refresh() {
        // The filter is captured when the read is requested, because an answer describes the filter
        // it was asked for rather than whatever the filter is when it arrives.
        val filters = _uiState.value.filters
        val readGeneration = ++listReadGeneration
        _uiState.update { it.copy(isLoading = true, failureReason = null) }
        viewModelScope.launch {
            // The read happens outside the state mutator: `update` may re-run its lambda on
            // contention, and a re-run must never issue a second request.
            val result = customersRepository.listCustomers(filters)
            if (readGeneration != listReadGeneration) {
                // A newer read was requested while this one was in flight, so this answer describes a
                // filter the list no longer shows. Applying it would overwrite the newer read's
                // state — its rows, its failure or its mark — with an older one's (`BR-001`).
                return@launch
            }
            _uiState.update { state ->
                when (result) {
                    is CustomersResult.Success ->
                        state.copy(
                            isLoading = false,
                            customers = result.customers.map { it.toListItem() },
                            // The rows may be the last the backend reported rather than a fresh
                            // answer, and the screen says so (`§2`, §7).
                            showingLastReported = result.source == ReadSource.WORKING_SET,
                            failureReason = null,
                        )

                    is CustomersResult.Failure ->
                        state.copy(
                            isLoading = false,
                            // A failed read must not leave the previous list on screen: it was
                            // read for a different filter, so showing it would present rows the
                            // user's current filter excludes (`BR-001`).
                            customers = emptyList(),
                            showingLastReported = false,
                            failureReason = result.reason,
                        )
                }
            }
        }
    }
}
