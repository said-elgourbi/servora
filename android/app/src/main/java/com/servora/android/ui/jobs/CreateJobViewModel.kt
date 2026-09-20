package com.servora.android.ui.jobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.servora.android.data.customers.CustomerDetailResult
import com.servora.android.data.customers.CustomersRepository
import com.servora.android.data.customers.CustomersResult
import com.servora.android.data.jobs.CreateJobRequest
import com.servora.android.data.jobs.JobCreateResult
import com.servora.android.data.jobs.JobDetailsRepository
import com.servora.android.domain.model.CustomerFilters
import com.servora.android.domain.model.CustomerStatusFilter
import com.servora.android.ui.customers.CustomerListItem
import com.servora.android.ui.customers.toListItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the Create Job form (`BR-094`).
 *
 * The ViewModel decides nothing about Jobs: it holds the values the user chose, refuses to submit an
 * incomplete form before the round trip, and reports what the repositories answered. The backend remains
 * the authority for authorization, validation, the Job number, the status and the address snapshot
 * (`BR-001`, `BR-007`).
 *
 * Two reads back the form. The **Customer** selector narrows the organization's active customers, read
 * through [CustomersRepository] — the API has no text-search parameter, so the narrowing is local and
 * needs no request. The **Property** selector reads the chosen Customer's detail and takes its `ACTIVE`
 * Properties, which is the existing read of `GET /customers/:id/properties` (`BR-081`, `BR-083`).
 *
 * Changing the Customer **clears the chosen Property** and its list: a Property belongs to the Customer
 * it was chosen for, and leaving one selected under another Customer would submit a Job at a location the
 * API would refuse (`BR-050`, `BR-094`).
 *
 * The submit is **online-only**: the API accepts no idempotency key for a create and no conflict policy is
 * decided for it, so it is never queued (`offline-first-architecture.md` §13, `BR-014`). A refusal keeps
 * every value the user entered, so the form is corrected rather than retyped.
 */
