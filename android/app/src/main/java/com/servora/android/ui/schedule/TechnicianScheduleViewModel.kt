package com.servora.android.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.offline.ReadSource
import com.servora.android.data.schedule.AdHocReportCustomerOptionsResult
import com.servora.android.data.schedule.AdHocReportJobOptionsResult
import com.servora.android.data.schedule.AdHocReportPropertyOptionsResult
import com.servora.android.data.schedule.AdHocWorkReportDraft
import com.servora.android.data.schedule.AdHocWorkReportResult
import com.servora.android.data.schedule.AdHocWorkReportsRepository
import com.servora.android.data.schedule.ScheduleRepository
import com.servora.android.data.schedule.ScheduleResult
import com.servora.android.data.schedule.VisitRequestReviewResult
import com.servora.android.data.schedule.VisitRequestsRepository
import com.servora.android.data.schedule.VisitRequestsResult
import com.servora.android.domain.model.AdHocReportCustomerOption
import com.servora.android.domain.model.AdHocReportJobOption
import com.servora.android.domain.model.AdHocReportPropertyOption
import com.servora.android.domain.model.FollowUpVisitRequest
import com.servora.android.domain.model.VisitOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the technician's schedule.
 *
 * The ViewModel decides nothing about the work: it asks [ScheduleRepository] for the day the technician
 * is looking at and reports what came back. Which Visits a day holds, their statuses, their crew and
 * whether one is overdue are the backend's answers (`BR-001`, `BR-042`), and **whose** Visits the day
 * may hold is the backend's too: the read is authorized as the caller's own assigned work and never
 * narrows itself to a technician the client picked (`BR-009`, `BR-007`).
 *
 * The day and the week are the screen's own state, because they are the technician's browsing rather
 * than facts about the operation. The device's zone and its first day of the week come from the screen,
 * so the day the API resolves and the week the strip draws are the ones the technician is working in
 * (`BR-041`).
 *
 * A day the backend cannot be reached for is answered from the working set when it was read before, and
 * the state says so rather than presenting a local copy as current (`BR-013`, offline standard §7, §13).
 *
 * The caller's **own requests** are read through [VisitRequestsRepository], which is the same route the
 * office reviews requests on and which answers a requester with their own rows only (`BR-FV-001`,
 * `BR-009`): which requests are the caller's is the API's answer, never a filter applied here
 * (`BR-007`). That read is **online-only** and holds no local copy — a request's answer is only useful
 * while it is current, and presenting a stored "rejected" as the office's answer is exactly what must
 * not happen (`BR-001`, `BR-014`) — so a read the backend could not answer reports its failure and
 * leaves the list it already had in place (`docs/tracker/057-qa-issue-list-visit-workflow.md` §5.8).
 */
