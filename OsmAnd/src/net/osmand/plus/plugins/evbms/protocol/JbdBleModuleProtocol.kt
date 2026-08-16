package net.osmand.plus.plugins.evbms.protocol

/**
 * Xiaoxiang BLE-dongle framing (not the JBD UART [DD A5] protocol).
 *
 * Request/response: FF AA &lt;cmd&gt; &lt;len&gt; &lt;payload&gt; &lt;sum(cmd+len+payload) &amp; 0xFF&gt;
 */
object JbdBleModuleProtocol {

	const val SOF0: Byte = 0xFF.toByte()
	const val SOF1: Byte = 0xAA.toByte()
	const val CMD_RANDOM: Int = 0x17
	const val CMD_VERIFY: Int = 0x18
	const val CMD_VERIFY_SECONDARY: Int = 0x1B

	fun randomRequest(): ByteArray = frame(CMD_RANDOM, ByteArray(0))

	fun verifyPassword(
		mac: String?,
		password: String,
		random: Int,
		newAppKey: Boolean
	): ByteArray? {
		val macBytes = parseMac(mac) ?: return null
		val pwd = ByteArray(6)
		for (i in 0 until 6) {
			pwd[i] = password[i].code.toByte()
		}
		val coded = ByteArray(6)
		for (i in 0 until 6) {
			val mixed = ((macBytes[i].toInt() and 0xFF) xor (pwd[i].toInt() and 0xFF)) + random
			coded[i] = mixed.toByte()
		}
		val payload = if (newAppKey) coded + byteArrayOf(random.toByte()) else coded
		return frame(CMD_VERIFY, payload)
	}

	fun frame(cmd: Int, payload: ByteArray): ByteArray {
		val len = payload.size
		var sum = cmd + len
		for (b in payload) {
			sum += b.toInt() and 0xFF
		}
		return byteArrayOf(
			SOF0, SOF1, cmd.toByte(), len.toByte()
		) + payload + byteArrayOf((sum and 0xFF).toByte())
	}

	fun extractFrames(buffer: ByteArray): Pair<List<ByteArray>, ByteArray> {
		val frames = ArrayList<ByteArray>()
		var i = 0
		while (i < buffer.size) {
			if (i + 1 < buffer.size && buffer[i] == SOF0 && buffer[i + 1] == SOF1) {
				if (i + 4 > buffer.size) {
					break
				}
				val len = buffer[i + 3].toInt() and 0xFF
				val frameLen = 5 + len
				if (i + frameLen > buffer.size) {
					break
				}
				frames.add(buffer.copyOfRange(i, i + frameLen))
				i += frameLen
				continue
			}
			if (buffer[i] == JbdBmsProtocol.START || buffer[i] == AntBmsProtocol.START1) {
				break
			}
			i++
		}
		val remainder = if (i < buffer.size) buffer.copyOfRange(i, buffer.size) else ByteArray(0)
		return Pair(frames, remainder)
	}

	fun parse(frame: ByteArray): Frame? {
		if (frame.size < 5 || frame[0] != SOF0 || frame[1] != SOF1) {
			return null
		}
		val cmd = frame[2].toInt() and 0xFF
		val len = frame[3].toInt() and 0xFF
		if (frame.size < 5 + len) {
			return null
		}
		val payload = if (len == 0) ByteArray(0) else frame.copyOfRange(4, 4 + len)
		val cs = frame[4 + len].toInt() and 0xFF
		var sum = cmd + len
		for (b in payload) {
			sum += b.toInt() and 0xFF
		}
		if ((sum and 0xFF) != cs) {
			return null
		}
		return Frame(cmd, payload)
	}

	fun randomFrom(frame: Frame): Int? {
		if (frame.cmd != CMD_RANDOM || frame.payload.isEmpty()) {
			return null
		}
		return frame.payload.last().toInt() and 0xFF
	}

	fun verifyAccepted(frame: Frame): Boolean {
		if (frame.cmd != CMD_VERIFY && frame.cmd != CMD_VERIFY_SECONDARY) {
			return false
		}
		if (frame.payload.isEmpty()) {
			return false
		}
		return (frame.payload.last().toInt() and 0xFF) == 0
	}

	fun parseMac(mac: String?): ByteArray? {
		val parts = mac?.split(':') ?: return null
		if (parts.size != 6) {
			return null
		}
		return try {
			ByteArray(6) { parts[it].toInt(16).toByte() }
		} catch (_: NumberFormatException) {
			null
		}
	}

	data class Frame(val cmd: Int, val payload: ByteArray)
}
