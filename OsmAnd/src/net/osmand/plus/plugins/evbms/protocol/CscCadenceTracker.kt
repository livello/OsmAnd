package net.osmand.plus.plugins.evbms.protocol

/**
 * BLE CSC (0x1816) crank revolutions → cadence, rpm.
 *
 * BK6LC and similar cadence-only sensors set flag bit 1 and omit wheel data.
 * Combined speed+cadence sensors put crank fields after the 6-byte wheel block.
 */
class CscCadenceTracker {

	@Volatile
	var rpm: Double? = null
		private set

	@Volatile
	var lastRxMs: Long = 0L
		private set

	@Volatile
	var hasData: Boolean = false
		private set

	private var lastRevs: Int? = null
	private var lastEventTime: Int? = null
	private var lastRevWallMs = 0L
	private var lastRevPeriodSec = 0.6
	private var notifyIntervalMs = 1_500L

	fun resetBaseline() {
		lastRevs = null
		lastEventTime = null
		lastRevWallMs = 0L
		rpm = null
	}

	fun ingest(payload: ByteArray): Boolean {
		val crank = parseCrank(payload) ?: run {
			if (payload.isNotEmpty()) {
				maybeCoastToZero(System.currentTimeMillis())
			}
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
		val revs = crank.first
		val eventTime = crank.second
		val prevRevs = lastRevs
		val prevTime = lastEventTime
		val prevWall = lastRevWallMs
		lastRevs = revs
		lastEventTime = eventTime
		hasData = true
		if (prevRevs == null) {
			lastRevWallMs = now
			rpm = 0.0
			return true
		}
		val dRevs = crankDelta(prevRevs, revs)
		if (dRevs > MAX_REVS_PER_NOTIFY) {
			return true
		}
		if (dRevs == 0) {
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
		lastRevWallMs = now
		if (dt != null && dt > 0.0) {
			lastRevPeriodSec = dt / dRevs.toDouble()
			val value = dRevs * 60.0 / dt
			if (value.isFinite() && value <= MAX_RPM) {
				rpm = value
			}
		}
		return true
	}

	private fun maybeCoastToZero(nowMs: Long) {
		if (lastRevWallMs <= 0L) {
			return
		}
		if (nowMs - lastRevWallMs > coastTimeoutMs()) {
			rpm = 0.0
		}
	}

	private fun coastTimeoutMs(): Long {
		val fromPeriod = (lastRevPeriodSec * 2_500.0).toLong()
		val fromNotify = notifyIntervalMs * 2L + 400L
		return maxOf(fromPeriod, fromNotify, MIN_COAST_MS).coerceAtMost(MAX_COAST_MS)
	}

	companion object {
		private const val MAX_REVS_PER_NOTIFY = 12
		private const val MIN_EVENT_SEC = 0.02
		private const val MAX_EVENT_SEC = 8.0
		private const val MAX_RPM = 240.0
		private const val MIN_COAST_MS = 1_500L
		private const val MAX_COAST_MS = 4_000L

		fun parseCrank(payload: ByteArray): Pair<Int, Int>? {
			if (payload.isEmpty()) {
				return null
			}
			val flags = payload[0].toInt() and 0xFF
			val crankPresent = flags and 0x02 != 0
			if (!crankPresent) {
				return null
			}
			var offset = 1
			if (flags and 0x01 != 0) {
				offset = 7
			}
			if (payload.size < offset + 4) {
				return null
			}
			val revs = u16le(payload, offset)
			val eventTime = u16le(payload, offset + 2)
			return Pair(revs, eventTime)
		}

		private fun crankDelta(prev: Int, cur: Int): Int {
			val d = cur - prev
			return if (d >= 0) d else d + 65536
		}

		private fun u16le(data: ByteArray, offset: Int): Int {
			return (data[offset].toInt() and 0xFF) or
					((data[offset + 1].toInt() and 0xFF) shl 8)
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
