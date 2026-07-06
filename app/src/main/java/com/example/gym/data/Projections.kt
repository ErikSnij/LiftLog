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

/** One logged set on a given date, with enough context to build a TrainHub sync payload. */
data class SyncLogEntryRow(
    val id: Long,
    val setRowId: Long,
    val exerciseId: Long,
    val exerciseName: String,
    val reps: Float?,
    val weight: Float?,
    val date: LocalDate,
    val note: String?,
    val flag: Flag,
)
