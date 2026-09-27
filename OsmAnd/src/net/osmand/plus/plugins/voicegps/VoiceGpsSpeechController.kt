package net.osmand.plus.plugins.voicegps

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R

/**
 * Two-stage mic usage for battery: short wake-word listen cycles, then full partial-result
 * dictation only after «заметка». No Porcupine/openWakeWord in the project — platform STT only.
 */
class VoiceGpsSpeechController(
	private val app: OsmandApplication,
	private val plugin: VoiceGpsPlugin,
) {

	enum class Mode {
		WAKE_LISTEN,
		AWAIT_COMMAND,
		DICTATE,
	}

	private val mainHandler = Handler(Looper.getMainLooper())
	private val noteWriter = VoiceGpsNoteWriter(app)
	private var speechRecognizer: SpeechRecognizer? = null
	private var mode = Mode.WAKE_LISTEN
	private var running = false
	private var noteLat: Double? = null
	private var noteLon: Double? = null
	private var noteStartedMs = 0L
	private val dictationFinal = StringBuilder()
	private var lastPartial = ""

	private val restartRunnable = Runnable { startListenCycle() }
	private val listenTimeoutRunnable = Runnable { onListenWindowElapsed() }

	companion object {
		private const val TAG = "VoiceGpsSpeech"
	}

	fun start() {
		mainHandler.post {
			if (running) {
				return@post
			}
			if (!SpeechRecognizer.isRecognitionAvailable(app)) {
				Log.w(TAG, "Speech recognition not available")
				return@post
			}
			running = true
			mode = Mode.WAKE_LISTEN
			ensureRecognizer()
			scheduleNextCycle(300L)
		}
	}

	fun stop() {
		mainHandler.post {
			running = false
			mainHandler.removeCallbacks(restartRunnable)
			mainHandler.removeCallbacks(listenTimeoutRunnable)
			destroyRecognizer()
			resetNoteSession()
		}
	}

	private fun ensureRecognizer() {
		if (speechRecognizer != null) {
			return
		}
		speechRecognizer = SpeechRecognizer.createSpeechRecognizer(app).also {
			it.setRecognitionListener(recognitionListener)
		}
	}

	private fun destroyRecognizer() {
		try {
			speechRecognizer?.destroy()
		} catch (_: Exception) {
		}
		speechRecognizer = null
	}

	private fun scheduleNextCycle(delayMs: Long) {
		if (!running) {
			return
		}
		mainHandler.removeCallbacks(restartRunnable)
		mainHandler.postDelayed(restartRunnable, delayMs)
	}

	private fun armListenTimeout() {
		if (!running) {
			return
		}
		mainHandler.removeCallbacks(listenTimeoutRunnable)
		val windowMs = when (mode) {
			Mode.DICTATE -> plugin.dictationListenWindowMs()
			Mode.AWAIT_COMMAND -> plugin.commandListenWindowMs()
			Mode.WAKE_LISTEN -> plugin.listenWindowMs()
		}
		mainHandler.postDelayed(listenTimeoutRunnable, windowMs)
	}

	private fun onListenWindowElapsed() {
		if (!running) {
			return
		}
		when (mode) {
			Mode.DICTATE -> {
				appendPartialToDictation()
				Log.d(TAG, "dictation listen window elapsed — restarting STT")
				try {
					speechRecognizer?.cancel()
				} catch (_: Exception) {
				}
				scheduleNextCycle(150L)
			}
			else -> stopActiveSession("listen_timeout")
		}
	}

	private fun startListenCycle() {
		if (!running || !plugin.shouldListenNow()) {
			scheduleNextCycle(plugin.wakePauseMs())
			return
		}
		ensureRecognizer()
		val recognizer = speechRecognizer ?: return
		try {
			recognizer.cancel()
			recognizer.startListening(buildIntent())
			armListenTimeout()
		} catch (e: Exception) {
			Log.w(TAG, "startListening failed: ${e.message}")
			scheduleNextCycle(plugin.wakePauseMs())
		}
	}

	private fun buildIntent(): Intent {
		return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
			putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
			putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
			putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, mode != Mode.WAKE_LISTEN || plugin.partialWakeEnabled())
			putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
			putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
		}
	}

	private fun stopActiveSession(reason: String) {
		Log.d(TAG, "stop session reason=$reason mode=$mode")
		mainHandler.removeCallbacks(listenTimeoutRunnable)
		try {
			speechRecognizer?.stopListening()
		} catch (_: Exception) {
		}
		if (mode == Mode.WAKE_LISTEN) {
			scheduleNextCycle(plugin.wakePauseMs())
		} else if (mode == Mode.DICTATE) {
			scheduleNextCycle(200L)
		} else {
			scheduleNextCycle(400L)
		}
	}

	private fun resetNoteSession() {
		noteLat = null
		noteLon = null
		dictationFinal.clear()
		lastPartial = ""
		mode = Mode.WAKE_LISTEN
	}

	private fun captureLocationForNote() {
		val loc = app.locationProvider.lastKnownLocation
		if (loc != null) {
			noteLat = loc.latitude
			noteLon = loc.longitude
			noteStartedMs = System.currentTimeMillis()
			return
		}
		app.showShortToastMessage(app.getString(R.string.voice_gps_no_location))
		resetNoteSession()
	}

	private fun finishNote(spoken: String) {
		val lat = noteLat
		val lon = noteLon
		if (lat == null || lon == null) {
			resetNoteSession()
			return
		}
		appendPartialToDictation()
		val body = VoiceGpsKeywordMatcher.stripEndKeyword(
			if (spoken.isNotBlank()) spoken else dictationFinal.toString()
		)
		if (body.isNotEmpty()) {
			noteWriter.saveTextNote(lat, lon, body, noteStartedMs)
			Log.i(TAG, "saved voice note len=${body.length}")
		}
		resetNoteSession()
		scheduleNextCycle(plugin.wakePauseMs())
	}

	private fun appendPartialToDictation() {
		val chunk = VoiceGpsKeywordMatcher.normalize(lastPartial)
		if (chunk.isEmpty()) {
			return
		}
		if (!dictationFinal.contains(chunk)) {
			if (dictationFinal.isNotEmpty()) {
				dictationFinal.append(' ')
			}
			dictationFinal.append(chunk)
		}
		lastPartial = ""
	}

	private fun onWakeDetected() {
		mode = Mode.AWAIT_COMMAND
		Log.i(TAG, "wake word detected")
		mainHandler.removeCallbacks(listenTimeoutRunnable)
		scheduleNextCycle(200L)
	}

	private fun onNoteCommandDetected() {
		mode = Mode.DICTATE
		dictationFinal.clear()
		lastPartial = ""
		captureLocationForNote()
		if (mode == Mode.DICTATE) {
			Log.i(TAG, "dictation started")
			scheduleNextCycle(200L)
		}
	}

	private fun handleText(raw: String, isFinal: Boolean) {
		when (mode) {
			Mode.WAKE_LISTEN -> {
				if (isFinal && VoiceGpsKeywordMatcher.containsWakeWord(raw)) {
					onWakeDetected()
				}
			}
			Mode.AWAIT_COMMAND -> {
				if (VoiceGpsKeywordMatcher.containsNoteCommand(raw)) {
					onNoteCommandDetected()
				}
			}
			Mode.DICTATE -> {
				if (VoiceGpsKeywordMatcher.containsEndKeyword(raw)) {
					val combined = (dictationFinal.toString() + " " + raw).trim()
					finishNote(combined)
					return
				}
				if (isFinal) {
					val chunk = VoiceGpsKeywordMatcher.normalize(raw)
					if (chunk.isNotEmpty() && !dictationFinal.contains(chunk)) {
						if (dictationFinal.isNotEmpty()) {
							dictationFinal.append(' ')
						}
						dictationFinal.append(chunk)
					}
					lastPartial = ""
					armListenTimeout()
				} else {
					lastPartial = raw
					armListenTimeout()
				}
			}
		}
	}

	private val recognitionListener = object : RecognitionListener {
		override fun onReadyForSpeech(params: android.os.Bundle?) {}

		override fun onBeginningOfSpeech() {
			if (mode == Mode.DICTATE || mode == Mode.AWAIT_COMMAND) {
				armListenTimeout()
			}
		}

		override fun onRmsChanged(rmsdB: Float) {}

		override fun onBufferReceived(buffer: ByteArray?) {}

		override fun onEndOfSpeech() {}

		override fun onError(error: Int) {
			if (!running) {
				return
			}
			if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
				plugin.onMicPermissionMissing()
				stop()
				return
			}
			if (mode == Mode.DICTATE) {
				appendPartialToDictation()
				scheduleNextCycle(600L)
				return
			}
			scheduleNextCycle(if (mode == Mode.WAKE_LISTEN) plugin.wakePauseMs() else 800L)
		}

		override fun onResults(results: android.os.Bundle?) {
			if (!running) {
				return
			}
			val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
			list?.forEach { handleText(it, true) }
			if (mode == Mode.DICTATE) {
				scheduleNextCycle(200L)
			} else {
				stopActiveSession("results")
			}
		}

		override fun onPartialResults(partialResults: android.os.Bundle?) {
			if (!running) {
				return
			}
			if (mode == Mode.WAKE_LISTEN && !plugin.partialWakeEnabled()) {
				return
			}
			val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
			val first = list?.firstOrNull() ?: return
			handleText(first, false)
		}

		override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
	}
}
