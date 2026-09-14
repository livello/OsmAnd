package net.osmand.shared.gpx

import net.osmand.shared.ColorPalette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvConsumptionScaleTest {

	@Test
	fun clampsAndMapsHeightToFixedRange() {
		assertEquals(40.0, EvConsumptionScale.clamp(10.0), 0.001)
		assertEquals(300.0, EvConsumptionScale.clamp(900.0), 0.001)
		assertEquals(170.0, EvConsumptionScale.clamp(170.0), 0.001)
		assertEquals(0f, EvConsumptionScale.toHeightMeters(40f), 0.01f)
		assertEquals(260f, EvConsumptionScale.toHeightMeters(300f), 0.01f)
		assertEquals(130f, EvConsumptionScale.toHeightMeters(170f), 0.5f)
	}

	@Test
	fun prefersRollingConsumptionThenPointTag() {
		val rolling = PointAttributes(1f, 1f, false, false)
		rolling.setAttributeValue(PointAttributes.EV_TAG_CONSUMPTION, 80f)
		rolling.setAttributeValue(PointAttributes.EV_TAG_CONSUMPTION_100M, 190f)
		assertEquals(190f, EvConsumptionScale.pointWhPerKm(rolling))

		val pointOnly = PointAttributes(1f, 1f, false, false)
		pointOnly.setAttributeValue(PointAttributes.EV_TAG_CONSUMPTION, 95f)
		assertEquals(95f, EvConsumptionScale.pointWhPerKm(pointOnly))
	}

	@Test
	fun segmentUsesEnergyDeltaOverDistance() {
		val analysis = GpxTrackAnalysis()
		analysis.totalDistance = 1000f
		analysis.pointAttributes.add(energyPoint(100f, 80f))
		analysis.pointAttributes.add(energyPoint(220f, 400f))
		assertEquals(120f, EvConsumptionScale.segmentWhPerKm(analysis), 0.01f)
	}

	@Test
	fun paletteCoversFixedFortyToThreeHundred() {
		val palette = EvConsumptionScale.paletteForFixedRange(ColorPalette.MIN_MAX_PALETTE)
		assertEquals(40.0, palette.colors.first().value, 0.001)
		assertEquals(300.0, palette.colors.last().value, 0.001)
		assertTrue(palette.getColorByValue(40.0) != palette.getColorByValue(300.0))
		assertEquals(palette.getColorByValue(40.0), palette.getColorByValue(0.0))
		assertEquals(palette.getColorByValue(300.0), palette.getColorByValue(900.0))
	}

	private fun energyPoint(energyWh: Float, whKm: Float): PointAttributes {
		return PointAttributes(1f, 1f, false, false).also {
			it.setAttributeValue(PointAttributes.EV_TAG_ENERGY, energyWh)
			it.setAttributeValue(PointAttributes.EV_TAG_CONSUMPTION, whKm)
		}
	}
}
