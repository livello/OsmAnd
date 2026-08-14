package net.osmand.plus.plugins.evbms

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import net.osmand.plus.R
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.plugins.evbms.ble.EvBleUartClient
import net.osmand.plus.settings.fragments.BaseSettingsFragment
import net.osmand.plus.settings.preferences.ListPreferenceEx
import net.osmand.plus.settings.preferences.SwitchPreferenceEx
import net.osmand.plus.utils.AndroidUtils
import net.osmand.plus.utils.UiUtilities

class EvBmsSettingsFragment : BaseSettingsFragment(), EvBmsPlugin.DeviceScanListener {

	private val plugin = PluginsHelper.requirePlugin(EvBmsPlugin::class.java)
	private val found = ArrayList<Pair<String, String>>()
	private var scanningRole: EvBleUartClient.Role? = null

	override fun setupPreferences() {
		setupDevicePref(
			plugin.BMS_ADDRESS.id,
			plugin.BMS_NAME.get(),
			plugin.BMS_ADDRESS.get(),
			plugin.isBmsConnected()
		)
		setupDevicePref(
			plugin.CONTROLLER_ADDRESS.id,
			plugin.CONTROLLER_NAME.get(),
			plugin.CONTROLLER_ADDRESS.get(),
			plugin.isControllerConnected()
		)
		setupPollInterval()
		setupSwitch(plugin.RECORD_TELEMETRY.id)
		setupSwitch(plugin.ANNOUNCE_SOC.id)
		setupSocStep()
		setupSwitch(plugin.ANNOUNCE_RANGE_ON_STOP.id)
		setupStopSpeed()
	}

	override fun onResume() {
		super.onResume()
		plugin.scanListener = this
	}

	override fun onPause() {
		super.onPause()
		plugin.scanListener = null
		plugin.stopScans()
	}

	private fun setupDevicePref(key: String, name: String?, address: String?, connected: Boolean) {
		val pref = findPreference<Preference>(key) ?: return
		val status = when {
			connected -> getString(R.string.ev_bms_status_connected)
			!address.isNullOrEmpty() -> getString(R.string.ev_bms_status_saved, name ?: address)
			else -> getString(R.string.ev_bms_status_not_selected)
		}
		pref.summary = status
	}

	private fun setupPollInterval() {
		val pref = findPreference<ListPreferenceEx>(plugin.POLL_INTERVAL_MS.id) ?: return
		pref.setEntries(arrayOf("1 s", "2 s", "5 s", "10 s"))
		pref.setEntryValues(arrayOf<Any>(1000, 2000, 5000, 10000))
		pref.setValue(plugin.POLL_INTERVAL_MS.get())
	}

	private fun setupSocStep() {
		val pref = findPreference<ListPreferenceEx>(plugin.SOC_STEP_PERCENT.id) ?: return
		pref.setEntries(arrayOf("1 %", "5 %", "10 %"))
		pref.setEntryValues(arrayOf<Any>(1, 5, 10))
		pref.setValue(plugin.SOC_STEP_PERCENT.get())
	}

	private fun setupStopSpeed() {
		val pref = findPreference<ListPreferenceEx>(plugin.STOP_SPEED_KMH.id) ?: return
		pref.setEntries(arrayOf("2 km/h", "3 km/h", "5 km/h"))
		pref.setEntryValues(arrayOf<Any>(2, 3, 5))
		pref.setValue(plugin.STOP_SPEED_KMH.get())
	}

	private fun setupSwitch(key: String) {
		findPreference<SwitchPreferenceEx>(key)
	}

	override fun onPreferenceClick(preference: Preference): Boolean {
		val activity = activity ?: return false
		when (preference.key) {
			plugin.BMS_ADDRESS.id -> {
				startScan(activity, EvBleUartClient.Role.BMS)
				return true
			}
			plugin.CONTROLLER_ADDRESS.id -> {
				startScan(activity, EvBleUartClient.Role.CONTROLLER)
				return true
			}
		}
		return super.onPreferenceClick(preference)
	}

	private fun startScan(activity: Activity, role: EvBleUartClient.Role) {
		if (!AndroidUtils.hasBLEPermission(activity)) {
			AndroidUtils.requestBLEPermissions(activity)
			return
		}
		found.clear()
		scanningRole = role
		app.showToastMessage(R.string.ev_bms_scanning)
		if (role == EvBleUartClient.Role.BMS) {
			plugin.startBmsScan(activity)
		} else {
			plugin.startControllerScan(activity)
		}
	}

	override fun onDeviceFound(role: EvBleUartClient.Role, name: String, address: String) {
		if (role != scanningRole) {
			return
		}
		if (found.none { it.second == address }) {
			found.add(Pair(name, address))
		}
	}

	override fun onScanFinished(role: EvBleUartClient.Role) {
		if (role != scanningRole) {
			return
		}
		val activity = activity ?: return
		if (found.isEmpty()) {
			app.showToastMessage(R.string.ev_bms_nothing_found)
			return
		}
		val names = found.map { "${it.first}\n${it.second}" }.toTypedArray()
		val themed = UiUtilities.getThemedContext(activity, isNightMode())
		AlertDialog.Builder(themed)
			.setTitle(R.string.ev_bms_select_device)
			.setItems(names) { _, which ->
				val selected = found[which]
				if (role == EvBleUartClient.Role.BMS) {
					plugin.connectBms(activity, selected.first, selected.second)
				} else {
					plugin.connectController(activity, selected.first, selected.second)
				}
				setupPreferences()
			}
			.setNegativeButton(R.string.shared_string_cancel, null)
			.show()
	}
}
