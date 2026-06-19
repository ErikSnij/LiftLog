package com.example.gym.data

import java.time.LocalDate

/** Newest log entry for a given set row — the row's current displayed value. */
data class LatestEntry(
    val setRowId: Long,
    val reps: Float?,
    val weight: Float?,
    val date: LocalDate,
)

/** Derived (never stored) last-performed date for a parent, keyed by that parent's id. */
data class LastPerformed(
    val parentId: Long,
    val lastPerformed: LocalDate?,
)
