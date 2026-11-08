package com.example.gym.ui

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Drops a trailing ".0" so 120.0 → "120" but keeps 117.5 → "117.5". */
internal fun trimFloat(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else value.toString()

/** "reps × weight" with the seed's null conventions: null weight → BW, null reps → ?. */
internal fun formatValue(reps: Float?, weight: Float?): String {
    if (reps == null && weight == null) return "—"
    val r = reps?.let(::trimFloat) ?: "?"
    val w = weight?.let(::trimFloat) ?: "BW"
    return "$r × $w"
}

/** Three-letter month + day, dropping the year when it's the current year. */
internal fun formatDate(date: LocalDate?): String {
    if (date == null) return "—"
    val mon = date.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase()
    return if (date.year == LocalDate.now().year)
        "%s %02d".format(mon, date.dayOfMonth)
    else "%s %02d %d".format(mon, date.dayOfMonth, date.year)
}

/** Just the lowercase 3-letter month + day, e.g. "jun 05" (never includes the year). */
internal fun formatMonthDay(date: LocalDate): String {
    val mon = date.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase()
    return "%s %02d".format(mon, date.dayOfMonth)
}

/** A labelled position on the History chart's date axis. */
internal data class DateTick(val date: LocalDate, val label: String)

/** Candidate tick spacings, finest first, as (days, months) — exactly one of the two is non-zero. */
private val DATE_TICK_STEPS = listOf(7 to 0, 14 to 0, 0 to 1, 0 to 2, 0 to 3, 0 to 6, 0 to 12, 0 to 24)

/**
 * Evenly spaced, calendar-aligned ticks for a date axis running [first]..[last]: Mondays for short
 * ranges, otherwise the 1st of every 1/2/3/6/12/24 months (aligned to the calendar, so a 3-month
 * step is always Jan/Apr/Jul/Oct). Uses the finest spacing that fits in [maxTicks] labels, so
 * the gaps between sessions can be read off the axis. Empty when the range is too short for two
 * ticks (or too long for any spacing), in which case the caller labels the data points instead.
 */
internal fun dateTicks(first: LocalDate, last: LocalDate, maxTicks: Int = 6): List<DateTick> {
    if (!last.isAfter(first)) return emptyList()
    for ((days, months) in DATE_TICK_STEPS) {
        val dates = tickDates(first, last, days, months)
        if (dates.size > maxTicks) continue // too crowded — try a coarser spacing
        if (dates.size < 2) return emptyList()
        // Months drop the day number; the year shows on January and where the axis crosses a year.
        val crossesYear = first.year != last.year
        return dates.mapIndexed { i, d ->
            val label = if (months == 0) formatMonthDay(d)
            else monthLabel(d, showYear = d.month == Month.JANUARY || (i == 0 && crossesYear))
            DateTick(d, label)
        }
    }
    return emptyList()
}

private fun tickDates(first: LocalDate, last: LocalDate, days: Int, months: Int): List<LocalDate> {
    val out = mutableListOf<LocalDate>()
    if (days > 0) {
        var d = first.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        while (!d.isAfter(last)) { out += d; d = d.plusDays(days.toLong()) }
    } else {
        var d = first.withDayOfMonth(1)
        if (d.isBefore(first)) d = d.plusMonths(1)
        // Align to the calendar: month index 0 is January of year 0, so step 3 lands on Jan/Apr/...
        while ((d.year * 12 + d.monthValue - 1) % months != 0) d = d.plusMonths(1)
        while (!d.isAfter(last)) { out += d; d = d.plusMonths(months.toLong()) }
    }
    return out
}

private fun monthLabel(date: LocalDate, showYear: Boolean): String {
    val mon = date.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase()
    return if (showYear) "%s '%02d".format(mon, date.year % 100) else mon
}

/** The year to show beside a date when it predates the current year (signals "old"), else null. */
internal fun olderYear(date: LocalDate?): Int? =
    if (date != null && date.year < LocalDate.now().year) date.year else null

/** Epley estimated 1RM = weight × (1 + reps / 30). Needs both values present. */
internal fun epley(reps: Float?, weight: Float?): Float? =
    if (reps != null && weight != null) weight * (1f + reps / 30f) else null

/**
 * The load actually lifted: [weight] (pin/bar) plus any hand-added [extraKg]. An entry with only
 * an add-on counts as that load alone; null when neither is known.
 */
internal fun totalWeight(weight: Float?, extraKg: Float?): Float? =
    if (weight != null) weight + (extraKg ?: 0f) else extraKg

/** Round to 1 decimal place and trim a trailing ".0" (avoids float-artifact labels). */
internal fun round1(value: Float): String = trimFloat(Math.round(value * 10f) / 10f)

/**
 * Inverts the Epley formula to ask "how many reps would this e1RM be at [targetWeight]?".
 * Anchoring to the heaviest weight ever lifted turns performance at any weight into a single
 * comparable rep count, so progress reads as a trend even as the working weight changes.
 *
 * One continuous formula across the whole range: 0 means "this set's e1RM exactly matches your
 * all-time max", positive means it exceeded it, negative means it fell short. Used to special-case
 * the below-max side to a small positive fraction instead, to avoid negative numbers — but that
 * made the two sides disagree at the seam (ratio == 1f): a set at or barely above the max scored
 * near 0, while a set further below it could score higher just for landing on the other branch.
 * Staying on one formula keeps it monotonic with actual strength; the chart handles negative
 * values on its own axis instead.
 */
internal fun repsAtWeight(e1RM: Float, targetWeight: Float): Float? {
    if (targetWeight <= 0f) return null
    return 30f * (e1RM / targetWeight - 1f)
}
