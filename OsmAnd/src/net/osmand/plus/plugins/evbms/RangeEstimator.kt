package net.osmand.plus.plugins.evbms

import net.osmand.Location
import java.util.ArrayDeque

/**
 * Remaining range from energy used vs distance over a rolling window.
 * Distance prefers GPS; FarDriver odometer is used when GPS jumps (jamming).
 */
class RangeEstimator(
	private val windowMs: Long = 5 * 60 * 1000L,
	private val minDistanceKm: Double = 0.05
) {

	data class Sample(
		val timeMs: Long,
		val remainingAh: Double,
		val voltageV: Double,
		val lat: Double?,
		val lon: Double?,
		val accuracyM: Float?,
		val odometerKm: Double?
	)

	data class RouteElevation(
		val remainingKm: Double,
		val climbM: Double,
		val descentM: Double
	)

	private val samples = ArrayDeque<Sample>()
	private val segmentWhPerKm = ArrayDeque<Double>()

	private var tripDistanceKm = 0.0
	private var tripWh = 0.0
	private var lastTripAh: Double? = null

	@Volatile
	var remainingRangeKm: Double? = null
		private set

	@Volatile
	var consumptionAhPerKm: Double? = null
		private set

	@Volatile
	var consumptionWhPerKm: Double? = null
		private set

	@Volatile
	var coverageWhPerKm: Double? = null
		private set

	@Volatile
	var weakCellFactor: Double = 1.0
		private set

	@Volatile
	var gpsUnreliable: Boolean = false
		private set

	@Volatile
	var usedFarDriverDistance: Boolean = false
		private set

	@Volatile
	var windowDistanceKm: Double? = null
		private set

	@Synchronized
	fun add(
		timeMs: Long,
		remainingAh: Double?,
		voltageV: Double?,
		restVoltageV: Double?,
		location: Location?,
		odometerKm: Double?,
		minCellV: Double?,
		batteryTempC: Double?,
		fullAh: Double?,
		farAvgWhPerKm: Double?,
		routeElevation: RouteElevation?,
		useRouteProfile: Boolean
	) {
		if (remainingAh == null || voltageV == null || voltageV <= 0.0) {
			return
		}
		if (location == null && odometerKm == null) {
			return
		}
		val prevAh = lastTripAh
		if (prevAh != null && remainingAh - prevAh > 1.0) {
			tripDistanceKm = 0.0
			tripWh = 0.0
			segmentWhPerKm.clear()
		}
		lastTripAh = remainingAh
		val sample = Sample(
			timeMs = timeMs,
			remainingAh = remainingAh,
			voltageV = voltageV,
			lat = location?.latitude,
			lon = location?.longitude,
			accuracyM = if (location != null && location.hasAccuracy()) location.accuracy else null,
			odometerKm = odometerKm
		)
		val previous = samples.lastOrNull()
		samples.addLast(sample)
		if (previous != null) {
			accumulateTrip(previous, sample, useRouteProfile)
		}
		trim(timeMs)
		recalculate(
			remainingAh,
			restVoltageV ?: voltageV,
			minCellV,
			batteryTempC,
			fullAh,
			farAvgWhPerKm,
			routeElevation,
			useRouteProfile
		)
	}

	@Synchronized
	fun reset() {
		samples.clear()
		segmentWhPerKm.clear()
		tripDistanceKm = 0.0
		tripWh = 0.0
		lastTripAh = null
		remainingRangeKm = null
		consumptionAhPerKm = null
		consumptionWhPerKm = null
		coverageWhPerKm = null
		weakCellFactor = 1.0
		gpsUnreliable = false
		usedFarDriverDistance = false
		windowDistanceKm = null
	}

	private fun accumulateTrip(prev: Sample, cur: Sample, useRouteProfile: Boolean) {
		val gpsKm = gpsDistanceKm(prev, cur)
		val odoKm = odometerDeltaKm(prev, cur)
		val dKm = segmentDistanceKm(prev, cur, gpsKm, odoKm) ?: return
		if (dKm < 0.01) {
			return
		}
		val dAh = prev.remainingAh - cur.remainingAh
		var dWh = dAh * (prev.voltageV + cur.voltageV) / 2.0
		if (useRouteProfile && dWh < 0) {
			dWh *= REGEN_EFFICIENCY
		}
		tripDistanceKm += dKm
		if (useRouteProfile || dWh > 0) {
			tripWh += dWh
		}
		if (dWh > 1.0) {
			segmentWhPerKm.addLast(dWh / dKm)
			while (segmentWhPerKm.size > 40) {
				segmentWhPerKm.removeFirst()
			}
		}
	}

	private fun trim(nowMs: Long) {
		while (samples.isNotEmpty() && nowMs - samples.first.timeMs > windowMs) {
			samples.removeFirst()
		}
	}

	private fun recalculate(
		currentRemainingAh: Double,
		energyVoltageV: Double,
		minCellV: Double?,
		batteryTempC: Double?,
		fullAh: Double?,
		farAvgWhPerKm: Double?,
		routeElevation: RouteElevation?,
		useRouteProfile: Boolean
	) {
		if (samples.size < 2) {
			return
		}
		var distanceKm = 0.0
		var windowWh = 0.0
		var usedFar = false
		var gpsBad = false
		val it = samples.iterator()
		var prev = it.next()
		while (it.hasNext()) {
			val cur = it.next()
			val gpsKm = gpsDistanceKm(prev, cur)
			val odoKm = odometerDeltaKm(prev, cur)
			val jump = isGpsUnreliable(prev, cur, gpsKm, odoKm)
			if (jump) {
				gpsBad = true
			}
			val dKm = segmentDistanceKm(prev, cur, gpsKm, odoKm) ?: 0.0
			if (jump && odoKm != null && odoKm >= 0.0) {
				usedFar = true
			} else if (jump.not() && gpsKm == null && odoKm != null && odoKm >= 0.0) {
				usedFar = true
			}
			distanceKm += dKm
			val dAh = prev.remainingAh - cur.remainingAh
			var dWh = dAh * (prev.voltageV + cur.voltageV) / 2.0
			if (useRouteProfile && dWh < 0) {
				dWh *= REGEN_EFFICIENCY
			}
			windowWh += dWh
			prev = cur
		}
		gpsUnreliable = gpsBad
		usedFarDriverDistance = usedFar
		windowDistanceKm = distanceKm

		val first = samples.first
		val last = samples.last
		val consumedAh = first.remainingAh - last.remainingAh
		val styleWh = if (useRouteProfile) windowWh else consumedAh * (first.voltageV + last.voltageV) / 2.0
		if (distanceKm >= minDistanceKm && styleWh > 1.0 && (useRouteProfile || consumedAh > 0.01)) {
			consumptionAhPerKm = consumedAh / distanceKm
			consumptionWhPerKm = styleWh / distanceKm
		}

		val candidates = ArrayList<Double>()
		consumptionWhPerKm?.let { candidates.add(it) }
		if (tripDistanceKm >= 0.5 && tripWh > 1.0) {
			candidates.add(tripWh / tripDistanceKm)
		}
		if (farAvgWhPerKm != null && farAvgWhPerKm > 10.0) {
			candidates.add(farAvgWhPerKm)
		}
		percentile(segmentWhPerKm, 0.8)?.let { candidates.add(it) }
		val coverage = candidates.maxOrNull() ?: return
		coverageWhPerKm = coverage

		val cellFactor = weakCellFactor(minCellV, currentRemainingAh, fullAh)
		weakCellFactor = cellFactor
		val remainingWh = currentRemainingAh * energyVoltageV * cellFactor * temperatureFactor(batteryTempC)
		var effectiveWhPerKm = coverage
		if (useRouteProfile && routeElevation != null && routeElevation.remainingKm > 0.05) {
			val climbWh = MASS_KG * G * routeElevation.climbM / 3600.0
			val regenWh = MASS_KG * G * routeElevation.descentM / 3600.0 * REGEN_EFFICIENCY
			effectiveWhPerKm = coverage + (climbWh - regenWh) / routeElevation.remainingKm
		}
		if (effectiveWhPerKm < 1.0) {
			effectiveWhPerKm = 1.0
		}
		remainingRangeKm = (remainingWh / effectiveWhPerKm).coerceAtLeast(0.0)
	}

	companion object {
		private const val MAX_PLAUSIBLE_KMH = 160.0
		private const val MAX_ACCURACY_M = 40f
		private const val JUMP_VS_ODO_RATIO = 3.0
		private const val MIN_JUMP_KM = 0.05
		const val REGEN_EFFICIENCY = 0.55
		const val MASS_KG = 280.0
		private const val G = 9.81
		private const val CELL_LVC_V = 3.00
		private const val CELL_HEALTHY_V = 3.50

		fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
			val r = 6371.0
			val dLat = Math.toRadians(lat2 - lat1)
			val dLon = Math.toRadians(lon2 - lon1)
			val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
					Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
					Math.sin(dLon / 2) * Math.sin(dLon / 2)
			return 2 * r * Math.asin(Math.sqrt(a))
		}

		fun weakCellFactor(minCellV: Double?, remainingAh: Double, fullAh: Double?): Double {
			if (minCellV == null) {
				return 1.0
			}
			val voltageFactor = ((minCellV - CELL_LVC_V) / (CELL_HEALTHY_V - CELL_LVC_V)).coerceIn(0.05, 1.0)
			if (fullAh != null && fullAh > 0.5 && remainingAh > 0.01) {
				val ahFraction = (remainingAh / fullAh).coerceIn(0.05, 1.0)
				return if (voltageFactor < ahFraction) {
					(voltageFactor / ahFraction).coerceIn(0.05, 1.0)
				} else {
					1.0
				}
			}
			return voltageFactor
		}

		fun temperatureFactor(batteryTempC: Double?): Double {
			if (batteryTempC == null) {
				return 1.0
			}
			return when {
				batteryTempC >= 20.0 -> 1.0
				batteryTempC >= 0.0 -> 0.8 + 0.2 * (batteryTempC / 20.0)
				else -> (0.65 + 0.015 * (batteryTempC + 10.0)).coerceIn(0.5, 0.8)
			}
		}

		private fun segmentDistanceKm(
			prev: Sample,
			cur: Sample,
			gpsKm: Double?,
			odoKm: Double?
		): Double? {
			val jump = isGpsUnreliable(prev, cur, gpsKm, odoKm)
			return when {
				jump && odoKm != null && odoKm >= 0.0 -> odoKm
				!jump && gpsKm != null -> gpsKm
				odoKm != null && odoKm >= 0.0 -> odoKm
				else -> null
			}
		}

		private fun percentile(values: ArrayDeque<Double>, p: Double): Double? {
			if (values.size < 4) {
				return null
			}
			val sorted = values.sorted()
			val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)
			return sorted[idx]
		}

		private fun gpsDistanceKm(prev: Sample, cur: Sample): Double? {
			val lat1 = prev.lat ?: return null
			val lon1 = prev.lon ?: return null
			val lat2 = cur.lat ?: return null
			val lon2 = cur.lon ?: return null
			return haversineKm(lat1, lon1, lat2, lon2)
		}

		private fun odometerDeltaKm(prev: Sample, cur: Sample): Double? {
			val a = prev.odometerKm ?: return null
			val b = cur.odometerKm ?: return null
			val d = b - a
			return if (d < -0.05) null else d
		}

		private fun isGpsUnreliable(
			prev: Sample,
			cur: Sample,
			gpsKm: Double?,
			odoKm: Double?
		): Boolean {
			if (cur.accuracyM != null && cur.accuracyM > MAX_ACCURACY_M) {
				return true
			}
			if (gpsKm == null) {
				return true
			}
			val dtSec = (cur.timeMs - prev.timeMs) / 1000.0
			if (dtSec > 0.2) {
				val impliedKmh = gpsKm / dtSec * 3600.0
				if (impliedKmh > MAX_PLAUSIBLE_KMH) {
					return true
				}
			}
			if (odoKm != null && odoKm >= 0.0 && gpsKm > MIN_JUMP_KM &&
				gpsKm > odoKm * JUMP_VS_ODO_RATIO + 0.03
			) {
				return true
			}
			return false
		}
	}
}
