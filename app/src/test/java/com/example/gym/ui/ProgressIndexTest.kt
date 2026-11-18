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
    fun aGapOfAFewMonthsIsBridgedNotLost() {
        // Sessions 9 and 12 weeks apart (an exercise trained every couple of months): the progress
        // made across each gap counts, spread evenly over the weeks between sessions.
        val entries = listOf(set(0, 100f, 1), set(9, 103f, 1), set(21, 108f, 1))
        val t = computeProgressTrends(entries, emptyList(), today(21), windowWeeks = 26)
        assertEquals(8f, t.areas.getValue(1).changePct, 0.01f)
        // Mid-gap the line is already sloping, not flat.
        val points = t.areas.getValue(1).points
        assertTrue(points[4] > points[3] && points[3] > points[2])
    }

    @Test
    fun aWindowStartingMidGapStartsOnTheSlope() {
        // Sessions at weeks 0 and 10, window covers weeks 5..10: half the change is in the window.
        val t = computeProgressTrends(listOf(set(0, 100f, 1), set(10, 121f, 1)), emptyList(), today(10), windowWeeks = 6)
        val expected = (100 * (exp(ln(1.21) / 2) - 1)).toFloat() // +10%
        assertEquals(expected, t.areas.getValue(1).changePct, 0.01f)
    }

    @Test
    fun aGapLongerThanSixMonthsReentersWithoutAStep() {
        val t = computeProgressTrends(listOf(set(0, 100f, 1), set(30, 150f, 1)), emptyList(), today(30), windowWeeks = 52)
        assertEquals(0f, t.areas.getValue(1).changePct, 0.01f)
    }

    @Test
    fun sessionPointsMarkTheLoggedWeeksInTheWindow() {
        val h = buildProgressHistory(listOf(set(0, 100f, 1), set(5, 105f, 1), set(9, 110f, 1)), emptyList(), today(10))!!
        val ex = ProgressNode(ProgressLevel.EXERCISE, 1)
        assertEquals(listOf(0, 5, 9), h.sessionPoints(ex, null))
        // A 6-week window starts at week 5.
        assertEquals(listOf(0, 4), h.sessionPoints(ex, 6))
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

    @Test
    fun exercisesHaveTheirOwnTrend() {
        val h = buildProgressHistory(
            listOf(set(0, 100f, 1), set(1, 120f, 1), set(0, 50f, 2), set(1, 50f, 2)),
            emptyList(),
            today(1),
        )!!
        assertEquals(20f, h.trend(ProgressNode(ProgressLevel.EXERCISE, 1), null)!!.changePct, 0.01f)
        assertEquals(0f, h.trend(ProgressNode(ProgressLevel.EXERCISE, 2), null)!!.changePct, 0.01f)
    }

    @Test
    fun allRangeStartsAtTheFirstLoggedWeek() {
        val h = buildProgressHistory(listOf(set(0, 100f, 1), set(30, 130f, 1)), emptyList(), today(40), staleWeeks = 52, maxGapWeeks = 52)!!
        val all = h.trend(ProgressNode.OVERALL, null)!!
        assertEquals(monday, all.startWeek)
        assertEquals(41, all.points.size)
        assertEquals(30f, all.changePct, 0.01f)
        // The same history cut to 4 weeks starts 4 weeks before today, flat.
        val recent = h.trend(ProgressNode.OVERALL, 4)!!
        assertEquals(monday.plusWeeks(37), recent.startWeek)
        assertEquals(0f, recent.changePct, 0.01f)
    }

    @Test
    fun lastLoggedRollsUpToEveryAncestor() {
        val h = buildProgressHistory(
            listOf(set(0, 100f, 1, area = 1, group = 1, category = 1), set(3, 100f, 2, area = 2, group = 1, category = 1)),
            emptyList(),
            today(5),
        )!!
        assertEquals(monday, h.lastLogged[ProgressNode(ProgressLevel.AREA, 1)])
        assertEquals(monday.plusWeeks(3), h.lastLogged[ProgressNode(ProgressLevel.MUSCLE_GROUP, 1)])
        assertEquals(monday.plusWeeks(3), h.lastLogged[ProgressNode.OVERALL])
    }

    @Test
    fun nothingUsableMeansNoHistory() {
        // Reps unknown: no e1RM can be estimated.
        val h = buildProgressHistory(listOf(ProgressEntry(monday, null, 100f, null, 1, 1, 1, 1)), emptyList(), today(1))
        assertNull(h)
    }

    @Test
    fun addOnWeightingScalesOnlyTheAddOn() {
        // Pin 54 + 10 on the bar, then pin 45 + 15: as logged it reads as a drop (64 → 60)...
        fun e(week: Int, pin: Float, extra: Float, factor: Float) =
            ProgressEntry(monday.plusWeeks(week.toLong()), 0f, pin, extra, 1, 1, 1, 1, addOnFactor = factor)
        val asLogged = computeProgressTrends(listOf(e(0, 54f, 10f, 1f), e(1, 45f, 15f, 1f)), emptyList(), today(1))
        assertEquals((60f / 64f - 1f) * 100f, asLogged.areas.getValue(1).changePct, 0.01f)
        // ...but if each add-on kilo counts 2.8x on this machine, it was a gain (82 → 87).
        val weighted = computeProgressTrends(listOf(e(0, 54f, 10f, 2.8f), e(1, 45f, 15f, 2.8f)), emptyList(), today(1))
        assertEquals((87f / 82f - 1f) * 100f, weighted.areas.getValue(1).changePct, 0.01f)
    }

    @Test
    fun headerTrendsAlsoCoverEachExercise() {
        val t = computeProgressTrends(
            listOf(set(0, 100f, 1), set(1, 110f, 1), set(0, 50f, 2), set(1, 50f, 2)),
            emptyList(),
            today(1),
        )
        assertEquals(10f, t.exercises.getValue(1).changePct, 0.01f)
        assertEquals(0f, t.exercises.getValue(2).changePct, 0.01f)
    }
}
