package com.example.gym.ui

import com.example.gym.data.WeightConfig
import com.example.gym.data.WeightMode
import com.example.gym.data.WeightRoundMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

private const val KG_PER_LB = 0.45359237f
private const val MAX_WEIGHT_KG = 300f

/**
 * The weight wheel's value domain for [config]: a leading null (BW) then an ascending list of
 * kg values up to ~300kg, generated per its [WeightMode] so the wheel (and the manual numeric
 * entry, which shares this same list for its "current value" lookup) lands on the equipment's
 * real increments — dumbbells, barbells, lb-plated machines — instead of always assuming the
 * app's default 0.5kg step.
 */
internal fun weightWheelValues(config: WeightConfig): List<Float?> = when (config.mode) {
    WeightMode.DEFAULT -> wheelValues(MAX_WEIGHT_KG)
    WeightMode.STEP -> stepWeightValues(
        step = config.stepKg?.takeIf { it > 0f } ?: 0.5f,
        heavyThreshold = config.heavyThresholdKg,
        heavyStep = config.heavyStepKg?.takeIf { it > 0f },
    )
    WeightMode.POUNDS -> poundsWeightValues(
        startLbs = config.startLbs ?: 0f,
        stepLbs = config.stepLbs?.takeIf { it > 0f } ?: 5f,
        round = config.roundMode,
    )
}

/**
 * 0, step, 2*step, ... up to [MAX_WEIGHT_KG]. Once a value reaches [heavyThreshold] the step
 * switches to [heavyStep] (e.g. a barbell: 0.5kg jumps below 50kg, 1kg jumps from 50kg up).
 */
private fun stepWeightValues(step: Float, heavyThreshold: Float?, heavyStep: Float?): List<Float?> =
    buildList {
        add(null)
        var value = 0f
        while (value <= MAX_WEIGHT_KG) {
            add(value)
            val nextStep = if (heavyThreshold != null && heavyStep != null && value >= heavyThreshold) heavyStep else step
            value += nextStep
        }
    }

/** A starting weight + interval defined in pounds (e.g. a 5lb-plated machine), converted to kg. */
private fun poundsWeightValues(startLbs: Float, stepLbs: Float, round: WeightRoundMode): List<Float?> =
    buildList {
        add(null)
        var lbs = startLbs
        var kg = poundsToKg(lbs, round)
        while (kg <= MAX_WEIGHT_KG) {
            add(kg)
            lbs += stepLbs
            kg = poundsToKg(lbs, round)
        }
    }

/**
 * Converts [lbs] to kg, rounded to the nearest [precisionKg] (0.1kg by default — 1 decimal
 * place) per [round]. Deliberately NOT snapped to the app's usual 0.5kg wheel step: a machine's
 * real pin values (e.g. 5.7kg, 14.7kg) rarely land on that grid, so pounds-mode keeps the
 * conversion's actual precision and only rounds off float noise beyond the first decimal.
 *
 * [precisionKg] is a Double (not Float) on purpose: e.g. 57 * 0.1f lands one float ULP away
 * from the actual nearest float to 5.7 (0.1f's own rounding error compounds into the product),
 * printing as "5.7000003" instead of "5.7". A genuine Double literal for 0.1 is precise enough
 * that the single final rounding to Float lands on the correct value.
 */
internal fun poundsToKg(lbs: Float, round: WeightRoundMode, precisionKg: Double = 0.1): Float {
    val units = (lbs.toDouble() * KG_PER_LB) / precisionKg
    val roundedUnits = when (round) {
        WeightRoundMode.UP -> ceil(units)
        WeightRoundMode.DOWN -> floor(units)
        WeightRoundMode.NEAREST -> units.roundToInt().toDouble()
    }
    return (roundedUnits * precisionKg).toFloat()
}

/** A plate/pin size worth checking a detected kg step against, in pounds. */
private val PLAUSIBLE_LB_STEPS = listOf(2.5f, 5f, 7.5f, 10f, 15f, 20f, 25f, 35f, 45f)