@HiltViewModel
class CreateJobViewModel @Inject constructor(
    private val customersRepository: CustomersRepository,
    private val jobDetailsRepository: JobDetailsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateJobUiState())
    val uiState: StateFlow<CreateJobUiState> = _uiState.asStateFlow()

    /**
     * The form session [uiState] currently belongs to, or `null` before one has started.
     *
     * It is bookkeeping for telling one session from another, not something the screen draws, so it
     * deliberately does not live in [CreateJobUiState].
     */
    private var sessionId: String? = null

    /** Whether a submit is in flight, so a second tap cannot send the same Job twice (`BR-014`). */
    private var submitInFlight = false

    /**
     * Begins the form session [sessionId], optionally fixed to [customerId].
     *
     * A session is one destination instance. Re-entering the same [sessionId] — the screen composed again
     * after a configuration change — keeps what the user entered and any message the screen is showing. A
     * different [sessionId] is a new form session, so the form starts empty and no validation or
     * submission error from the previous session can appear (`BR-042`).
     *
     * A [customerId] makes the Customer fixed context: it is shown as a selected row rather than searched
     * for, and its Properties are read immediately. Without one, the user searches for the Customer —
     * which is what keeps this screen reusable from a Jobs entry point added later.
     */
    fun start(customerId: String?, customerName: String?, sessionId: String) {
        if (sessionId == this.sessionId) {
            return
        }
        this.sessionId = sessionId
        _uiState.value = CreateJobUiState(
            fixedCustomerId = customerId,
            fixedCustomerName = customerName,
            selectedCustomerId = customerId,
            selectedCustomerName = customerName,
        )
        if (customerId == null) {
            loadCustomers()
        } else {
            loadProperties(customerId)
        }
    }

    fun onCustomerQueryChange(query: String) = edit { it.copy(customerQuery = query) }

    /**
     * Records that the Customer picker was opened, so the list is read on first use.
     *
     * A caller who was handed a Customer never searches at all, so the organization's customer list is
     * not read for nothing.
     */
    fun onCustomerPickerOpen() {
        if (_uiState.value.customers.isEmpty() && !_uiState.value.customersLoading) {
            loadCustomers()
        }
    }

    /** Re-runs the Customer read after a failure the picker reported. */
    fun retryCustomers() = loadCustomers()

    /**
     * Chooses [customer] and reads the Properties it currently holds.
     *
     * The Property section is reset rather than carried over: the previous Customer's Property is not a
     * location this Job may be created at (`BR-050`, `BR-094`).
     */
    fun selectCustomer(customer: CustomerListItem) {
        _uiState.update { state ->
            state.copy(
                selectedCustomerId = customer.id,
                selectedCustomerName = customer.displayName,
                customerQuery = "",
                properties = emptyList(),
                selectedPropertyId = null,
                propertiesLoading = false,
                propertiesFailure = null,
                failureReason = null,
            )
        }
        loadProperties(customer.id)
    }

    /** Re-runs the Property read after a failure the selector reported. */
    fun retryProperties() {
        _uiState.value.selectedCustomerId?.let(::loadProperties)
    }

    fun selectProperty(propertyId: String) = edit { it.copy(selectedPropertyId = propertyId) }

    fun onTitleChange(value: String) = edit { it.copy(title = value) }

    fun onDescriptionChange(value: String) = edit { it.copy(description = value) }

    /**
     * Submits the form.
     *
     * An incomplete form is not sent: the attempt is recorded so the screen can mark what is missing, and
     * no request is made (`BR-042`). A form already being submitted is not sent twice, so a second tap
     * cannot create a second Job (`BR-014`).
     */
    fun submit() {
        val state = _uiState.value
        if (submitInFlight || state.isSubmitting) {
            return
        }
        val customerId = state.selectedCustomerId
        val propertyId = state.selectedPropertyId
        if (!state.canSubmit || customerId == null || propertyId == null) {
            // A new attempt must not leave the previous attempt's answer on screen.
            _uiState.update { it.copy(submitAttempted = true, failureReason = null) }
            return
        }

        _uiState.update {
            it.copy(isSubmitting = true, submitAttempted = true, failureReason = null)
        }
        submitInFlight = true
        val startedSession = sessionId
        viewModelScope.launch {
            val result = jobDetailsRepository.createJob(
                CreateJobRequest(
                    customerId = customerId,
                    propertyId = propertyId,
                    title = state.title.trim(),
                    description = state.description.trim().takeIf { it.isNotEmpty() },
                ),
            )
            submitInFlight = false
            // A reply that arrives after the user has left this form session must not be folded into the
            // next one (`BR-042`).
            if (startedSession != sessionId) return@launch
            _uiState.update { current ->
                when (result) {
                    is JobCreateResult.Success ->
                        // The Job is the backend's row; the destination navigates to it and the Job
                        // Details screen reads it from the API rather than being handed a local copy
                        // (`BR-001`).
                        current.copy(
                            isSubmitting = false,
                            createdJobId = result.jobId,
                            failureReason = null,
                        )

                    is JobCreateResult.Failure ->
                        // Every entered value stays: the user corrects what the API refused rather than
                        // retyping the form (`BR-042`).
                        current.copy(isSubmitting = false, failureReason = result.reason)
                }
            }
        }
    }

    /** Dismisses the reported failure without discarding what the user entered. */
    fun dismissFailure() = _uiState.update { it.copy(failureReason = null) }

    /** Releases the form's values when the app session ends (`BR-001`). */
    fun reset() {
        sessionId = null
        submitInFlight = false
        _uiState.value = CreateJobUiState()
    }

    /**
     * Reads the organization's active customers, which the Customer picker narrows locally.
     *
     * The filter is applied by the backend, so only a Customer that can take new work reaches the picker
     * (`BR-001`, `BR-023`).
     */
    private fun loadCustomers() {
        _uiState.update { it.copy(customersLoading = true, customersFailure = null) }
        viewModelScope.launch {
            val result = customersRepository.listCustomers(
                CustomerFilters(status = CustomerStatusFilter.ACTIVE),
            )
            _uiState.update { state ->
                when (result) {
                    is CustomersResult.Success ->
                        state.copy(
                            customersLoading = false,
                            customers = result.customers.map { it.toListItem() },
                            customersFailure = null,
                        )

                    is CustomersResult.Failure ->
                        state.copy(
                            customersLoading = false,
                            customers = emptyList(),
                            customersFailure = result.reason,
                        )
                }
            }
        }
    }

    /**
     * Reads the Properties the Customer currently holds.
     *
     * The customer detail's Property projection **is** the `ACTIVE` set (`BR-081`, `BR-083`), which is
     * exactly what a Job may be created at, and the read is the one the customer screen already performs —
     * including its offline fallback (`offline-first-architecture.md` §2).
     */
    private fun loadProperties(customerId: String) {
        _uiState.update {
            it.copy(
                propertiesLoading = true,
                propertiesFailure = null,
                properties = emptyList(),
                selectedPropertyId = null,
            )
        }
        viewModelScope.launch {
            val result = customersRepository.loadCustomerDetail(customerId)
            _uiState.update { state ->
                // The answer describes the Customer it was read for, so it is never folded into another
                // Customer's form (`BR-001`).
                if (state.selectedCustomerId != customerId) {
                    return@update state
                }
                when (result) {
                    is CustomerDetailResult.Success ->
                        state.copy(
                            propertiesLoading = false,
                            properties = result.detail.properties,
                            propertiesFailure = null,
                        )

                    is CustomerDetailResult.Failure ->
                        state.copy(
                            propertiesLoading = false,
                            properties = emptyList(),
                            propertiesFailure = result.reason,
                        )
                }
            }
        }
    }

    private fun edit(transform: (CreateJobUiState) -> CreateJobUiState) {
        _uiState.update { state -> transform(state).copy(failureReason = null) }
    }
}
