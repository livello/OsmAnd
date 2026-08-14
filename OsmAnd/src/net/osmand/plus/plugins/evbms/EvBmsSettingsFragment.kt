package net.osmand.plus.plugins.evbms

import android.app.Activity
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentManager
import androidx.preference.Preference
import net.osmand.plus.R
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.plugins.evbms.ble.EvBleUartClient
import net.osmand.plus.settings.bottomsheets.BooleanRadioButtonsBottomSheet
import net.osmand.plus.settings.fragments.ApplyQueryType
import net.osmand.plus.settings.fragments.BaseSettingsFragment
import net.osmand.plus.settings.preferences.ListPreferenceEx
import net.osmand.plus.settings.preferences.SwitchPreferenceEx
import net.osmand.plus.utils.AndroidUtils
import net.osmand.plus.utils.UiUtilities

class EvBmsSettingsFragment : BaseSettingsFragment(), EvBmsPlugin.DeviceScanListener {

	private val plugin = PluginsHelper.requirePlugin(EvBmsPlugin::class.java)
	private val found = ArrayList<Pair<String, String>>()
	private var scanningRole: EvBleUartClient.Role? = null
	private var pendingScanRole: EvBleUartClient.Role? = null
	private var picker: AlertDialog? = null
	private var pickerAdapter: ArrayAdapter<String>? = null

