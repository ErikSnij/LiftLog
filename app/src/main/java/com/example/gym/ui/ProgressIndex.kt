package com.example.gym.ui

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.exp
import kotlin.math.ln

/** One logged set, with every level of the tree it sits under (for rolling progress up). */
data class ProgressEntry(
    val date: LocalDate,
    val reps: Float?,
    val weight: Float?,
    val extraKg: Float?,
    val exerciseId: Long,
    val areaId: Long,
    val muscleGroupId: Long,
    val categoryId: Long,
)

/**
 * A node's progress over the window: [points] is a weekly index (first point = 100) and
 * [changePct] is how far the last point is from the first, e.g. 8f for +8%.
 */
data class Trend(val points: List<Float>, val changePct: Float)

data class ProgressTrends(
    val overall: Trend? = null,
    val categories: Map<Long, Trend> = emptyMap(),
    val muscleGroups: Map<Long, Trend> = emptyMap(),
    val areas: Map<Long, Trend> = emptyMap(),
)

/**
 * Overall-progress index for every level of the tree, built like a stock index:
 *
 * 1. Each exercise's strength in a week is its best Epley e1RM that week (pin/bar or body
 *    weight, plus any add-on), across all its set rows.
 * 2. Week to week, an exercise contributes its own change (as a log-ratio), so exercises at
 *    very different loads (a 9kg raise, a 120kg squat) count equally.
 * 3. A parent's weekly change is the plain average of its children's (equal weight per
 *    sibling): exercises → area → muscle group → category → overall.
 * 4. The weekly changes are chained into an index. Only exercises active in both weeks of a step
 *    count towards it, so adding, dropping or swapping an exercise never makes the line jump.
 *
 * An exercise not logged in a week holds flat; after [staleWeeks] without a log it drops out of
 * the averages until it's logged again (re-entering without a step, like a brand-new one).
 *
 * Returned trends cover the last [windowWeeks] weeks up to [today]'s week; nodes with no active
 * exercise anywhere in that window get no trend.
 */
fun computeProgressTrends(
    entries: List<ProgressEntry>,
    bodyWeights: List<Pair<LocalDate, Float>>,
    today: LocalDate,
    windowWeeks: Int = 26,
    staleWeeks: Int = 8,
): ProgressTrends {
    val sortedBw = bodyWeights.sortedBy { it.first }
    fun load(e: ProgressEntry): Float? {
        // Same rule as the History chart: a BW set uses the latest body weight on or before it.
        val base = e.weight ?: sortedBw.lastOrNull { !it.first.isAfter(e.date) }?.second
        return totalWeight(base, e.extraKg)
    }

    // Best e1RM per exercise per week.
    val weekly = HashMap<Long, HashMap<LocalDate, Float>>()
    for (e in entries) {
        val w = load(e) ?: continue
        if (w <= 0f) continue
        val e1rm = epley(e.reps, w) ?: continue
        if (e1rm <= 0f) continue
        val byWeek = weekly.getOrPut(e.exerciseId) { HashMap() }
        val wk = weekStart(e.date)
        if (e1rm > (byWeek[wk] ?: 0f)) byWeek[wk] = e1rm
    }
    if (weekly.isEmpty()) return ProgressTrends()

    val first = weekly.values.minOf { it.keys.min() }
    val last = maxOf(weekStart(today), weekly.values.maxOf { it.keys.max() })
    val weekCount = (ChronoUnit.WEEKS.between(first, last) + 1).toInt()
    fun indexOf(d: LocalDate) = ChronoUnit.WEEKS.between(first, d).toInt()

    // Per exercise: log-ratio step for each week, or null when it isn't active in both weeks.
    val exerciseSteps = HashMap<Long, Array<Double?>>()
    for ((exId, byWeek) in weekly) {
        val values = arrayOfNulls<Float>(weekCount)
        for ((wk, v) in byWeek) values[indexOf(wk)] = v
        val steps = arrayOfNulls<Double>(weekCount)
        var lastValue: Float? = null
        var lastWeek = -1
        for (i in 0 until weekCount) {
            val v = values[i]
            val activeBefore = lastValue != null && i - lastWeek <= staleWeeks
            if (v != null) {
                steps[i] = if (activeBefore && i > 0) ln(v.toDouble() / lastValue!!.toDouble()) else null
                lastValue = v
                lastWeek = i
            } else {
                // Not logged: holds flat while still fresh; drops out once stale.
                steps[i] = if (activeBefore && i > 0) 0.0 else null
            }
        }
        exerciseSteps[exId] = steps
    }

    // Tree shape, taken from the entries themselves (only nodes that have data).
    val exToArea = HashMap<Long, Long>()
    val areaToGroup = HashMap<Long, Long>()
    val groupToCategory = HashMap<Long, Long>()
    for (e in entries) {
        if (e.exerciseId !in weekly) continue
        exToArea[e.exerciseId] = e.areaId
        areaToGroup[e.areaId] = e.muscleGroupId
        groupToCategory[e.muscleGroupId] = e.categoryId
    }

    fun average(children: Collection<Array<Double?>>): Array<Double?> =
        Array(weekCount) { i ->
            var sum = 0.0
            var n = 0
            for (c in children) c[i]?.let { sum += it; n++ }
            if (n > 0) sum / n else null
        }

    fun <K> rollUp(childSteps: Map<Long, Array<Double?>>, parentOf: Map<Long, K>): Map<K, Array<Double?>> =
        childSteps.entries.groupBy({ parentOf.getValue(it.key) }, { it.value }).mapValues { average(it.value) }

    val areaSteps = rollUp(exerciseSteps, exToArea)
    val groupSteps = rollUp(areaSteps, areaToGroup)
    val categorySteps = rollUp(groupSteps, groupToCategory)
    val overallSteps = average(categorySteps.values)

    val windowStart = maxOf(0, weekCount - windowWeeks)
    fun trend(steps: Array<Double?>): Trend? {
        // Only steps inside the window move the line; the window's first week is the 100 base.
        if ((windowStart + 1 until weekCount).none { steps[it] != null }) return null
        var level = 0.0
        val points = ArrayList<Float>(weekCount - windowStart)
        points += 100f
        for (i in windowStart + 1 until weekCount) {
            steps[i]?.let { level += it }
            points += (100.0 * exp(level)).toFloat()
        }
        return Trend(points, points.last() - 100f)
    }

    return ProgressTrends(
        overall = trend(overallSteps),
        categories = categorySteps.mapNotNull { (k, v) -> trend(v)?.let { k to it } }.toMap(),
        muscleGroups = groupSteps.mapNotNull { (k, v) -> trend(v)?.let { k to it } }.toMap(),
        areas = areaSteps.mapNotNull { (k, v) -> trend(v)?.let { k to it } }.toMap(),
    )
}

private fun weekStart(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
