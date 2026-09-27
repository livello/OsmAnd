package net.osmand.plus.plugins.voicegps

/**
 * Keyword matching on [android.speech.SpeechRecognizer] text (ru-RU).
 *
 * Energy tradeoff (documented in [VoiceGpsSpeechController]): we reuse the platform STT engine
 * in short listen windows instead of Porcupine/openWakeWord — those libraries are not in
 * OsmAnd deps. That avoids a second always-on model but wake-word hits depend on Google/offline
 * recognition quality and may false-trigger on similar phrases.
 */
object VoiceGpsKeywordMatcher {

	private val WAKE = listOf("османд", "osmand", "os mand", "озманд")
	private val NOTE_CMD = listOf("заметка", "zametka")
	private val END_CMD = listOf("конец", "konec")

	fun normalize(raw: CharSequence?): String {
		if (raw.isNullOrBlank()) {
			return ""
		}
		return raw.toString()
			.lowercase()
			.replace('ё', 'е')
			.replace(Regex("[^a-zа-я0-9\\s]"), " ")
			.replace(Regex("\\s+"), " ")
			.trim()
	}

	fun containsWakeWord(text: String): Boolean {
		val n = normalize(text)
		if (n.isEmpty()) {
			return false
		}
		return WAKE.any { matchesWakeToken(n, it) }
	}

	/** Avoid substring false positives (e.g. «росманд») on partial STT noise. */
	private fun matchesWakeToken(normalized: String, keyword: String): Boolean {
		if (normalized == keyword) {
			return true
		}
		return Regex("(^|\\s)$keyword(\\s|$)").containsMatchIn(normalized)
	}

	fun containsNoteCommand(text: String): Boolean {
		val n = normalize(text)
		return NOTE_CMD.any { tokenMatch(n, it) || n.contains(it) }
	}

	fun stripEndKeyword(text: String): String {
		var n = normalize(text)
		for (end in END_CMD) {
			val idx = n.lastIndexOf(end)
			if (idx >= 0) {
				n = (n.substring(0, idx) + n.substring(idx + end.length)).trim()
			}
		}
		return n.trim()
	}

	fun containsEndKeyword(text: String): Boolean {
		val n = normalize(text)
		return END_CMD.any { tokenMatch(n, it) || n.endsWith(it) || n.contains(" $it ") }
	}

	private fun tokenMatch(normalized: String, keyword: String): Boolean {
		if (normalized == keyword) {
			return true
		}
		return normalized.startsWith("$keyword ") || normalized.endsWith(" $keyword")
	}
}
