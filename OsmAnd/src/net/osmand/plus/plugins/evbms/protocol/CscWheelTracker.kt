package net.osmand.plus.plugins.evbms.protocol

/**
 * BLE Cycling Speed and Cadence (0x1816) wheel revolutions → speed and distance.
 * Circumference is the nominal tire size; [calFactor] is GPS / wheel from auto-calibration.
 */
class CscWheelTracker {

	@Volatile
	var speedKmh: Double? = null
		private set

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
		lastRevs = null
		lastEventTime = null
		speedKmh = null
		hasWheelData = false
	}

	fun resetBaseline() {
		lastRevs = null
		lastEventTime = null
		speedKmh = null
	}

	fun rawMetersSince(startRawM: Double): Double = (rawM - startRawM).coerceAtLeast(0.0)

	fun rawMeters(): Double = rawM

	fun takePersistDeltaKm(): Double? {
		if (persistDueM < 50.0) {
			return null
		}
		val km = persistDueM / 1000.0
		persistDueM = 0.0
		return km
	}

	fun ingest(payload: ByteArray): Boolean {
		if (payload.isEmpty()) {
			return false
		}
		val flags = payload[0].toInt() and 0xFF
		val wheelPresent = flags and 0x01 != 0
		if (!wheelPresent || payload.size < 7) {
			return false
		}
		val revs = u32le(payload, 1)
		val eventTime = u16le(payload, 5)
		val prevRevs = lastRevs
		val prevTime = lastEventTime
		lastRevs = revs
		lastEventTime = eventTime
		lastRxMs = System.currentTimeMillis()
		hasWheelData = true
		if (prevRevs == null || prevTime == null) {
			return true
		}
		if (revs + 8L < prevRevs) {
			speedKmh = 0.0
			return true
		}
		val dRevs = (revs - prevRevs).coerceAtLeast(0L)
		if (dRevs > MAX_REVS_PER_NOTIFY) {
			return true
		}
		val dTime = eventTimeDeltaSec(prevTime, eventTime)
		val rawDeltaM = dRevs * circumferenceM
		rawM += rawDeltaM
		val calDeltaM = rawDeltaM * calFactor
		calibratedM += calDeltaM
		persistDueM += calDeltaM
		tripKm += calDeltaM / 1000.0
		odometerKm = calibratedM / 1000.0
		if (dTime != null && dTime in MIN_EVENT_SEC..MAX_EVENT_SEC && rawDeltaM > 0.0) {
			val kmh = (calDeltaM / dTime) * 3.6
			if (kmh.isFinite() && kmh <= MAX_KMH) {
				speedKmh = kmh
			}
		} else if (dTime != null && dTime > 0.0 && dRevs == 0L) {
			speedKmh = 0.0
		}
		return true
	}

	companion object {
		const val DEFAULT_CIRCUMFERENCE_M = 2.0
		const val DEFAULT_CIRCUMFERENCE_MM = 2000
		private const val MAX_REVS_PER_NOTIFY = 80L
		private const val MIN_EVENT_SEC = 0.05
		private const val MAX_EVENT_SEC = 8.0
		private const val MAX_KMH = 160.0

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

		private fun eventTimeDeltaSec(prev: Int, cur: Int): Double? {
			val ticks = if (cur >= prev) cur - prev else cur + 65536 - prev
			if (ticks == 0) {
				return 0.0
			}
			return ticks / 1024.0
		}
	}
}
