package net.osmand.plus.plugins.loraosmand

import kotlin.math.abs

/**
 * Decides whether an incoming channel line is a copy of a message this phone just sent.
 * Packet id and TAK message id win. Otherwise a line is the same send only when it is
 * from us, the text matches, and its own timestamp is within [WINDOW_MS] of that send.
 * Each send absorbs the copies closest to it, so two intentional sends of the same text
 * stay as two rows when their timestamps differ.
 */
internal object LoraChatDedupe {
	const val WINDOW_MS = 30_000L

	class Slot(
		val channel: Int,
		val text: String,
		val timeMs: Long,
		val packetId: Int?,
		var messageId: String?,
		var consumed: Boolean,
	)

	fun match(
		slots: List<Slot>,
		sender: String,
		text: String,
		channel: Int,
		timeMs: Long,
		nowMs: Long,
		packetId: Int?,
		messageId: String?,
		fromSelf: Boolean,
	): Slot? {
		val body = canon(text)
		if (body.isEmpty()) return null
		val id = packetId?.takeIf { it != 0 }
		if (id != null) {
			slots.find { it.packetId == id }?.let { return it }
		}
		val mid = messageId?.takeIf { it.isNotBlank() }
		if (mid != null) {
			slots.find { it.messageId == mid }?.let { return it }
		}
		if (!fromSelf) return null
		val stamp = messageStamp(timeMs, nowMs)
		val candidates = slots.filter { slot ->
			sameText(slot.text, body, sender) &&
				abs(slot.timeMs - stamp) <= WINDOW_MS &&
				packetIdsCompatible(slot.packetId, id) &&
				messageIdsCompatible(slot.messageId, mid)
		}
		if (candidates.isEmpty()) return null
		val sameChannel = candidates.filter { it.channel == channel }
		val pool = if (sameChannel.isNotEmpty()) sameChannel else candidates
		return pool.minWith(Comparator { left, right ->
			val byTime = abs(left.timeMs - stamp).compareTo(abs(right.timeMs - stamp))
			if (byTime != 0) byTime else left.consumed.compareTo(right.consumed)
		})
	}

	fun sameText(sent: String, incoming: String, sender: String): Boolean {
		val body = canon(incoming)
		val original = canon(sent)
		if (original == body) return true
		val name = sender.trim()
		if (name.isEmpty()) return false
		val prefixed = canon(name) + ":"
		if (body.startsWith(prefixed, ignoreCase = true)) {
			return canon(body.substring(prefixed.length)) == original
		}
		return false
	}

	fun selfCheck(): List<String> {
		val errors = ArrayList<String>()
		val first = Slot(0, "ok", 1_000L, 10, null, false)
		val second = Slot(0, "ok", 10_000L, 11, null, false)
		val slots = listOf(first, second)
		val byId = match(slots, "Other", "different", 0, 9_000L, 9_000L, 11, null, false)
		if (byId !== second) errors.add("packet id must select that send")
		val other = match(slots, "Other", "ok", 0, 1_200L, 1_200L, 99, null, false)
		if (other != null) errors.add("another node with the same text must be kept")
		val clash = match(slots, "Me", "ok", 0, 1_200L, 1_200L, 99, null, true)
		if (clash != null) errors.add("a different packet id is a different message")
		val echo = match(slots, "Я", "ok", 0, 1_400L, 50_000L, null, null, true)
		if (echo !== first) errors.add("self echo must pair with the closest send")
		echo?.consumed = true
		val replay = match(slots, "Я", "Я: ok", 0, 1_500L, 80_000L, null, null, true)
		if (replay !== first) errors.add("a second copy must not take the other send")
		if (second.consumed) errors.add("the later send must stay open")
		val again = match(slots, "Я", "ok", 0, 10_200L, 10_200L, null, null, true)
		if (again !== second) errors.add("the second send must keep its own echo")
		val late = match(slots, "Я", "ok", 0, 50_000L, 50_000L, null, null, true)
		if (late != null) errors.add("a later repeat from us must be kept")
		val tagged = Slot(2, "ping", 5_000L, null, "abc", false)
		val byMessage = match(listOf(tagged), "mesh", "nope", 2, 5_000L, 5_000L, null, "abc", false)
		if (byMessage !== tagged) errors.add("TAK message id must match")
		val otherChannel = Slot(3, "ping", 4_000L, null, null, false)
		val preferred = match(listOf(tagged, otherChannel), "Я", "ping", 3, 4_100L, 4_100L, null, null, true)
		if (preferred !== otherChannel) errors.add("same channel must win when both are close")
		return errors
	}

	private fun messageStamp(timeMs: Long, nowMs: Long): Long {
		if (timeMs <= 0L) return nowMs
		// Unix seconds are 1e9..1e10. Millisecond stamps from this phone are larger.
		if (timeMs in 1_000_000_000L until 10_000_000_000L) return timeMs * 1000L
		return timeMs
	}

	private fun packetIdsCompatible(slotId: Int?, incoming: Int?): Boolean {
		if (slotId == null || slotId == 0 || incoming == null) return true
		return slotId == incoming
	}

	private fun messageIdsCompatible(slotId: String?, incoming: String?): Boolean {
		if (slotId.isNullOrBlank() || incoming.isNullOrBlank()) return true
		return slotId == incoming
	}

	private fun canon(text: String): String = text.trim().replace(Regex("\\s+"), " ")
}
