package com.arbelonson.ozen

import kotlin.math.roundToInt

internal class SliderStops(val range: ClosedFloatingPointRange<Float>, private val perUnit: Int) {
    val steps: Int = ((range.endInclusive - range.start) * perUnit).roundToInt() - 1

    val values: List<Float> get() = (0..steps + 1).map { kept(range.start + it / perUnit.toFloat()) }

    fun kept(value: Float): Float = (value * perUnit).roundToInt() / perUnit.toFloat()

    companion object {
        val speakerSeparation = SliderStops(0.2f..0.95f, perUnit = 20)
        val speechRate = SliderStops(0.2f..0.7f, perUnit = 20)
    }
}
