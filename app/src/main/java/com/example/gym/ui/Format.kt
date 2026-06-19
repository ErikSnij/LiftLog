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

/** Epley estimated 1RM = weight × (1 + reps / 30). Needs both values present. */
internal fun epley(reps: Float?, weight: Float?): Float? =
    if (reps != null && weight != null) weight * (1f + reps / 30f) else null

/** Round to 1 decimal place and trim a trailing ".0" (avoids float-artifact labels). */
internal fun round1(value: Float): String = trimFloat(Math.round(value * 10f) / 10f)
