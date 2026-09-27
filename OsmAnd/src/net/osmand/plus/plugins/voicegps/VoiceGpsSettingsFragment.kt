package net.osmand.plus.plugins.voicegps

import android.Manifest
import android.content.pm.PackageManager
import androidx.preference.Preference
import net.osmand.plus.R
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.settings.fragments.BaseSettingsFragment
import net.osmand.plus.settings.preferences.SwitchPreferenceEx

class VoiceGpsSettingsFragment : BaseSettingsFragment() {

	private val plugin: VoiceGpsPlugin
		get() = PluginsHelper.requirePlugin(VoiceGpsPlugin::class.java)

	override fun setupPreferences() {
		updatePermissionSummary()
		setupSwitch(plugin.LISTEN_IN_BACKGROUND.id, R.string.voice_gps_listen_background_desc)
		setupSwitch(plugin.PARTIAL_WAKE.id, R.string.voice_gps_partial_wake_desc)
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
			plugin.PARTIAL_WAKE.id -> plugin.syncListeningService()
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
