package net.osmand.plus.voice

import android.content.Context
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * Google's on-device speech engine. Neural voices that are already downloaded
 * report themselves as not requiring a network; those are the ones OsmAnd uses.
 */
object GoogleNeuralTts {
	const val ENGINE = "com.google.android.tts"

	@JvmStatic
	fun isInstalled(context: Context): Boolean {
		return try {
			context.packageManager.getPackageInfo(ENGINE, 0)
			true
		} catch (_: PackageManager.NameNotFoundException) {
			false
		}
	}

	@JvmStatic
	fun applyOfflineVoice(tts: TextToSpeech, locale: Locale) {
		val voices = tts.voices ?: return
		val language = locale.language
		val offline = voices.filter { voice ->
			voice.locale.language.equals(language, ignoreCase = true) &&
				!voice.isNetworkConnectionRequired &&
				voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
		}
		val best = offline.maxWithOrNull(compareBy<Voice>({ it.quality }, { neuralRank(it.name) })) ?: return
		tts.voice = best
	}

	private fun neuralRank(name: String): Int {
		val lower = name.lowercase(Locale.US)
		return when {
			"neural" in lower -> 3
			"local" in lower -> 2
			else -> 1
		}
	}
}
