package com.example.gym.ui

import java.time.LocalDate

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

/** Full ISO date, but drops the year (→ MM-dd) when it's the current year. */
internal fun formatDate(date: LocalDate?): String {
    if (date == null) return "—"
    return if (date.year == LocalDate.now().year)
        "%02d-%02d".format(date.monthValue, date.dayOfMonth)
    else date.toString()
}

/** Epley estimated 1RM = weight × (1 + reps / 30). Needs both values present. */
internal fun epley(reps: Float?, weight: Float?): Float? =
    if (reps != null && weight != null) weight * (1f + reps / 30f) else null

/** Round to 1 decimal place and trim a trailing ".0" (avoids float-artifact labels). */
internal fun round1(value: Float): String = trimFloat(Math.round(value * 10f) / 10f)
