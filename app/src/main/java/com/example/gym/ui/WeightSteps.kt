package com.example.gym.ui

import com.example.gym.data.WeightConfig
import com.example.gym.data.WeightMode
import com.example.gym.data.WeightRoundMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
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
 * `base + n * step` for some small, consistent step, that step is very likely the equipment's
 * real increment (a machine's pin spacing, a specific plate size) rather than coincidence. If
 * that step is already a clean multiple of the app's default 0.5kg grid there's nothing to
 * suggest; if it lands close to a standard pounds plate size converted to kg (e.g. 5lb ≈
 * 2.27kg) a POUNDS config reproduces it exactly, otherwise a plain STEP config captures it.
 */
internal fun guessWeightConfig(historyKg: List<Float>): WeightConfig? {
    val distinct = historyKg.map { (it * 100f).roundToInt() / 100f }.distinct().sorted()
    if (distinct.size < 2) return null // one data point can't confirm a repeating interval

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
