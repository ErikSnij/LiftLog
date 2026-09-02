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

/** The subset of an exercise's fields that drive its weight wheel/manual-entry increments. */
data class WeightConfig(
    val mode: WeightMode = WeightMode.DEFAULT,
    val stepKg: Float? = null,
    val heavyThresholdKg: Float? = null,
    val heavyStepKg: Float? = null,
    val startLbs: Float? = null,
    val stepLbs: Float? = null,
    val roundMode: WeightRoundMode = WeightRoundMode.UP,
)

fun ExerciseEntity.weightConfig(): WeightConfig = WeightConfig(
    mode = weightMode,
    stepKg = weightStepKg,
    heavyThresholdKg = weightHeavyThresholdKg,
    heavyStepKg = weightHeavyStepKg,
    startLbs = weightStartLbs,
    stepLbs = weightStepLbs,
    roundMode = weightRoundMode,
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
