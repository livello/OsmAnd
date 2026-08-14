package net.osmand.plus.plugins.evbms.protocol

/**
 * Nanjing FarDriver ND-series serial/BLE protocol.
 * Status frames are 16 bytes: AA | id:6+flags:2 | data[12] | crc[2]
 *
 * Address map and CRC tables from jackhumbert/fardriver-controllers.
 */
object FarDriverProtocol {

	const val MAGIC: Byte = 0xAA.toByte()
	const val FRAME_LEN = 16

	private val FLASH_READ_ADDR = intArrayOf(
		0xE2, 0xE8, 0xEE, 0x00, 0x06, 0x0C, 0x12,
		0xE2, 0xE8, 0xEE, 0x18, 0x1E, 0x24, 0x2A,
		0xE2, 0xE8, 0xEE, 0x30, 0x5D, 0x63, 0x69,
		0xE2, 0xE8, 0xEE, 0x7C, 0x82, 0x88, 0x8E,
		0xE2, 0xE8, 0xEE, 0x94, 0x9A, 0xA0, 0xA6,
		0xE2, 0xE8, 0xEE, 0xAC, 0xB2, 0xB8, 0xBE,
		0xE2, 0xE8, 0xEE, 0xC4, 0xCA, 0xD0,
		0xE2, 0xE8, 0xEE, 0xD6, 0xDC, 0xF4, 0xFA
	)

	private val CRC_LO = intArrayOf(
		0, 192, 193, 1, 195, 3, 2, 194, 198, 6, 7, 199, 5, 197, 196, 4, 204, 12, 13, 205, 15, 207, 206, 14, 10, 202, 203, 11, 201, 9, 8, 200, 216, 24, 25, 217, 27, 219, 218, 26, 30, 222, 223, 31, 221, 29, 28, 220, 20, 212, 213, 21, 215, 23, 22, 214, 210, 18, 19, 211, 17, 209, 208, 16, 240, 48, 49, 241, 51, 243, 242, 50, 54, 246, 247, 55, 245, 53, 52, 244, 60, 252, 253, 61, 255, 63, 62, 254, 250, 58, 59, 251, 57, 249, 248, 56, 40, 232, 233, 41, 235, 43, 42, 234, 238, 46, 47, 239, 45, 237, 236, 44, 228, 36, 37, 229, 39, 231, 230, 38, 34, 226, 227, 35, 225, 33, 32, 224, 160, 96, 97, 161, 99, 163, 162, 98, 102, 166, 167, 103, 165, 101, 100, 164, 108, 172, 173, 109, 175, 111, 110, 174, 170, 106, 107, 171, 105, 169, 168, 104, 120, 184, 185, 121, 187, 123, 122, 186, 190, 126, 127, 191, 125, 189, 188, 124, 180, 116, 117, 181, 119, 183, 182, 118, 114, 178, 179, 115, 177, 113, 112, 176, 80, 144, 145, 81, 147, 83, 82, 146, 150, 86, 87, 151, 85, 149, 148, 84, 156, 92, 93, 157, 95, 159, 158, 94, 90, 154, 155, 91, 153, 89, 88, 152, 136, 72, 73, 137, 75, 139, 138, 74, 78, 142, 143, 79, 141, 77, 76, 140, 68, 132, 133, 69, 135, 71, 70, 134, 130, 66, 67, 131, 65, 129, 128, 64
	)

	private val CRC_HI = intArrayOf(
		0, 193, 129, 64, 1, 192, 128, 65, 1, 192, 128, 65, 0, 193, 129, 64, 1, 192, 128, 65, 0, 193, 129, 64, 0, 193, 129, 64, 1, 192, 128, 65, 1, 192, 128, 65, 0, 193, 129, 64, 0, 193, 129, 64, 1, 192, 128, 65, 0, 193, 129, 64, 1, 192, 128, 65, 1, 192, 128, 65, 0, 193, 129, 64, 1, 192, 128, 65, 0, 193, 129, 64, 0, 193, 129, 64, 1, 192, 128, 65, 0, 193, 129, 64, 1, 192, 128, 65, 1, 192, 128, 65, 0, 193, 129, 64, 0, 193, 129, 64, 1, 192, 128, 65, 1, 192, 128, 65, 0, 193, 129, 64, 1, 192, 128, 65, 0, 193, 129, 64, 0, 193, 129, 64, 1, 192, 128, 65, 1, 192, 128, 65, 0, 193, 129, 64, 0, 193, 129, 64, 1, 192, 128, 65, 0, 193, 129, 64, 1, 192, 128, 65, 1, 192, 128, 65, 0, 193, 129, 64, 0, 193, 129, 64, 1, 192, 128, 65, 1, 192, 128, 65, 0, 193, 129, 64, 1, 192, 128, 65, 0, 193, 129, 64, 0, 193, 129, 64, 1, 192, 128, 65, 0, 193, 129, 64, 1, 192, 128, 65, 1, 192, 128, 65, 0, 193, 129, 64, 1, 192, 128, 65, 0, 193, 129, 64, 0, 193, 129, 64, 1, 192, 128, 65, 1, 192, 128, 65, 0, 193, 129, 64, 0, 193, 129, 64, 1, 192, 128, 65, 0, 193, 129, 64, 1, 192, 128, 65, 1, 192, 128, 65, 0, 193, 129, 64
	)

