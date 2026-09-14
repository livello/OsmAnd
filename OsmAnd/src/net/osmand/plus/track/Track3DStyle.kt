package net.osmand.plus.track

import net.osmand.shared.gpx.EvConsumptionScale
import net.osmand.shared.routing.Gpx3DWallColorType
import net.osmand.util.Algorithms

class Track3DStyle @JvmOverloads constructor(
	val visualizationType: Gpx3DVisualizationType,
	val wallColorType: Gpx3DWallColorType,
	val linePositionType: Gpx3DLinePositionType,
	val exaggeration: Float,
	val elevation: Float,
	val consumptionMin: Double = EvConsumptionScale.MIN_WH_KM,
	val consumptionMax: Double = EvConsumptionScale.MAX_WH_KM
) {

	override fun equals(other: Any?): Boolean {
		return other is Track3DStyle
				&& visualizationType == other.visualizationType
				&& wallColorType == other.wallColorType
				&& linePositionType == other.linePositionType
				&& exaggeration == other.exaggeration
				&& elevation == other.elevation
				&& consumptionMin == other.consumptionMin
				&& consumptionMax == other.consumptionMax
	}

	override fun hashCode(): Int {
		return Algorithms.hash(
			visualizationType,
			wallColorType,
			linePositionType,
			exaggeration,
			elevation,
			consumptionMin,
			consumptionMax
		)
	}

	override fun toString(): String {
		return "Track3DStyle { visualization $visualizationType wallColor $wallColorType linePosition " +
				"$linePositionType exaggeration $exaggeration elevation $elevation " +
				"consumption $consumptionMin-$consumptionMax}"
	}
}