package net.osmand.plus.plugins.voicegps

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.KeyEvent
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.fragment.app.FragmentActivity
import net.osmand.aidlapi.OsmAndCustomizationConstants
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.plugins.OsmandPlugin
import net.osmand.plus.settings.backend.ApplicationMode
import net.osmand.plus.settings.backend.WidgetsAvailabilityHelper
import net.osmand.plus.settings.backend.preferences.CommonPreference
import net.osmand.plus.settings.enums.ScreenLayoutMode
import net.osmand.plus.settings.fragments.SettingsScreenType
import net.osmand.plus.voice.JsTtsCommandPlayer
import java.util.Locale
import net.osmand.plus.views.mapwidgets.MapWidgetInfo
import net.osmand.plus.views.mapwidgets.WidgetInfoCreator
import net.osmand.plus.views.mapwidgets.WidgetType
import net.osmand.plus.views.mapwidgets.WidgetsPanel
import net.osmand.plus.views.mapwidgets.widgets.MapWidget
import net.osmand.plus.widgets.ctxmenu.ContextMenuAdapter
import net.osmand.plus.widgets.ctxmenu.callback.OnDataChangeUiAdapter
import net.osmand.plus.widgets.ctxmenu.data.ContextMenuItem

class VoiceGpsPlugin(app: OsmandApplication) : OsmandPlugin(app) {

	companion object {
		const val REQUEST_RECORD_AUDIO = 88501
		private const val DEFAULT_WAKE_PAUSE_MS = 5000L
		private const val DEFAULT_LISTEN_WINDOW_MS = 25_000L

		const val MANUAL_WAKE_KEY_VOLUME_UP = "volume_up"
		const val MANUAL_WAKE_KEY_VOLUME_DOWN = "volume_down"
		const val MANUAL_WAKE_KEY_SIDE = "side"
		private const val TAG = "VoiceGps"
		private const val DEFAULT_COMMAND_LISTEN_MS = 12_000L
		private const val DEFAULT_DICTATION_LISTEN_MS = 45_000L
		private const val FOREGROUND_SYNC_MS = 2500L
		const val DEFAULT_MOVING_SPEED_THRESHOLD_KMH = 8
		const val MIN_MOVING_SPEED_THRESHOLD_KMH = 2
		const val MAX_MOVING_SPEED_THRESHOLD_KMH = 40
	}

	val LISTEN_IN_BACKGROUND: CommonPreference<Boolean> =
		registerBooleanPreference("voice_gps_listen_background", false).makeGlobal().makeShared()
	val WAKE_PAUSE_MS: CommonPreference<Int> =
		registerIntPreference("voice_gps_wake_pause_ms", DEFAULT_WAKE_PAUSE_MS.toInt()).makeGlobal().makeShared()
	val LISTEN_WINDOW_MS: CommonPreference<Int> =
		registerIntPreference("voice_gps_listen_window_ms", DEFAULT_LISTEN_WINDOW_MS.toInt()).makeGlobal().makeShared()
	val PARTIAL_WAKE: CommonPreference<Boolean> =
		registerBooleanPreference("voice_gps_partial_wake", true).makeGlobal().makeShared()
	val SHOW_VOICE_GPX_ON_MAP: CommonPreference<Boolean> =
		registerBooleanPreference("ev_voice_gpx_show_on_map", true).makeGlobal().makeShared()
	val PAUSE_WHEN_MOVING: CommonPreference<Boolean> =
		registerBooleanPreference("voice_gps_pause_when_moving", true).makeGlobal().makeShared()
	val MOVING_SPEED_THRESHOLD_KMH: CommonPreference<Int> =
		registerIntPreference(
			"voice_gps_moving_speed_threshold_kmh",
			DEFAULT_MOVING_SPEED_THRESHOLD_KMH
		).makeGlobal().makeShared()
	val MANUAL_WAKE: CommonPreference<Boolean> =
		registerBooleanPreference("voice_gps_manual_wake", false).makeGlobal().makeShared()
	val MANUAL_WAKE_KEY: CommonPreference<String> =
		registerStringPreference("voice_gps_manual_wake_key", MANUAL_WAKE_KEY_SIDE).makeGlobal().makeShared()
	val PREFER_ONLINE_STT: CommonPreference<Boolean> =
		registerBooleanPreference("voice_gps_prefer_online_stt", false).makeGlobal().makeShared()
	val DEBUG_LOG: CommonPreference<Boolean> =
		registerBooleanPreference("voice_gps_debug_log", false).makeGlobal().makeShared()

	@Volatile
	private var forceOnlineSttSession = false

	init {
		// Not shown until the user adds it from Configure screen. Available for every profile.
		WidgetsAvailabilityHelper.regWidgetVisibility(WidgetType.VOICE_GPS_NOTE)
	}

