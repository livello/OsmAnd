package net.osmand.plus.plugins.evbms.protocol

/**
 * JBD / Xiaoxiang Smart BMS UART/BLE protocol V4.
 *
 * Request:  DD A5 &lt;reg&gt; &lt;len&gt; &lt;checksum:2&gt; 77
 * Response: DD &lt;reg&gt; &lt;status&gt; &lt;len&gt; &lt;payload&gt; &lt;checksum:2&gt; 77
 */
object JbdBmsProtocol {

	const val START: Byte = 0xDD.toByte()
	const val END: Byte = 0x77
	const val READ: Byte = 0xA5.toByte()
	const val REG_BASIC: Byte = 0x03
	const val REG_CELLS: Byte = 0x04

	fun readCommand(register: Byte): ByteArray {
		val checksum = checksum(byteArrayOf(register, 0x00))
		return byteArrayOf(
			START, READ, register, 0x00,
			((checksum shr 8) and 0xFF).toByte(),
			(checksum and 0xFF).toByte(),
			END
		)
	}

	fun readBasicInfo(): ByteArray = readCommand(REG_BASIC)

	fun readCellVoltages(): ByteArray = readCommand(REG_CELLS)

	fun checksum(payload: ByteArray): Int {
		var sum = 0
		for (b in payload) {
			sum += b.toInt() and 0xFF
		}
		return (0x10000 - sum) and 0xFFFF
	}

	fun extractFrames(buffer: ByteArray): Pair<List<ByteArray>, ByteArray> {
		val frames = ArrayList<ByteArray>()
		var i = 0
		while (i < buffer.size) {
			if (buffer[i] != START) {
				i++
				continue
			}
			if (i + 4 >= buffer.size) {
				break
			}
			val len = buffer[i + 3].toInt() and 0xFF
			val frameLen = 4 + len + 3
			if (i + frameLen > buffer.size) {
				break
			}
			if (buffer[i + frameLen - 1] == END) {
				frames.add(buffer.copyOfRange(i, i + frameLen))
				i += frameLen
			} else {
				i++
			}
		}
		val remainder = if (i < buffer.size) buffer.copyOfRange(i, buffer.size) else ByteArray(0)
		return Pair(frames, remainder)
	}

	fun parseBasicInfo(frame: ByteArray): JbdBasicInfo? {
		if (frame.size < 7 || frame[0] != START || frame[1] != REG_BASIC) {
			return null
		}
		val status = frame[2].toInt() and 0xFF
		if (status != 0) {
			return null
		}
		val len = frame[3].toInt() and 0xFF
		if (frame.size < 4 + len + 3 || len < 23) {
			return null
		}
		val d = frame.copyOfRange(4, 4 + len)
		val voltageMv = u16(d, 0) * 10
		val currentMa = s16(d, 2) * 10
		val remainMah = u16(d, 4) * 10
		val fullMah = u16(d, 6) * 10
		val cycles = u16(d, 8)
		val soc = d[19].toInt() and 0xFF
		val fet = d[20].toInt() and 0xFF
		val cellCount = d[21].toInt() and 0xFF
		val ntcCount = d[22].toInt() and 0xFF
		val temps = ArrayList<Float>()
		var offset = 23
		for (n in 0 until ntcCount) {
			if (offset + 1 >= d.size) {
				break
			}
			val raw = u16(d, offset)
			temps.add((raw - 2731) / 10f)
			offset += 2
		}
		return JbdBasicInfo(
			voltageV = voltageMv / 1000.0,
			currentA = currentMa / 1000.0,
			remainingMah = remainMah,
			fullMah = fullMah,
			cycles = cycles,
			socPercent = soc,
			chargeEnabled = fet and 0x01 != 0,
			dischargeEnabled = fet and 0x02 != 0,
			cellCount = cellCount,
			temperaturesC = temps
		)
	}

	fun parseCellVoltages(frame: ByteArray): List<Double>? {
		if (frame.size < 7 || frame[0] != START || frame[1] != REG_CELLS) {
			return null
		}
		val status = frame[2].toInt() and 0xFF
		if (status != 0) {
			return null
		}
		val len = frame[3].toInt() and 0xFF
		if (frame.size < 4 + len + 3 || len < 2) {
			return null
		}
		val d = frame.copyOfRange(4, 4 + len)
		val cells = ArrayList<Double>(len / 2)
		var offset = 0
		while (offset + 1 < d.size) {
			cells.add(u16(d, offset) / 1000.0)
			offset += 2
		}
		return if (cells.isEmpty()) null else cells
	}

	private fun u16(d: ByteArray, offset: Int): Int {
		return ((d[offset].toInt() and 0xFF) shl 8) or (d[offset + 1].toInt() and 0xFF)
	}

	private fun s16(d: ByteArray, offset: Int): Int {
		val v = u16(d, offset)
		return if (v and 0x8000 != 0) v - 0x10000 else v
	}

	data class JbdBasicInfo(
		val voltageV: Double,
		val currentA: Double,
		val remainingMah: Int,
		val fullMah: Int,
		val cycles: Int,
		val socPercent: Int,
		val chargeEnabled: Boolean,
		val dischargeEnabled: Boolean,
		val cellCount: Int,
		val temperaturesC: List<Float>
	)
}
