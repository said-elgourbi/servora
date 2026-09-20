package com.servora.android.ui.schedule

import com.servora.android.domain.model.ScheduleTechnician
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

/*
 * The schedule's own presentation decisions, kept out of the composables so they can be asserted
 * without a device: how much of a crew a card names, how the filter's search compares a name, how the
 * filter's options are listed, how the collapsed filter summarises itself, where the "now" cue
 * belongs in a day's chronology, and how that cue's clock reads.
 *
 * None of them decides anything about the work. Which Visits a day holds, who is assigned, whether a
 * Visit is overdue and what is unassigned are the API's own answers (`BR-001`, `BR-042`); these are
 * only ways of drawing the day the screen was handed (`BR-041`).
 */

/** How many crew names a card names before it counts the rest (`BR-068`, `BR-012`). */
internal const val ScheduleCrewNamesShown = 2

/**
 * A crew as one line: the names the card shows, and how many assigned members those names do not
 * account for.
 */
internal data class CrewLine(val names: List<String>, val hidden: Int)

/**
 * The crew's names for the card: Lead first, at most [ScheduleCrewNamesShown] of them, and how many
 * assigned members are left over.
 *
 * A card is scanned rather than read (`BR-012`), so a long crew is named up to a limit and the rest
 * is counted instead of wrapping the card onto another line. A member with no profile yet carries no
 * name (`BR-020`) and is therefore never one of the names, but still counts as assigned — which is
 * what [hidden] reports, so a crew of five is never presented as a crew of two.
 */
internal fun crewLine(names: List<String?>): CrewLine {
    val named = names.mapNotNull { it?.trim()?.takeIf { name -> name.isNotEmpty() } }
    val shown = named.take(ScheduleCrewNamesShown)
    return CrewLine(names = shown, hidden = names.size - shown.size)
}

/**
 * Whether a technician matches what the filter's search field holds.
 *
 * Names are compared case- and accent-insensitively, because Servora is bilingual and a manager
 * typing `cote` is looking for `Côté` (`BR-028`, `Project.md` §10). An empty query matches every
 * technician, and a member with no profile yet (`BR-020`) is matched by nothing else: the search
 * never invents a name for them.
 */
internal fun matchesTechnicianQuery(name: String?, query: String): Boolean {
    val wanted = searchText(query)
    if (wanted.isEmpty()) {
        return true
    }
    val candidate = name ?: return false
    return searchText(candidate).contains(wanted)
}

/**
 * The filter's options as its sheet lists them: the ones already selected first, then the rest, each
 * group in the order the backend returned them (`BR-024`), with every option narrowed by [query].
 *
 * The selection is the manager's draft, not the search's result, so a technician selected before a
 * query was typed stays selected while they are off screen — the search narrows what is *offered*
 * and never what is *chosen* (`BR-012`). Selected-first is what makes a long team usable: the answer
 * to "who did I pick?" is at the top of the list rather than wherever the name happens to sort.
 */
internal fun orderTechnicianOptions(
    technicians: List<ScheduleTechnician>,
    selectedIds: Set<String>,
    query: String,
): List<ScheduleTechnician> {
    val (chosen, rest) = technicians
        .filter { matchesTechnicianQuery(it.name, query) }
        .partition { it.membershipId in selectedIds }
    return chosen + rest
}

/**
 * How the collapsed technician control summarises the filter that is in effect (`BR-012`).
 *
 * A control one tap tall cannot name a long list, so it names one technician, counts several, or
 * says that the whole organization is shown; the sheet is where the full selection is read
 * (`BR-042`).
 */
internal sealed interface TechnicianFilterSummary {
    /** Nobody is selected: the day is the whole organization. */
    data object All : TechnicianFilterSummary

    /** One technician is selected, and the control can name them. */
    data class One(val technician: ScheduleTechnician) : TechnicianFilterSummary

    /** Several are selected, so the control counts them rather than listing names. */
    data class Many(val count: Int) : TechnicianFilterSummary
}

/** The summary for [selected], which is the applied filter (`BR-068`). */
internal fun technicianFilterSummary(selected: List<ScheduleTechnician>): TechnicianFilterSummary =
    when {
        selected.isEmpty() -> TechnicianFilterSummary.All
        selected.size == 1 -> TechnicianFilterSummary.One(selected.first())
        else -> TechnicianFilterSummary.Many(selected.size)
    }

/**
 * The technicians [ids] name, in the order the organization's own list holds them (`BR-024`).
 *
 * The sheet selects ids because that is what an assignment names (`BR-068`), and the chip needs the
 * people behind them to say who is being shown. Ordering by the API's list rather than by the order
 * they were tapped keeps the control steady: the technician it names does not change because another
 * one was tapped first, and an id the list no longer holds is simply not offered again (`BR-042`).
 */
internal fun chosenTechnicians(
    technicians: List<ScheduleTechnician>,
    ids: Collection<String>,
): List<ScheduleTechnician> = technicians.filter { it.membershipId in ids }

/** The instant a scheduled value names, or `null` when this build cannot read it (`BR-042`). */
internal fun instantOf(scheduled: String?): Instant? =
    try {
        scheduled?.let { Instant.parse(it) }
    } catch (unreadable: DateTimeParseException) {
        null
    }

/**
 * Where the "now" cue belongs in the day's chronological rows, or `null` when it does not belong in
 * them at all.
 *
 * The cue stands immediately before the first Visit that has not started yet, read from [starts] —
 * the day's Visits in the order the API returned them (`BR-072`) — and at the end of a day whose
 * Visits have all started. A list with no readable start at all has no chronology to place a cue in
 * and reports none; that is the unassigned lane, where nobody has agreed a time yet (`BR-071`).
 *
 * It is the device's own clock, and it decides nothing: a Visit's status and its overdue condition
 * stay the API's answers (`BR-001`, `BR-074`). The cue only tells the manager where in the day they
 * are reading.
 */
internal fun nowCueIndex(starts: List<Instant?>, now: Instant): Int? {
    if (starts.none { it != null }) {
        return null
    }
    val firstUpcoming = starts.indexOfFirst { start -> start != null && !start.isBefore(now) }
    return if (firstUpcoming < 0) starts.size else firstUpcoming
}

/** Text compared without case or accents, so a search is not defeated by either (`BR-028`). */
private fun searchText(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .lowercase(Locale.ROOT)
        .trim()

/**
 * The clock time the "now" cue states, in the device's own language and zone.
 *
 * It is the device's clock and nothing else: the cue tells the manager where in the day they are
 * reading, while a Visit's status and overdue condition stay the API's answers (`BR-001`).
 */
internal fun formatClockTime(instant: Instant, zone: ZoneId, locale: Locale): String =
    instant.atZone(zone)
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))

private val DIACRITICS = "\\p{Mn}+".toRegex()
