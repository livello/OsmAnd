package net.osmand.plus.plugins.voicegps

import android.Manifest
import android.content.pm.PackageManager
import androidx.preference.Preference
import net.osmand.plus.R
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.settings.fragments.BaseSettingsFragment
import net.osmand.plus.settings.preferences.ListPreferenceEx
import net.osmand.plus.settings.preferences.SwitchPreferenceEx

class VoiceGpsSettingsFragment : BaseSettingsFragment() {

	private val plugin: VoiceGpsPlugin
		get() = PluginsHelper.requirePlugin(VoiceGpsPlugin::class.java)

	override fun setupPreferences() {
		updatePermissionSummary()
		setupSwitch(plugin.LISTEN_IN_BACKGROUND.id, R.string.voice_gps_listen_background_desc)
		setupSwitch(plugin.PAUSE_WHEN_MOVING.id, R.string.voice_gps_pause_when_moving_desc)
		setupMovingSpeedThreshold()
		setupSwitch(plugin.PARTIAL_WAKE.id, R.string.voice_gps_partial_wake_desc)
		setupManualWake()
		setupSwitch(plugin.SHOW_VOICE_GPX_ON_MAP.id, R.string.ev_voice_gpx_show_on_map_desc)
	}

	private fun setupManualWake() {
		setupSwitch(plugin.MANUAL_WAKE.id, R.string.voice_gps_manual_wake_desc)
		val pref = findPreference<ListPreferenceEx>(plugin.MANUAL_WAKE_KEY.id) ?: return
		val keys = arrayOf(
			VoiceGpsPlugin.MANUAL_WAKE_KEY_SIDE,
			VoiceGpsPlugin.MANUAL_WAKE_KEY_VOLUME_UP,
			VoiceGpsPlugin.MANUAL_WAKE_KEY_VOLUME_DOWN,
		)
		pref.setEntries(
			arrayOf(
				getString(R.string.voice_gps_manual_wake_key_side),
				getString(R.string.voice_gps_manual_wake_key_volume_up),
				getString(R.string.voice_gps_manual_wake_key_volume_down),
			)
		)
		pref.setEntryValues(keys.map { it as Any }.toTypedArray())
		pref.setValue(plugin.MANUAL_WAKE_KEY.get())
		pref.setDescription(R.string.voice_gps_manual_wake_key_desc)
		updateManualWakeKeyEnabled()
	}

	private fun updateManualWakeKeyEnabled() {
		findPreference<ListPreferenceEx>(plugin.MANUAL_WAKE_KEY.id)?.isEnabled = plugin.manualWakeEnabled()
		findPreference<SwitchPreferenceEx>(plugin.PARTIAL_WAKE.id)?.isEnabled = !plugin.manualWakeEnabled()
	}

	private fun setupMovingSpeedThreshold() {
		val pref = findPreference<ListPreferenceEx>(plugin.MOVING_SPEED_THRESHOLD_KMH.id) ?: return
		val values = intArrayOf(5, 8, 10, 15, 20)
		pref.setEntries(values.map { getString(R.string.ev_bms_n_kmh, it) }.toTypedArray())
		pref.setEntryValues(values.map { it as Any }.toTypedArray())
		pref.setValue(plugin.MOVING_SPEED_THRESHOLD_KMH.get())
		pref.setDescription(R.string.voice_gps_moving_speed_threshold_desc)
		updateMovingSpeedThresholdEnabled()
	}

	private fun updateMovingSpeedThresholdEnabled() {
		findPreference<ListPreferenceEx>(plugin.MOVING_SPEED_THRESHOLD_KMH.id)?.isEnabled =
			plugin.PAUSE_WHEN_MOVING.get()
	}

	private fun setupSwitch(key: String, desc: Int) {
		findPreference<SwitchPreferenceEx>(key)?.setDescription(desc)
	}

	private fun updatePermissionSummary() {
		val pref = findPreference<Preference>("voice_gps_request_mic")
		val granted = plugin.hasRecordAudioPermission()
		pref?.summary = getString(
			if (granted) R.string.voice_gps_mic_granted else R.string.voice_gps_mic_denied
		)
	}

	override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
		val result = super.onPreferenceChange(preference, newValue)
		when (preference.key) {
			plugin.LISTEN_IN_BACKGROUND.id,
			plugin.PAUSE_WHEN_MOVING.id,
			plugin.MOVING_SPEED_THRESHOLD_KMH.id,
			plugin.PARTIAL_WAKE.id,
			plugin.MANUAL_WAKE.id,
			plugin.MANUAL_WAKE_KEY.id,
			plugin.SHOW_VOICE_GPX_ON_MAP.id -> {
				if (preference.key == plugin.PAUSE_WHEN_MOVING.id) {
					updateMovingSpeedThresholdEnabled()
				}
				if (preference.key == plugin.MANUAL_WAKE.id) {
					updateManualWakeKeyEnabled()
				}
				plugin.syncListeningService()
			}
		}
		return result
	}

	override fun onPreferenceClick(preference: Preference): Boolean {
		when (preference.key) {
			"voice_gps_request_mic" -> {
				val activity = activity ?: return true
				if (!plugin.hasRecordAudioPermission()) {
					plugin.requestRecordAudio(activity)
				} else {
					plugin.syncListeningService()
				}
				return true
			}
		}
		return super.onPreferenceClick(preference)
	}

	override fun onResume() {
		super.onResume()
		updatePermissionSummary()
	}

	override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
		if (requestCode == VoiceGpsPlugin.REQUEST_RECORD_AUDIO) {
			if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				plugin.syncListeningService()
			}
			updatePermissionSummary()
		} else {
			super.onRequestPermissionsResult(requestCode, permissions, grantResults)
		}
	}
}
