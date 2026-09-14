package net.osmand.shared.gpx

import net.osmand.shared.ColorPalette

/**
 * Wh/km domain for track coloring, 3D height and split labels.
 * Color/3D use Δenergy / Δdistance over [windowMeters]. Values outside
 * [consumptionMin, consumptionMax] clamp to the ends.
 */
class TrackColorScale(
	val windowMeters: Double = EvConsumptionScale.DEFAULT_WINDOW_M,
	val consumptionMin: Double = EvConsumptionScale.MIN_WH_KM,
	val consumptionMax: Double = EvConsumptionScale.MAX_WH_KM,
	val speedMinMps: Double = 0.0,
	val speedMaxMps: Double = EvConsumptionScale.DEFAULT_SPEED_MAX_KMH / 3.6
) {
	fun cacheKey(): String {
		return "${windowMeters.toInt()}_${consumptionMin.toInt()}_${consumptionMax.toInt()}_" +
				"${(speedMinMps * 10).toInt()}_${(speedMaxMps * 10).toInt()}"
	}

	companion object {
		@JvmField
		val DEFAULT = TrackColorScale()
	}
}

object EvConsumptionScale {
	const val MIN_WH_KM = 40.0
	const val MAX_WH_KM = 300.0
	const val HEIGHT_RANGE_M = 260.0
	const val DEFAULT_WINDOW_M = 100.0
	const val DEFAULT_SPEED_MAX_KMH = 60.0

	private const val CONSUMPTION_FIELD_ENERGY = "consumption_wh_km"

	@JvmStatic
	fun clamp(whKm: Double): Double = clamp(whKm, MIN_WH_KM, MAX_WH_KM)

	@JvmStatic
	fun clamp(whKm: Double, min: Double, max: Double): Double {
		if (whKm.isNaN()) {
			return Double.NaN
		}
		val lo = min.coerceAtMost(max)
		val hi = max.coerceAtLeast(min + 1.0)
		return whKm.coerceIn(lo, hi)
	}

	@JvmStatic
	fun toHeightMeters(whKm: Float): Float = toHeightMeters(whKm, MIN_WH_KM, MAX_WH_KM)

	@JvmStatic
	fun toHeightMeters(whKm: Float, min: Double, max: Double): Float {
		if (whKm.isNaN()) {
			return 0f
		}
		val lo = min.coerceAtMost(max)
		val hi = max.coerceAtLeast(min + 1.0)
		val clamped = clamp(whKm.toDouble(), lo, hi)
		return ((clamped - lo) / (hi - lo) * HEIGHT_RANGE_M).toFloat()
	}

	@JvmStatic
	fun pointWhPerKm(attributes: PointAttributes?): Float {
		if (attributes == null) {
			return Float.NaN
		}
		val windowed = attributes.getAttributeValue(PointAttributes.EV_TAG_CONSUMPTION_WINDOW)
		if (!windowed.isNaN()) {
			return windowed
		}
		return rawPointWhPerKm(attributes)
	}

	private fun rawPointWhPerKm(attributes: PointAttributes): Float {
		val rolling = attributes.getAttributeValue(PointAttributes.EV_TAG_CONSUMPTION_100M)
		if (!rolling.isNaN()) {
			return rolling
		}
		return attributes.getAttributeValue(PointAttributes.EV_TAG_CONSUMPTION)
	}

	@JvmStatic
	fun windowedWhPerKm(attributes: List<PointAttributes>, windowMeters: Double): FloatArray {
		val n = attributes.size
		val result = FloatArray(n) { Float.NaN }
		if (n == 0) {
			return result
		}
		val window = windowMeters.coerceAtLeast(10.0)
		val cumDist = DoubleArray(n)
		for (i in 1 until n) {
			val step = attributes[i].distance
			cumDist[i] = cumDist[i - 1] + if (step.isNaN() || step < 0f) 0.0 else step.toDouble()
		}
		var start = 0
		for (i in 0 until n) {
			while (start < i && cumDist[i] - cumDist[start] > window) {
				start++
			}
			val from = if (start > 0 && cumDist[i] - cumDist[start - 1] >= window * 0.5) start - 1 else start
			val dKm = (cumDist[i] - cumDist[from]) / 1000.0
			val e1 = energyWh(attributes[from])
			val e2 = energyWh(attributes[i])
			if (!e1.isNaN() && !e2.isNaN() && dKm > 0.001) {
				val whKm = ((e2 - e1) / dKm).toFloat()
				result[i] = if (whKm.isFinite() && whKm >= 0f) whKm else rawPointWhPerKm(attributes[i])
			} else {
				result[i] = rawPointWhPerKm(attributes[i])
			}
		}
		return result
	}

	@JvmStatic
	fun stampWindowed(attributes: List<PointAttributes>, windowMeters: Double): FloatArray {
		val values = windowedWhPerKm(attributes, windowMeters)
		for (i in attributes.indices) {
			val value = values[i]
			if (!value.isNaN()) {
				attributes[i].setAttributeValue(PointAttributes.EV_TAG_CONSUMPTION_WINDOW, value)
			}
		}
		return values
	}

	@JvmStatic
	fun segmentWhPerKm(analysis: GpxTrackAnalysis): Float {
		val attrs = analysis.pointAttributes
		var firstEnergy = Float.NaN
		var lastEnergy = Float.NaN
		var energySamples = 0
		for (attribute in attrs) {
			val energy = energyWh(attribute)
			if (energy.isNaN()) {
				continue
			}
			energySamples++
			if (firstEnergy.isNaN()) {
				firstEnergy = energy
			}
			lastEnergy = energy
		}
		val distanceKm = analysis.totalDistance / 1000f
		if (energySamples >= 2 && distanceKm > 0.001f) {
			return (lastEnergy - firstEnergy) / distanceKm
		}
		var sum = 0f
		var count = 0
		for (attribute in attrs) {
			val value = pointWhPerKm(attribute)
			if (value.isNaN()) {
				continue
			}
			sum += value
			count++
		}
		return if (count > 0) sum / count else Float.NaN
	}

	@JvmStatic
	fun paletteForFixedRange(source: ColorPalette?): ColorPalette {
		return paletteForFixedRange(source, MIN_WH_KM, MAX_WH_KM)
	}

	@JvmStatic
	fun paletteForFixedRange(source: ColorPalette?, min: Double, max: Double): ColorPalette {
		val lo = min.coerceAtMost(max)
		val hi = max.coerceAtLeast(min + 1.0)
		val colors = source?.colors?.filter { !it.value.isNaN() }?.takeIf { it.size >= 2 }
			?: ColorPalette.MIN_MAX_PALETTE.colors
		val minColorValue = colors.first().value
		val maxColorValue = colors.last().value
		val span = maxColorValue - minColorValue
		if (span <= 0.0) {
			return ColorPalette(ColorPalette.MIN_MAX_PALETTE, lo, hi)
		}
		val result = ColorPalette()
		for (color in colors) {
			val t = (color.value - minColorValue) / span
			result.addPoint(lo + t * (hi - lo), color.clr)
		}
		result.noDataColor = source?.noDataColor
		return result
	}

	private fun energyWh(attributes: PointAttributes): Float {
		val energy = attributes.getAttributeValue(PointAttributes.EV_TAG_ENERGY)
		if (!energy.isNaN()) {
			return energy
		}
		return attributes.getAttributeValue(CONSUMPTION_FIELD_ENERGY)
	}
}
