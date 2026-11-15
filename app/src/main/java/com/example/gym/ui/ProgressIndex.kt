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

/** The levels of the tree, top to bottom. */
enum class ProgressLevel { OVERALL, CATEGORY, MUSCLE_GROUP, AREA, EXERCISE }

/** A node of the tree the progress index is computed for. [id] is unused for OVERALL. */
data class ProgressNode(val level: ProgressLevel, val id: Long = 0) {
    companion object {
        val OVERALL = ProgressNode(ProgressLevel.OVERALL)
    }
}

/**
 * A node's progress over a window: [points] is a weekly index starting at 100 on [startWeek]
 * (a Monday), one point per week, and [changePct] is how far the last point is from the first,
 * e.g. 8f for +8%.
 */
data class Trend(val points: List<Float>, val changePct: Float, val startWeek: LocalDate = LocalDate.MIN)

data class ProgressTrends(
    val overall: Trend? = null,
    val categories: Map<Long, Trend> = emptyMap(),
    val muscleGroups: Map<Long, Trend> = emptyMap(),
    val areas: Map<Long, Trend> = emptyMap(),
)

/**
 * The progress index for every node of the tree over its whole history, from which a trend for
 * any window can be cut (see [buildProgressHistory] for how it's computed).
 */
class ProgressHistory internal constructor(
    /** Monday of every week from the first logged week to today's week, oldest first. */
    val weeks: List<LocalDate>,
    /** Per node, per week: the index's log change that week, or null when nothing beneath it was active. */
    private val steps: Map<ProgressNode, Array<Double?>>,
    /** Per node: the latest date anything beneath it was logged. */
    val lastLogged: Map<ProgressNode, LocalDate>,
) {
    /** True when nothing usable has been logged at all. */
    val isEmpty: Boolean get() = steps.isEmpty()

    /** Whether anything was ever logged beneath [node]. */
    fun hasData(node: ProgressNode): Boolean = node in steps

    /** All nodes of [level] that have data. */
    fun nodes(level: ProgressLevel): List<ProgressNode> = steps.keys.filter { it.level == level }

    /**
     * [node]'s trend over the last [windowWeeks] weeks (the whole history when null), rebased to
     * 100 at the window's first week. Null when nothing beneath it was active in the window.
     */
    fun trend(node: ProgressNode, windowWeeks: Int?): Trend? {
        val s = steps[node] ?: return null
        val start = if (windowWeeks == null) 0 else maxOf(0, weeks.size - windowWeeks)
        if ((start + 1 until weeks.size).none { s[it] != null }) return null
        var level = 0.0
        val points = ArrayList<Float>(weeks.size - start)
        points += 100f
        for (i in start + 1 until weeks.size) {
            s[i]?.let { level += it }
            points += (100.0 * exp(level)).toFloat()
        }
        return Trend(points, points.last() - 100f, weeks[start])
    }

    companion object {
        /** A history with nothing in it (as opposed to one still loading). */
        val EMPTY = ProgressHistory(emptyList(), emptyMap(), emptyMap())
    }

    /** Category / muscle group / area trends for the tree headers. */
    fun headerTrends(windowWeeks: Int): ProgressTrends {
        fun byId(level: ProgressLevel) =
            nodes(level).mapNotNull { n -> trend(n, windowWeeks)?.let { n.id to it } }.toMap()
        return ProgressTrends(
            overall = trend(ProgressNode.OVERALL, windowWeeks),
            categories = byId(ProgressLevel.CATEGORY),
            muscleGroups = byId(ProgressLevel.MUSCLE_GROUP),
            areas = byId(ProgressLevel.AREA),
        )
    }
}

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
 * Returns null when nothing usable has been logged.
 */
