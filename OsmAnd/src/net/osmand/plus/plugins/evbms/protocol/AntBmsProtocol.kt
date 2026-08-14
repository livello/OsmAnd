package net.osmand.plus.plugins.evbms.protocol

/**
 * ANT BMS BLE protocol used by syssi/esphome-ant-bms.
 *
 * Request:  7E A1 01 00 00 BE 18 55 AA 55
 * Response: 7E A1 11 ... CRC16-MODBUS AA 55
 */
object AntBmsProtocol {

	const val START1: Byte = 0x7E
	const val START2: Byte = 0xA1.toByte()
	const val END1: Byte = 0xAA.toByte()
	const val END2: Byte = 0x55
	const val FUNC_STATUS: Byte = 0x11
	private const val MAX_FRAME = 512

	fun statusRequest(): ByteArray {
		val frame = byteArrayOf(
			START1, START2, 0x01, 0x00, 0x00, 0xBE.toByte(), 0x00, 0x00, END1, END2
		)
		val crc = crc16Modbus(frame, 1, 5)
		frame[6] = (crc and 0xFF).toByte()
		frame[7] = ((crc shr 8) and 0xFF).toByte()
		return frame
	}

	fun extractFrames(buffer: ByteArray): Pair<List<ByteArray>, ByteArray> {
		val frames = ArrayList<ByteArray>()
		var i = 0
		while (i < buffer.size) {
			if (i + 1 >= buffer.size) {
				break
			}
			if (buffer[i] != START1 || buffer[i + 1] != START2) {
				i++
				continue
			}
			if (i + 6 > buffer.size) {
				break
			}
			val dataLen = buffer[i + 5].toInt() and 0xFF
			val frameLen = 6 + dataLen + 4
			if (frameLen < 10 || frameLen > MAX_FRAME) {
				i++
				continue
			}
			if (i + frameLen > buffer.size) {
				break
			}
			val frame = buffer.copyOfRange(i, i + frameLen)
			if (frame[frameLen - 2] == END1 && frame[frameLen - 1] == END2 && verifyCrc(frame)) {
				frames.add(frame)
				i += frameLen
			} else {
				i++
			}
		}
		val remainder = if (i < buffer.size) buffer.copyOfRange(i, buffer.size) else ByteArray(0)
		return Pair(frames, remainder)
	}

	fun parseStatus(frame: ByteArray): BmsSnapshot? {
		if (frame.size < 10 || frame[0] != START1 || frame[1] != START2 || frame[2] != FUNC_STATUS) {
			return null
		}
		val dataLen = frame[5].toInt() and 0xFF
		if (frame.size < 6 + dataLen + 4) {
			return null
		}
		val tempSensors = frame[8].toInt() and 0xFF
		val cellCount = frame[9].toInt() and 0xFF
		if (cellCount <= 0 || cellCount > 32 || tempSensors > 8) {
			return null
		}
		val cells = ArrayList<Double>(cellCount)
		for (n in 0 until cellCount) {
			val off = 34 + n * 2
			if (off + 1 >= frame.size) {
				return null
			}
			cells.add(u16le(frame, off) / 1000.0)
		}
		var offset = cellCount * 2
		val temps = ArrayList<Float>()
		for (n in 0 until tempSensors) {
			val off = 34 + offset + n * 2
			if (off + 1 >= frame.size) {
				break
			}
			addTemp(temps, s16le(frame, off).toFloat())
		}
		offset += tempSensors * 2
		if (34 + offset + 1 < frame.size) {
			addTemp(temps, s16le(frame, 34 + offset).toFloat())
		}
		if (36 + offset + 1 < frame.size) {
			addTemp(temps, s16le(frame, 36 + offset).toFloat())
		}
		val voltOff = 38 + offset
		if (voltOff + 7 >= frame.size) {
			return null
		}
		val voltageV = u16le(frame, voltOff) * 0.01
		val currentA = s16le(frame, 40 + offset) * 0.1
		val soc = u16le(frame, 42 + offset).coerceIn(0, 100)
		val chargeMos = frame.getOrNull(46 + offset)?.toInt()?.and(0xFF)
		val dischargeMos = frame.getOrNull(47 + offset)?.toInt()?.and(0xFF)
		if (50 + offset + 7 >= frame.size) {
			return null
		}
		val fullAh = u32le(frame, 50 + offset) * 0.000001
		val remainAh = u32le(frame, 54 + offset) * 0.000001
		return BmsSnapshot(
			voltageV = voltageV,
			currentA = currentA,
			remainingMah = (remainAh * 1000.0).toInt(),
			fullMah = (fullAh * 1000.0).toInt(),
			cycles = 0,
			socPercent = soc,
			chargeEnabled = chargeMos == 0x01,
			dischargeEnabled = dischargeMos == 0x01,
			cellCount = cellCount,
			temperaturesC = temps,
			cells = cells
		)
	}

	fun looksLike(buffer: ByteArray): Boolean {
		var i = 0
		while (i + 1 < buffer.size) {
			if (buffer[i] == START1 && buffer[i + 1] == START2) {
				return true
			}
			i++
		}
		return false
	}

	private fun addTemp(temps: MutableList<Float>, value: Float) {
		if (value in -40f..120f) {
			temps.add(value)
		}
	}

	private fun verifyCrc(frame: ByteArray): Boolean {
		val computed = crc16Modbus(frame, 1, frame.size - 5)
		val remote = (frame[frame.size - 4].toInt() and 0xFF) or
				((frame[frame.size - 3].toInt() and 0xFF) shl 8)
		return computed == remote
	}

	private fun crc16Modbus(data: ByteArray, start: Int, length: Int): Int {
		var crc = 0xFFFF
		val end = (start + length).coerceAtMost(data.size)
		for (i in start until end) {
			crc = crc xor (data[i].toInt() and 0xFF)
			repeat(8) {
				crc = if (crc and 1 != 0) {
					(crc shr 1) xor 0xA001
				} else {
					crc shr 1
				}
			}
		}
		return crc and 0xFFFF
	}

	private fun u16le(d: ByteArray, offset: Int): Int {
		return (d[offset].toInt() and 0xFF) or ((d[offset + 1].toInt() and 0xFF) shl 8)
	}

	private fun s16le(d: ByteArray, offset: Int): Int {
		val v = u16le(d, offset)
		return if (v and 0x8000 != 0) v - 0x10000 else v
	}

	private fun u32le(d: ByteArray, offset: Int): Long {
		return (d[offset].toLong() and 0xFF) or
				((d[offset + 1].toLong() and 0xFF) shl 8) or
				((d[offset + 2].toLong() and 0xFF) shl 16) or
				((d[offset + 3].toLong() and 0xFF) shl 24)
	}
}
