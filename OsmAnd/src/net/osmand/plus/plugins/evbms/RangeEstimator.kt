package net.osmand.plus.plugins.evbms

import net.osmand.Location
import java.util.ArrayDeque

/**
 * Remaining range from GPS distance and BMS remaining capacity over a rolling window.
 */
class RangeEstimator(
	private val windowMs: Long = 5 * 60 * 1000L,
	private val minDistanceKm: Double = 0.05
) {

	data class Sample(
		val timeMs: Long,
		val remainingAh: Double,
		val voltageV: Double,
		val lat: Double,
		val lon: Double
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

	@Synchronized
	fun add(timeMs: Long, remainingAh: Double?, voltageV: Double?, location: Location?) {
		if (remainingAh == null || voltageV == null || voltageV <= 0.0 || location == null) {
			return
		}
		samples.addLast(Sample(timeMs, remainingAh, voltageV, location.latitude, location.longitude))
		trim(timeMs)
		recalculate(remainingAh, voltageV)
	}

	@Synchronized
	fun reset() {
		samples.clear()
		remainingRangeKm = null
		consumptionAhPerKm = null
		consumptionWhPerKm = null
	}

	private fun trim(nowMs: Long) {
		while (samples.isNotEmpty() && nowMs - samples.first.timeMs > windowMs) {
			samples.removeFirst()
		}
	}

	private fun recalculate(currentRemainingAh: Double, currentVoltageV: Double) {
		if (samples.size < 2) {
			remainingRangeKm = null
			consumptionAhPerKm = null
			consumptionWhPerKm = null
			return
		}
		var distanceKm = 0.0
		val it = samples.iterator()
		var prev = it.next()
		while (it.hasNext()) {
			val cur = it.next()
			distanceKm += haversineKm(prev.lat, prev.lon, cur.lat, cur.lon)
			prev = cur
		}
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
		remainingRangeKm = (currentRemainingAh * currentVoltageV / whPerKm).coerceAtLeast(0.0)
	}

	companion object {
		fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
			val r = 6371.0
			val dLat = Math.toRadians(lat2 - lat1)
			val dLon = Math.toRadians(lon2 - lon1)
			val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
					Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
					Math.sin(dLon / 2) * Math.sin(dLon / 2)
			return 2 * r * Math.asin(Math.sqrt(a))
		}
	}
}