	private val csvFolderLauncher = registerForActivityResult(
		object : ActivityResultContracts.OpenDocumentTree() {
			override fun createIntent(context: android.content.Context, input: Uri?): Intent {
				return super.createIntent(context, input).apply {
					addFlags(
						Intent.FLAG_GRANT_READ_URI_PERMISSION or
								Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
								Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
								Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
					)
				}
			}
		}
	) { uri ->
		if (uri == null) {
			return@registerForActivityResult
		}
		if (plugin.setCsvFolderUri(uri)) {
			setupCsvFolder()
		} else {
			app.showToastMessage(R.string.folder_access_denied)
		}
	}

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
		setupBmsProtocol()
		setupPollInterval()
		setupSwitch(plugin.RECORD_TELEMETRY.id)
		setupCsvFolder()
		setupSwitch(plugin.ANNOUNCE_SOC.id)
		setupSocStep()
		setupSwitch(plugin.ANNOUNCE_RANGE_ON_STOP.id)
		setupSwitch(plugin.ANNOUNCE_RANGE_VS_ROUTE.id)
		setupStopSpeed()
		setupSwitch(plugin.ANNOUNCE_CELL_VOLTAGE.id)
		setupCellThreshold(plugin.LOW_CELL_MV, arrayOf(3600, 3550, 3500, 3450, 3400))
		setupCellThreshold(plugin.CRITICAL_CELL_MV, arrayOf(3400, 3350, 3300, 3250, 3200, 3100))
		setupCellAlertInterval()
		setupRouteProfile()
	}

	override fun onResume() {
		super.onResume()
		plugin.scanListener = this
		val pending = pendingScanRole
		val activity = activity
		if (pending != null && activity != null && AndroidUtils.hasBLEPermission(activity)) {
			pendingScanRole = null
			startScan(activity, pending)
		}
	}

	override fun onDestroyView() {
		dismissPicker()
		plugin.scanListener = null
		plugin.stopScans()
		super.onDestroyView()
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

	private fun setupBmsProtocol() {
		val pref = findPreference<ListPreferenceEx>(plugin.BMS_PROTOCOL.id) ?: return
		pref.setEntries(
			arrayOf(
				getString(R.string.ev_bms_protocol_auto),
				getString(R.string.ev_bms_protocol_jbd),
				getString(R.string.ev_bms_protocol_ant)
			)
		)
		pref.setEntryValues(arrayOf<Any>("auto", "jbd", "ant"))
		pref.setValue(plugin.BMS_PROTOCOL.get())
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

	private fun setupCellThreshold(prefHolder: net.osmand.plus.settings.backend.preferences.CommonPreference<Int>, millivolts: Array<Int>) {
		val pref = findPreference<ListPreferenceEx>(prefHolder.id) ?: return
		pref.setEntries(millivolts.map { String.format(java.util.Locale.US, "%.2f V", it / 1000.0) }.toTypedArray())
		pref.setEntryValues(millivolts.map { it as Any }.toTypedArray())
		pref.setValue(prefHolder.get())
	}

	private fun setupCellAlertInterval() {
		val pref = findPreference<ListPreferenceEx>(plugin.CELL_ALERT_INTERVAL_SEC.id) ?: return
		pref.setEntries(arrayOf("15 s", "30 s", "1 min", "2 min", "5 min"))
		pref.setEntryValues(arrayOf<Any>(15, 30, 60, 120, 300))
		pref.setValue(plugin.CELL_ALERT_INTERVAL_SEC.get())
	}

	private fun setupRouteProfile() {
		val pref = findPreference<SwitchPreferenceEx>(plugin.USE_ROUTE_PROFILE.id) ?: return
		pref.setDescription(R.string.ev_bms_use_route_profile_desc)
		pref.summary = getString(
			if (plugin.USE_ROUTE_PROFILE.get()) R.string.shared_string_enabled
			else R.string.shared_string_disabled
		)
	}

	private fun setupCsvFolder() {
		val pref = findPreference<Preference>("ev_bms_csv_folder") ?: return
		pref.summary = plugin.csvFolderSummary()
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
			"ev_bms_csv_folder" -> {
				csvFolderLauncher.launch(null)
				return true
			}
			"ev_bms_export_csv" -> {
				showExportDialog(activity)
				return true
			}
		}
		return super.onPreferenceClick(preference)
	}

	override fun onDisplayPreferenceDialog(preference: Preference) {
		if (preference.key == plugin.USE_ROUTE_PROFILE.id) {
			val manager: FragmentManager = fragmentManager ?: return
			BooleanRadioButtonsBottomSheet.showInstance(
				manager,
				preference.key,
				applyQueryType ?: ApplyQueryType.NONE,
				this,
				selectedAppMode,
				false,
				isProfileDependent
			)
			return
		}
		super.onDisplayPreferenceDialog(preference)
	}

	override fun onPreferenceChanged(prefId: String) {
		super.onPreferenceChanged(prefId)
		if (prefId == plugin.USE_ROUTE_PROFILE.id) {
			setupRouteProfile()
		}
		if (prefId == plugin.RECORD_TELEMETRY.id) {
			plugin.applyHikeTelemetryState()
		}
		if (prefId == plugin.BMS_PROTOCOL.id) {
			val act = activity ?: return
			val name = plugin.BMS_NAME.get()
			val address = plugin.BMS_ADDRESS.get()
			if (!address.isNullOrEmpty()) {
				plugin.connectBms(act, name ?: address, address)
			}
		}
	}

	private fun showExportDialog(activity: Activity) {
		val files = plugin.listCsvFiles()
		if (files.isEmpty()) {
			app.showToastMessage(R.string.ev_bms_csv_none)
			return
		}
		val themed = UiUtilities.getThemedContext(activity, isNightMode())
		val labels = files.map { it.name }.toTypedArray()
		AlertDialog.Builder(themed)
			.setTitle(R.string.ev_bms_export_csv)
			.setItems(labels) { _, which ->
				if (which in files.indices) {
					plugin.shareCsv(activity, listOf(files[which].uri))
				}
			}
			.setPositiveButton(R.string.shared_string_share) { _, _ ->
				plugin.shareCsv(activity, files.map { it.uri })
			}
			.setNegativeButton(R.string.shared_string_cancel, null)
			.show()
	}

	private fun startScan(activity: Activity, role: EvBleUartClient.Role) {
		if (!AndroidUtils.hasBLEPermission(activity)) {
			pendingScanRole = role
			AndroidUtils.requestBLEPermissions(activity)
			return
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			val lm = activity.getSystemService(LocationManager::class.java)
			if (lm != null && !lm.isLocationEnabled) {
				app.showToastMessage(R.string.ev_bms_location_off)
			}
		}
		found.clear()
		scanningRole = role
		showPicker(activity)
		if (role == EvBleUartClient.Role.BMS) {
			plugin.startBmsScan(activity)
		} else {
			plugin.startControllerScan(activity)
		}
	}

	private fun showPicker(activity: Activity) {
		dismissPicker()
		val themed = UiUtilities.getThemedContext(activity, isNightMode())
		val adapter = ArrayAdapter(themed, android.R.layout.simple_list_item_1, ArrayList<String>())
		pickerAdapter = adapter
		picker = AlertDialog.Builder(themed)
			.setTitle(R.string.ev_bms_scanning)
			.setAdapter(adapter) { _, which ->
				if (which in found.indices) {
					val selected = found[which]
					val role = scanningRole
					if (role == EvBleUartClient.Role.BMS) {
						plugin.connectBms(activity, selected.first, selected.second)
					} else if (role == EvBleUartClient.Role.CONTROLLER) {
						plugin.connectController(activity, selected.first, selected.second)
					}
					setupPreferences()
				}
				plugin.stopScans()
			}
			.setNegativeButton(R.string.shared_string_cancel) { _, _ -> plugin.stopScans() }
			.setOnDismissListener {
				picker = null
				pickerAdapter = null
			}
			.show()
	}

	private fun dismissPicker() {
		picker?.setOnDismissListener(null)
		picker?.dismiss()
		picker = null
		pickerAdapter = null
	}

	override fun onDeviceFound(role: EvBleUartClient.Role, name: String, address: String) {
		if (role != scanningRole) {
			return
		}
		if (found.any { it.second == address }) {
			return
		}
		found.add(Pair(name, address))
		val activity = activity ?: return
		if (picker == null) {
			showPicker(activity)
		}
		pickerAdapter?.add("$name\n$address")
		picker?.setTitle(R.string.ev_bms_select_device)
	}

	override fun onScanFinished(role: EvBleUartClient.Role) {
		if (role != scanningRole) {
			return
		}
		if (found.isEmpty()) {
			dismissPicker()
			app.showToastMessage(R.string.ev_bms_nothing_found)
			return
		}
		picker?.setTitle(R.string.ev_bms_select_device)
	}
}