fun buildProgressHistory(
    entries: List<ProgressEntry>,
    bodyWeights: List<Pair<LocalDate, Float>>,
    today: LocalDate,
    staleWeeks: Int = 8,
): ProgressHistory? {
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
    if (weekly.isEmpty()) return null

    val first = weekly.values.minOf { it.keys.min() }
    val last = maxOf(weekStart(today), weekly.values.maxOf { it.keys.max() })
    val weekCount = (ChronoUnit.WEEKS.between(first, last) + 1).toInt()
    val weeks = List(weekCount) { first.plusWeeks(it.toLong()) }
    fun indexOf(d: LocalDate) = ChronoUnit.WEEKS.between(first, d).toInt()

    // Per exercise: log-ratio step for each week, or null when it isn't active in both weeks.
    val exerciseSteps = HashMap<Long, Array<Double?>>()
    for ((exId, byWeek) in weekly) {
        val values = arrayOfNulls<Float>(weekCount)
        for ((wk, v) in byWeek) values[indexOf(wk)] = v
        val s = arrayOfNulls<Double>(weekCount)
        var lastValue: Float? = null
        var lastWeek = -1
        for (i in 0 until weekCount) {
            val v = values[i]
            val activeBefore = lastValue != null && i - lastWeek <= staleWeeks
            if (v != null) {
                s[i] = if (activeBefore && i > 0) ln(v.toDouble() / lastValue!!.toDouble()) else null
                lastValue = v
                lastWeek = i
            } else {
                // Not logged: holds flat while still fresh; drops out once stale.
                s[i] = if (activeBefore && i > 0) 0.0 else null
            }
        }
        exerciseSteps[exId] = s
    }

    // Tree shape, taken from the entries themselves (only nodes that have data), and the latest
    // date anything was logged beneath each node.
    val exToArea = HashMap<Long, Long>()
    val areaToGroup = HashMap<Long, Long>()
    val groupToCategory = HashMap<Long, Long>()
    val lastLogged = HashMap<ProgressNode, LocalDate>()
    fun bump(node: ProgressNode, d: LocalDate) {
        val cur = lastLogged[node]
        if (cur == null || d.isAfter(cur)) lastLogged[node] = d
    }
    for (e in entries) {
        if (e.exerciseId !in weekly) continue
        exToArea[e.exerciseId] = e.areaId
        areaToGroup[e.areaId] = e.muscleGroupId
        groupToCategory[e.muscleGroupId] = e.categoryId
        bump(ProgressNode(ProgressLevel.EXERCISE, e.exerciseId), e.date)
        bump(ProgressNode(ProgressLevel.AREA, e.areaId), e.date)
        bump(ProgressNode(ProgressLevel.MUSCLE_GROUP, e.muscleGroupId), e.date)
        bump(ProgressNode(ProgressLevel.CATEGORY, e.categoryId), e.date)
        bump(ProgressNode.OVERALL, e.date)
    }

    fun average(children: Collection<Array<Double?>>): Array<Double?> =
        Array(weekCount) { i ->
            var sum = 0.0
            var n = 0
            for (c in children) c[i]?.let { sum += it; n++ }
            if (n > 0) sum / n else null
        }

    fun rollUp(childSteps: Map<Long, Array<Double?>>, parentOf: Map<Long, Long>): Map<Long, Array<Double?>> =
        childSteps.entries.groupBy({ parentOf.getValue(it.key) }, { it.value }).mapValues { average(it.value) }

    val areaSteps = rollUp(exerciseSteps, exToArea)
    val groupSteps = rollUp(areaSteps, areaToGroup)
    val categorySteps = rollUp(groupSteps, groupToCategory)

    val all = HashMap<ProgressNode, Array<Double?>>()
    exerciseSteps.forEach { (id, s) -> all[ProgressNode(ProgressLevel.EXERCISE, id)] = s }
    areaSteps.forEach { (id, s) -> all[ProgressNode(ProgressLevel.AREA, id)] = s }
    groupSteps.forEach { (id, s) -> all[ProgressNode(ProgressLevel.MUSCLE_GROUP, id)] = s }
    categorySteps.forEach { (id, s) -> all[ProgressNode(ProgressLevel.CATEGORY, id)] = s }
    all[ProgressNode.OVERALL] = average(categorySteps.values)

    return ProgressHistory(weeks, all, lastLogged)
}

/** Header trends over the last [windowWeeks] weeks; see [buildProgressHistory]. */
fun computeProgressTrends(
    entries: List<ProgressEntry>,
    bodyWeights: List<Pair<LocalDate, Float>>,
    today: LocalDate,
    windowWeeks: Int = 26,
    staleWeeks: Int = 8,
): ProgressTrends =
    buildProgressHistory(entries, bodyWeights, today, staleWeeks)?.headerTrends(windowWeeks) ?: ProgressTrends()

private fun weekStart(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
