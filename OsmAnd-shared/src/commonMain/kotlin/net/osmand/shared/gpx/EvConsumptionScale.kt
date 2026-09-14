package net.osmand.shared.gpx

import net.osmand.shared.ColorPalette

/**
 * Fixed Wh/km domain for track coloring, 3D height and split labels.
 * Values outside [MIN, MAX] clamp to the ends so tracks stay comparable.
 */
object EvConsumptionScale {
	const val MIN_WH_KM = 40.0
	const val MAX_WH_KM = 300.0
	const val HEIGHT_RANGE_M = 260.0

	private const val CONSUMPTION_FIELD_ENERGY = "consumption_wh_km"

	@JvmStatic
	fun clamp(whKm: Double): Double {
		if (whKm.isNaN()) {
			return Double.NaN
		}
		return whKm.coerceIn(MIN_WH_KM, MAX_WH_KM)
	}

	@JvmStatic
	fun toHeightMeters(whKm: Float): Float {
		if (whKm.isNaN()) {
			return 0f
		}
		val clamped = clamp(whKm.toDouble())
		return ((clamped - MIN_WH_KM) / (MAX_WH_KM - MIN_WH_KM) * HEIGHT_RANGE_M).toFloat()
	}

	@JvmStatic
	fun pointWhPerKm(attributes: PointAttributes?): Float {
		if (attributes == null) {
			return Float.NaN
		}
		val rolling = attributes.getAttributeValue(PointAttributes.EV_TAG_CONSUMPTION_100M)
		if (!rolling.isNaN()) {
			return rolling
		}
		return attributes.getAttributeValue(PointAttributes.EV_TAG_CONSUMPTION)
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
		val colors = source?.colors?.filter { !it.value.isNaN() }?.takeIf { it.size >= 2 }
			?: ColorPalette.MIN_MAX_PALETTE.colors
		val minColorValue = colors.first().value
		val maxColorValue = colors.last().value
		val span = maxColorValue - minColorValue
		if (span <= 0.0) {
			return ColorPalette(ColorPalette.MIN_MAX_PALETTE, MIN_WH_KM, MAX_WH_KM)
		}
		val result = ColorPalette()
		for (color in colors) {
			val t = (color.value - minColorValue) / span
			result.addPoint(MIN_WH_KM + t * (MAX_WH_KM - MIN_WH_KM), color.clr)
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
