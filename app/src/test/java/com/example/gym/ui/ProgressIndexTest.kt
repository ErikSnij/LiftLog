package com.example.gym.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln

class ProgressIndexTest {

    /** A Monday, so week offsets are exact. */
    private val monday = LocalDate.of(2026, 1, 5)

    /** reps = 0 makes the Epley e1RM equal to the load, which keeps the expected numbers obvious. */
    private fun set(
        week: Int,
        weight: Float?,
        exercise: Long,
        area: Long = 1,
        group: Long = 1,
        category: Long = 1,
        reps: Float = 0f,
        extra: Float? = null,
    ) = ProgressEntry(monday.plusWeeks(week.toLong()), reps, weight, extra, exercise, area, group, category)

    private fun today(week: Int) = monday.plusWeeks(week.toLong())

    @Test
    fun singleExerciseGrowthIsItsOwnPercentage() {
        val t = computeProgressTrends(listOf(set(0, 100f, 1), set(1, 110f, 1)), emptyList(), today(1))
        assertEquals(10f, t.areas.getValue(1).changePct, 0.01f)
        assertEquals(10f, t.overall!!.changePct, 0.01f)
    }

    @Test
    fun siblingsCountEquallyWhateverTheirLoad() {
        // A light exercise gains 10%, a heavy one stays flat: the area moves by their average change.
        val entries = listOf(
            set(0, 10f, 1), set(1, 11f, 1),
            set(0, 200f, 2), set(1, 200f, 2),
        )
        val t = computeProgressTrends(entries, emptyList(), today(1))
        val expected = (100 * (exp(ln(1.1) / 2) - 1)).toFloat()
        assertEquals(expected, t.areas.getValue(1).changePct, 0.01f)
    }

    @Test
    fun aNewExerciseJoiningDoesNotMoveTheLine() {
        val entries = listOf(
            set(0, 100f, 1), set(1, 100f, 1), set(2, 100f, 1), set(3, 100f, 1),
            set(2, 500f, 2), set(3, 500f, 2),
        )
        val t = computeProgressTrends(entries, emptyList(), today(3))
        t.areas.getValue(1).points.forEach { assertEquals(100f, it, 0.001f) }
    }

    @Test
    fun anExerciseReloggedWithinEightWeeksCountsTheChange() {
        val t = computeProgressTrends(listOf(set(0, 100f, 1), set(8, 150f, 1)), emptyList(), today(8))
        assertEquals(50f, t.areas.getValue(1).changePct, 0.01f)
    }

    @Test
    fun anExerciseStaleForOverEightWeeksReentersWithoutAStep() {
        val t = computeProgressTrends(listOf(set(0, 100f, 1), set(9, 150f, 1)), emptyList(), today(9))
        assertEquals(0f, t.areas.getValue(1).changePct, 0.01f)
    }

    @Test
    fun nodesWithNothingActiveInTheWindowGetNoTrend() {
        // Logged once, a year ago: long stale, so no line rather than a misleading flat one.
        val t = computeProgressTrends(listOf(set(0, 100f, 1)), emptyList(), today(52), windowWeeks = 26)
        assertNull(t.areas[1])
        assertNull(t.overall)
    }

    @Test
    fun bodyweightSetsUseBodyWeightPlusAddOn() {
        val bw = listOf(monday to 80f)
        val entries = listOf(set(0, null, 1), set(1, null, 1, extra = 8f))
        val t = computeProgressTrends(entries, bw, today(1))
        assertEquals(10f, t.areas.getValue(1).changePct, 0.01f)
    }

    @Test
    fun rollsUpThroughEveryLevelWithEqualSiblings() {
        // Category 1 has two groups: group 1 +20%, group 2 flat. Category 2 is flat.
        val entries = listOf(
            set(0, 100f, 1, area = 1, group = 1, category = 1), set(1, 120f, 1, area = 1, group = 1, category = 1),
            set(0, 100f, 2, area = 2, group = 2, category = 1), set(1, 100f, 2, area = 2, group = 2, category = 1),
            set(0, 100f, 3, area = 3, group = 3, category = 2), set(1, 100f, 3, area = 3, group = 3, category = 2),
        )
        val t = computeProgressTrends(entries, emptyList(), today(1))
        assertEquals(20f, t.muscleGroups.getValue(1).changePct, 0.01f)
        val cat1 = (100 * (exp(ln(1.2) / 2) - 1)).toFloat()
        assertEquals(cat1, t.categories.getValue(1).changePct, 0.01f)
        assertEquals(0f, t.categories.getValue(2).changePct, 0.01f)
        val overall = (100 * (exp(ln(1.2) / 4) - 1)).toFloat()
        assertEquals(overall, t.overall!!.changePct, 0.01f)
    }

    @Test
    fun windowIsRebasedToStartAtOneHundred() {
        // +50% long before the window, +10% inside it: only the in-window change shows.
        val entries = listOf(set(0, 100f, 1), set(1, 150f, 1), set(20, 150f, 1), set(21, 165f, 1))
        val t = computeProgressTrends(entries, emptyList(), today(21), windowWeeks = 4)
        val trend = t.areas.getValue(1)
        assertEquals(4, trend.points.size)
        assertEquals(100f, trend.points.first(), 0.001f)
        assertEquals(10f, trend.changePct, 0.01f)
    }

    @Test
    fun bestSetOfTheWeekCounts() {
        val entries = listOf(set(0, 100f, 1), set(1, 90f, 1), set(1, 120f, 1, reps = 0f))
        val t = computeProgressTrends(entries, emptyList(), today(1))
        assertTrue(t.areas.getValue(1).changePct > 19.9f)
    }
}
