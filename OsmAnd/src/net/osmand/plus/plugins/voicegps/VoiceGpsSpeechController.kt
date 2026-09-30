package net.osmand.plus.plugins.voicegps

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R

/** Notified when the controller fully stops (e.g. end of a manual key session). */
typealias VoiceGpsControllerStopListener = () -> Unit

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
	private val silenceEndRunnable = Runnable { onSilenceEndElapsed() }

	/** Last time we saw speech (STT text or mic level) while dictating. */
	private var lastSpeechActivityElapsed = 0L

	/**
	 * Manual mode ([VoiceGpsPlugin.manualWakeEnabled]): one widget/key session, then idle.
	 * Wake-word mode keeps the duty cycle and clears this flag when the note ends.
	 */
	private var manualOneShot = false
	/** Absolute [SystemClock.elapsedRealtime] deadline. Manual mode must not slide this. */
	private var sessionDeadlineElapsed = 0L
	private var recognizerSessionOpen = false
	private var manualErrorRetries = 0
	private var offlineFallbackUsed = false
	private var stopCleanupPosted = false
	/** True while the activation prompt is spoken; recognizer stays closed until it finishes. */
	private var awaitingPrompt = false

	var onStopped: VoiceGpsControllerStopListener? = null

	companion object {
		private const val TAG = "VoiceGpsSpeech"
		/** Keep wake-mode STT alive longer to cut start/stop system beeps. */
		private const val WAKE_MIN_SPEECH_MS = 15_000L
		private const val MANUAL_ERROR_RETRIES = 1
		private const val DICTATION_SILENCE_END_MS = 10_000L
		/** dB threshold — treat as speech when RMS is above this (relative quiet = below). */
		private const val SPEECH_RMS_DB = 3.5f
	}

	fun start() {
		mainHandler.post {
			if (running) {
				return@post
			}
			if (plugin.manualWakeEnabled()) {
				Log.i(TAG, "start ignored: manual mode stays idle")
				return@post
			}
			if (!SpeechRecognizer.isRecognitionAvailable(app)) {
				Log.w(TAG, "Speech recognition not available")
				return@post
			}
			running = true
			manualOneShot = false
			mode = Mode.WAKE_LISTEN
			ensureRecognizer()
			scheduleNextCycle(300L)
		}
	}

	fun stop() {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			requestStopOnMain()
		} else {
			mainHandler.post { requestStopOnMain() }
		}
	}

	/**
	 * Widget or hardware key: one session (await «заметка», then dictate until «конец»).
	 * In manual mode this does not return to the wake-word listen cycle.
	 */
	fun startManualActivation() {
		mainHandler.post { startManualOnMain() }
	}

	private fun startManualOnMain() {
		if (awaitingPrompt) {
			Log.i(TAG, "manual prompt already playing")
			return
		}
		if (running && manualOneShot && mode != Mode.WAKE_LISTEN) {
			Log.i(TAG, "manual session already active")
			return
		}
		mainHandler.removeCallbacks(restartRunnable)
		mainHandler.removeCallbacks(listenTimeoutRunnable)
		clearSilenceWatchdog()
		if (!plugin.shouldListenNow() || !SpeechRecognizer.isRecognitionAvailable(app)) {
			Log.w(TAG, "manual activation unavailable")
			running = true
			manualOneShot = true
			requestStopOnMain()
			return
		}
		running = true
		manualOneShot = plugin.manualWakeEnabled()
		manualErrorRetries = 0
		awaitingPrompt = true
		Log.i(TAG, "manual activation — one session manual=$manualOneShot")
		app.showShortToastMessage(app.getString(R.string.voice_gps_activated))
		plugin.speakListenPrompt {
			if (!running || !awaitingPrompt) {
				return@speakListenPrompt
			}
			awaitingPrompt = false
			if (!captureLocationForNote()) {
				endManualSession("no_location")
				return@speakListenPrompt
			}
			beginDictation(manualSession = true, startListening = true)
		}
	}

	private fun beginDictation(manualSession: Boolean, startListening: Boolean) {
		mode = Mode.DICTATE
		dictationFinal.clear()
		lastPartial = ""
		manualErrorRetries = 0
		offlineFallbackUsed = false
		if (manualSession) {
			sessionDeadlineElapsed = SystemClock.elapsedRealtime() + plugin.dictationListenWindowMs()
		}
		markSpeechActivity()
		Log.i(TAG, "dictation started manual=$manualSession")
		if (startListening) {
			ensureRecognizer()
			startListenCycle()
		}
	}

	private fun markSpeechActivity() {
		lastSpeechActivityElapsed = SystemClock.elapsedRealtime()
		if (mode == Mode.DICTATE && running) {
			armSilenceEndWatchdog()
		}
	}

	private fun armSilenceEndWatchdog() {
		mainHandler.removeCallbacks(silenceEndRunnable)
		if (mode != Mode.DICTATE || !running) {
			return
		}
		mainHandler.postDelayed(silenceEndRunnable, DICTATION_SILENCE_END_MS)
	}

	private fun onSilenceEndElapsed() {
		if (!running || mode != Mode.DICTATE) {
			return
		}
		val silentMs = SystemClock.elapsedRealtime() - lastSpeechActivityElapsed
		if (silentMs < DICTATION_SILENCE_END_MS - 200L) {
			armSilenceEndWatchdog()
			return
		}
		appendPartialToDictation()
		val spoken = dictationFinal.toString()
		if (spoken.isNotBlank()) {
			Log.i(TAG, "dictation end: silence ${silentMs}ms")
			finishNote(spoken)
		} else if (manualOneShot || plugin.manualWakeEnabled()) {
			endManualSession("silence_no_text")
		} else {
			scheduleNextCycle(plugin.wakePauseMs())
		}
	}

	private fun clearSilenceWatchdog() {
		mainHandler.removeCallbacks(silenceEndRunnable)
	}

	/** Flags drop immediately so a callback cannot schedule another cycle. Destroy runs after the callback. */
	private fun requestStopOnMain() {
		val notify = running || manualOneShot || speechRecognizer != null
		running = false
		manualOneShot = false
		manualErrorRetries = 0
		awaitingPrompt = false
		recognizerSessionOpen = false
		mainHandler.removeCallbacks(restartRunnable)
		mainHandler.removeCallbacks(listenTimeoutRunnable)
		clearSilenceWatchdog()
		if (!notify || stopCleanupPosted) {
			return
		}
		stopCleanupPosted = true
		mainHandler.post {
			stopCleanupPosted = false
			if (running) {
				return@post
			}
			quietEndRecognizerSession()
			destroyRecognizer()
			resetNoteSession()
			plugin.clearForceOnlineSttSession()
			onStopped?.invoke()
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
		recognizerSessionOpen = false
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
		if (manualOneShot && mode == Mode.WAKE_LISTEN) {
			endManualSession("manual_no_wake_cycle")
			return
		}
		if (plugin.manualWakeEnabled() && !manualOneShot) {
			endManualSession("manual_idle")
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
		val windowMs = if (manualOneShot) {
			(sessionDeadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
		} else {
			when (mode) {
				Mode.DICTATE -> plugin.dictationListenWindowMs()
				Mode.AWAIT_COMMAND -> plugin.commandListenWindowMs()
				Mode.WAKE_LISTEN -> plugin.listenWindowMs()
			}
		}
		mainHandler.postDelayed(listenTimeoutRunnable, windowMs)
	}

	private fun onListenWindowElapsed() {
		if (!running) {
			return
		}
		if (manualOneShot) {
			when (mode) {
				Mode.DICTATE -> {
					appendPartialToDictation()
					val spoken = dictationFinal.toString()
					if (spoken.isNotBlank()) {
						finishNote(spoken)
					} else {
						endManualSession("dictation_timeout")
					}
				}
				else -> endManualSession("manual_timeout")
			}
			return
		}
		when (mode) {
			Mode.DICTATE -> {
				appendPartialToDictation()
				Log.d(TAG, "dictation listen window elapsed — restarting STT")
				quietEndRecognizerSession()
				scheduleNextCycle(150L)
			}
			Mode.AWAIT_COMMAND -> {
				if (plugin.manualWakeEnabled()) {
					endManualSession("await_command_timeout")
				} else {
					stopActiveSession("listen_timeout")
				}
			}
			Mode.WAKE_LISTEN -> {
				quietEndRecognizerSession()
				scheduleNextCycle(plugin.wakePauseMs())
			}
		}
	}

	private fun startListenCycle() {
		if (!running) {
			return
		}
		if (plugin.manualWakeEnabled() && !manualOneShot) {
			endManualSession("manual_idle")
			return
		}
		if (!plugin.shouldListenNow()) {
			if (manualOneShot) {
				endManualSession("listen_unavailable")
			} else {
				scheduleNextCycle(plugin.wakePauseMs())
			}
			return
		}
		ensureRecognizer()
		val recognizer = speechRecognizer ?: return
		try {
			if (manualOneShot && recognizerSessionOpen) {
				// A live session is already up. Cancelling here and starting again is the beep loop.
				return
			}
			if (!manualOneShot) {
				quietEndRecognizerSession()
			}
			recognizer.startListening(buildIntent())
			recognizerSessionOpen = true
			armListenTimeout()
		} catch (e: Exception) {
			Log.w(TAG, "startListening failed: ${e.message}")
			recognizerSessionOpen = false
			if (manualOneShot) {
				endManualSession("start_failed")
			} else {
				scheduleNextCycle(plugin.wakePauseMs())
			}
		}
	}

	private fun buildIntent(): Intent {
		return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
			putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
			putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
			putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, mode != Mode.WAKE_LISTEN || plugin.partialWakeEnabled())
			putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
			putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
			// Empty prompt avoids spoken UI feedback from the recognizer service.
			putExtra(RecognizerIntent.EXTRA_PROMPT, "")
			if (plugin.useOnlineSttForSession()) {
				putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
			} else {
				putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
			}
			if (mode == Mode.WAKE_LISTEN) {
				putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, WAKE_MIN_SPEECH_MS)
			} else if (mode == Mode.DICTATE) {
				// A long minimum keeps the mic open but never returns a short note.
				putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400L)
				putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
				putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
			}
		}
	}

	private fun sessionTimeLeftMs(): Long =
		sessionDeadlineElapsed - SystemClock.elapsedRealtime()

	private fun isOfflineUnavailable(error: Int): Boolean {
		return error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
			error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
			error == SpeechRecognizer.ERROR_SERVER ||
			error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
			error == SpeechRecognizer.ERROR_NETWORK ||
			error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
	}

	private fun isContinuableRecognizerError(error: Int): Boolean {
		return error == SpeechRecognizer.ERROR_NO_MATCH ||
			error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
	}

	private fun isStartupRecognizerError(error: Int): Boolean {
		return isContinuableRecognizerError(error) ||
			error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
			error == SpeechRecognizer.ERROR_CLIENT
	}

	/** cancel() restarts without the end-of-session earcon that stopListening() often plays. */
	private fun quietEndRecognizerSession() {
		recognizerSessionOpen = false
		try {
			speechRecognizer?.cancel()
		} catch (_: Exception) {
		}
	}

	private fun endManualSession(reason: String) {
		Log.i(TAG, "manual session end reason=$reason")
		stop()
	}

	private fun stopActiveSession(reason: String) {
		Log.d(TAG, "stop session reason=$reason mode=$mode")
		if (manualOneShot || (plugin.manualWakeEnabled() && mode != Mode.DICTATE)) {
			endManualSession(reason)
			return
		}
		mainHandler.removeCallbacks(listenTimeoutRunnable)
		quietEndRecognizerSession()
		if (mode == Mode.WAKE_LISTEN) {
			scheduleNextCycle(plugin.wakePauseMs())
		} else if (mode == Mode.DICTATE) {
			scheduleNextCycle(200L)
		} else {
			scheduleNextCycle(400L)
		}
	}

	private fun resetNoteSession() {
		clearSilenceWatchdog()
		noteLat = null
		noteLon = null
		dictationFinal.clear()
		lastPartial = ""
		mode = Mode.WAKE_LISTEN
	}

	private fun captureLocationForNote(): Boolean {
		val loc = app.locationProvider.lastKnownLocation
		if (loc != null) {
			noteLat = loc.latitude
			noteLon = loc.longitude
			noteStartedMs = System.currentTimeMillis()
			return true
		}
		app.showShortToastMessage(app.getString(R.string.voice_gps_no_location))
		return false
	}

	private fun finishNote(spoken: String) {
		val lat = noteLat
		val lon = noteLon
		if (lat == null || lon == null) {
			if (manualOneShot || plugin.manualWakeEnabled()) {
				endManualSession("no_location")
			} else {
				resetNoteSession()
				scheduleNextCycle(plugin.wakePauseMs())
			}
			return
		}
		appendPartialToDictation()
		val body = VoiceGpsKeywordMatcher.stripEndKeyword(
			if (spoken.isNotBlank()) spoken else dictationFinal.toString()
		)
		if (body.isNotEmpty()) {
			Log.i(TAG, "finish note len=${body.length}")
			noteWriter.saveTextNote(lat, lon, body, noteStartedMs)
		} else {
			Log.i(TAG, "finish note empty")
			app.showShortToastMessage(app.getString(R.string.voice_gps_note_empty))
		}
		resetNoteSession()
		if (manualOneShot || plugin.manualWakeEnabled()) {
			endManualSession(if (body.isNotEmpty()) "note_saved" else "note_empty")
			return
		}
		manualOneShot = false
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
		if (plugin.manualWakeEnabled()) {
			endManualSession("wake_while_manual")
			return
		}
		mode = Mode.AWAIT_COMMAND
		Log.i(TAG, "wake word detected")
		mainHandler.removeCallbacks(listenTimeoutRunnable)
		scheduleNextCycle(200L)
	}

	private fun onNoteCommandDetected() {
		if (!captureLocationForNote()) {
			if (manualOneShot || plugin.manualWakeEnabled()) {
				endManualSession("no_location")
			} else {
				resetNoteSession()
				scheduleNextCycle(plugin.wakePauseMs())
			}
			return
		}
		beginDictation(manualSession = manualOneShot, startListening = false)
		if (!manualOneShot) {
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
					val tail = VoiceGpsKeywordMatcher.stripNoteCommand(raw)
					if (tail.isNotEmpty() && mode == Mode.DICTATE) {
						handleText(tail, isFinal)
					}
				}
			}
			Mode.DICTATE -> {
				if (VoiceGpsKeywordMatcher.containsEndKeyword(raw)) {
					val combined = (dictationFinal.toString() + " " + raw).trim()
					finishNote(combined)
					return
				}
				markSpeechActivity()
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
				if (mode == Mode.DICTATE) {
					markSpeechActivity()
				}
				armListenTimeout()
			}
		}

		override fun onRmsChanged(rmsdB: Float) {
			if (mode == Mode.DICTATE && rmsdB >= SPEECH_RMS_DB) {
				markSpeechActivity()
			}
		}

		override fun onBufferReceived(buffer: ByteArray?) {}

		override fun onEndOfSpeech() {}

		override fun onError(error: Int) {
			recognizerSessionOpen = false
			if (!running) {
				return
			}
			if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
				plugin.onMicPermissionMissing()
				stop()
				return
			}
			if (manualOneShot || plugin.manualWakeEnabled()) {
				if (mode == Mode.DICTATE &&
					!plugin.useOnlineSttForSession() &&
					!offlineFallbackUsed &&
					isOfflineUnavailable(error)
				) {
					offlineFallbackUsed = true
					plugin.fallbackToOnlineStt()
					appendPartialToDictation()
					Log.i(TAG, "offline stt unavailable error=$error, retry online")
					scheduleNextCycle(400L)
					return
				}
				if (mode == Mode.DICTATE &&
					isContinuableRecognizerError(error) &&
					sessionTimeLeftMs() > 800L &&
					manualErrorRetries < MANUAL_ERROR_RETRIES
				) {
					manualErrorRetries++
					appendPartialToDictation()
					scheduleNextCycle(600L)
					return
				}
				if (mode == Mode.DICTATE) {
					appendPartialToDictation()
					val spoken = dictationFinal.toString()
					if (spoken.isNotBlank()) {
						finishNote(spoken)
					} else {
						endManualSession("dictate_error_$error")
					}
					return
				}
				if (mode == Mode.AWAIT_COMMAND &&
					isStartupRecognizerError(error) &&
					manualErrorRetries < MANUAL_ERROR_RETRIES &&
					sessionTimeLeftMs() > 800L
				) {
					manualErrorRetries++
					scheduleNextCycle(500L)
					return
				}
				endManualSession("error_$error")
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
			recognizerSessionOpen = false
			if (!running) {
				return
			}
			val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
			list?.firstOrNull { it.isNotBlank() }?.let { handleText(it, true) }
			if (!running) {
				return
			}
			val hadText = list?.any { it.isNotBlank() } == true
			if (manualOneShot || plugin.manualWakeEnabled()) {
				val canContinueDictation = mode == Mode.DICTATE &&
					sessionTimeLeftMs() > 400L &&
					(hadText || manualErrorRetries < MANUAL_ERROR_RETRIES)
				if (canContinueDictation) {
					if (!hadText) {
						manualErrorRetries++
					} else {
						manualErrorRetries = 0
					}
					scheduleNextCycle(200L)
				} else if (mode == Mode.DICTATE) {
					appendPartialToDictation()
					val spoken = dictationFinal.toString()
					if (spoken.isNotBlank()) {
						finishNote(spoken)
					} else {
						endManualSession("dictation_done")
					}
				} else {
					endManualSession("no_note_command")
				}
				return
			}
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