/**
 * Best-effort guess at a [WeightConfig] for an exercise, from every weight ever logged against
 * it — null when the history isn't consistent/plentiful enough to be confident about anything.
 *
 * The idea: if every distinct weight this exercise has ever been logged at lines up with
 * `base + n * step` for some consistent step, that step is very likely the equipment's real
 * increment (a machine's pin spacing, a specific plate size) rather than coincidence. Weights on
 * a clean kg grid (5kg jumps, 2kg dumbbells) get a plain kg step from [guessFromKgGrid].
 * Otherwise two detectors run in order: [guessFromPoundSteps] (tests standard lb pin sizes
 * directly, tolerant of whole-kg rounding — how lb machines usually get logged) and
 * [guessFromGcd] (derives an arbitrary small step from the weights' differences, for
 * precisely-logged data).
 */
internal fun guessWeightConfig(historyKg: List<Float>): WeightConfig? {
    val distinct = historyKg.map { (it * 100f).roundToInt() / 100f }.distinct().sorted()
    if (distinct.size < 2) return null // one data point can't confirm a repeating interval

    // Weights on a clean kg grid (5kg plate jumps, 2kg dumbbells, ...) are a plate/dumbbell
    // setup, not a pounds stack — settle it here so it isn't misread as one below.
    val base = distinct.first()
    if (NATURAL_KG_STEPS.any { k -> distinct.all { isCloseToMultiple(it - base, k, tolerance = 0.05f) } }) {
        return guessFromKgGrid(distinct)
    }

    guessFromPoundSteps(distinct)?.let { return it }
    return guessFromGcd(distinct)
}

/** Largest first. Capped at 5kg: coarser jumps are rare as real equipment steps and would skip weights. */
private val KG_GRID_STEPS = listOf(5f, 2.5f, 2f)

/**
 * The default wheel can already land on weights like 50/60/80, but scrolling 0.5kg at a time to get
 * there is slow. If every logged weight is a whole multiple of 5kg (else 2.5, else 2) suggest that
 * as the step. A step wheel counts up from 0, so the weights must be multiples of the step itself
 * — being a fixed distance apart isn't enough.
 */
private fun guessFromKgGrid(distinct: List<Float>): WeightConfig? {
    val step = KG_GRID_STEPS.firstOrNull { k ->
        distinct.all { isCloseToMultiple(it, k, tolerance = 0.05f) }
    } ?: return null
    return WeightConfig(mode = WeightMode.STEP, stepKg = step)
}

/** Pin/plate sizes in lb worth testing as a candidate step, coarsest first. */
private val POUND_STEP_CANDIDATES = listOf(25f, 20f, 15f, 10f, 7.5f, 5f, 2.5f)

/** Step sizes people choose in kg on purpose (plate jumps, dumbbell pairs) — not a pounds stack. */
private val NATURAL_KG_STEPS = listOf(2f, 2.5f, 5f, 10f, 20f, 25f)

/** Max chance that the logged weights would line up with a candidate step purely by accident. */
private const val MAX_FALSE_FIT_ODDS = 0.15

/**
 * Tests each plausible lb step directly against the logged weights instead of deriving a step
 * from their differences. A machine labelled in lb but logged in whole kg ("93, 100, 107, 113"
 * for 205/220/235/250 lb) has every weight rounded by up to 0.5kg, which makes a difference-based
 * GCD collapse to ~1kg; checking "do these all sit on a 15 lb grid, give or take rounding?" doesn't.
 *
 * A step fits when the weights' offsets within it (modulo the step) bunch into a window no wider
 * than the rounding error. Coarse steps are tried first, and a fit only counts if it would be an
 * unlikely coincidence for this many weights (few weights vs. a fine step prove nothing).
 */
