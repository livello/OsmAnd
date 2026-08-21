package net.osmand.plus.plugins.evbms.protocol

/**
 * BLE Cycling Speed and Cadence (0x1816) wheel revolutions → speed and distance.
 *
 * Cheap sensors (Cycplus BK467) keep notifying the last CSC sample after the wheel
 * stops. Speed is therefore coasted to zero on wall-clock time since the last new
 * revolution, not on the frozen Last Wheel Event Time field.
 */
class CscWheelTracker {

	@Volatile
	var odometerKm: Double? = null
		private set

	@Volatile
	var tripKm: Double = 0.0
		private set

	@Volatile
	var lastRxMs: Long = 0L
		private set

	@Volatile
	var hasWheelData: Boolean = false
		private set

	private var circumferenceM = DEFAULT_CIRCUMFERENCE_M
	private var calFactor = 1.0
	private var lastRevs: Long? = null
	private var lastEventTime: Int? = null
	private var lastRevWallMs = 0L
	private var lastRevPeriodSec = 1.0
	private var notifyIntervalMs = 1_500L
	private var lastInstantKmh: Double? = null
	private var calibratedM = 0.0
	private var rawM = 0.0
	private var persistDueM = 0.0

	fun configure(circumferenceMeters: Double, factor: Double) {
		circumferenceM = circumferenceMeters.takeIf { it.isFinite() && it in 1.2..3.0 }
			?: DEFAULT_CIRCUMFERENCE_M
		calFactor = factor.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(0.5, 2.0) ?: 1.0
	}

	fun restoreOdometerKm(km: Double?) {
		if (km == null || !km.isFinite() || km < 0.0) {
			return
		}
		calibratedM = km * 1000.0
		odometerKm = km
	}

	fun resetTrip() {
		tripKm = 0.0
		resetBaseline()
		hasWheelData = false
		odometerKm = if (calibratedM > 0.0) calibratedM / 1000.0 else odometerKm
	}

	fun resetBaseline() {
		lastRevs = null
		lastEventTime = null
		lastRevWallMs = 0L
		lastInstantKmh = null
	}

	fun rawMetersSince(startRawM: Double): Double = (rawM - startRawM).coerceAtLeast(0.0)

	fun rawMeters(): Double = rawM

	fun lastWheelRevs(): Long? = lastRevs

	fun revsSince(startRevs: Long?): Long? {
		val end = lastRevs ?: return null
		val start = startRevs ?: return null
		val delta = end - start
		return if (delta >= 0L) delta else delta + 0x1_0000_0000L
	}

	fun takePersistDeltaKm(): Double? {
		if (persistDueM < 50.0) {
			return null
		}
		val km = persistDueM / 1000.0
		persistDueM = 0.0
		return km
	}

	fun currentSpeedKmh(): Double? {
		if (!hasWheelData) {
			return null
		}
		return lastInstantKmh ?: 0.0
	}

	fun ingest(payload: ByteArray): Boolean {
		if (payload.isEmpty()) {
			return false
		}
		val now = System.currentTimeMillis()
		if (lastRxMs > 0L) {
			val gap = now - lastRxMs
			if (gap in 200L..4_000L) {
				notifyIntervalMs = gap
			}
		}
		lastRxMs = now
		val flags = payload[0].toInt() and 0xFF
		val wheelPresent = flags and 0x01 != 0
		if (!wheelPresent || payload.size < 7) {
			maybeCoastToZero(now)
			return true
		}
		val revs = u32le(payload, 1)
		val eventTime = u16le(payload, 5)
		val prevRevs = lastRevs
		val prevTime = lastEventTime
		val prevWall = lastRevWallMs
		lastRevs = revs
		lastEventTime = eventTime
		hasWheelData = true
		if (prevRevs == null) {
			lastRevWallMs = now
			lastInstantKmh = 0.0
			if (calibratedM > 0.0) {
				odometerKm = calibratedM / 1000.0
			}
			return true
		}
		if (revs + 8L < prevRevs) {
			lastInstantKmh = 0.0
			lastRevWallMs = now
			return true
		}
		val dRevs = (revs - prevRevs).coerceAtLeast(0L)
		if (dRevs > MAX_REVS_PER_NOTIFY) {
			return true
		}
		if (dRevs == 0L) {
			maybeCoastToZero(now)
			return true
		}
		val cscDt = eventTimeDeltaSec(prevTime, eventTime)
		val wallDt = if (prevWall > 0L) (now - prevWall) / 1000.0 else 0.0
		val dt = when {
			cscDt != null && cscDt in MIN_EVENT_SEC..MAX_EVENT_SEC -> cscDt
			wallDt in MIN_EVENT_SEC..MAX_EVENT_SEC -> wallDt
			else -> null
		}
		val rawDeltaM = dRevs * circumferenceM
		rawM += rawDeltaM
		val calDeltaM = rawDeltaM * calFactor
		calibratedM += calDeltaM
		persistDueM += calDeltaM
		tripKm += calDeltaM / 1000.0
		odometerKm = calibratedM / 1000.0
		lastRevWallMs = now
		if (dt != null && dt > 0.0) {
			lastRevPeriodSec = dt / dRevs.toDouble()
			val kmh = (calDeltaM / dt) * 3.6
			if (kmh.isFinite() && kmh <= MAX_KMH) {
				lastInstantKmh = kmh
			}
		}
		return true
	}

	private fun maybeCoastToZero(nowMs: Long) {
		if (lastRevWallMs <= 0L) {
			return
		}
		if (nowMs - lastRevWallMs > coastTimeoutMs()) {
			lastInstantKmh = 0.0
		}
	}

	private fun coastTimeoutMs(): Long {
		val fromPeriod = (lastRevPeriodSec * 2_500.0).toLong()
		val fromNotify = notifyIntervalMs * 2L + 400L
		return maxOf(fromPeriod, fromNotify, MIN_COAST_MS).coerceAtMost(MAX_COAST_MS)
	}

	companion object {
		const val DEFAULT_CIRCUMFERENCE_M = 2.0
		const val DEFAULT_CIRCUMFERENCE_MM = 2000
		private const val MAX_REVS_PER_NOTIFY = 80L
		private const val MIN_EVENT_SEC = 0.02
		private const val MAX_EVENT_SEC = 8.0
		private const val MAX_KMH = 160.0
		private const val MIN_COAST_MS = 2_500L
		private const val MAX_COAST_MS = 5_000L

		fun mmToMeters(mm: Int): Double = (mm.coerceIn(1200, 2800) / 1000.0)

		private fun u16le(data: ByteArray, offset: Int): Int {
			return (data[offset].toInt() and 0xFF) or
					((data[offset + 1].toInt() and 0xFF) shl 8)
		}

		private fun u32le(data: ByteArray, offset: Int): Long {
			return (data[offset].toLong() and 0xFF) or
					((data[offset + 1].toLong() and 0xFF) shl 8) or
					((data[offset + 2].toLong() and 0xFF) shl 16) or
					((data[offset + 3].toLong() and 0xFF) shl 24)
		}

		private fun eventTimeDeltaSec(prev: Int?, cur: Int): Double? {
			if (prev == null) {
				return null
			}
			val ticks = if (cur >= prev) cur - prev else cur + 65536 - prev
			if (ticks == 0) {
				return 0.0
			}
			return ticks / 1024.0
		}
	}
}
