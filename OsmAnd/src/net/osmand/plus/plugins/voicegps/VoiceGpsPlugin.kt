package net.osmand.plus.plugins.voicegps

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import net.osmand.aidlapi.OsmAndCustomizationConstants
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.plugins.OsmandPlugin
import net.osmand.plus.settings.backend.preferences.CommonPreference
import net.osmand.plus.settings.fragments.BaseSettingsFragment
import net.osmand.plus.settings.fragments.SettingsScreenType
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
		private const val DEFAULT_COMMAND_LISTEN_MS = 12_000L
		private const val DEFAULT_DICTATION_LISTEN_MS = 45_000L
		private const val FOREGROUND_SYNC_MS = 2500L
		const val DEFAULT_MOVING_SPEED_THRESHOLD_KMH = 8
		private const val MIN_MOVING_SPEED_THRESHOLD_KMH = 2
		private const val MAX_MOVING_SPEED_THRESHOLD_KMH = 40
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

	private val mainHandler = Handler(Looper.getMainLooper())
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
		VoiceGpsListenService.sync(app, false)
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

	fun onManualWakeKeyPressed() {
		if (!isActive || !manualWakeEnabled() || !hasRecordAudioPermission()) {
			return
		}
		if (!shouldListenNow()) {
			app.showShortToastMessage(app.getString(R.string.voice_gps_manual_wake_unavailable))
			return
		}
		VoiceGpsListenService.startManualSession(app)
	}

	fun syncListeningService() {
		updateManualWakeKeyInterceptor()
		if (manualWakeEnabled()) {
			VoiceGpsListenService.sync(app, false)
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
		} else if (!want && manualWakeKeyCallbackInstalled) {
			app.getKeyEventHelper().setExternalCallback(null)
			manualWakeKeyCallbackInstalled = false
		}
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
					BaseSettingsFragment.showInstance(mapActivity, SettingsScreenType.VOICE_GPS_SETTINGS)
					true
				}
		)
	}
}
