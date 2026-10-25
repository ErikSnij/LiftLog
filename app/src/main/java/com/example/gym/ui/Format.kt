package com.example.gym.ui

import java.time.LocalDate
import java.time.format.TextStyle
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

/** The year to show beside a date when it predates the current year (signals "old"), else null. */
internal fun olderYear(date: LocalDate?): Int? =
    if (date != null && date.year < LocalDate.now().year) date.year else null

/** Epley estimated 1RM = weight × (1 + reps / 30). Needs both values present. */
internal fun epley(reps: Float?, weight: Float?): Float? =
    if (reps != null && weight != null) weight * (1f + reps / 30f) else null

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
