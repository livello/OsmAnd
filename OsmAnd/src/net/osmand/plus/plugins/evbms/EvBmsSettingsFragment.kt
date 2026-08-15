package net.osmand.plus.plugins.evbms

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentManager
import androidx.preference.Preference
import androidx.recyclerview.widget.RecyclerView
import net.osmand.plus.R
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.plugins.evbms.ble.EvBleUartClient
import net.osmand.plus.settings.bottomsheets.BooleanRadioButtonsBottomSheet
import net.osmand.plus.settings.fragments.ApplyQueryType
import net.osmand.plus.settings.fragments.BaseSettingsFragment
import net.osmand.plus.settings.preferences.EditTextPreferenceEx
import net.osmand.plus.settings.preferences.ListPreferenceEx
import net.osmand.plus.settings.preferences.SwitchPreferenceEx
import net.osmand.plus.utils.AndroidUtils
import net.osmand.plus.utils.ColorUtilities
import net.osmand.plus.utils.OsmAndFormatter
import net.osmand.plus.utils.UiUtilities
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EvBmsSettingsFragment : BaseSettingsFragment(), EvBmsPlugin.DeviceScanListener {

	companion object {
		const val EMBEDDED_KEY = "ev_bms_settings_embedded"
	}

	private val plugin = PluginsHelper.requirePlugin(EvBmsPlugin::class.java)
	private val found = ArrayList<Pair<String, String>>()
	private var scanningRole: EvBleUartClient.Role? = null
	private var pendingScanRole: EvBleUartClient.Role? = null
	private var picker: AlertDialog? = null
	private var pickerAdapter: ArrayAdapter<String>? = null
	private val uiHandler = Handler(Looper.getMainLooper())
	private val fieldValueViews = ArrayList<Pair<TelemetryField, TextView>>()
	private val refreshFieldValues = object : Runnable {
		override fun run() {
			val ctx = context ?: return
			val sample = plugin.latestTelemetry
			for ((field, view) in fieldValueViews) {
				view.text = field.liveValue(ctx, sample)
			}
			uiHandler.postDelayed(this, 1000)
		}
	}
	private val refreshCalibration = object : Runnable {
		override fun run() {
			if (view == null) {
				return
			}
			refreshCalibrationPref()
			(parentFragment as? EvBmsSettingsBottomSheet)?.onCalibrationTick()
			uiHandler.postDelayed(this, 1000)
		}
	}

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

	private fun isEmbedded(): Boolean = arguments?.getBoolean(EMBEDDED_KEY) == true

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View {
		val view = super.onCreateView(inflater, container, savedInstanceState)!!
		if (isEmbedded()) {
			view.findViewById<View>(R.id.appbar)?.visibility = View.GONE
			view.setPadding(view.paddingLeft, 0, view.paddingRight, view.paddingBottom)
			listView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
				override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
					val atTop = !recyclerView.canScrollVertically(-1)
					(parentFragment as? EvBmsSettingsBottomSheet)?.setActionButtonsVisible(atTop)
				}
			})
			setActionFooterInset(true)
		}
		return view
	}

	fun setActionFooterInset(buttonsVisible: Boolean) {
		if (!isEmbedded()) {
			return
		}
		val bottom = AndroidUtils.dpToPx(app, if (buttonsVisible) 88f else 12f)
		listView.setPadding(listView.paddingLeft, listView.paddingTop, listView.paddingRight, bottom)
	}

	override fun updateStatusBar() {
		if (!isEmbedded()) {
			super.updateStatusBar()
		}
	}

	override fun setupPreferences() {
		findPreference<Preference>("ev_bms_devices")?.isVisible = false
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
		setupControllerTitle()
		setupBmsProtocol()
		setupJbdPassword()
		setupControllerProtocol()
		setupSwitch(plugin.HIKE_MODE.id)
		setupCalDistance()
		setupCalFactor()
		setupCalAction()
		setupPollInterval()
		setupSwitch(plugin.RECORD_TELEMETRY.id)
		setupSwitch(plugin.RECORD_GPX.id)
		findPreference<SwitchPreferenceEx>(plugin.RECORD_GPX.id)?.setDescription(R.string.ev_bms_record_gpx_desc)
		setupTelemetryFields()
		setupCsvFolder()
		setupChargeStillSec()
		setupChargeStillKmh()
		setupChargeCurrent()
		setupChargeRearmDistance()
		setupChargeRearmAh()
		setupSwitch(plugin.ANNOUNCE_SOC.id)
		setupChargeVoltStep()
		setupSwitch(plugin.ANNOUNCE_RANGE_ON_STOP.id)
		setupStopRepeats()
		setupSwitch(plugin.ANNOUNCE_RANGE_VS_ROUTE.id)
		setupStopSpeed()
		setupSwitch(plugin.ANNOUNCE_CELL_VOLTAGE.id)
		setupCellThreshold(plugin.LOW_CELL_MV, arrayOf(3600, 3550, 3500, 3450, 3400))
		setupCellThreshold(plugin.CRITICAL_CELL_MV, arrayOf(3400, 3350, 3300, 3250, 3200, 3100))
		setupCellAlertInterval()
		setupSwitch(plugin.ANNOUNCE_MOTOR_HEAT.id)
		setupTempThreshold(plugin.MOTOR_HEAT_C, arrayOf(70, 80, 90, 100, 110, 120))
		setupSwitch(plugin.ANNOUNCE_BATTERY_OVERHEAT.id)
		setupTempThreshold(plugin.BATTERY_OVERHEAT_C, arrayOf(40, 45, 50, 55, 60))
		setupSwitch(plugin.ANNOUNCE_BATTERY_FREEZE.id)
		setupTempThreshold(plugin.BATTERY_FREEZE_C, arrayOf(5, 0, -5, -10))
		setupSwitch(plugin.ANNOUNCE_LINK.id)
		setupSwitch(plugin.ANNOUNCE_CHARGE_ETA.id)
		setupHistoryPrefs()
		setupRouteProfile()
		setupMassPrefs()
		setupIcons()
		refreshRecordingPref()
		refreshCalibrationPref()
	}

	fun refreshTelemetryFieldsPref() {
		setupTelemetryFields()
	}

	fun refreshRecordingPref() {
		val pref = findPreference<SwitchPreferenceEx>(plugin.RECORD_TELEMETRY.id) ?: return
		pref.isChecked = plugin.hasTelemetrySession()
	}

	fun refreshCalibrationPref() {
		setupCalFactor()
		setupCalAction()
	}

	private fun setupIcons() {
		decorate(plugin.BMS_ADDRESS.id, "🔋", R.drawable.ic_action_battery)
		decorate(plugin.BMS_PROTOCOL.id, "🔗", R.drawable.ic_action_settings)
		decorate(plugin.BMS_PASSWORD.id, "🔐", R.drawable.ic_action_lock)
		decorate(plugin.CONTROLLER_ADDRESS.id, "🛵", R.drawable.ic_action_car_info)
		decorate(plugin.CONTROLLER_PROTOCOL.id, "⚙️", R.drawable.ic_action_settings)
		decorate(plugin.HIKE_MODE.id, "🥾", R.drawable.ic_action_trekking_dark)
		decorate(plugin.SPEED_CAL_DISTANCE_M.id, "📏", R.drawable.ic_action_distance)
		decorate(plugin.SPEED_CAL_FACTOR.id, "✖️", R.drawable.ic_action_speed)
		decorate("ev_bms_cal_start", "▶️", R.drawable.ic_action_play_dark)
		decorate(plugin.POLL_INTERVAL_MS.id, "⏱️", R.drawable.ic_action_time)
		decorate(plugin.RECORD_TELEMETRY.id, "📝", R.drawable.ic_action_save_to_file)
		decorate(plugin.RECORD_GPX.id, "🗺️", R.drawable.ic_action_polygom_dark)
		decorate(plugin.TELEMETRY_FIELDS.id, "☑️", R.drawable.ic_action_list_flat)
		decorate("ev_bms_csv_folder", "📁", R.drawable.ic_action_folder)
		decorate("ev_bms_export_csv", "📤", R.drawable.ic_action_gshare_dark)
		decorate(plugin.ANNOUNCE_SOC.id, "🔋", R.drawable.ic_action_battery)
		decorate(plugin.CHARGE_VOLT_STEP_MV.id, "⚡", R.drawable.ic_action_obd_battery_voltage)
		decorate(plugin.ANNOUNCE_RANGE_ON_STOP.id, "📏", R.drawable.ic_action_distance)
		decorate(plugin.STOP_ANNOUNCE_REPEATS.id, "🔁", R.drawable.ic_action_time_span)
		decorate(plugin.ANNOUNCE_RANGE_VS_ROUTE.id, "🧭", R.drawable.ic_action_gdirections_dark)
		decorate(plugin.STOP_SPEED_KMH.id, "🐢", R.drawable.ic_action_speed)
		decorate(plugin.ANNOUNCE_CELL_VOLTAGE.id, "⚠️", R.drawable.ic_action_alert)
		decorate(plugin.LOW_CELL_MV.id, "🔻", R.drawable.ic_action_arrow_down)
		decorate(plugin.CRITICAL_CELL_MV.id, "⛔", R.drawable.ic_action_alert)
		decorate(plugin.CELL_ALERT_INTERVAL_SEC.id, "⏰", R.drawable.ic_action_time_span)
		decorate(plugin.ANNOUNCE_MOTOR_HEAT.id, "🔥", R.drawable.ic_action_thermometer)
		decorate(plugin.MOTOR_HEAT_C.id, "🌡️", R.drawable.ic_action_thermometer)
		decorate(plugin.ANNOUNCE_BATTERY_OVERHEAT.id, "🥵", R.drawable.ic_action_thermometer)
		decorate(plugin.BATTERY_OVERHEAT_C.id, "🌡️", R.drawable.ic_action_thermometer)
		decorate(plugin.ANNOUNCE_BATTERY_FREEZE.id, "❄️", R.drawable.ic_action_thermometer)
		decorate(plugin.BATTERY_FREEZE_C.id, "❄️", R.drawable.ic_action_thermometer)
		decorate(plugin.ANNOUNCE_LINK.id, "📡", R.drawable.ic_action_offline)
		decorate(plugin.CHARGE_STILL_SEC.id, "⏸️", R.drawable.ic_action_time)
		decorate(plugin.CHARGE_STILL_KMH.id, "🚶", R.drawable.ic_action_speed)
		decorate(plugin.CHARGE_CURRENT_A.id, "⚡", R.drawable.ic_action_battery)
		decorate(plugin.CHARGE_REARM_M.id, "🛵", R.drawable.ic_action_distance)
		decorate(plugin.CHARGE_REARM_MAH.id, "🔋", R.drawable.ic_action_battery)
		decorate(plugin.ANNOUNCE_CHARGE_ETA.id, "⏳", R.drawable.ic_action_time_to_distance)
		decorate("ev_bms_charge_history", "📋", R.drawable.ic_action_history)
		decorate("ev_bms_trip_history", "🛵", R.drawable.ic_action_track_recordable)
		decorate(plugin.USE_ROUTE_PROFILE.id, "⛰️", R.drawable.ic_action_altitude)
		decorate(plugin.VEHICLE_MASS_KG.id, "⚖️", R.drawable.ic_action_weight_limit)
		decorate(plugin.DRIVER_MASS_KG.id, "👤", R.drawable.ic_action_user)
	}

	private fun decorate(key: String, emoji: String, iconRes: Int) {
		val pref = findPreference<Preference>(key) ?: return
		val title = pref.title?.toString().orEmpty()
		if (title.isNotEmpty() && !title.startsWith(emoji)) {
			pref.title = "$emoji $title"
		}
		pref.icon = getContentIcon(iconRes)
	}

	override fun onResume() {
		super.onResume()
		plugin.scanListener = this
		uiHandler.removeCallbacks(refreshCalibration)
		uiHandler.post(refreshCalibration)
		val pending = pendingScanRole
		val activity = activity
		if (pending != null && activity != null && AndroidUtils.hasBLEPermission(activity)) {
			pendingScanRole = null
			startScan(activity, pending)
		}
	}

	override fun onDestroyView() {
		uiHandler.removeCallbacks(refreshFieldValues)
		uiHandler.removeCallbacks(refreshCalibration)
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

	private fun setupControllerTitle() {
		val pref = findPreference<Preference>(plugin.CONTROLLER_ADDRESS.id) ?: return
		pref.title = plugin.controllerDisplayName()
	}

	private fun setupControllerProtocol() {
		val pref = findPreference<ListPreferenceEx>(plugin.CONTROLLER_PROTOCOL.id) ?: return
		pref.setEntries(
			arrayOf(
				getString(R.string.ev_bms_protocol_auto),
				getString(R.string.ev_bms_protocol_fardriver),
				getString(R.string.ev_bms_protocol_vesc)
			)
		)
		pref.setEntryValues(arrayOf<Any>("auto", "fardriver", "vesc"))
		pref.setValue(plugin.CONTROLLER_PROTOCOL.get())
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

	private fun setupJbdPassword() {
		val pref = findPreference<EditTextPreferenceEx>(plugin.BMS_PASSWORD.id) ?: return
		val value = plugin.BMS_PASSWORD.get()
		pref.isVisible = plugin.BMS_PROTOCOL.get() != "ant"
		pref.text = value
		pref.summary = if (value.isEmpty()) {
			getString(R.string.ev_bms_jbd_password_not_set)
		} else {
			"••••••"
		}
		pref.setDescription(R.string.ev_bms_jbd_password_desc)
	}

	private fun setupPollInterval() {
		val pref = findPreference<ListPreferenceEx>(plugin.POLL_INTERVAL_MS.id) ?: return
		pref.setEntries(
			arrayOf(
				getString(R.string.ev_bms_n_sec, 1),
				getString(R.string.ev_bms_n_sec, 2),
				getString(R.string.ev_bms_n_sec, 5),
				getString(R.string.ev_bms_n_sec, 10)
			)
		)
		pref.setEntryValues(arrayOf<Any>(1000, 2000, 5000, 10000))
		pref.setValue(plugin.POLL_INTERVAL_MS.get())
	}

	private fun setupCalDistance() {
		val pref = findPreference<ListPreferenceEx>(plugin.SPEED_CAL_DISTANCE_M.id) ?: return
		val meters = arrayOf(500, 1000, 2000, 5000)
		pref.setEntries(meters.map { OsmAndFormatter.getFormattedDistance(it.toFloat(), app) }.toTypedArray())
		pref.setEntryValues(meters.map { it as Any }.toTypedArray())
		pref.setValue(plugin.SPEED_CAL_DISTANCE_M.get())
	}

	private fun setupCalFactor() {
		val pref = findPreference<EditTextPreferenceEx>(plugin.SPEED_CAL_FACTOR.id) ?: return
		val text = plugin.formattedSpeedCalFactor()
		pref.text = text
		pref.summary = text
		pref.setDescription(R.string.ev_bms_cal_factor_desc)
	}

	private fun setupCalAction() {
		val pref = findPreference<Preference>("ev_bms_cal_start") ?: return
		if (plugin.isSpeedCalibrating()) {
			pref.title = getString(R.string.ev_bms_calibrate_stop)
			val done = OsmAndFormatter.getFormattedDistance(plugin.speedCalProgressGpsM().toFloat(), app)
			val target = OsmAndFormatter.getFormattedDistance(plugin.speedCalTargetMeters().toFloat(), app)
			pref.summary = getString(R.string.ev_bms_cal_progress, done, target)
		} else {
			pref.title = getString(R.string.ev_bms_cal_start)
			pref.summary = getString(R.string.ev_bms_cal_factor_desc)
		}
	}

	private fun setupChargeVoltStep() {
		val pref = findPreference<ListPreferenceEx>(plugin.CHARGE_VOLT_STEP_MV.id) ?: return
		pref.setEntries(
			arrayOf(
				getString(R.string.ev_bms_n_volt, 0.5),
				getString(R.string.ev_bms_n_volt, 1.0),
				getString(R.string.ev_bms_n_volt, 2.0)
			)
		)
		pref.setEntryValues(arrayOf<Any>(500, 1000, 2000))
		pref.setValue(plugin.CHARGE_VOLT_STEP_MV.get())
	}

	private fun setupStopRepeats() {
		val pref = findPreference<ListPreferenceEx>(plugin.STOP_ANNOUNCE_REPEATS.id) ?: return
		val values = arrayOf(1, 2, 3, 5, 10)
		pref.setEntries(values.map { getString(R.string.ev_bms_n_repeats, it) }.toTypedArray())
		pref.setEntryValues(values.map { it as Any }.toTypedArray())
		pref.setValue(plugin.STOP_ANNOUNCE_REPEATS.get())
	}

	private fun setupStopSpeed() {
		val pref = findPreference<ListPreferenceEx>(plugin.STOP_SPEED_KMH.id) ?: return
		pref.setEntries(
			arrayOf(
				getString(R.string.ev_bms_n_kmh, 2),
				getString(R.string.ev_bms_n_kmh, 3),
				getString(R.string.ev_bms_n_kmh, 5)
			)
		)
		pref.setEntryValues(arrayOf<Any>(2, 3, 5))
		pref.setValue(plugin.STOP_SPEED_KMH.get())
	}

	private fun setupCellThreshold(prefHolder: net.osmand.plus.settings.backend.preferences.CommonPreference<Int>, millivolts: Array<Int>) {
		val pref = findPreference<ListPreferenceEx>(prefHolder.id) ?: return
		pref.setEntries(millivolts.map { getString(R.string.ev_bms_n_volt, it / 1000.0) }.toTypedArray())
		pref.setEntryValues(millivolts.map { it as Any }.toTypedArray())
		pref.setValue(prefHolder.get())
	}

	private fun setupCellAlertInterval() {
		val pref = findPreference<ListPreferenceEx>(plugin.CELL_ALERT_INTERVAL_SEC.id) ?: return
		pref.setEntries(
			arrayOf(
				getString(R.string.ev_bms_n_sec, 15),
				getString(R.string.ev_bms_n_sec, 30),
				getString(R.string.ev_bms_n_min, 1),
				getString(R.string.ev_bms_n_min, 2),
				getString(R.string.ev_bms_n_min, 5)
			)
		)
		pref.setEntryValues(arrayOf<Any>(15, 30, 60, 120, 300))
		pref.setValue(plugin.CELL_ALERT_INTERVAL_SEC.get())
	}

	private fun setupTempThreshold(prefHolder: net.osmand.plus.settings.backend.preferences.CommonPreference<Int>, celsius: Array<Int>) {
		val pref = findPreference<ListPreferenceEx>(prefHolder.id) ?: return
		pref.setEntries(celsius.map { getString(R.string.ev_bms_n_celsius, it) }.toTypedArray())
		pref.setEntryValues(celsius.map { it as Any }.toTypedArray())
		pref.setValue(prefHolder.get())
	}

	private fun setupRouteProfile() {
		val pref = findPreference<SwitchPreferenceEx>(plugin.USE_ROUTE_PROFILE.id) ?: return
		pref.setDescription(R.string.ev_bms_use_route_profile_desc)
		pref.summary = getString(
			if (plugin.USE_ROUTE_PROFILE.get()) R.string.shared_string_enabled
			else R.string.shared_string_disabled
		)
	}

	private fun setupMassPrefs() {
		setupMassPref(plugin.VEHICLE_MASS_KG, R.string.ev_bms_vehicle_mass_desc)
		setupMassPref(plugin.DRIVER_MASS_KG, R.string.ev_bms_driver_mass_desc)
	}

	private fun setupMassPref(
		holder: net.osmand.plus.settings.backend.preferences.CommonPreference<Float>,
		descRes: Int
	) {
		val pref = findPreference<EditTextPreferenceEx>(holder.id) ?: return
		val text = plugin.formattedMassKg(holder.get())
		pref.text = text
		pref.summary = getString(R.string.ev_bms_n_kg, text)
		pref.setDescription(descRes)
	}

	private fun setupTelemetryFields() {
		val pref = findPreference<Preference>(plugin.TELEMETRY_FIELDS.id) ?: return
		pref.summary = plugin.telemetryFieldsSummary(requireContext())
	}

	private fun setupChargeStillSec() {
		val pref = findPreference<ListPreferenceEx>(plugin.CHARGE_STILL_SEC.id) ?: return
		pref.setEntries(
			arrayOf(
				getString(R.string.ev_bms_n_sec, 30),
				getString(R.string.ev_bms_n_sec, 60),
				getString(R.string.ev_bms_n_sec, 90),
				getString(R.string.ev_bms_n_sec, 120)
			)
		)
		pref.setEntryValues(arrayOf<Any>(30, 60, 90, 120))
		pref.setValue(plugin.CHARGE_STILL_SEC.get())
	}

	private fun setupChargeStillKmh() {
		val pref = findPreference<ListPreferenceEx>(plugin.CHARGE_STILL_KMH.id) ?: return
		pref.setEntries(
			arrayOf(
				getString(R.string.ev_bms_n_kmh, 1),
				getString(R.string.ev_bms_n_kmh, 2),
				getString(R.string.ev_bms_n_kmh, 3)
			)
		)
		pref.setEntryValues(arrayOf<Any>(1, 2, 3))
		pref.setValue(plugin.CHARGE_STILL_KMH.get())
	}

	fun refreshHistoryPrefs() {
		setupHistoryPrefs()
	}

	private fun setupChargeRearmDistance() {
		val pref = findPreference<ListPreferenceEx>(plugin.CHARGE_REARM_M.id) ?: return
		pref.setEntries(
			arrayOf(
				getString(R.string.ev_bms_n_meters, 50),
				getString(R.string.ev_bms_n_meters, 100),
				getString(R.string.ev_bms_n_meters, 200),
				getString(R.string.ev_bms_n_meters, 500),
				getString(R.string.ev_bms_n_km_int, 1)
			)
		)
		pref.setEntryValues(arrayOf<Any>(50, 100, 200, 500, 1000))
		pref.setValue(plugin.CHARGE_REARM_M.get())
	}

	private fun setupChargeRearmAh() {
		val pref = findPreference<ListPreferenceEx>(plugin.CHARGE_REARM_MAH.id) ?: return
		pref.setEntries(
			arrayOf(
				getString(R.string.ev_bms_n_ah, "0.1"),
				getString(R.string.ev_bms_n_ah, "0.2"),
				getString(R.string.ev_bms_n_ah, "0.3"),
				getString(R.string.ev_bms_n_ah, "0.5"),
				getString(R.string.ev_bms_n_ah, "1.0")
			)
		)
		pref.setEntryValues(arrayOf<Any>(100, 200, 300, 500, 1000))
		pref.setValue(plugin.CHARGE_REARM_MAH.get())
	}

	private fun setupChargeCurrent() {
		val pref = findPreference<ListPreferenceEx>(plugin.CHARGE_CURRENT_A.id) ?: return
		pref.setEntries(
			arrayOf(
				getString(R.string.ev_bms_n_amp, 2),
				getString(R.string.ev_bms_n_amp, 3),
				getString(R.string.ev_bms_n_amp, 5),
				getString(R.string.ev_bms_n_amp, 8),
				getString(R.string.ev_bms_n_amp, 10)
			)
		)
		pref.setEntryValues(arrayOf<Any>(2, 3, 5, 8, 10))
		pref.setValue(plugin.CHARGE_CURRENT_A.get())
	}

	private fun setupCsvFolder() {
		val pref = findPreference<Preference>("ev_bms_csv_folder") ?: return
		pref.summary = plugin.csvFolderSummary()
	}

	private fun setupHistoryPrefs() {
		val charges = plugin.chargeHistory().size
		findPreference<Preference>("ev_bms_charge_history")?.summary =
			getString(R.string.ev_bms_history_count, charges)
		val trips = plugin.tripHistory().size
		findPreference<Preference>("ev_bms_trip_history")?.summary =
			getString(R.string.ev_bms_history_count, trips)
	}

	private fun setupSwitch(key: String) {
		findPreference<SwitchPreferenceEx>(key)
	}

	override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
		if (preference.key == plugin.HIKE_MODE.id) {
			val enabled = newValue as? Boolean ?: return false
			val mapActivity = getMapActivity()
			if (mapActivity != null && plugin.isHikeMode() != enabled) {
				plugin.toggleHikeMode(mapActivity)
			}
			return true
		}
		if (preference.key == plugin.SPEED_CAL_FACTOR.id) {
			val parsed = plugin.parseSpeedCalFactor(newValue as? String)
			if (parsed == null) {
				app.showToastMessage(R.string.ev_bms_cal_factor_invalid)
				return false
			}
			plugin.SPEED_CAL_FACTOR.set(parsed)
			setupCalFactor()
			return true
		}
		if (preference.key == plugin.VEHICLE_MASS_KG.id) {
			val parsed = plugin.parseMassKg(newValue as? String, 20f, 2000f)
			if (parsed == null) {
				app.showToastMessage(R.string.ev_bms_mass_invalid)
				return false
			}
			plugin.VEHICLE_MASS_KG.set(parsed)
			setupMassPrefs()
			return true
		}
		if (preference.key == plugin.DRIVER_MASS_KG.id) {
			val parsed = plugin.parseMassKg(newValue as? String, 0f, 400f)
			if (parsed == null) {
				app.showToastMessage(R.string.ev_bms_mass_invalid)
				return false
			}
			plugin.DRIVER_MASS_KG.set(parsed)
			setupMassPrefs()
			return true
		}
		if (preference.key == plugin.BMS_PASSWORD.id) {
			val parsed = plugin.parseJbdPassword(newValue as? String)
			if (parsed == null) {
				app.showToastMessage(R.string.ev_bms_jbd_password_invalid)
				return false
			}
			plugin.BMS_PASSWORD.set(parsed)
			plugin.onJbdPasswordChanged()
			setupJbdPassword()
			return true
		}
		return super.onPreferenceChange(preference, newValue)
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
			plugin.TELEMETRY_FIELDS.id, "ev_bms_telemetry_fields" -> {
				val sheet = parentFragment as? EvBmsSettingsBottomSheet
				if (sheet != null) {
					sheet.showTab(EvBmsSheetTab.FIELDS)
				} else {
					showTelemetryFieldsDialog(activity)
				}
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
			"ev_bms_charge_history" -> {
				val sheet = parentFragment as? EvBmsSettingsBottomSheet
				if (sheet != null) {
					sheet.showTab(EvBmsSheetTab.HISTORY)
				} else {
					showChargeHistoryDialog(activity)
				}
				return true
			}
			"ev_bms_trip_history" -> {
				val sheet = parentFragment as? EvBmsSettingsBottomSheet
				if (sheet != null) {
					sheet.showTab(EvBmsSheetTab.HISTORY)
				} else {
					showTripHistoryDialog(activity)
				}
				return true
			}
			"ev_bms_cal_start" -> {
				if (plugin.isSpeedCalibrating()) {
					plugin.stopSpeedCalibration()
				} else {
					plugin.startSpeedCalibration()
				}
				refreshCalibrationPref()
				(parentFragment as? EvBmsSettingsBottomSheet)?.onCalibrationTick()
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
		if (prefId == plugin.SPEED_CAL_FACTOR.id) {
			setupCalFactor()
		}
		if (prefId == plugin.VEHICLE_MASS_KG.id || prefId == plugin.DRIVER_MASS_KG.id) {
			setupMassPrefs()
		}
		if (prefId == plugin.BMS_PASSWORD.id) {
			setupJbdPassword()
		}
		if (prefId == plugin.RECORD_TELEMETRY.id) {
			plugin.applyHikeTelemetryState()
		}
		if (prefId == plugin.RECORD_GPX.id) {
			plugin.restartTelemetryIfRecording()
		}
		if (prefId == plugin.BMS_PROTOCOL.id) {
			setupJbdPassword()
			val act = activity ?: return
			val name = plugin.BMS_NAME.get()
			val address = plugin.BMS_ADDRESS.get()
			if (!address.isNullOrEmpty()) {
				plugin.connectBms(act, name ?: address, address)
			}
		}
		if (prefId == plugin.CONTROLLER_PROTOCOL.id) {
			val act = activity ?: return
			val name = plugin.CONTROLLER_NAME.get()
			val address = plugin.CONTROLLER_ADDRESS.get()
			if (!address.isNullOrEmpty()) {
				plugin.connectController(act, name ?: address, address)
			}
			setupControllerTitle()
		}
	}

	private fun showTelemetryFieldsDialog(activity: Activity) {
		val themed = UiUtilities.getThemedContext(activity, isNightMode())
		val selected = plugin.selectedTelemetryFields().toMutableSet()
		val inflater = LayoutInflater.from(themed)
		val content = inflater.inflate(R.layout.ev_bms_telemetry_fields_dialog, null)
		val list = content.findViewById<LinearLayout>(R.id.fields_list)
		val checkboxes = ArrayList<Pair<TelemetryField, CheckBox>>()
		fieldValueViews.clear()
		fun bindChecks() {
			for ((field, box) in checkboxes) {
				box.isChecked = field in selected
			}
		}
		content.findViewById<TextView>(R.id.select_all).apply {
			contentDescription = getString(R.string.shared_string_select_all)
			setOnClickListener {
				selected.clear()
				selected.addAll(TelemetryField.entries)
				bindChecks()
			}
		}
		content.findViewById<TextView>(R.id.reset).apply {
			contentDescription = getString(R.string.shared_string_reset)
			setOnClickListener {
				selected.clear()
				selected.addAll(TelemetryField.parse(TelemetryField.DEFAULT_IDS))
				bindChecks()
			}
		}
		for ((groupRes, fields) in TelemetryField.grouped()) {
			list.addView(telemetryGroupHeader(themed, groupRes))
			for (field in fields) {
				val row = inflater.inflate(R.layout.ev_bms_telemetry_field_row, list, false)
				val box = row.findViewById<CheckBox>(R.id.compound_button)
				val title = row.findViewById<TextView>(R.id.title)
				val value = row.findViewById<TextView>(R.id.value)
				title.text = "${field.emoji} ${getString(field.titleRes)}"
				value.text = field.liveValue(themed, plugin.latestTelemetry)
				box.isChecked = field in selected
				UiUtilities.setupCompoundButton(box, isNightMode(), UiUtilities.CompoundButtonType.GLOBAL)
				row.setOnClickListener {
					box.isChecked = !box.isChecked
					if (box.isChecked) selected.add(field) else selected.remove(field)
				}
				checkboxes.add(field to box)
				fieldValueViews.add(field to value)
				list.addView(row)
			}
		}
		val dialog = Dialog(themed)
		dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
		dialog.setContentView(content)
		dialog.setOnDismissListener {
			uiHandler.removeCallbacks(refreshFieldValues)
			fieldValueViews.clear()
		}
		content.findViewById<View>(R.id.cancel).setOnClickListener { dialog.dismiss() }
		content.findViewById<View>(R.id.apply).setOnClickListener {
			val chosen = TelemetryField.entries.filter { it in selected }
			if (chosen.isEmpty()) {
				app.showToastMessage(R.string.ev_bms_telemetry_fields_empty)
				return@setOnClickListener
			}
			plugin.setTelemetryFields(chosen)
			setupTelemetryFields()
			dialog.dismiss()
		}
		uiHandler.removeCallbacks(refreshFieldValues)
		uiHandler.post(refreshFieldValues)
		dialog.show()
		dialog.window?.apply {
			setGravity(Gravity.FILL)
			decorView.setPadding(0, 0, 0, 0)
			setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
			attributes = attributes.apply {
				width = ViewGroup.LayoutParams.MATCH_PARENT
				height = ViewGroup.LayoutParams.MATCH_PARENT
				horizontalMargin = 0f
				verticalMargin = 0f
			}
			addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
			statusBarColor = ColorUtilities.getListBgColor(themed, isNightMode())
			setBackgroundDrawable(
				ColorDrawable(ColorUtilities.getListBgColor(themed, isNightMode()))
			)
		}
	}

	private fun telemetryGroupHeader(themed: android.content.Context, groupRes: Int): View {
		val hPad = AndroidUtils.dpToPx(themed, 16f)
		val line = View(themed).apply {
			layoutParams = LinearLayout.LayoutParams(0, AndroidUtils.dpToPx(themed, 1f), 1f)
			setBackgroundColor(ColorUtilities.getDividerColor(themed, isNightMode()))
		}
		val label = TextView(themed).apply {
			text = "${TelemetryField.groupEmoji(groupRes)} ${getString(groupRes)}"
			setTextColor(ColorUtilities.getSecondaryTextColor(themed, isNightMode()))
			textSize = 12f
			maxLines = 1
			setPadding(AndroidUtils.dpToPx(themed, 8f), 0, 0, 0)
		}
		return LinearLayout(themed).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			setPadding(hPad, AndroidUtils.dpToPx(themed, 4f), hPad, AndroidUtils.dpToPx(themed, 2f))
			addView(line)
			addView(label)
		}
	}

	private fun showChargeHistoryDialog(activity: Activity) {
		val rows = plugin.chargeHistory().asReversed()
		if (rows.isEmpty()) {
			app.showToastMessage(R.string.ev_bms_history_empty)
			return
		}
		val themed = UiUtilities.getThemedContext(activity, isNightMode())
		val text = buildHistoryText(themed) { buf ->
			for (row in rows) {
				buf.append(fmtDateTime(row.startMs)).append(" → ").append(fmtTime(row.endMs)).append('\n')
				buf.append(getString(R.string.ev_bms_history_duration, fmtDuration(row.durationMs()))).append('\n')
				buf.append(getString(R.string.ev_bms_history_temp, n(row.startTempC), n(row.endTempC))).append('\n')
				buf.append(getString(R.string.ev_bms_history_charged_ah, n(row.chargedAh))).append("\n\n")
			}
		}
		AlertDialog.Builder(themed)
			.setTitle(R.string.ev_bms_charge_history)
			.setView(text)
			.setPositiveButton(R.string.shared_string_close, null)
			.show()
	}

	private fun showTripHistoryDialog(activity: Activity) {
		val rows = plugin.tripHistory().asReversed()
		if (rows.isEmpty()) {
			app.showToastMessage(R.string.ev_bms_history_empty)
			return
		}
		val themed = UiUtilities.getThemedContext(activity, isNightMode())
		val text = buildHistoryText(themed) { buf ->
			for (row in rows) {
				buf.append(fmtDateTime(row.startMs)).append(" → ").append(fmtTime(row.endMs)).append('\n')
				buf.append(getString(R.string.ev_bms_history_date, fmtDate(row.startMs))).append('\n')
				buf.append(getString(R.string.ev_bms_history_distance, n(row.distanceKm))).append('\n')
				buf.append(getString(R.string.ev_bms_history_ride, fmtDuration(row.movingMs), fmtDuration(row.durationMs()))).append('\n')
				buf.append(getString(R.string.ev_bms_history_voltage, n(row.startVoltageV), n(row.endVoltageV))).append('\n')
				buf.append(getString(R.string.ev_bms_history_min_cell, n(row.minCellV))).append('\n')
				buf.append(getString(R.string.ev_bms_history_temp, n(row.startTempC), n(row.endTempC))).append("\n\n")
			}
		}
		AlertDialog.Builder(themed)
			.setTitle(R.string.ev_bms_trip_history)
			.setView(text)
			.setPositiveButton(R.string.shared_string_close, null)
			.show()
	}

	private fun buildHistoryText(themed: android.content.Context, fill: (StringBuilder) -> Unit): ScrollView {
		val pad = AndroidUtils.dpToPx(themed, 16f)
		val view = TextView(themed).apply {
			setPadding(pad, pad, pad, pad)
			setTextIsSelectable(true)
			val buf = StringBuilder()
			fill(buf)
			text = buf.toString().trim()
		}
		return ScrollView(themed).apply { addView(view) }
	}

	private fun fmtDateTime(ms: Long): String =
		SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(ms))

	private fun fmtTime(ms: Long): String =
		SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

	private fun fmtDate(ms: Long): String =
		SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(ms))

	private fun fmtDuration(ms: Long): String =
		OsmAndFormatter.getFormattedDurationShort((ms / 1000L).toInt().coerceAtLeast(0))

	private fun n(v: Double?): String {
		if (v == null || v.isNaN() || v.isInfinite()) {
			return getString(R.string.ev_bms_value_none)
		}
		return String.format(Locale.US, "%.2f", v)
	}

	private fun showExportDialog(activity: Activity) {
		val sessions = plugin.listTelemetrySessions()
		if (sessions.isEmpty()) {
			app.showToastMessage(R.string.ev_bms_csv_none)
			return
		}
		val themed = UiUtilities.getThemedContext(activity, isNightMode())
		val inflater = LayoutInflater.from(themed)
		val items = ArrayList(sessions)
		lateinit var dialog: AlertDialog
		val adapter = object : ArrayAdapter<TelemetryRecorder.LogSession>(themed, 0, items) {
			override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
				val view = convertView ?: inflater.inflate(R.layout.ev_bms_export_log_row, parent, false)
				val session = getItem(position) ?: return view
				view.findViewById<ImageView>(R.id.icon).setImageDrawable(
					app.uiUtilities.getThemedIcon(R.drawable.ic_action_save_to_file)
				)
				view.findViewById<TextView>(R.id.title).text = session.stamp
				view.findViewById<TextView>(R.id.description).text = formatLogMeta(session)
				val delete = view.findViewById<TextView>(R.id.delete_btn)
				val active = plugin.isActiveTelemetrySession(session)
				delete.text = getString(R.string.ev_bms_delete_emoji)
				delete.isEnabled = !active
				delete.alpha = if (active) 0.35f else 1f
				delete.setOnClickListener {
					if (active) {
						app.showToastMessage(R.string.ev_bms_log_in_use)
					} else {
						confirmDeleteLog(themed, session) {
							remove(session)
							notifyDataSetChanged()
							if (isEmpty) {
								dialog.dismiss()
							}
						}
					}
				}
				view.setOnClickListener {
					plugin.shareCsv(activity, session.files.map { it.uri })
				}
				return view
			}
		}
		dialog = AlertDialog.Builder(themed)
			.setTitle(R.string.ev_bms_export_csv)
			.setAdapter(adapter, null)
			.setPositiveButton(R.string.shared_string_share) { _, _ ->
				plugin.shareCsv(activity, items.flatMap { session -> session.files.map { it.uri } })
			}
			.setNegativeButton(R.string.shared_string_cancel, null)
			.create()
		dialog.showFullScreen()
	}

	private fun formatLogMeta(session: TelemetryRecorder.LogSession): String {
		val size = AndroidUtils.formatSize(app, session.sizeBytes)
		val duration = session.durationMs?.let {
			OsmAndFormatter.getFormattedDurationShort((it / 1000L).toInt().coerceAtLeast(0))
		} ?: getString(R.string.ev_bms_value_none)
		val distance = session.distanceM?.let {
			OsmAndFormatter.getFormattedDistance(it.toFloat(), app)
		} ?: getString(R.string.ev_bms_value_none)
		return getString(R.string.ev_bms_log_meta, size, duration, distance)
	}

	private fun confirmDeleteLog(
		themed: android.content.Context,
		session: TelemetryRecorder.LogSession,
		onDeleted: () -> Unit
	) {
		AlertDialog.Builder(themed)
			.setMessage(getString(R.string.ev_bms_delete_log, session.stamp))
			.setPositiveButton(R.string.shared_string_delete) { _, _ ->
				if (plugin.deleteTelemetrySession(session)) {
					app.showToastMessage(R.string.shared_string_deleted)
					onDeleted()
				}
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

	private fun AlertDialog.showFullScreen() {
		setOnShowListener {
			window?.setLayout(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT
			)
			val match = ViewGroup.LayoutParams.MATCH_PARENT
			fun stretch(id: Int, fill: Boolean) {
				val view = findViewById<View>(id) ?: return
				val lp = view.layoutParams
				lp.width = match
				if (lp is LinearLayout.LayoutParams) {
					if (fill) {
						lp.height = 0
						lp.weight = 1f
					} else {
						lp.height = match
					}
				} else {
					lp.height = match
				}
				view.layoutParams = lp
			}
			stretch(androidx.appcompat.R.id.parentPanel, false)
			stretch(androidx.appcompat.R.id.contentPanel, true)
			stretch(androidx.appcompat.R.id.customPanel, true)
			findViewById<ListView>(android.R.id.list)?.let { list ->
				list.layoutParams = list.layoutParams.apply { height = match }
			}
		}
		show()
	}
}
