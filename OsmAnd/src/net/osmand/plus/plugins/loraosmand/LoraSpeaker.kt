package net.osmand.plus.plugins.loraosmand

import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import net.osmand.plus.OsmandApplication
import net.osmand.plus.voice.GoogleNeuralTts
import java.util.Locale

/** Speaks mesh text with the downloaded Google neural voice, offline. */
internal class LoraSpeaker(private val app: OsmandApplication) {
	private var engine: TextToSpeech? = null
	private var ready = false
	private var usedDefault = false
	private val waiting = ArrayDeque<String>()

	private val listener = TextToSpeech.OnInitListener { status ->
		val created = engine ?: return@OnInitListener
		if (status != TextToSpeech.SUCCESS) {
			created.shutdown()
			engine = null
			ready = false
			if (!usedDefault) {
				usedDefault = true
				open(null)
			}
			return@OnInitListener
		}
		created.setAudioAttributes(
			AudioAttributes.Builder()
				.setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
				.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
				.build(),
		)
		GoogleNeuralTts.applyOfflineVoice(created, RUSSIAN)
		ready = true
		while (waiting.isNotEmpty()) {
			say(created, waiting.removeFirst())
		}
	}

	fun speak(sender: String, text: String) {
		speakPhrase("$sender. $text")
	}

	fun speakPhrase(phrase: String) {
		val spoken = phrase.trim()
		if (spoken.isEmpty()) {
			return
		}
		val current = engine
		if (current != null && ready) {
			say(current, spoken)
			return
		}
		waiting.add(spoken)
		if (current == null) {
			val google = !usedDefault && GoogleNeuralTts.isInstalled(app)
			open(if (google) GoogleNeuralTts.ENGINE else null)
		}
	}

	fun shutdown() {
		ready = false
		waiting.clear()
		engine?.shutdown()
		engine = null
	}

	private fun open(packageName: String?) {
		engine = if (packageName == null) {
			TextToSpeech(app, listener)
		} else {
			TextToSpeech(app, listener, packageName)
		}
	}

	private fun say(tts: TextToSpeech, phrase: String) {
		tts.speak(phrase, TextToSpeech.QUEUE_ADD, Bundle(), "lora-${System.nanoTime()}")
	}

	companion object {
		private val RUSSIAN = Locale("ru", "RU")
	}
}
