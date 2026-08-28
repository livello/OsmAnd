package net.osmand.plus.plugins.torrentmaps

import androidx.preference.Preference
import net.osmand.plus.R
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.settings.fragments.BaseSettingsFragment
import net.osmand.plus.settings.preferences.SwitchPreferenceEx

class TorrentMapsSettingsFragment : BaseSettingsFragment() {

	private val plugin: TorrentMapsPlugin
		get() = PluginsHelper.requirePlugin(TorrentMapsPlugin::class.java)

	override fun setupPreferences() {
		findPreference<Preference>("torrent_maps_open_client")?.summary =
			getString(R.string.torrent_maps_open_client_desc)
		setupSwitch(plugin.TORRENT_ENABLED.id, R.string.torrent_maps_enabled_desc)
		setupSwitch(plugin.TORRENT_WIFI_ONLY.id, R.string.torrent_maps_wifi_only_desc)
		setupSwitch(plugin.TORRENT_SEED_ON_CHARGE.id, R.string.torrent_maps_seed_charge_desc)
		setupSwitch(plugin.TORRENT_DOWNLOAD_NEW.id, R.string.torrent_maps_download_new_desc)
	}

	private fun setupSwitch(key: String, desc: Int) {
		findPreference<SwitchPreferenceEx>(key)?.setDescription(desc)
	}

	override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
		val result = super.onPreferenceChange(preference, newValue)
		when (preference.key) {
			plugin.TORRENT_ENABLED.id,
			plugin.TORRENT_WIFI_ONLY.id,
			plugin.TORRENT_SEED_ON_CHARGE.id,
			plugin.TORRENT_DOWNLOAD_NEW.id -> plugin.syncMapTorrent()
		}
		return result
	}

	override fun onPreferenceClick(preference: Preference): Boolean {
		when (preference.key) {
			"torrent_maps_open_client" -> {
				plugin.openClientUi(requireActivity())
				return true
			}
			"torrent_maps_verify_hashes" -> {
				plugin.verifyDownloadedMaps()
				plugin.openClientUi(requireActivity())
				return true
			}
		}
		return super.onPreferenceClick(preference)
	}
}