@HiltViewModel
class TechnicianScheduleViewModel @Inject constructor(
    private val repository: ScheduleRepository,
    private val visitRequestsRepository: VisitRequestsRepository,
    private val adHocWorkReportsRepository: AdHocWorkReportsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TechnicianScheduleUiState())
    val uiState: StateFlow<TechnicianScheduleUiState> = _uiState.asStateFlow()

    /**
     * Identifies the read a result belongs to.
     *
     * Every read takes the next generation, so an answer that arrives after the technician has already
     * moved to another day is dropped rather than written over the day now on screen (`BR-067`).
     */
    private var generation = 0

    /**
     * Identifies the request read a result belongs to.
     *
     * A request read takes the next one too, so an answer that arrives after the session has ended is
     * dropped rather than written into the next session's state (`BR-001`, `BR-067`).
     */
    private var requestsGeneration = 0

    /** The type-ahead Customer search in flight, cancelled when a newer query arrives. */
    private var customerSearchJob: Job? = null

    /**
     * Opens the screen: selects [today] the first time and reads the view that is open.
     *
     * Coming back to the destination keeps the day the technician had selected and refreshes it, so
     * leaving the screen to open a Job and coming back does not lose their place. Whichever view was
     * open is refreshed, so returning to the screen shows current answers on the view in front of the
     * technician.
     */
    fun start(today: LocalDate, timeZoneId: String, firstDayOfWeek: DayOfWeek) {
        _uiState.update { it.copy(timeZoneId = timeZoneId, firstDayOfWeek = firstDayOfWeek) }
        if (_uiState.value.tab == TechnicianScheduleTab.REQUESTS) {
            readRequests()
            return
        }
        val hadADate = _uiState.value.selectedDate != null
        if (hadADate) {
            read()
        } else {
            selectDate(today, firstDayOfWeek)
        }
    }

    /**
     * Re-runs the read the view on screen needs, after a failure the screen reported.
     *
     * The day and the caller's own requests are two reads of two different things, so the retry
     * belongs to the view the technician is looking at rather than to the screen as a whole
     * (`BR-012`).
     */
    fun retry() {
        if (_uiState.value.tab == TechnicianScheduleTab.REQUESTS) {
            readRequests()
        } else {
            read()
        }
    }

    /**
     * Opens one of the screen's two views.
     *
     * The requests view is read when it is entered. Returning to the schedule does **not** read the day
     * again: the day on screen is the answer the read already gave for the selected date, and the
     * destination's own re-entry is where it is refreshed — the same courtesy the office screen's lanes
     * extend to the day it holds (`BR-012`, `BR-041`).
     */
    fun selectTab(tab: TechnicianScheduleTab) {
        if (_uiState.value.tab == tab) {
            return
        }
        _uiState.update { it.copy(tab = tab) }
        if (tab == TechnicianScheduleTab.REQUESTS) {
            readRequests()
        }
    }

    /**
     * Makes sure the caller's own requests have been read, for a surface that shows one of them.
     *
     * The request details destination shows one request out of the list, so it needs the list even when
     * it was opened without the list having answered — which is what a back stack restored after the
     * process was killed composes. The view is therefore set to the requests view as well, so returning
     * from the details lands on the list the request was read from rather than on a view the caller did
     * not choose (`BR-012`).
     *
     * A list that has already answered is **not** read again: it is the answer the screen is showing,
     * and re-reading it as the technician opens one of its rows would replace it for no reason
     * (`BR-013`). A read that failed has answered nothing, so it is retried here the way the list's own
     * retry does.
     */
    fun openOwnRequests() {
        if (_uiState.value.tab != TechnicianScheduleTab.REQUESTS) {
            _uiState.update { it.copy(tab = TechnicianScheduleTab.REQUESTS) }
        }
        if (!_uiState.value.requestsRead) {
            readRequests()
        }
    }

    /**
     * Selects [date] and reads its schedule.
     *
     * Content that belonged to the day left behind is released with it: what is on screen is always the
     * day it was read for, so a day that fails to load reports the failure rather than showing another
     * day's Visits as if they were this one's (`BR-042`). The week strip moves to the week the date is
     * in, so tapping a day never leaves the strip showing a week the agenda is not about (`BR-041`).
     */
    fun selectDate(date: LocalDate, firstDayOfWeek: DayOfWeek) {
        if (
            _uiState.value.selectedDate == date &&
            _uiState.value.displayedWeekStart == weekStartOf(date, firstDayOfWeek)
        ) {
            return
        }
        generation += 1
        _uiState.update {
            it.copy(
                selectedDate = date,
                displayedWeekStart = weekStartOf(date, firstDayOfWeek),
                schedule = null,
                source = ReadSource.BACKEND,
                failureReason = null,
            )
        }
        read()
    }

    /**
     * Shows [weekStart] in the strip without changing the selected day.
     *
     * Swiping moves the strip through nearby weeks; the agenda keeps describing the selected day until
     * a day in another week is tapped, so a swipe never reads a day the technician did not choose.
     */
    fun showWeek(weekStart: LocalDate) {
        if (_uiState.value.displayedWeekStart == weekStart) {
            return
        }
        _uiState.update { it.copy(displayedWeekStart = weekStart) }
    }

    /**
     * Returns to [today], which is the day the screen opens on.
     *
     * It is the same selection a tap on the strip makes, so the header's way back and the strip's own
     * day cannot disagree about what "today" is (`BR-041`).
     */
    fun showToday(today: LocalDate, firstDayOfWeek: DayOfWeek) {
        selectDate(today, firstDayOfWeek)
    }

    /**
     * Forgets the loaded state and allows it to be read again.
     *
     * Called when the session ends, so the technician who signs in next does not see the previous
     * technician's work (`BR-001`). A read already in flight is abandoned with it.
     */
    fun reset() {
        generation += 1
        requestsGeneration += 1
        _uiState.value = TechnicianScheduleUiState()
    }


    fun openAdHocReport() {
        val now = Instant.now()
        customerSearchJob?.cancel()
        _uiState.update {
            it.copy(
                adHocReportOpen = true,
                adHocReportForm = AdHocReportFormState(
                    workStartedAt = now.minusSeconds(3600),
                    workEndedAt = now,
                ),
                isSubmittingAdHocReport = false,
                adHocReportSubmitted = false,
                adHocReportQueued = false,
                adHocReportFailureReason = null,
            )
        }
    }

    fun dismissAdHocReport() {
        if (_uiState.value.isSubmittingAdHocReport) {
            return
        }
        customerSearchJob?.cancel()
        _uiState.update { it.copy(adHocReportOpen = false) }
    }

    fun updateAdHocReportCustomerQuery(query: String) {
        _uiState.update {
            val form = it.adHocReportForm
            val keepsSelection = form.selectedCustomer != null &&
                form.selectedCustomer.displayName == query
            it.copy(
                adHocReportForm = form.copy(
                    customerQuery = query,
                    selectedCustomer = if (keepsSelection) form.selectedCustomer else null,
                    selectedProperty = if (keepsSelection) form.selectedProperty else null,
                    selectedJob = if (keepsSelection) form.selectedJob else null,
                    propertyOptions = if (keepsSelection) form.propertyOptions else emptyList(),
                    jobOptions = if (keepsSelection) form.jobOptions else emptyList(),
                ),
            )
        }
        searchAdHocReportCustomers(query)
    }

    fun selectAdHocReportCustomer(customer: AdHocReportCustomerOption) {
        customerSearchJob?.cancel()
        _uiState.update {
            it.copy(
                adHocReportForm = it.adHocReportForm.copy(
                    selectedCustomer = customer,
                    selectedProperty = null,
                    selectedJob = null,
                    propertyOptions = emptyList(),
                    jobOptions = emptyList(),
                    customerOptions = emptyList(),
                    customerQuery = customer.displayName,
                    unknownCustomer = false,
                    reportedCustomerName = "",
                    reportedCustomerPhone = "",
                    reportedCustomerAddress = "",
                ),
            )
        }
        readAdHocReportProperties(customer.id)
        readAdHocReportJobs(customer.id)
    }

    fun selectAdHocReportProperty(property: AdHocReportPropertyOption) {
        _uiState.update {
            it.copy(adHocReportForm = it.adHocReportForm.copy(selectedProperty = property))
        }
    }

    fun selectAdHocReportJob(job: AdHocReportJobOption) {
        _uiState.update {
            it.copy(adHocReportForm = it.adHocReportForm.copy(selectedJob = job))
        }
    }

    fun updateAdHocReportWorkStartedAt(instant: Instant) {
        _uiState.update {
            it.copy(adHocReportForm = it.adHocReportForm.copy(workStartedAt = instant))
        }
    }

    fun updateAdHocReportWorkEndedAt(instant: Instant) {
        _uiState.update {
            it.copy(adHocReportForm = it.adHocReportForm.copy(workEndedAt = instant))
        }
    }

    fun updateAdHocReportOutcome(outcome: VisitOutcome) {
        _uiState.update {
            it.copy(adHocReportForm = it.adHocReportForm.copy(outcome = outcome))
        }
    }

    fun updateAdHocReportSummary(summary: String) {
        _uiState.update {
            it.copy(adHocReportForm = it.adHocReportForm.copy(summary = summary))
        }
    }

    fun updateAdHocReportNotes(notes: String) {
        _uiState.update {
            it.copy(adHocReportForm = it.adHocReportForm.copy(notes = notes))
        }
    }

    fun setAdHocReportUnknownCustomer(unknown: Boolean) {
        if (!unknown) {
            _uiState.update {
                it.copy(adHocReportForm = it.adHocReportForm.copy(unknownCustomer = false))
            }
            return
        }
        customerSearchJob?.cancel()
        _uiState.update {
            it.copy(
                adHocReportForm = it.adHocReportForm.copy(
                    unknownCustomer = true,
                    selectedCustomer = null,
                    selectedProperty = null,
                    selectedJob = null,
                    propertyOptions = emptyList(),
                    jobOptions = emptyList(),
                    customerOptions = emptyList(),
                    customerQuery = "",
                ),
            )
        }
    }

    fun updateReportedCustomerName(value: String) {
        _uiState.update {
            it.copy(adHocReportForm = it.adHocReportForm.copy(reportedCustomerName = value))
        }
    }

    fun updateReportedCustomerPhone(value: String) {
        _uiState.update {
            it.copy(adHocReportForm = it.adHocReportForm.copy(reportedCustomerPhone = value))
        }
    }

    fun updateReportedCustomerAddress(value: String) {
        _uiState.update {
            it.copy(adHocReportForm = it.adHocReportForm.copy(reportedCustomerAddress = value))
        }
    }

    fun submitAdHocWorkReport() {
        val form = _uiState.value.adHocReportForm
        if (_uiState.value.isSubmittingAdHocReport || form.problem != null) {
            return
        }
        _uiState.update {
            it.copy(
                isSubmittingAdHocReport = true,
                adHocReportSubmitted = false,
                adHocReportQueued = false,
                adHocReportFailureReason = null,
            )
        }
        viewModelScope.launch {
            val draft = AdHocWorkReportDraft(
                clientOperationId = UUID.randomUUID().toString(),
                customerId = form.selectedCustomer?.id,
                propertyId = form.selectedProperty?.id,
                knownJobId = form.selectedJob?.id,
                workStartedAt = form.workStartedAt,
                workEndedAt = form.workEndedAt,
                outcomeCode = form.outcome.name,
                summary = form.summary.trim(),
                notes = form.notes.trim().takeIf { it.isNotEmpty() },
                reportedCustomerName = form.reportedCustomerName.trim().takeIf { it.isNotEmpty() },
                reportedCustomerPhone = form.reportedCustomerPhone.trim().takeIf { it.isNotEmpty() },
                reportedCustomerAddress = form.reportedCustomerAddress.trim().takeIf { it.isNotEmpty() },
            )
            val result = adHocWorkReportsRepository.submit(draft)
            _uiState.update { current ->
                when (result) {
                    AdHocWorkReportResult.Success -> current.copy(
                        isSubmittingAdHocReport = false,
                        adHocReportOpen = false,
                        adHocReportSubmitted = true,
                        adHocReportFailureReason = null,
                    )

                    AdHocWorkReportResult.Queued -> current.copy(
                        isSubmittingAdHocReport = false,
                        adHocReportOpen = false,
                        adHocReportQueued = true,
                        adHocReportFailureReason = null,
                    )

                    is AdHocWorkReportResult.Failure -> current.copy(
                        isSubmittingAdHocReport = false,
                        adHocReportSubmitted = false,
                        adHocReportFailureReason = result.reason,
                    )
                }
            }
        }
    }

    fun acknowledgeAdHocReportResult() {
        _uiState.update {
            it.copy(
                adHocReportSubmitted = false,
                adHocReportQueued = false,
                adHocReportFailureReason = null,
            )
        }
    }

    /** Debounced type-ahead Customer search; answers only past the minimum length (`BR-AH-009`). */
    private fun searchAdHocReportCustomers(query: String) {
        customerSearchJob?.cancel()
        if (query.trim().length < AD_HOC_CUSTOMER_SEARCH_MIN_LENGTH) {
            _uiState.update {
                it.copy(
                    adHocReportForm = it.adHocReportForm.copy(
                        customerOptions = emptyList(),
                        isSearchingCustomers = false,
                        customerSearchFailureReason = null,
                    ),
                )
            }
            return
        }
        _uiState.update {
            it.copy(adHocReportForm = it.adHocReportForm.copy(isSearchingCustomers = true))
        }
        customerSearchJob = viewModelScope.launch {
            delay(AD_HOC_CUSTOMER_SEARCH_DEBOUNCE_MILLIS)
            val result = adHocWorkReportsRepository.searchCustomers(query.trim())
            _uiState.update { current ->
                if (current.adHocReportForm.customerQuery != query) {
                    current
                } else {
                    val form = current.adHocReportForm
                    when (result) {
                        is AdHocReportCustomerOptionsResult.Success -> current.copy(
                            adHocReportForm = form.copy(
                                isSearchingCustomers = false,
                                customerOptions = result.customers,
                                customerSearchFailureReason = null,
                            ),
                        )

                        is AdHocReportCustomerOptionsResult.Failure -> current.copy(
                            adHocReportForm = form.copy(
                                isSearchingCustomers = false,
                                customerSearchFailureReason = result.reason,
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Reads the active Properties of the selected Customer (`BR-050`). */
    private fun readAdHocReportProperties(customerId: String) {
        _uiState.update {
            it.copy(adHocReportForm = it.adHocReportForm.copy(isReadingProperties = true))
        }
        viewModelScope.launch {
            val result = adHocWorkReportsRepository.listProperties(customerId)
            _uiState.update { current ->
                if (current.adHocReportForm.selectedCustomer?.id != customerId) {
                    current
                } else {
                    val form = current.adHocReportForm
                    when (result) {
                        is AdHocReportPropertyOptionsResult.Success -> current.copy(
                            adHocReportForm = form.copy(
                                isReadingProperties = false,
                                propertyOptions = result.properties,
                                propertyFailureReason = null,
                            ),
                        )

                        is AdHocReportPropertyOptionsResult.Failure -> current.copy(
                            adHocReportForm = form.copy(
                                isReadingProperties = false,
                                propertyFailureReason = result.reason,
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Reads the Jobs of the selected Customer, the optional related-work hint. */
    private fun readAdHocReportJobs(customerId: String) {
        _uiState.update {
            it.copy(adHocReportForm = it.adHocReportForm.copy(isReadingJobs = true))
        }
        viewModelScope.launch {
            val result = adHocWorkReportsRepository.listJobs(customerId)
            _uiState.update { current ->
                if (current.adHocReportForm.selectedCustomer?.id != customerId) {
                    current
                } else {
                    val form = current.adHocReportForm
                    when (result) {
                        is AdHocReportJobOptionsResult.Success -> current.copy(
                            adHocReportForm = form.copy(
                                isReadingJobs = false,
                                jobOptions = result.jobs,
                                jobFailureReason = null,
                            ),
                        )

                        is AdHocReportJobOptionsResult.Failure -> current.copy(
                            adHocReportForm = form.copy(
                                isReadingJobs = false,
                                jobFailureReason = result.reason,
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * Answers a request the office returned for clarification (`BR-FV-012`).
     *
     * The answer is the technician's own move, and it is **one** move: the API appends it to the
     * request's conversation and returns the request to the office's review in a single operation, so
     * there is no state in which the answer exists and the request does not. What the screen shows
     * afterwards is the API's own answer — the status it applied and the conversation it holds, including
     * the answer's own recorded time — never the status this client expected, so an exchange the backend
     * recorded differently is presented as the backend's (`BR-001`).
     *
     * It is online-only, like the read that listed the request: a locally invented exchange would present
     * something the office cannot see, and the route carries no idempotency key, so nothing is queued
     * (`BR-013`, `BR-014`). A failure is reported and the request is left exactly as the backend still
     * holds it (`BR-FV-013`).
     */
    fun replyToRequest(request: FollowUpVisitRequest, body: String) {
        if (!request.awaitsAnswer || _uiState.value.isReplying) {
            return
        }
        _uiState.update {
            it.copy(
                replyingRequestId = request.id,
                answeredRequestId = null,
                replyFailureReason = null,
            )
        }
        viewModelScope.launch {
            // As with the reads, the request happens outside the state mutator: `update` may re-run its
            // lambda on contention, and a re-run must never send a second answer.
            val result = visitRequestsRepository.reply(request, body)
            _uiState.update { current ->
                when (result) {
                    is VisitRequestReviewResult.Success ->
                        current.copy(
                            replyingRequestId = null,
                            answeredRequestId = request.id,
                            replyFailureReason = null,
                            // The API's own request replaces the one the list held: its status and its
                            // conversation are the backend's answers, not a local expectation
                            // (`BR-001`, `BR-041`).
                            requests = current.requests.map { listed ->
                                if (listed.id == request.id) result.request else listed
                            },
                        )

                    is VisitRequestReviewResult.Failure ->
                        current.copy(
                            replyingRequestId = null,
                            replyFailureReason = result.reason,
                        )
                }
            }
        }
    }

    /** Releases the report of an answer the API accepted, once the screen has shown it (`BR-FV-013`). */
    fun acknowledgeReply() {
        _uiState.update { it.copy(answeredRequestId = null, replyFailureReason = null) }
    }

    private fun read() {
        val state = _uiState.value
        val date = state.selectedDate ?: return
        generation += 1
        val readGeneration = generation
        _uiState.update { it.copy(isRefreshing = true, failureReason = null) }
        viewModelScope.launch {
            // The read happens outside the state mutator: `update` may re-run its lambda on
            // contention, and a re-run must never issue a second request. The filter is deliberately
            // empty: this read is the caller's own work, and naming technicians belongs to the office
            // scope (`BR-009`, `BR-068`).
            val result = repository.loadSchedule(
                localDate = date.toString(),
                timeZone = state.timeZoneId,
                membershipIds = emptyList(),
            )
            if (readGeneration != generation) {
                return@launch
            }
            _uiState.update { current ->
                when (result) {
                    is ScheduleResult.Success ->
                        current.copy(
                            isRefreshing = false,
                            schedule = result.schedule,
                            source = result.source,
                            failureReason = null,
                        )

                    // A refresh of the day already on screen that failed leaves it there and reports
                    // why; after a day change there is nothing to keep, so the failure stands alone
                    // (`BR-013`).
                    is ScheduleResult.Failure ->
                        current.copy(
                            isRefreshing = false,
                            failureReason = result.reason,
                        )
                }
            }
        }
    }

    /**
     * Reads the caller's own follow-up requests.
     *
     * The read is the API's own scope answer: a requester receives their own requests and nobody
     * else's (`BR-FV-001`, `BR-009`, `BR-007`). It is **online-only** — there is no local copy of a
     * request's state, because the office's answer is only meaningful while it is current and a stored
     * one would present a decision the backend may have replaced (`BR-001`, `BR-014`) — so a failure is
     * reported rather than answered from the device, and a list already read stays on screen under that
     * notice (`BR-013`).
     */
    private fun readRequests() {
        requestsGeneration += 1
        val readGeneration = requestsGeneration
        // A failure the screen was reporting is released as the new read starts, so a retry shows the
        // loading state again rather than the refusal it is already answering (`BR-013`).
        _uiState.update { it.copy(requestsFailureReason = null) }
        viewModelScope.launch {
            // As with the day's read, the request happens outside the state mutator: `update` may
            // re-run its lambda on contention, and a re-run must never issue a second request.
            val result = visitRequestsRepository.loadRequests()
            if (readGeneration != requestsGeneration) {
                return@launch
            }
            _uiState.update { current ->
                when (result) {
                    is VisitRequestsResult.Success ->
                        current.copy(
                            requestsRead = true,
                            requests = result.requests,
                            requestsFailureReason = null,
                        )

                    is VisitRequestsResult.Failure ->
                        current.copy(requestsFailureReason = result.reason)
                }
            }
        }
    }
}

/** The shortest Customer query the type-ahead search answers (`BR-AH-009`). */
private const val AD_HOC_CUSTOMER_SEARCH_MIN_LENGTH = 2

/** How long the type-ahead search waits for the technician to finish typing. */
private const val AD_HOC_CUSTOMER_SEARCH_DEBOUNCE_MILLIS = 300L
