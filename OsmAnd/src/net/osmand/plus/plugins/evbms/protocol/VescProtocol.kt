package net.osmand.plus.plugins.evbms.protocol

/**
 * VESC UART packet protocol (also used over Nordic UART BLE).
 *
 * Short frame: 02 | len8 | payload | crc16be | 03
 * Long frame:  03 | len16be | payload | crc16be | 03
 * CRC-16/XMODEM over payload. COMM_GET_VALUES = 4, COMM_FW_VERSION = 0,
 * COMM_GET_VALUES_SETUP = 47.
 */
object VescProtocol {

	const val COMM_FW_VERSION = 0
	const val COMM_GET_VALUES = 4
	const val COMM_GET_VALUES_SETUP = 47
	private const val MAX_PAYLOAD = 512

	fun getValues(): ByteArray = pack(byteArrayOf(COMM_GET_VALUES.toByte()))

	fun getValuesSetup(): ByteArray = pack(byteArrayOf(COMM_GET_VALUES_SETUP.toByte()))

	fun fwVersion(): ByteArray = pack(byteArrayOf(COMM_FW_VERSION.toByte()))

	fun looksLike(buffer: ByteArray): Boolean {
		return buffer.any { it == 0x02.toByte() || it == 0x03.toByte() }
	}

	fun extractFrames(buffer: ByteArray): Pair<List<ByteArray>, ByteArray> {
		val frames = ArrayList<ByteArray>()
		var i = 0
		while (i < buffer.size) {
			val start = buffer[i].toInt() and 0xFF
			if (start != 0x02 && start != 0x03) {
				i++
				continue
			}
			val len: Int
			val header: Int
			if (start == 0x02) {
				if (i + 2 > buffer.size) {
					break
				}
				len = buffer[i + 1].toInt() and 0xFF
				header = 2
			} else {
				if (i + 3 > buffer.size) {
					break
				}
				len = ((buffer[i + 1].toInt() and 0xFF) shl 8) or (buffer[i + 2].toInt() and 0xFF)
				header = 3
			}
			if (len <= 0 || len > MAX_PAYLOAD) {
				i++
				continue
			}
			val frameLen = header + len + 3
			if (i + frameLen > buffer.size) {
				break
			}
			if ((buffer[i + frameLen - 1].toInt() and 0xFF) != 0x03) {
				i++
				continue
			}
			val payloadStart = i + header
			val crc = ((buffer[i + header + len].toInt() and 0xFF) shl 8) or
					(buffer[i + header + len + 1].toInt() and 0xFF)
			if (crc16(buffer, payloadStart, len) == crc) {
				frames.add(buffer.copyOfRange(payloadStart, payloadStart + len))
				i += frameLen
			} else {
				i++
			}
		}
		val remainder = if (i < buffer.size) buffer.copyOfRange(i, buffer.size) else ByteArray(0)
		return Pair(frames, remainder)
	}

	fun parsePayload(payload: ByteArray, snapshot: VescSnapshot): Boolean {
		if (payload.isEmpty()) {
			return false
		}
		return when (payload[0].toInt() and 0xFF) {
			COMM_FW_VERSION -> parseFwVersion(payload, snapshot)
			COMM_GET_VALUES -> parseGetValues(payload, snapshot)
			COMM_GET_VALUES_SETUP -> parseGetValuesSetup(payload, snapshot)
			else -> false
		}
	}

	private fun parseFwVersion(payload: ByteArray, snapshot: VescSnapshot): Boolean {
		if (payload.size < 3) {
			return false
		}
		snapshot.fwMajor = payload[1].toInt() and 0xFF
		snapshot.fwMinor = payload[2].toInt() and 0xFF
		val name = StringBuilder()
		var i = 3
		while (i < payload.size && payload[i] != 0.toByte() && name.length < 48) {
			val ch = payload[i].toInt() and 0xFF
			if (ch in 32..126) {
				name.append(ch.toChar())
			}
			i++
		}
		if (name.isNotEmpty()) {
			snapshot.hwName = name.toString()
		}
		snapshot.updatedAtMs = System.currentTimeMillis()
		return true
	}

	private fun parseGetValues(payload: ByteArray, snapshot: VescSnapshot): Boolean {
		// Command byte + 53 bytes of classic fields.
		if (payload.size < 54) {
			return false
		}
		var o = 1
		snapshot.controllerTempC = i16be(payload, o) / 10.0
		o += 2
		snapshot.motorTempC = i16be(payload, o) / 10.0
		o += 2
		snapshot.currentMotorA = i32be(payload, o) / 100.0
		o += 4
		snapshot.currentInA = i32be(payload, o) / 100.0
		o += 4
		o += 8 // id, iq
		o += 2 // duty
		snapshot.rpm = i32be(payload, o)
		o += 4
		snapshot.voltageV = i16be(payload, o) / 10.0
		o += 2
		snapshot.ampHours = i32be(payload, o) / 10000.0
		o += 4
		o += 4 // ah charged
		snapshot.wattHours = i32be(payload, o) / 10000.0
		o += 4
		o += 4 // wh charged
		o += 4 // tachometer
		snapshot.tachometerAbs = i32be(payload, o)
		snapshot.updatedAtMs = System.currentTimeMillis()
		snapshot.refreshDerived()
		return true
	}