private fun guessFromPoundSteps(distinct: List<Float>): WeightConfig? {
    val base = distinct.first()
    val wholeKg = distinct.all { isCloseToMultiple(it, 1f, tolerance = 0.01f) }
    // Whole-kg logs are a lb value rounded to the nearest kg (each up to 0.5kg off, so offsets can
    // span 1kg); logs from this app's own pounds wheel are rounded up to 0.1kg.
    val window = if (wholeKg) 1.001 else 0.12

    for (lbs in POUND_STEP_CANDIDATES) {
        val stepKg = lbs.toDouble() * KG_PER_LB
        if (offsetSpread(distinct, stepKg) > window) continue
        val oddsOfCoincidence = (window / stepKg).coerceAtMost(1.0).pow(distinct.size - 1)
        if (oddsOfCoincidence > MAX_FALSE_FIT_ODDS) continue

        // Whole-kg data: snap to a plain 2.5 lb mark (stacks are numbered that way). Wheel-made
        // data: reproduce the lowest weight exactly under round-up.
        val startLbs = if (wholeKg) {
            (floor(base / KG_PER_LB / 2.5f + 0.5f) * 2.5f)
        } else {
            floor((base / KG_PER_LB) * 10.0).toFloat() / 10f
        }
        val config = WeightConfig(
            mode = WeightMode.POUNDS,
            startLbs = startLbs,
            stepLbs = lbs,
            roundMode = WeightRoundMode.UP,
        )
        if (wheelCovers(config, distinct, toleranceKg = if (wholeKg) 0.55f else 0.12f)) return config
    }
    return null
}

/** Width of the smallest arc of [0, step) that contains every weight's offset within a step. */
private fun offsetSpread(weights: List<Float>, stepKg: Double): Double {
    val offsets = weights.map { ((it % stepKg) + stepKg) % stepKg }.sorted()
    var widestGap = offsets.first() + stepKg - offsets.last() // the gap that wraps around
    for (i in 1 until offsets.size) widestGap = maxOf(widestGap, offsets[i] - offsets[i - 1])
    return stepKg - widestGap
}

/** True if every logged weight is within [toleranceKg] of some value on [config]'s wheel. */
private fun wheelCovers(config: WeightConfig, weights: List<Float>, toleranceKg: Float): Boolean {
    val wheel = weightWheelValues(config).filterNotNull()
    return weights.all { w -> wheel.any { abs(it - w) <= toleranceKg } }
}

private fun guessFromGcd(distinct: List<Float>): WeightConfig? {
    val base = distinct.first()
    var step = distinct[1] - base
    for (v in distinct.drop(2)) step = approxGcd(step, v - base)

    // Equipment increments worth configuring are small and deliberate — anything this fine is
    // probably measurement noise, anything this coarse is probably just a heavier working set,
    // not a fixed step.
    if (step < 0.2f || step > 5f) return null
    // Already lands on the app's default 0.5kg wheel — nothing to suggest.
    if (isCloseToMultiple(step, 0.5f, tolerance = 0.05f)) return null
    // Every logged weight must actually land on the grid this step implies, or it isn't a real
    // pattern — it's a coincidence between whichever two values happened to produce it.
    if (distinct.any { !isCloseToMultiple(it - base, step, tolerance = 0.1f) }) return null

    val bestLbStep = PLAUSIBLE_LB_STEPS.minByOrNull { abs(it * KG_PER_LB - step) }
    return if (bestLbStep != null && abs(bestLbStep * KG_PER_LB - step) < bestLbStep * KG_PER_LB * 0.08f) {
        // Reproduces `base` exactly under round-up: the largest lbs value whose rounded-up
        // conversion doesn't exceed it.
        val startLbs = floor((base / KG_PER_LB) * 10.0).toFloat() / 10f
        WeightConfig(mode = WeightMode.POUNDS, startLbs = startLbs, stepLbs = bestLbStep, roundMode = WeightRoundMode.UP)
    } else {
        WeightConfig(mode = WeightMode.STEP, stepKg = (step * 10f).roundToInt() / 10f)
    }
}

/** Euclidean GCD adapted for floats: stops once the remainder is within [tolerance] of zero. */
private fun approxGcd(a: Float, b: Float, tolerance: Float = 0.05f): Float {
    var x = abs(a)
    var y = abs(b)
    while (y > tolerance) {
        val r = x % y
        x = y
        y = r
    }
    return x
}

private fun isCloseToMultiple(value: Float, unit: Float, tolerance: Float): Boolean {
    val n = (value / unit).roundToInt()
    return abs(value - n * unit) <= tolerance
}
