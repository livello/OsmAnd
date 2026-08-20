package net.osmand.plus.plugins.evbms

import net.osmand.Location
import java.util.ArrayDeque

/**
 * Remaining range and trip energy.
 *
 * Energy (Wh) is the coulomb integral remainingAh·voltage, independent of GPS.
 * Distance prefers the front-wheel BLE sensor, then filtered GPS, then the
 * controller odometer (rear wheel can slip). Primary range uses remaining Wh /
 * Wh/km over the last 10 km; 5-minute and distance-on-charge (ПНЗ) estimates
 * stay available separately.
 */
class RangeEstimator(
	private val windowMs: Long = 5 * 60 * 1000L,
	private val minDistanceKm: Double = 0.05
) {

	enum class DistanceSource { WHEEL, GPS, CONTROLLER, SPEED }

	data class DistanceStep(val km: Double, val source: DistanceSource)

	data class Sample(
		val timeMs: Long,
		val remainingAh: Double,
		val voltageV: Double,
		val currentA: Double?,
		val speedKmh: Double?,
		val lat: Double?,
		val lon: Double?,
		val accuracyM: Float?,
		val wheelOdometerKm: Double?,
		val controllerOdometerKm: Double?
	)

	data class RouteElevation(
		val remainingKm: Double,
		val climbM: Double,
		val descentM: Double
	)

	private data class KmSeg(val dKm: Double, val dWh: Double)

	private val samples = ArrayDeque<Sample>()
	private val kmWindow = ArrayDeque<KmSeg>()
	private var kmWindowKm = 0.0
	private var kmWindowWh = 0.0

	private var tripDistanceKm = 0.0
	private var tripWh = 0.0
	private var tripAh = 0.0
	private var smoothedRangeKm: Double? = null

	@Volatile
	var remainingRangeKm: Double? = null
		private set

	@Volatile
	var windowRangeKm: Double? = null
		private set

	@Volatile
	var pnzRangeKm: Double? = null
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
		wheelOdometerKm: Double?,
		controllerOdometerKm: Double?,
		minCellV: Double?,
		batteryTempC: Double?,
		fullAh: Double?,
		farAvgWhPerKm: Double?,
		routeElevation: RouteElevation?,
		useRouteProfile: Boolean,
		massKg: Double = MASS_KG,
		currentA: Double? = null,
		speedKmh: Double? = null
	) {
		if (remainingAh == null || voltageV == null || voltageV <= 0.0) {
			return
		}
		val sample = Sample(
			timeMs = timeMs,
			remainingAh = remainingAh,
			voltageV = voltageV,
			currentA = currentA,
			speedKmh = speedKmh,
			lat = location?.latitude,
			lon = location?.longitude,
			accuracyM = if (location != null && location.hasAccuracy()) location.accuracy else null,
			wheelOdometerKm = wheelOdometerKm,
			controllerOdometerKm = controllerOdometerKm
		)
		val previous = samples.lastOrNull()
		samples.addLast(sample)
		if (previous != null) {
			accumulateTrip(previous, sample)
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
			useRouteProfile,
			massKg
		)
	}

	@Synchronized
	fun tripEnergyWh(): Double? = tripWh.takeIf { it > 0.01 }

	@Synchronized
	fun tripUsedAh(): Double? = tripAh.takeIf { it > 0.001 }

	@Synchronized
	fun tripDistanceKm(): Double? = tripDistanceKm.takeIf { it > 0.01 }

	@Synchronized
	fun markTripBoundary() {
		reset()
	}

	@Synchronized
	fun reset() {
		samples.clear()
		kmWindow.clear()
		kmWindowKm = 0.0
		kmWindowWh = 0.0
		tripDistanceKm = 0.0
		tripWh = 0.0
		tripAh = 0.0
		smoothedRangeKm = null
		remainingRangeKm = null
		windowRangeKm = null
		pnzRangeKm = null
		consumptionAhPerKm = null
		consumptionWhPerKm = null
		coverageWhPerKm = null
		weakCellFactor = 1.0
		gpsUnreliable = false
		usedFarDriverDistance = false
		windowDistanceKm = null
	}

	private fun accumulateTrip(prev: Sample, cur: Sample) {
		val dAh = coulombAh(prev, cur)
		if (dAh < -CHARGE_DISCONTINUITY_AH) {
			return
		}
		val dWh = segmentEnergyWh(prev, cur)
		tripWh += dWh
		tripAh = (tripAh + dAh).coerceAtLeast(0.0)
		val step = distanceStep(prev, cur) ?: return
		if (step.km < 0.002) {
			return
		}
		tripDistanceKm += step.km
		pushKm(step.km, dWh)
	}

	private fun pushKm(dKm: Double, dWh: Double) {
		kmWindow.addLast(KmSeg(dKm, dWh))
		kmWindowKm += dKm
		kmWindowWh += dWh
		while (kmWindow.size > 1 && kmWindowKm > ROLLING_KM) {
			val first = kmWindow.removeFirst()
			kmWindowKm -= first.dKm
			kmWindowWh -= first.dWh
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
		useRouteProfile: Boolean,
		massKg: Double
	) {
		if (samples.size < 2) {
			return
		}
		var distanceKm = 0.0
		var windowWh = 0.0
		var usedController = false
		var gpsBad = false
		val it = samples.iterator()
		var prev = it.next()
		while (it.hasNext()) {
			val cur = it.next()
			val step = distanceStep(prev, cur)
			if (step != null) {
				distanceKm += step.km
				if (step.source == DistanceSource.CONTROLLER) {
					usedController = true
				}
			}
			if (isGpsUnreliable(prev, cur)) {
				gpsBad = true
			}
			windowWh += segmentEnergyWh(prev, cur)
			prev = cur
		}
		gpsUnreliable = gpsBad
		usedFarDriverDistance = usedController
		windowDistanceKm = distanceKm

		val first = samples.first
		val last = samples.last
		val consumedAh = (first.remainingAh - last.remainingAh)
		if (distanceKm >= minDistanceKm && windowWh > 1.0) {
			consumptionAhPerKm = if (consumedAh > 0.01) consumedAh / distanceKm else consumptionAhPerKm
			consumptionWhPerKm = windowWh / distanceKm
		}

		val rollingWhPerKm = if (kmWindowKm >= 1.0 && kmWindowWh > 10.0) {
			kmWindowWh / kmWindowKm
		} else {
			null
		}
		val pnzWhPerKm = if (tripDistanceKm >= TRIP_RANGE_MIN_KM && tripWh > 50.0) {
			tripWh / tripDistanceKm
		} else {
			null
		}
		val windowWhPerKm = consumptionWhPerKm
		val controllerWhPerKm = farAvgWhPerKm?.takeIf { it >= 1.0 }
		coverageWhPerKm = rollingWhPerKm ?: windowWhPerKm ?: pnzWhPerKm ?: controllerWhPerKm

		val cellFactor = weakCellFactor(minCellV, currentRemainingAh, fullAh)
		weakCellFactor = cellFactor
		val tempFactor = if (kmWindowKm >= 3.0) 1.0 else temperatureFactor(batteryTempC)
		val remainingWh = currentRemainingAh * energyVoltageV * cellFactor * tempFactor

		fun rangeFrom(whPerKm: Double?): Double? {
			if (whPerKm == null || whPerKm < 1.0 || remainingWh <= 0.0) {
				return null
			}
			var effective = whPerKm
			if (useRouteProfile && routeElevation != null && routeElevation.remainingKm > 0.05) {
				val mass = if (massKg.isFinite() && massKg > 0.0) massKg else MASS_KG
				val climbWh = mass * G * routeElevation.climbM / 3600.0 / DRIVE_EFFICIENCY
				val regenWh = mass * G * routeElevation.descentM / 3600.0 * REGEN_EFFICIENCY
				effective = whPerKm + (climbWh - regenWh) / routeElevation.remainingKm
			}
			if (effective < 1.0) {
				effective = 1.0
			}
			return (remainingWh / effective).coerceAtLeast(0.0)
		}

		windowRangeKm = rangeFrom(windowWhPerKm)
		pnzRangeKm = rangeFrom(pnzWhPerKm)
		remainingRangeKm = smoothRange(
			rangeFrom(rollingWhPerKm) ?: rangeFrom(windowWhPerKm) ?: rangeFrom(pnzWhPerKm) ?:
				rangeFrom(controllerWhPerKm)
		)
	}

	private fun smoothRange(raw: Double?): Double? {
		if (raw == null) {
			return smoothedRangeKm
		}
		val prev = smoothedRangeKm
		if (prev == null) {
			smoothedRangeKm = raw
			return raw
		}
		val maxStep = maxOf(3.0, prev * 0.15)
		val limited = raw.coerceIn(prev - maxStep, prev + maxStep)
		val blended = prev * 0.7 + limited * 0.3
		smoothedRangeKm = blended
		return blended
	}

	companion object {
		private const val MAX_PLAUSIBLE_KMH = 160.0
		private const val MAX_ACCURACY_M = 40f
		private const val JUMP_VS_ODO_RATIO = 3.0
		private const val MIN_JUMP_KM = 0.05
		private const val TRIP_RANGE_MIN_KM = 2.0
		private const val ROLLING_KM = 10.0
		private const val CHARGE_DISCONTINUITY_AH = 0.25
		const val REGEN_EFFICIENCY = 0.55
		const val DRIVE_EFFICIENCY = 0.80
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

		fun maxPlausibleStepKm(dtMs: Long): Double {
			val dtSec = dtMs / 1000.0
			if (dtSec <= 0.05) {
				return 0.002
			}
			return (MAX_PLAUSIBLE_KMH / 3600.0 * dtSec).coerceIn(0.002, 0.5)
		}

		fun chooseDistanceKm(
			dtMs: Long,
			gpsKm: Double?,
			gpsUnreliable: Boolean,
			wheelDeltaKm: Double?,
			controllerDeltaKm: Double?,
			speedKm: Double?
		): DistanceStep? {
			val maxKm = maxPlausibleStepKm(dtMs)
			fun accept(delta: Double?, source: DistanceSource): DistanceStep? {
				if (delta != null && delta in 0.0002..maxKm) {
					return DistanceStep(delta, source)
				}
				return null
			}
			accept(wheelDeltaKm, DistanceSource.WHEEL)?.let { return it }
			if (!gpsUnreliable) {
				accept(gpsKm, DistanceSource.GPS)?.let { return it }
			}
			accept(controllerDeltaKm, DistanceSource.CONTROLLER)?.let { return it }
			accept(speedKm, DistanceSource.SPEED)?.let { return it }
			return null
		}

		fun odometerDeltaKm(prev: Double?, cur: Double?, dtMs: Long): Double? {
			if (prev == null || cur == null) {
				return null
			}
			val d = cur - prev
			val maxKm = maxPlausibleStepKm(dtMs)
			return if (d < -0.001 || d > maxKm) null else d
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

		private fun coulombAh(prev: Sample, cur: Sample): Double {
			val dAh = prev.remainingAh - cur.remainingAh
			if (kotlin.math.abs(dAh) >= 0.0005) {
				return dAh
			}
			val i = prev.currentA ?: return 0.0
			val dtH = (cur.timeMs - prev.timeMs).coerceAtLeast(0L) / 3_600_000.0
			return -i * dtH
		}

		private fun segmentEnergyWh(prev: Sample, cur: Sample): Double {
			val vAvg = (prev.voltageV + cur.voltageV) / 2.0
			var dWh = coulombAh(prev, cur) * vAvg
			if (kotlin.math.abs(prev.remainingAh - cur.remainingAh) < 0.0005) {
				val i = prev.currentA
				val dtH = (cur.timeMs - prev.timeMs).coerceAtLeast(0L) / 3_600_000.0
				if (i != null && dtH > 0.0) {
					dWh = -i * vAvg * dtH
				}
			}
			return dWh
		}

		private fun distanceStep(prev: Sample, cur: Sample): DistanceStep? {
			val dtMs = (cur.timeMs - prev.timeMs).coerceAtLeast(0L)
			val gpsKm = gpsDistanceKm(prev, cur)
			val wheel = odometerDeltaKm(prev.wheelOdometerKm, cur.wheelOdometerKm, dtMs)
			val ctrl = odometerDeltaKm(prev.controllerOdometerKm, cur.controllerOdometerKm, dtMs)
			return chooseDistanceKm(
				dtMs,
				gpsKm,
				isGpsUnreliable(prev, cur, gpsKm, wheel ?: ctrl),
				wheel,
				ctrl,
				speedDistanceKm(prev, cur)
			)
		}

		private fun speedDistanceKm(prev: Sample, cur: Sample): Double? {
			val speeds = listOfNotNull(prev.speedKmh, cur.speedKmh)
			if (speeds.isEmpty()) {
				return null
			}
			val speed = speeds.average()
			if (speed < 2.0 || speed > MAX_PLAUSIBLE_KMH) {
				return null
			}
			val dtH = (cur.timeMs - prev.timeMs).coerceAtLeast(0L) / 3_600_000.0
			val dKm = speed * dtH
			val maxKm = maxPlausibleStepKm(cur.timeMs - prev.timeMs)
			return dKm.takeIf { it in 0.0002..maxKm }
		}

		private fun gpsDistanceKm(prev: Sample, cur: Sample): Double? {
			val lat1 = prev.lat ?: return null
			val lon1 = prev.lon ?: return null
			val lat2 = cur.lat ?: return null
			val lon2 = cur.lon ?: return null
			return haversineKm(lat1, lon1, lat2, lon2)
		}

		private fun isGpsUnreliable(prev: Sample, cur: Sample): Boolean {
			val gpsKm = gpsDistanceKm(prev, cur)
			val dtMs = (cur.timeMs - prev.timeMs).coerceAtLeast(0L)
			val wheel = odometerDeltaKm(prev.wheelOdometerKm, cur.wheelOdometerKm, dtMs)
			val ctrl = odometerDeltaKm(prev.controllerOdometerKm, cur.controllerOdometerKm, dtMs)
			return isGpsUnreliable(prev, cur, gpsKm, wheel ?: ctrl)
		}

		fun isGpsUnreliable(
			prev: Sample,
			cur: Sample,
			gpsKm: Double?,
			odoKm: Double?
		): Boolean {
			return isGpsUnreliable(cur.accuracyM, prev.timeMs, cur.timeMs, gpsKm, odoKm)
		}

		fun isGpsUnreliable(
			accuracyM: Float?,
			prevTimeMs: Long,
			curTimeMs: Long,
			gpsKm: Double?,
			odoKm: Double?
		): Boolean {
			if (accuracyM != null && accuracyM > MAX_ACCURACY_M) {
				return true
			}
			if (gpsKm == null) {
				return true
			}
			val dtSec = (curTimeMs - prevTimeMs) / 1000.0
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
