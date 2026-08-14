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

	private val samples = ArrayDeque<Sample>()

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
		odometerKm: Double?
	) {
		if (remainingAh == null || voltageV == null || voltageV <= 0.0) {
			return
		}
		if (location == null && odometerKm == null) {
			return
		}
		samples.addLast(
			Sample(
				timeMs = timeMs,
				remainingAh = remainingAh,
				voltageV = voltageV,
				lat = location?.latitude,
				lon = location?.longitude,
				accuracyM = if (location != null && location.hasAccuracy()) location.accuracy else null,
				odometerKm = odometerKm
			)
		)
		trim(timeMs)
		recalculate(remainingAh, restVoltageV ?: voltageV)
	}

	@Synchronized
	fun reset() {
		samples.clear()
		remainingRangeKm = null
		consumptionAhPerKm = null
		consumptionWhPerKm = null
		gpsUnreliable = false
		usedFarDriverDistance = false
		windowDistanceKm = null
	}

	private fun trim(nowMs: Long) {
		while (samples.isNotEmpty() && nowMs - samples.first.timeMs > windowMs) {
			samples.removeFirst()
		}
	}

	private fun recalculate(currentRemainingAh: Double, energyVoltageV: Double) {
		if (samples.size < 2) {
			remainingRangeKm = null
			consumptionAhPerKm = null
			consumptionWhPerKm = null
			windowDistanceKm = null
			return
		}
		var distanceKm = 0.0
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
			when {
				jump && odoKm != null && odoKm >= 0.0 -> {
					distanceKm += odoKm
					usedFar = true
				}
				!jump && gpsKm != null -> distanceKm += gpsKm
				odoKm != null && odoKm >= 0.0 -> {
					distanceKm += odoKm
					usedFar = true
				}
			}
			prev = cur
		}
		gpsUnreliable = gpsBad
		usedFarDriverDistance = usedFar
		windowDistanceKm = distanceKm
		val first = samples.first
		val last = samples.last
		val consumedAh = first.remainingAh - last.remainingAh
		val avgVoltage = (first.voltageV + last.voltageV) / 2.0
		val consumedWh = consumedAh * avgVoltage
		if (distanceKm < minDistanceKm || consumedAh <= 0.01 || consumedWh <= 1.0) {
			return
		}
		val ahPerKm = consumedAh / distanceKm
		val whPerKm = consumedWh / distanceKm
		consumptionAhPerKm = ahPerKm
		consumptionWhPerKm = whPerKm
		remainingRangeKm = (currentRemainingAh * energyVoltageV / whPerKm).coerceAtLeast(0.0)
	}

	companion object {
		private const val MAX_PLAUSIBLE_KMH = 160.0
		private const val MAX_ACCURACY_M = 40f
		private const val JUMP_VS_ODO_RATIO = 3.0
		private const val MIN_JUMP_KM = 0.05

		fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
			val r = 6371.0
			val dLat = Math.toRadians(lat2 - lat1)
			val dLon = Math.toRadians(lon2 - lon1)
			val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
					Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
					Math.sin(dLon / 2) * Math.sin(dLon / 2)
			return 2 * r * Math.asin(Math.sqrt(a))
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