	fun startStatusCommand(): ByteArray {
		val cmd = 0x13
		val frame = byteArrayOf(
			MAGIC, cmd.toByte(), cmd.inv().toByte(), 0x07, 0x00, 0x00, 0x00, 0x00
		)
		var crc = 0
		for (i in 0..5) {
			crc += frame[i].toInt() and 0xFF
		}
		frame[6] = crc.toByte()
		frame[7] = crc.inv().toByte()
		return frame
	}

	fun extractFrames(buffer: ByteArray): Pair<List<ByteArray>, ByteArray> {
		val frames = ArrayList<ByteArray>()
		var i = 0
		while (i + FRAME_LEN <= buffer.size) {
			if (buffer[i] != MAGIC) {
				i++
				continue
			}
			val frame = buffer.copyOfRange(i, i + FRAME_LEN)
			if (verifyCrc(frame)) {
				frames.add(frame)
				i += FRAME_LEN
			} else {
				i++
			}
		}
		val remainder = if (i < buffer.size) buffer.copyOfRange(i, buffer.size) else ByteArray(0)
		return Pair(frames, remainder)
	}

	fun verifyCrc(frame: ByteArray): Boolean {
		if (frame.size != FRAME_LEN) {
			return false
		}
		var a = 0x3C
		var b = 0x7F
		for (i in 0 until FRAME_LEN - 2) {
			val idx = (a xor (frame[i].toInt() and 0xFF)) and 0xFF
			a = (b xor CRC_HI[idx]) and 0xFF
			b = CRC_LO[idx] and 0xFF
		}
		return (frame[14].toInt() and 0xFF) == a && (frame[15].toInt() and 0xFF) == b
	}

	fun parseFrame(frame: ByteArray, snapshot: FarDriverSnapshot): Boolean {
		if (!verifyCrc(frame)) {
			return false
		}
		val id = frame[1].toInt() and 0x3F
		if (id >= FLASH_READ_ADDR.size) {
			return false
		}
		val address = FLASH_READ_ADDR[id]
		val data = frame.copyOfRange(2, 14)
		when (address) {
			0xE2 -> {
				snapshot.gear = ((data[0].toInt() shr 2) and 0x03) + 1
				snapshot.rawRpm = s16le(data, 6)
				snapshot.brake = data[3].toInt() and 0x80 != 0
			}
			0xE8 -> {
				snapshot.voltageV = u16le(data, 0) / 10.0
				snapshot.lineCurrentA = s16le(data, 4) / 4.0
			}
			0xD6 -> {
				snapshot.controllerTempC = s16le(data, 10).toDouble()
			}
			0xF4 -> {
				snapshot.motorTempC = s16le(data, 0).toDouble()
				snapshot.controllerSoc = data[3].toInt() and 0xFF
			}
		}
		snapshot.updatedAtMs = System.currentTimeMillis()
		return true
	}

	private fun u16le(d: ByteArray, offset: Int): Int {
		return (d[offset].toInt() and 0xFF) or ((d[offset + 1].toInt() and 0xFF) shl 8)
	}

	private fun s16le(d: ByteArray, offset: Int): Int {
		val v = u16le(d, offset)
		return if (v and 0x8000 != 0) v - 0x10000 else v
	}

	class FarDriverSnapshot {
		@Volatile var voltageV: Double? = null
		@Volatile var lineCurrentA: Double? = null
		@Volatile var rawRpm: Int? = null
		@Volatile var gear: Int? = null
		@Volatile var motorTempC: Double? = null
		@Volatile var controllerTempC: Double? = null
		@Volatile var controllerSoc: Int? = null
		@Volatile var brake: Boolean = false
		@Volatile var updatedAtMs: Long = 0L

		val powerW: Double?
			get() {
				val v = voltageV
				val i = lineCurrentA
				return if (v != null && i != null) v * i else null
			}
	}
}
