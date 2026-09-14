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

	@Test
	fun windowedUsesEnergyDeltaOverWindow() {
		val attrs = listOf(
			energyPoint(0f, 80f, 0f),
			energyPoint(5f, 80f, 50f),
			energyPoint(10f, 80f, 50f),
			energyPoint(15f, 80f, 50f),
			energyPoint(20f, 80f, 50f)
		)
		val values = EvConsumptionScale.windowedWhPerKm(attrs, 100.0)
		assertEquals(100f, values[4], 0.5f)
		assertEquals(100f, values[2], 0.5f)
	}

	@Test
	fun pointPrefersWindowedTag() {
		val attributes = PointAttributes(1f, 1f, false, false)
		attributes.setAttributeValue(PointAttributes.EV_TAG_CONSUMPTION, 80f)
		attributes.setAttributeValue(PointAttributes.EV_TAG_CONSUMPTION_100M, 190f)
		attributes.setAttributeValue(PointAttributes.EV_TAG_CONSUMPTION_WINDOW, 142f)
		assertEquals(142f, EvConsumptionScale.pointWhPerKm(attributes))
	}

	@Test
	fun paletteCoversCustomRange() {
		val palette = EvConsumptionScale.paletteForFixedRange(ColorPalette.MIN_MAX_PALETTE, 20.0, 200.0)
		assertEquals(20.0, palette.colors.first().value, 0.001)
		assertEquals(200.0, palette.colors.last().value, 0.001)
	}

	private fun energyPoint(energyWh: Float, whKm: Float): PointAttributes {
		return energyPoint(energyWh, whKm, 1f)
	}

	private fun energyPoint(energyWh: Float, whKm: Float, distanceM: Float): PointAttributes {
		return PointAttributes(distanceM, 1f, false, false).also {
			it.setAttributeValue(PointAttributes.EV_TAG_ENERGY, energyWh)
			it.setAttributeValue(PointAttributes.EV_TAG_CONSUMPTION, whKm)
		}
	}
}
