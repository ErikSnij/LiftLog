package com.example.gym.ui

import com.example.gym.data.WeightConfig
import com.example.gym.data.WeightMode
import com.example.gym.data.WeightRoundMode
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
 */
internal fun poundsToKg(lbs: Float, round: WeightRoundMode, precisionKg: Float = 0.1f): Float {
    val units = (lbs * KG_PER_LB) / precisionKg
    val roundedUnits = when (round) {
        WeightRoundMode.UP -> ceil(units)
        WeightRoundMode.DOWN -> floor(units)
        WeightRoundMode.NEAREST -> units.roundToInt().toFloat()
    }
    return roundedUnits * precisionKg
}