	private val mainHandler = Handler(Looper.getMainLooper())
	private val promptHandler = Handler(Looper.getMainLooper())
	private var promptGeneration = 0
	private var promptTts: TextToSpeech? = null
	private var promptReady = false
	private var manualWakeKeyCallbackInstalled = false
	private val manualWakeKeyCallback = object : KeyEvent.Callback {
		override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
			if (event == null || event.repeatCount > 0) {
				return false
			}
			if (!isActive || !manualWakeEnabled()) {
				return false
			}
			if (keyCode != manualWakeKeyCode()) {
				return false
			}
			Log.i(TAG, "keyDown code=$keyCode matched manual key ${MANUAL_WAKE_KEY.get()}")
			onManualWakeKeyPressed()
			return true
		}

		override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean = false

		override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
			if (!isActive || !manualWakeEnabled()) {
				return false
			}
			return keyCode == manualWakeKeyCode()
		}

		override fun onKeyMultiple(keyCode: Int, count: Int, event: KeyEvent?): Boolean = false
	}
	private val foregroundSyncRunnable = object : Runnable {
		override fun run() {
			syncListeningService()
			mainHandler.postDelayed(this, FOREGROUND_SYNC_MS)
		}
	}

	override fun getId(): String = OsmAndCustomizationConstants.PLUGIN_EV_VOICE_GPX

	override fun getName(): String = app.getString(R.string.voice_gps_plugin_name)

	override fun getDescription(linksEnabled: Boolean): CharSequence {
		return HtmlCompat.fromHtml(
			app.getString(R.string.voice_gps_plugin_description),
			HtmlCompat.FROM_HTML_MODE_LEGACY
		)
	}

	override fun getLogoResourceId(): Int = R.drawable.ic_action_micro_dark

	override fun getAssetResourceImage(): Drawable? =
		app.uiUtilities.getIcon(R.drawable.ic_action_micro_dark)

	override fun getSettingsScreenType(): SettingsScreenType =
		SettingsScreenType.VOICE_GPS_SETTINGS

	override fun init(app: OsmandApplication, activity: Activity?): Boolean {
		EvVoiceGpxStore.migrateLegacyPluginId(app, getId())
		mainHandler.removeCallbacks(foregroundSyncRunnable)
		mainHandler.post(foregroundSyncRunnable)
		syncListeningService()
		return true
	}

	override fun disable(app: OsmandApplication) {
		super.disable(app)
		mainHandler.removeCallbacks(foregroundSyncRunnable)
		shutdownListenPrompt()
		VoiceGpsListenService.sync(app, false)
	}

	fun showSettings(activity: FragmentActivity) {
		VoiceGpsSettingsBottomSheet.showInstance(activity.supportFragmentManager)
	}

	fun speakNoteRecorded() {
		val phrase = app.getString(R.string.voice_gps_note_recorded)
		val generation = ++promptGeneration
		val player = try {
			app.routingHelper.voiceRouter.player
		} catch (_: Exception) {
			null
		}
		if (player is JsTtsCommandPlayer && player.speakAdditional(phrase)) {
			return
		}
		speakPromptWithEngine(phrase, generation, Runnable { })
	}

	/**
	 * Speaks «слушаю заметку» once. [onFinished] runs after the utterance (or if TTS cannot start)
	 * so speech recognition does not open the mic over the prompt.
	 */
	fun speakListenPrompt(onFinished: () -> Unit) {
		val generation = ++promptGeneration
		var ran = false
		val finished = Runnable {
			if (ran || generation != promptGeneration) {
				return@Runnable
			}
			ran = true
			onFinished()
		}
		val phrase = app.getString(R.string.voice_gps_listen_prompt)
		val player = try {
			app.routingHelper.voiceRouter.player
		} catch (_: Exception) {
			null
		}
		if (player is JsTtsCommandPlayer && player.speakAdditional(phrase)) {
			promptHandler.postDelayed(finished, 1800L)
			return
		}
		speakPromptWithEngine(phrase, generation, finished)
	}

	private fun speakPromptWithEngine(phrase: String, generation: Int, finished: Runnable) {
		fun deliver(engine: TextToSpeech) {
			if (generation != promptGeneration) {
				return
			}
			engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
				override fun onStart(utteranceId: String?) {}
				override fun onDone(utteranceId: String?) {
					promptHandler.post(finished)
				}

				@Deprecated("Deprecated in Java")
				override fun onError(utteranceId: String?) {
					promptHandler.post(finished)
				}

				override fun onError(utteranceId: String?, errorCode: Int) {
					promptHandler.post(finished)
				}
			})
			val code = engine.speak(phrase, TextToSpeech.QUEUE_FLUSH, Bundle(), "voice-gps-listen")
			if (code == TextToSpeech.ERROR) {
				promptHandler.post(finished)
			} else {
				promptHandler.postDelayed(finished, 4000L)
			}
		}
		val existing = promptTts
		if (existing != null && promptReady) {
			deliver(existing)
			return
		}
		if (promptTts != null) {
			promptHandler.postDelayed(finished, 4000L)
			return
		}
		promptTts = TextToSpeech(app) { status ->
			promptReady = status == TextToSpeech.SUCCESS
			val engine = promptTts
			if (!promptReady || engine == null) {
				promptHandler.post(finished)
				return@TextToSpeech
			}
			engine.language = Locale.forLanguageTag("ru")
			engine.setAudioAttributes(
				AudioAttributes.Builder()
					.setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
					.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
					.build()
			)
			promptHandler.post {
				if (generation == promptGeneration) {
					deliver(engine)
				}
			}
		}
	}

	private fun shutdownListenPrompt() {
		promptGeneration++
		promptHandler.removeCallbacksAndMessages(null)
		promptReady = false
		promptTts?.stop()
		promptTts?.shutdown()
		promptTts = null
	}

	fun hasRecordAudioPermission(): Boolean {
		return ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
			PackageManager.PERMISSION_GRANTED
	}

	fun requestRecordAudio(activity: Activity) {
		ActivityCompat.requestPermissions(
			activity,
			arrayOf(Manifest.permission.RECORD_AUDIO),
			REQUEST_RECORD_AUDIO
		)
	}

	override fun handleRequestPermissionsResult(
		requestCode: Int,
		permissions: Array<out String>?,
		grantResults: IntArray?
	) {
		if (requestCode != REQUEST_RECORD_AUDIO || grantResults == null) {
			return
		}
		if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
			syncListeningService()
		}
	}

	fun onMicPermissionMissing() {
		syncListeningService()
	}

	fun shouldListenNow(): Boolean {
		if (!isActive || !hasRecordAudioPermission()) {
			return false
		}
		if (isListeningPausedByMotion()) {
			return false
		}
		if (LISTEN_IN_BACKGROUND.get()) {
			return true
		}
		return app.isAppInForeground
	}

	/** True when GPS speed is at or above [movingSpeedThresholdKmh] and pause-when-moving is on. */
	fun isListeningPausedByMotion(): Boolean {
		if (!PAUSE_WHEN_MOVING.get()) {
			return false
		}
		val loc = app.locationProvider.lastKnownLocation ?: return false
		if (!loc.hasSpeed()) {
			return false
		}
		val speedKmh = loc.speed * 3.6
		return speedKmh >= movingSpeedThresholdKmh()
	}

	fun movingSpeedThresholdKmh(): Int =
		MOVING_SPEED_THRESHOLD_KMH.get().coerceIn(MIN_MOVING_SPEED_THRESHOLD_KMH, MAX_MOVING_SPEED_THRESHOLD_KMH)

	fun wakePauseMs(): Long = WAKE_PAUSE_MS.get().toLong().coerceIn(1500L, 30_000L)

	fun listenWindowMs(): Long = LISTEN_WINDOW_MS.get().toLong().coerceIn(3000L, 30_000L)

	fun commandListenWindowMs(): Long =
		(DEFAULT_COMMAND_LISTEN_MS).coerceIn(listenWindowMs(), 30_000L)

	fun dictationListenWindowMs(): Long =
		maxOf(DEFAULT_DICTATION_LISTEN_MS, listenWindowMs() * 4L).coerceAtMost(120_000L)

	fun partialWakeEnabled(): Boolean = PARTIAL_WAKE.get()

	fun manualWakeEnabled(): Boolean = MANUAL_WAKE.get()

	fun manualWakeKeyCode(): Int = when (MANUAL_WAKE_KEY.get()) {
		MANUAL_WAKE_KEY_VOLUME_UP -> KeyEvent.KEYCODE_VOLUME_UP
		MANUAL_WAKE_KEY_VOLUME_DOWN -> KeyEvent.KEYCODE_VOLUME_DOWN
		else -> KeyEvent.KEYCODE_HEADSETHOOK
	}

	fun showVoiceGpxOnMap(): Boolean = SHOW_VOICE_GPX_ON_MAP.get()

	fun preferOnlineStt(): Boolean = PREFER_ONLINE_STT.get()

	fun useOnlineSttForSession(): Boolean = forceOnlineSttSession || preferOnlineStt()

	fun clearForceOnlineSttSession() {
		forceOnlineSttSession = false
	}

	fun fallbackToOnlineStt() {
		forceOnlineSttSession = true
	}

	fun beginOnlineVoiceNote(activity: Activity?) {
		forceOnlineSttSession = true
		beginManualSession(activity)
	}

	/**
	 * One voice-note session: listen for «заметка», then dictate until «конец».
	 * Started from the widget long-press, the settings emoji button, or the side key.
	 * Does not start idle recognizer cycling. The hardware key still requires manual mode
	 * (default key is the side / headset button, [KEYCODE_HEADSETHOOK]).
	 */
	fun beginManualSession(activity: Activity?) {
		if (!isActive) {
			Log.i(TAG, "beginManualSession ignored: plugin inactive")
			return
		}
		if (!hasRecordAudioPermission()) {
			Log.i(TAG, "beginManualSession: microphone permission missing")
			val host = activity ?: app.keyEventHelper?.mapActivity
			if (host != null) {
				requestRecordAudio(host)
			} else {
				app.showShortToastMessage(app.getString(R.string.voice_gps_mic_denied))
			}
			return
		}
		if (!shouldListenNow()) {
			Log.i(TAG, "beginManualSession unavailable motion=${isListeningPausedByMotion()} foreground=${app.isAppInForeground}")
			app.showShortToastMessage(app.getString(R.string.voice_gps_manual_wake_unavailable))
			return
		}
		Log.i(TAG, "beginManualSession")
		VoiceGpsListenService.startManualSession(app)
	}

	fun onManualWakeKeyPressed() {
		if (!isActive || !manualWakeEnabled()) {
			Log.i(TAG, "manual key ignored active=$isActive manual=${manualWakeEnabled()}")
			return
		}
		beginManualSession(app.keyEventHelper?.mapActivity)
	}

	fun syncListeningService() {
		updateManualWakeKeyInterceptor()
		if (manualWakeEnabled()) {
			// Never start the foreground listen service from the 2.5s sync.
			// It runs only after an explicit create-note action (beginManualSession).
			if (!VoiceGpsListenService.manualSessionRunning) {
				VoiceGpsListenService.sync(app, false)
			}
			return
		}
		val start = shouldListenNow()
		VoiceGpsListenService.sync(app, start)
	}

	private fun updateManualWakeKeyInterceptor() {
		val want = isActive && manualWakeEnabled()
		if (want && !manualWakeKeyCallbackInstalled) {
			app.getKeyEventHelper().setExternalCallback(manualWakeKeyCallback)
			manualWakeKeyCallbackInstalled = true
			Log.i(TAG, "key interceptor on key=${MANUAL_WAKE_KEY.get()} code=${manualWakeKeyCode()}")
		} else if (!want && manualWakeKeyCallbackInstalled) {
			app.getKeyEventHelper().setExternalCallback(null)
			manualWakeKeyCallbackInstalled = false
			Log.i(TAG, "key interceptor off")
		}
	}

	override fun createWidgets(
		mapActivity: MapActivity,
		widgetInfos: MutableList<MapWidgetInfo>,
		appMode: ApplicationMode,
		layoutMode: ScreenLayoutMode?,
	) {
		val creator = WidgetInfoCreator(app, appMode, layoutMode)
		val widget = createMapWidgetForParams(mapActivity, WidgetType.VOICE_GPS_NOTE)
		if (widget != null) {
			val info = creator.createWidgetInfo(widget)
			if (info != null) {
				widgetInfos.add(info)
			}
		}
	}

	override fun createMapWidgetForParams(
		mapActivity: MapActivity,
		widgetType: WidgetType,
		customId: String?,
		widgetsPanel: WidgetsPanel?,
	): MapWidget? {
		if (widgetType == WidgetType.VOICE_GPS_NOTE) {
			return VoiceGpsNoteWidget(mapActivity, customId, widgetsPanel)
		}
		return null
	}

	override fun mapActivityResume(activity: MapActivity) {
		updateManualWakeKeyInterceptor()
	}

	override fun mapActivityPause(activity: MapActivity) {
		if (manualWakeKeyCallbackInstalled) {
			app.getKeyEventHelper().setExternalCallback(null)
			manualWakeKeyCallbackInstalled = false
		}
	}

	override fun registerOptionsMenuItems(mapActivity: MapActivity, helper: ContextMenuAdapter) {
		if (!isActive) {
			return
		}
		helper.addItem(
			ContextMenuItem(OsmAndCustomizationConstants.DRAWER_VOICE_GPS_ID)
				.setTitleId(R.string.voice_gps_plugin_name, mapActivity)
				.setIcon(R.drawable.ic_action_micro_dark)
				.setListener { _: OnDataChangeUiAdapter?, _: View?, _: ContextMenuItem?, _: Boolean ->
					showSettings(mapActivity)
					true
				}
		)
	}
}