	private fun parseGetValuesSetup(payload: ByteArray, snapshot: VescSnapshot): Boolean {
		if (payload.size < 48) {
			return false
		}
		var o = 1
		snapshot.controllerTempC = i16be(payload, o) / 10.0
		o += 2
		snapshot.motorTempC = i16be(payload, o) / 10.0
		o += 2
		snapshot.currentInA = i32be(payload, o) / 100.0
		o += 4
		snapshot.currentMotorA = i32be(payload, o) / 100.0
		o += 4
		o += 2 // duty
		snapshot.rpm = i32be(payload, o)
		o += 4
		snapshot.speedMps = i32be(payload, o) / 1000.0
		o += 4
		snapshot.voltageV = i16be(payload, o) / 10.0
		o += 2
		if (o + 2 <= payload.size) {
			snapshot.batteryLevel = i16be(payload, o) / 1000.0
			o += 2
		}
		if (o + 16 <= payload.size) {
			snapshot.ampHours = i32be(payload, o) / 10000.0
			o += 4
			o += 4
			snapshot.wattHours = i32be(payload, o) / 10000.0
			o += 8
		}
		if (o + 8 <= payload.size) {
			snapshot.distanceM = i32be(payload, o) / 1000.0
			o += 4
			snapshot.distanceAbsM = i32be(payload, o) / 1000.0
		}
		snapshot.updatedAtMs = System.currentTimeMillis()
		snapshot.refreshDerived()
		return true
	}

	fun pack(payload: ByteArray): ByteArray {
		val crc = crc16(payload, 0, payload.size)
		val out = ByteArray(2 + payload.size + 3)
		out[0] = 0x02
		out[1] = payload.size.toByte()
		System.arraycopy(payload, 0, out, 2, payload.size)
		out[2 + payload.size] = ((crc shr 8) and 0xFF).toByte()
		out[3 + payload.size] = (crc and 0xFF).toByte()
		out[4 + payload.size] = 0x03
		return out
	}

	private fun crc16(data: ByteArray, start: Int, len: Int): Int {
		var crc = 0
		val end = start + len
		for (i in start until end) {
			crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
			repeat(8) {
				crc = if (crc and 0x8000 != 0) {
					((crc shl 1) xor 0x1021) and 0xFFFF
				} else {
					(crc shl 1) and 0xFFFF
				}
			}
		}
		return crc
	}

	private fun i16be(d: ByteArray, offset: Int): Int {
		val v = ((d[offset].toInt() and 0xFF) shl 8) or (d[offset + 1].toInt() and 0xFF)
		return if (v and 0x8000 != 0) v - 0x10000 else v
	}

	private fun i32be(d: ByteArray, offset: Int): Int {
		return (d[offset].toInt() shl 24) or
				((d[offset + 1].toInt() and 0xFF) shl 16) or
				((d[offset + 2].toInt() and 0xFF) shl 8) or
				(d[offset + 3].toInt() and 0xFF)
	}

	class VescSnapshot {
		@Volatile var voltageV: Double? = null
		@Volatile var currentInA: Double? = null
		@Volatile var currentMotorA: Double? = null
		@Volatile var rpm: Int? = null
		@Volatile var motorTempC: Double? = null
		@Volatile var controllerTempC: Double? = null
		@Volatile var speedMps: Double? = null
		@Volatile var batteryLevel: Double? = null
		@Volatile var ampHours: Double? = null
		@Volatile var wattHours: Double? = null
		@Volatile var tachometerAbs: Int? = null
		@Volatile var distanceM: Double? = null
		@Volatile var distanceAbsM: Double? = null
		@Volatile var hwName: String? = null
		@Volatile var fwMajor: Int? = null
		@Volatile var fwMinor: Int? = null
		@Volatile var odometerKm: Double? = null
		@Volatile var speedKmh: Double? = null
		@Volatile var avgPowerWhPerKm: Double? = null
		@Volatile var updatedAtMs: Long = 0L

		val powerW: Double?
			get() {
				val v = voltageV
				val i = currentInA
				return if (v != null && i != null) v * i else null
			}

		fun typeLabel(): String {
			val hw = hwName
			val fw = if (fwMajor != null && fwMinor != null) " $fwMajor.$fwMinor" else ""
			return if (!hw.isNullOrBlank()) "VESC $hw$fw" else "VESC$fw"
		}

		fun refreshDerived() {
			val meters = distanceAbsM ?: distanceM
			odometerKm = meters?.div(1000.0) ?: odometerKm
			speedKmh = speedMps?.times(3.6) ?: speedKmh
			val wh = wattHours
			val km = odometerKm
			avgPowerWhPerKm = if (wh != null && km != null && km > 0.05 && wh > 1.0) {
				wh / km
			} else {
				avgPowerWhPerKm
			}
		}

		fun reset() {
			voltageV = null
			currentInA = null
			currentMotorA = null
			rpm = null
			motorTempC = null
			controllerTempC = null
			speedMps = null
			batteryLevel = null
			ampHours = null
			wattHours = null
			tachometerAbs = null
			distanceM = null
			distanceAbsM = null
			hwName = null
			fwMajor = null
			fwMinor = null
			odometerKm = null
			speedKmh = null
			avgPowerWhPerKm = null
			updatedAtMs = 0L
		}
	}
}
