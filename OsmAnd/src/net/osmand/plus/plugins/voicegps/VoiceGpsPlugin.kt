package net.osmand.plus.plugins.voicegps

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
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
		private const val DEFAULT_WAKE_PAUSE_MS = 4000L
		private const val DEFAULT_LISTEN_WINDOW_MS = 8000L
		private const val FOREGROUND_SYNC_MS = 2500L
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

	private val mainHandler = Handler(Looper.getMainLooper())
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
		if (LISTEN_IN_BACKGROUND.get()) {
			return true
		}
		return app.isAppInForeground
	}

	fun wakePauseMs(): Long = WAKE_PAUSE_MS.get().toLong().coerceIn(1500L, 30_000L)

	fun listenWindowMs(): Long = LISTEN_WINDOW_MS.get().toLong().coerceIn(3000L, 30_000L)

	fun partialWakeEnabled(): Boolean = PARTIAL_WAKE.get()

	fun showVoiceGpxOnMap(): Boolean = SHOW_VOICE_GPX_ON_MAP.get()

	fun syncListeningService() {
		val start = shouldListenNow()
		VoiceGpsListenService.sync(app, start)
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
