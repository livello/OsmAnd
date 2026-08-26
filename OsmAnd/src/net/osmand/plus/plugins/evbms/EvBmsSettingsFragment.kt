package net.osmand.plus.plugins.evbms

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.widget.EditText
import android.widget.FrameLayout
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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.text.HtmlCompat
import androidx.fragment.app.FragmentManager
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import net.osmand.plus.R
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.plugins.evbms.ble.EvBleUartClient
import net.osmand.plus.settings.backend.ApplicationMode
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

		data class SettingsJump(val key: String, val emoji: String, val titleRes: Int)

		val SETTINGS_JUMPS = listOf(
			SettingsJump("ev_bms_settings_profile_cat", "📁", R.string.ev_bms_settings_profile),
			SettingsJump("ev_bms_devices", "🔋", R.string.ev_bms_devices),
			SettingsJump("ev_bms_modes", "🏍️", R.string.ev_bms_ride_modes),
			SettingsJump("ev_bms_hud", "👁️", R.string.ev_bms_hud),
			SettingsJump("ev_bms_calibration", "📏", R.string.ev_bms_calibration),
			SettingsJump("ev_bms_recording", "💾", R.string.ev_bms_recording),
			SettingsJump("ev_bms_voice", "🔊", R.string.ev_bms_voice),
			SettingsJump("ev_bms_charge", "🔌", R.string.ev_bms_charge_settings),
			SettingsJump("ev_bms_history_cat", "📋", R.string.ev_bms_history_group),
			SettingsJump("ev_bms_range", "🛣️", R.string.ev_bms_range_settings),
			SettingsJump("ev_bms_torrent_cat", "🧲", R.string.ev_bms_torrent_title)
		)
	}

	private val plugin = PluginsHelper.requirePlugin(EvBmsPlugin::class.java)
	private val found = ArrayList<ScannedBle>()
	private var scanningRole: EvBleUartClient.Role? = null
	private var pendingScanRole: EvBleUartClient.Role? = null
	private var picker: AlertDialog? = null
	private var pickerAdapter: BleDeviceAdapter? = null
	private val uiHandler = Handler(Looper.getMainLooper())
	private val fieldValueViews = ArrayList<Pair<TelemetryField, TextView>>()
	private val refreshFieldValues = object : Runnable {
		override fun run() {
			val ctx = context ?: return
			val sample = plugin.latestTelemetry
			for ((field, view) in fieldValueViews) {
				view.text = field.liveValue(ctx, sample)
			}
			setupDevicePrefs()
			uiHandler.postDelayed(this, 1000)
		}
	}
	private val refreshCalibration = object : Runnable {
		override fun run() {
			if (view == null) {
				return
			}
			setupDevicePrefs()
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

	private val exportProfileLauncher = registerForActivityResult(
		ActivityResultContracts.CreateDocument("application/json")
	) { uri ->
		if (uri == null) {
			return@registerForActivityResult
		}
		if (plugin.exportSettingsProfile(uri)) {
			app.showToastMessage(R.string.ev_bms_settings_profile_exported)
		} else {
			app.showToastMessage(R.string.ev_bms_settings_profile_failed)
		}
	}

	private val importProfileLauncher = registerForActivityResult(
		ActivityResultContracts.OpenDocument()
	) { uri ->
		if (uri == null) {
			return@registerForActivityResult
		}
		val name = plugin.importSettingsProfile(uri)
		if (name != null) {
			app.showToastMessage(getString(R.string.ev_bms_settings_profile_loaded, name))
			activity?.let { plugin.connectSavedDevices(it) }
			setupPreferences()
		} else {
			app.showToastMessage(R.string.ev_bms_settings_profile_failed)
		}
	}

	private val torrentFileLauncher = registerForActivityResult(
		ActivityResultContracts.OpenDocument()
	) { uri ->
		if (uri == null) {
			return@registerForActivityResult
		}
		if (plugin.importTorrentFile(uri)) {
			setupTorrentPrefs()
		} else {
			app.showToastMessage(R.string.ev_bms_torrent_invalid)
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
					val sheet = parentFragment as? EvBmsSettingsBottomSheet
					sheet?.setActionButtonsVisible(atTop)
					sheet?.onSettingsScrolled()
				}
			})
			setActionFooterInset(true)
		}
		return view
	}

	@SuppressLint("RestrictedApi")
	fun visibleSettingsJumpKeys(): Set<String> {
		val list = listView ?: return emptySet()
		val lm = list.layoutManager as? LinearLayoutManager ?: return emptySet()
		val adapter = list.adapter as? PreferenceGroupAdapter ?: return emptySet()
		val first = lm.findFirstVisibleItemPosition()
		val last = lm.findLastVisibleItemPosition()
		if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) {
			return emptySet()
		}
		val positions = SETTINGS_JUMPS.mapNotNull { jump ->
			val pref = findPreference<Preference>(jump.key) ?: return@mapNotNull null
			val pos = adapter.getPreferenceAdapterPosition(pref)
			if (pos >= 0) jump.key to pos else null
		}.sortedBy { it.second }
		if (positions.isEmpty()) {
			return emptySet()
		}
		val visible = linkedSetOf<String>()
		for (i in positions.indices) {
			val (key, start) = positions[i]
			val end = positions.getOrNull(i + 1)?.second ?: (last + 1)
			if (start <= last && end - 1 >= first) {
				visible.add(key)
			}
		}
		return visible
	}

	fun setActionFooterInset(sessionVisible: Boolean, jumpVisible: Boolean = false) {
		if (!isEmbedded()) {
			return
		}
		val list = listView ?: return
		val bottom = AndroidUtils.dpToPx(
			app,
			when {
				sessionVisible -> 88f
				jumpVisible -> 48f
				else -> 12f
			}
		)
		list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, bottom)
	}

	@SuppressLint("RestrictedApi")
	fun scrollToSettingsGroup(key: String) {
		val pref = findPreference<Preference>(key) ?: return
		val list = listView ?: return
		val adapter = list.adapter
		if (adapter is PreferenceGroupAdapter) {
			val pos = adapter.getPreferenceAdapterPosition(pref)
			if (pos >= 0) {
				(list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(pos, 0)
				list.post {
					val atTop = !list.canScrollVertically(-1)
					val sheet = parentFragment as? EvBmsSettingsBottomSheet
					sheet?.setActionButtonsVisible(atTop)
					sheet?.onSettingsScrolled()
				}
				return
			}
		}
		scrollToPreference(pref)
	}

	override fun updateStatusBar() {
		if (!isEmbedded()) {
			super.updateStatusBar()
		}
	}

	override fun setupPreferences() {
		setupProfiles()
		setupDevicePrefs()
		setupControllerTitle()
		setupBmsProtocol()
		setupJbdPassword()
		setupControllerProtocol()
		setupWheelCircumference()
		setupSwitch(plugin.HIKE_MODE.id)
		setupSpeedProfile()
		setupHudDemo()
		setupHudLook()
		setupHudLimits()
		setupCalDistance()
		setupCalFactor()
		setupCalAction()
		setupFilterCtrlOdo()
		setupCtrlOdoExcess()
		setupPollInterval()
		setupRecordInterval()
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
		setupSwitch(plugin.ANNOUNCE_RANGE_RESERVE.id)
		setupSwitch(plugin.ANNOUNCE_RANGE_RESERVE_SMALL.id)
		setupSwitch(plugin.ANNOUNCE_RANGE_RESERVE_LOW.id)
		setupKmThreshold(plugin.RANGE_RESERVE_SMALL_KM, arrayOf(2, 5, 8, 10, 15, 20))
		setupKmThreshold(plugin.RANGE_RESERVE_LOW_KM, arrayOf(8, 10, 15, 20, 30, 50))
		setupRangeForReserve()
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
		setupTorrentPrefs()
		setupIcons()
		refreshRecordingPref()
		refreshCalibrationPref()
	}

	fun refreshTelemetryFieldsPref() {
		setupTelemetryFields()
	}

	fun refreshAnnouncePrefs() {
		for (pref in plugin.announcePreferences) {
			findPreference<SwitchPreferenceEx>(pref.id)?.isChecked = pref.get()
		}
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
		decorate("ev_bms_settings_profile", "📁", R.drawable.ic_action_settings)
		decorateCategory("ev_bms_settings_profile_cat", "📁")
		decorateCategory("ev_bms_devices", "🔋")
		decorateCategory("ev_bms_modes", "🏍️")
		decorateCategory("ev_bms_hud", "👁️")
		decorateCategory("ev_bms_calibration", "📏")
		decorateCategory("ev_bms_recording", "💾")
		decorateCategory("ev_bms_voice", "🔊")
		decorateCategory("ev_bms_voice_stop_range", "🧭")
		decorateCategory("ev_bms_voice_cells", "⚠️")
		decorateCategory("ev_bms_voice_temps", "🌡️")
		decorateCategory("ev_bms_charge", "🔌")
		decorateCategory("ev_bms_history_cat", "📋")
		decorateCategory("ev_bms_range", "🛣️")
		decorateCategory("ev_bms_torrent_cat", "🧲")
		decorate(plugin.BMS_ADDRESS.id, "🔋", R.drawable.ic_action_battery)
		decorate(plugin.BMS_PROTOCOL.id, "🔗", R.drawable.ic_action_settings)
		decorate(plugin.BMS_PASSWORD.id, "🔐", R.drawable.ic_action_lock)
		decorate(plugin.CONTROLLER_ADDRESS.id, "🛵", R.drawable.ic_action_car_info)
		decorate(plugin.CONTROLLER_PROTOCOL.id, "⚙️", R.drawable.ic_action_settings)
		decorate(plugin.SPEED_SENSOR_ADDRESS.id, "🚲", R.drawable.ic_action_bicycle_dark)
		decorate(plugin.CADENCE_SENSOR_ADDRESS.id, "🚴", R.drawable.ic_action_bicycle_dark)
		decorate(plugin.WHEEL_CIRCUMFERENCE_MM.id, "⭕", R.drawable.ic_action_distance)
		decorate(plugin.HIKE_MODE.id, "🥾", R.drawable.ic_action_trekking_dark)
		decorate(plugin.SPEED_PROFILE_AUTO.id, "🏍️", R.drawable.ic_action_speed)
		decorate(plugin.SPEED_PROFILE_KMH.id, "🎚️", R.drawable.ic_action_speed)
		decorate(plugin.SPEED_PROFILE_SLOW.id, "🐢", R.drawable.ic_action_map_style)
		decorate(plugin.SPEED_PROFILE_FAST.id, "🏁", R.drawable.ic_action_map_style)
		decorate(plugin.HUD_DEMO.id, "🎬", R.drawable.ic_action_play_dark)
		decorate(plugin.HUD_SHOW_KMH.id, "👁️", R.drawable.ic_action_speed)
		decorate(plugin.HUD_HIDE_DELAY_SEC.id, "⏳", R.drawable.ic_action_time_span)
		decorate(plugin.HUD_STROKE_PERCENT.id, "➖", R.drawable.ic_action_speed)
		decorate(plugin.HUD_HEIGHT_PERCENT.id, "↕️", R.drawable.ic_action_speed)
		decorate(plugin.HUD_FPS.id, "🎞️", R.drawable.ic_action_time_span)
		decorate(plugin.HUD_FONT_PERCENT.id, "🔢", R.drawable.ic_action_speed)
		decorate(plugin.HUD_SHOW_UNITS.id, "🏷️", R.drawable.ic_action_speed)
		decorate(plugin.HUD_STATS_MINUTES.id, "📊", R.drawable.ic_action_time_span)
		decorate(plugin.HUD_LIMIT1_KMH.id, "🟢", R.drawable.ic_action_speed)
		decorate(plugin.HUD_BUFFER1_KMH.id, "🟡", R.drawable.ic_action_speed)
		decorate(plugin.HUD_LIMIT2_KMH.id, "🟠", R.drawable.ic_action_speed)
		decorate(plugin.HUD_BUFFER2_KMH.id, "🔴", R.drawable.ic_action_speed)
		decorate(plugin.SPEED_CAL_DISTANCE_M.id, "📏", R.drawable.ic_action_distance)
		decorate(plugin.SPEED_CAL_FACTOR.id, "✖️", R.drawable.ic_action_speed)
		decorate("ev_bms_cal_start", "▶️", R.drawable.ic_action_play_dark)
		decorate(plugin.FILTER_CTRL_ODO.id, "🛣️", R.drawable.ic_action_distance)
		decorate(plugin.CTRL_ODO_EXCESS_PERCENT.id, "📏", R.drawable.ic_action_speed)
		decorate(plugin.BMS_POLL_MS.id, "🔋", R.drawable.ic_action_time)
		decorate(plugin.CONTROLLER_POLL_MS.id, "🛵", R.drawable.ic_action_time)
		decorate(plugin.RECORD_INTERVAL_MS.id, "💾", R.drawable.ic_action_time_span)
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
		decorate(plugin.ANNOUNCE_RANGE_RESERVE.id, "🛣️", R.drawable.ic_action_distance)
		decorate(plugin.ANNOUNCE_RANGE_RESERVE_SMALL.id, "⚠️", R.drawable.ic_action_alert)
		decorate(plugin.ANNOUNCE_RANGE_RESERVE_LOW.id, "⛔", R.drawable.ic_action_alert)
		decorate(plugin.RANGE_RESERVE_SMALL_KM.id, "📉", R.drawable.ic_action_arrow_down)
		decorate(plugin.RANGE_RESERVE_LOW_KM.id, "📉", R.drawable.ic_action_arrow_down)
		decorate(plugin.RANGE_FOR_RESERVE.id, "🎯", R.drawable.ic_action_distance)
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
		decorate(plugin.TORRENT_ENABLED.id, "🧲", R.drawable.ic_action_gsave_dark)
		decorate("ev_bms_torrent_path", "📄", R.drawable.ic_action_folder)
		decorate(plugin.TORRENT_SEED_ON_CHARGE.id, "🔌", R.drawable.ic_action_battery)
		decorate(plugin.TORRENT_WIFI_ONLY.id, "📶", R.drawable.ic_action_wifi_off)
		decorate(plugin.TORRENT_DOWNLOAD_NEW.id, "⬇️", R.drawable.ic_action_gsave_dark)
		decorate("ev_bms_torrent_start", "▶️", R.drawable.ic_action_play_dark)
	}

	private fun decorate(key: String, emoji: String, iconRes: Int) {
		val pref = findPreference<Preference>(key) ?: return
		val title = pref.title?.toString().orEmpty()
		if (title.isNotEmpty() && !title.startsWith(emoji)) {
			pref.title = "$emoji $title"
		}
		pref.icon = getContentIcon(iconRes)
	}

	private fun decorateCategory(key: String, emoji: String) {
		val pref = findPreference<Preference>(key) ?: return
		val title = pref.title?.toString().orEmpty()
		if (title.isNotEmpty() && !title.startsWith(emoji)) {
			pref.title = "$emoji $title"
		}
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
		plugin.captureActiveProfile()
		super.onDestroyView()
	}

	private fun setupProfiles() {
		plugin.captureActiveProfile()
		val pref = findPreference<Preference>(plugin.SETTINGS_PROFILE.id) ?: return
		val active = plugin.activeProfileName()
		pref.summary = active
	}

	override fun onPause() {
		plugin.captureActiveProfile()
		super.onPause()
	}

	private fun setupDevicePrefs() {
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
		setupDevicePref(
			plugin.SPEED_SENSOR_ADDRESS.id,
			plugin.SPEED_SENSOR_NAME.get(),
			plugin.SPEED_SENSOR_ADDRESS.get(),
			plugin.isSpeedSensorConnected()
		)
		setupDevicePref(
			plugin.CADENCE_SENSOR_ADDRESS.id,
			plugin.CADENCE_SENSOR_NAME.get(),
			plugin.CADENCE_SENSOR_ADDRESS.get(),
			plugin.isCadenceSensorConnected()
		)
	}

	private fun setupDevicePref(key: String, name: String?, address: String?, connected: Boolean) {
		val pref = findPreference<Preference>(key) ?: return
		val label = deviceSummaryLabel(name, address)
		val role = when (key) {
			plugin.BMS_ADDRESS.id -> EvBleUartClient.Role.BMS
			plugin.SPEED_SENSOR_ADDRESS.id -> EvBleUartClient.Role.SPEED
			plugin.CADENCE_SENSOR_ADDRESS.id -> EvBleUartClient.Role.CADENCE
			else -> EvBleUartClient.Role.CONTROLLER
		}
		val stats = plugin.bleLinkStats(role)
		val status = when {
			connected -> getString(R.string.ev_bms_status_connected, label)
			!address.isNullOrEmpty() || !name.isNullOrBlank() ->
				getString(R.string.ev_bms_status_disconnected, label)
			else -> getString(R.string.ev_bms_status_not_selected)
		}
		pref.summary = if (stats != null && (!address.isNullOrEmpty() || !name.isNullOrBlank())) {
			"$status\n${bleStatsLine(stats)}"
		} else {
			status
		}
	}

	private fun bleStatsLine(stats: EvBleUartClient.LinkStats): String {
		val rssi = stats.rssiDbm?.let { "$it dBm" } ?: "— dBm"
		return getString(R.string.ev_bms_status_ble_stats, rssi, stats.txPackets, stats.rxPackets)
	}

	private fun deviceSummaryLabel(name: String?, address: String?): String {
		val mac = address?.trim().orEmpty()
		val title = name?.trim().orEmpty()
		return when {
			title.isNotEmpty() && mac.isNotEmpty() && !title.equals(mac, ignoreCase = true) ->
				"$title  $mac"
			mac.isNotEmpty() -> mac
			else -> title
		}
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
		setupPollMsPref(plugin.BMS_POLL_MS, R.string.ev_bms_bms_poll_desc)
		setupPollMsPref(plugin.CONTROLLER_POLL_MS, R.string.ev_bms_ctrl_poll_desc)
	}

	private fun setupPollMsPref(
		holder: net.osmand.plus.settings.backend.preferences.CommonPreference<Int>,
		descriptionRes: Int
	) {
		val pref = findPreference<ListPreferenceEx>(holder.id) ?: return
		pref.setEntries(EvBmsPlugin.POLL_MS_VALUES.map { pollLabel(it) }.toTypedArray())
		pref.setEntryValues(EvBmsPlugin.POLL_MS_VALUES.map { it as Any }.toTypedArray())
		pref.setValue(holder.get())
		pref.setDescription(descriptionRes)
	}

	private fun setupRecordInterval() {
		val pref = findPreference<ListPreferenceEx>(plugin.RECORD_INTERVAL_MS.id) ?: return
		pref.setEntries(
			EvBmsPlugin.RECORD_INTERVAL_MS_VALUES.map { ms ->
				if (ms == EvBmsPlugin.RECORD_INTERVAL_SAME) {
					getString(R.string.ev_bms_record_interval_same)
				} else {
					pollLabel(ms)
				}
			}.toTypedArray()
		)
		pref.setEntryValues(EvBmsPlugin.RECORD_INTERVAL_MS_VALUES.map { it as Any }.toTypedArray())
		pref.setValue(plugin.RECORD_INTERVAL_MS.get())
		pref.setDescription(R.string.ev_bms_record_interval_desc)
	}

	private fun pollLabel(ms: Int): String {
		return if (ms < 1000) {
			getString(R.string.ev_bms_n_ms, ms)
		} else {
			getString(R.string.ev_bms_n_sec, ms / 1000)
		}
	}

	private fun setupSpeedProfile() {
		val autoPref = findPreference<SwitchPreferenceEx>(plugin.SPEED_PROFILE_AUTO.id) ?: return
		autoPref.setDescription(R.string.ev_bms_speed_profile_auto_desc)

		val thresholdPref = findPreference<ListPreferenceEx>(plugin.SPEED_PROFILE_KMH.id) ?: return
		val kmh = arrayOf(10, 15, 20, 25, 30, 35, 40, 50, 60)
		thresholdPref.setEntries(kmh.map { getString(R.string.ev_bms_n_kmh, it) }.toTypedArray())
		thresholdPref.setEntryValues(kmh.map { it as Any }.toTypedArray())
		thresholdPref.setValue(plugin.SPEED_PROFILE_KMH.get())
		thresholdPref.setDescription(R.string.ev_bms_speed_profile_kmh_desc)

		setupSpeedProfileMode(plugin.SPEED_PROFILE_SLOW.id, plugin.SPEED_PROFILE_SLOW.get())
		setupSpeedProfileMode(plugin.SPEED_PROFILE_FAST.id, plugin.SPEED_PROFILE_FAST.get())
	}

	private fun setupSpeedProfileMode(prefId: String, selectedKey: String?) {
		val pref = findPreference<ListPreferenceEx>(prefId) ?: return
		val modes = ApplicationMode.values(app)
		val names = ArrayList<String>(modes.size + 1)
		val keys = ArrayList<Any>(modes.size + 1)
		names.add(getString(R.string.ev_bms_value_none))
		keys.add("")
		for (mode in modes) {
			names.add(mode.toHumanString())
			keys.add(mode.stringKey)
		}
		if (!selectedKey.isNullOrEmpty() && keys.none { it == selectedKey }) {
			val orphan = ApplicationMode.valueOfStringKey(selectedKey, null)
			names.add(orphan?.toHumanString() ?: selectedKey)
			keys.add(selectedKey)
		}
		pref.setEntries(names.toTypedArray())
		pref.setEntryValues(keys.toTypedArray())
		pref.setValue(selectedKey ?: "")
	}

	private fun setupHudDemo() {
		val pref = findPreference<SwitchPreferenceEx>(plugin.HUD_DEMO.id) ?: return
		pref.setDescription(R.string.ev_bms_hud_demo_desc)
	}

	private fun setupHudLook() {
		setupHudKmh(
			plugin.HUD_SHOW_KMH.id,
			plugin.HUD_SHOW_KMH.get(),
			R.string.ev_bms_hud_show_desc
		)
		setupHudHideDelay()
		setupHudPercent(
			plugin.HUD_STROKE_PERCENT.id,
			plugin.HUD_STROKE_PERCENT.get(),
			arrayOf(10, 15, 20, 25, 30, 35, 40, 50),
			R.string.ev_bms_hud_stroke_desc
		)
		setupHudPercent(
			plugin.HUD_HEIGHT_PERCENT.id,
			plugin.HUD_HEIGHT_PERCENT.get(),
			arrayOf(50, 60, 70, 80, 90, 100),
			R.string.ev_bms_hud_height_desc
		)
		setupHudFps()
		setupHudPercent(
			plugin.HUD_FONT_PERCENT.id,
			plugin.HUD_FONT_PERCENT.get(),
			arrayOf(13, 20, 26, 32, 39, 45, 52),
			R.string.ev_bms_hud_font_desc
		)
		findPreference<SwitchPreferenceEx>(plugin.HUD_SHOW_UNITS.id)
			?.setDescription(R.string.ev_bms_hud_show_units_desc)
		val stats = findPreference<ListPreferenceEx>(plugin.HUD_STATS_MINUTES.id) ?: return
		val minutes = arrayOf(0, 1, 2, 3, 5, 10, 15, 30)
		stats.setEntries(minutes.map { min ->
			if (min == 0) getString(R.string.shared_string_disabled) else getString(R.string.ev_bms_n_min, min)
		}.toTypedArray())
		stats.setEntryValues(minutes.map { it as Any }.toTypedArray())
		stats.setValue(plugin.HUD_STATS_MINUTES.get())
		stats.setDescription(R.string.ev_bms_hud_stats_desc)
	}

	private fun setupHudHideDelay() {
		val pref = findPreference<ListPreferenceEx>(plugin.HUD_HIDE_DELAY_SEC.id) ?: return
		val values = EvBmsPlugin.HUD_HIDE_DELAY_SEC_VALUES
		pref.setEntries(values.map { getString(R.string.ev_bms_n_sec, it) }.toTypedArray())
		pref.setEntryValues(values.map { it as Any }.toTypedArray())
		pref.setValue(plugin.HUD_HIDE_DELAY_SEC.get())
		pref.setDescription(R.string.ev_bms_hud_hide_delay_desc)
	}

	private fun setupHudFps() {
		val pref = findPreference<ListPreferenceEx>(plugin.HUD_FPS.id) ?: return
		val values = EvBmsPlugin.HUD_FPS_VALUES
		pref.setEntries(values.map { getString(R.string.ev_bms_n_fps, it) }.toTypedArray())
		pref.setEntryValues(values.map { it as Any }.toTypedArray())
		pref.setValue(plugin.HUD_FPS.get())
		pref.setDescription(R.string.ev_bms_hud_fps_desc)
	}

	private fun setupHudPercent(prefId: String, value: Int, values: Array<Int>, descId: Int) {
		val pref = findPreference<ListPreferenceEx>(prefId) ?: return
		pref.setEntries(values.map { getString(R.string.ev_bms_n_percent, it) }.toTypedArray())
		pref.setEntryValues(values.map { it as Any }.toTypedArray())
		pref.setValue(value)
		pref.setDescription(descId)
	}

	private fun setupHudLimits() {
		setupHudKmh(
			plugin.HUD_LIMIT1_KMH.id,
			plugin.HUD_LIMIT1_KMH.get(),
			R.string.ev_bms_hud_limit1_desc
		)
		setupHudKmh(
			plugin.HUD_BUFFER1_KMH.id,
			plugin.HUD_BUFFER1_KMH.get(),
			R.string.ev_bms_hud_buffer1_desc
		)
		setupHudKmh(
			plugin.HUD_LIMIT2_KMH.id,
			plugin.HUD_LIMIT2_KMH.get(),
			R.string.ev_bms_hud_limit2_desc
		)
		setupHudKmh(
			plugin.HUD_BUFFER2_KMH.id,
			plugin.HUD_BUFFER2_KMH.get(),
			R.string.ev_bms_hud_buffer2_desc
		)
	}

	private fun setupHudKmh(prefId: String, value: Int, descId: Int) {
		val pref = findPreference<ListPreferenceEx>(prefId) ?: return
		val kmh = arrayOf(20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 80, 90)
		pref.setEntries(kmh.map { getString(R.string.ev_bms_n_kmh, it) }.toTypedArray())
		pref.setEntryValues(kmh.map { it as Any }.toTypedArray())
		pref.setValue(value)
		pref.setDescription(descId)
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
		pref.setDescription(plugin.speedCalFactorDescriptionRes())
	}

	private fun setupWheelCircumference() {
		val pref = findPreference<EditTextPreferenceEx>(plugin.WHEEL_CIRCUMFERENCE_MM.id) ?: return
		val text = plugin.formattedWheelCircumferenceMm()
		pref.text = text
		pref.summary = getString(R.string.ev_bms_n_mm, plugin.WHEEL_CIRCUMFERENCE_MM.get())
		pref.setDescription(R.string.ev_bms_wheel_circumference_desc)
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
			pref.summary = getString(plugin.speedCalFactorDescriptionRes())
		}
	}

	private fun setupFilterCtrlOdo() {
		val pref = findPreference<SwitchPreferenceEx>(plugin.FILTER_CTRL_ODO.id) ?: return
		pref.setDescription(R.string.ev_bms_filter_ctrl_odo_desc)
		setupCtrlOdoExcess()
	}

	private fun setupCtrlOdoExcess() {
		val pref = findPreference<ListPreferenceEx>(plugin.CTRL_ODO_EXCESS_PERCENT.id) ?: return
		val values = arrayOf(100, 110, 120, 130, 140, 150)
		pref.setEntries(values.map { getString(R.string.ev_bms_n_percent, it) }.toTypedArray())
		pref.setEntryValues(values.map { it as Any }.toTypedArray())
		pref.setValue(plugin.CTRL_ODO_EXCESS_PERCENT.get())
		pref.isEnabled = plugin.FILTER_CTRL_ODO.get()
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

	private fun setupKmThreshold(prefHolder: net.osmand.plus.settings.backend.preferences.CommonPreference<Int>, km: Array<Int>) {
		val pref = findPreference<ListPreferenceEx>(prefHolder.id) ?: return
		pref.setEntries(km.map { getString(R.string.ev_bms_n_km_int, it) }.toTypedArray())
		pref.setEntryValues(km.map { it as Any }.toTypedArray())
		pref.setValue(prefHolder.get())
	}

	private fun setupRangeForReserve() {
		val pref = findPreference<ListPreferenceEx>(plugin.RANGE_FOR_RESERVE.id) ?: return
		pref.setEntries(
			arrayOf(
				getString(R.string.ev_bms_range_source_10km),
				getString(R.string.ev_bms_range_source_5min),
				getString(R.string.ev_bms_range_source_pnz)
			)
		)
		pref.setEntryDescriptions(
			arrayOf(
				getString(R.string.ev_bms_range_source_10km_method),
				getString(R.string.ev_bms_range_source_5min_method),
				getString(R.string.ev_bms_range_source_pnz_method)
			)
		)
		pref.setEntryValues(
			arrayOf<Any>(
				EvBmsPlugin.RANGE_SOURCE_10KM,
				EvBmsPlugin.RANGE_SOURCE_5MIN,
				EvBmsPlugin.RANGE_SOURCE_PNZ
			)
		)
		pref.setValue(plugin.RANGE_FOR_RESERVE.get())
		pref.setDescription(R.string.ev_bms_range_for_reserve_desc)
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
		val trips = plugin.tripHistory().size
		val total = charges + trips
		findPreference<Preference>("ev_bms_charge_history")?.summary =
			getString(R.string.ev_bms_history_count, total)
		findPreference<Preference>("ev_bms_trip_history")?.summary =
			getString(R.string.ev_bms_history_count, total)
	}

	private fun setupTorrentPrefs() {
		setupSwitch(plugin.TORRENT_ENABLED.id)
		findPreference<SwitchPreferenceEx>(plugin.TORRENT_ENABLED.id)
			?.setDescription(R.string.ev_bms_torrent_enabled_desc)
		setupSwitch(plugin.TORRENT_SEED_ON_CHARGE.id)
		findPreference<SwitchPreferenceEx>(plugin.TORRENT_SEED_ON_CHARGE.id)
			?.setDescription(R.string.ev_bms_torrent_seed_charge_desc)
		setupSwitch(plugin.TORRENT_WIFI_ONLY.id)
		findPreference<SwitchPreferenceEx>(plugin.TORRENT_WIFI_ONLY.id)
			?.setDescription(R.string.ev_bms_torrent_wifi_only_desc)
		setupSwitch(plugin.TORRENT_DOWNLOAD_NEW.id)
		findPreference<SwitchPreferenceEx>(plugin.TORRENT_DOWNLOAD_NEW.id)
			?.setDescription(R.string.ev_bms_torrent_download_new_desc)
		findPreference<Preference>("ev_bms_torrent_path")?.summary = plugin.torrentPathSummary()
		val start = findPreference<Preference>("ev_bms_torrent_start") ?: return
		val st = plugin.mapTorrentStatus()
		start.summary = when {
			!st.error.isNullOrBlank() -> st.error
			st.running -> getString(
				R.string.ev_bms_torrent_status_brief,
				st.state,
				st.peers,
				st.seeds
			)
			!st.waitingReason.isNullOrBlank() -> st.waitingReason
			else -> getString(R.string.ev_bms_torrent_start_desc)
		}
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
			plugin.applySpeedCalFactor(parsed)
			setupCalFactor()
			return true
		}
		if (preference.key == plugin.WHEEL_CIRCUMFERENCE_MM.id) {
			val parsed = plugin.parseWheelCircumferenceMm(newValue as? String)
			if (parsed == null) {
				app.showToastMessage(R.string.ev_bms_wheel_circumference_invalid)
				return false
			}
			plugin.applyWheelCircumferenceMm(parsed)
			setupWheelCircumference()
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
		if (preference.key == plugin.FILTER_CTRL_ODO.id) {
			val result = super.onPreferenceChange(preference, newValue)
			findPreference<ListPreferenceEx>(plugin.CTRL_ODO_EXCESS_PERCENT.id)?.isEnabled =
				newValue as? Boolean ?: plugin.FILTER_CTRL_ODO.get()
			return result
		}
		if (preference.key == plugin.RECORD_TELEMETRY.id) {
			val enable = newValue as? Boolean ?: return false
			if (enable == plugin.hasTelemetrySession()) {
				return true
			}
			val title = if (enable) R.string.ev_bms_record_telemetry else R.string.shared_string_control_stop
			val message = if (enable) R.string.ev_bms_confirm_record_start else R.string.ev_bms_confirm_record_stop
			confirmAction(title, message) {
				if (enable) {
					plugin.startTelemetryRecording()
				} else {
					plugin.stopTelemetryRecording()
				}
				refreshRecordingPref()
				(parentFragment as? EvBmsSettingsBottomSheet)?.rebuildBottomButtons()
			}
			return false
		}
		if (preference.key == plugin.TORRENT_ENABLED.id ||
			preference.key == plugin.TORRENT_SEED_ON_CHARGE.id ||
			preference.key == plugin.TORRENT_WIFI_ONLY.id ||
			preference.key == plugin.TORRENT_DOWNLOAD_NEW.id
		) {
			val result = super.onPreferenceChange(preference, newValue)
			plugin.syncMapTorrent()
			setupTorrentPrefs()
			return result
		}
		return super.onPreferenceChange(preference, newValue)
	}

	override fun onPreferenceClick(preference: Preference): Boolean {
		val activity = activity ?: return false
		when (preference.key) {
			plugin.SETTINGS_PROFILE.id -> {
				showProfileModal()
				return true
			}
			plugin.BMS_ADDRESS.id -> {
				startScan(activity, EvBleUartClient.Role.BMS)
				return true
			}
			plugin.CONTROLLER_ADDRESS.id -> {
				startScan(activity, EvBleUartClient.Role.CONTROLLER)
				return true
			}
			plugin.SPEED_SENSOR_ADDRESS.id -> {
				startScan(activity, EvBleUartClient.Role.SPEED)
				return true
			}
			plugin.CADENCE_SENSOR_ADDRESS.id -> {
				startScan(activity, EvBleUartClient.Role.CADENCE)
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
				val start = !plugin.isSpeedCalibrating()
				val title = if (start) R.string.ev_bms_cal_start else R.string.ev_bms_calibrate_stop
				val message = if (start) R.string.ev_bms_confirm_cal_start else R.string.ev_bms_confirm_cal_stop
				AlertDialog.Builder(activity)
					.setTitle(title)
					.setMessage(message)
					.setNegativeButton(R.string.shared_string_cancel, null)
					.setPositiveButton(R.string.shared_string_yes) { _, _ ->
						if (plugin.isSpeedCalibrating()) {
							plugin.stopSpeedCalibration()
						} else {
							plugin.startSpeedCalibration()
						}
						refreshCalibrationPref()
						(parentFragment as? EvBmsSettingsBottomSheet)?.onCalibrationTick()
					}
					.show()
				return true
			}
			"ev_bms_torrent_path" -> {
				torrentFileLauncher.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*"))
				return true
			}
			"ev_bms_torrent_start" -> {
				showTorrentStatusDialog(activity)
				return true
			}
		}
		return super.onPreferenceClick(preference)
	}

	private fun showProfileModal() {
		val activity = activity ?: return
		val themed = UiUtilities.getThemedContext(activity, isNightMode())
		val content = LayoutInflater.from(themed).inflate(R.layout.ev_bms_profile_dialog, null)
		val namesGroup = content.findViewById<RadioGroup>(R.id.profile_names)
		val actions = content.findViewById<LinearLayout>(R.id.profile_actions)
		val active = plugin.activeProfileName()
		val textColor = ColorUtilities.getPrimaryTextColor(themed, isNightMode())
		for (name in plugin.profileNames()) {
			val radio = RadioButton(themed).apply {
				id = View.generateViewId()
				text = name
				isChecked = name == active
				setTextColor(textColor)
			}
			UiUtilities.setupCompoundButton(radio, isNightMode(), UiUtilities.CompoundButtonType.GLOBAL)
			namesGroup.addView(radio)
		}
		val dialog = AlertDialog.Builder(themed)
			.setTitle(R.string.ev_bms_settings_profile_select)
			.setView(content)
			.setNegativeButton(R.string.shared_string_close, null)
			.create()
		namesGroup.setOnCheckedChangeListener { group, checkedId ->
			val button = group.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
			val name = button.text.toString()
			if (name == plugin.activeProfileName()) {
				return@setOnCheckedChangeListener
			}
			if (plugin.selectSettingsProfile(name)) {
				app.showToastMessage(getString(R.string.ev_bms_settings_profile_loaded, name))
				plugin.connectSavedDevices(activity)
				setupPreferences()
			}
			dialog.dismiss()
		}
		fun addAction(titleRes: Int, emoji: String, onClick: () -> Unit) {
			val padH = AndroidUtils.dpToPx(themed, 16f)
			val padV = AndroidUtils.dpToPx(themed, 12f)
			val row = TextView(themed).apply {
				text = "$emoji  ${getString(titleRes)}"
				setTextColor(textColor)
				textSize = 16f
				setPadding(padH, padV, padH, padV)
				val typed = android.util.TypedValue()
				themed.theme.resolveAttribute(android.R.attr.selectableItemBackground, typed, true)
				setBackgroundResource(typed.resourceId)
				setOnClickListener {
					dialog.dismiss()
					onClick()
				}
			}
			actions.addView(row)
		}
		addAction(R.string.ev_bms_settings_profile_rename, "✏️") {
			askProfileName(R.string.ev_bms_settings_profile_rename, plugin.activeProfileName()) { typed ->
				if (plugin.renameSettingsProfile(typed)) {
					setupProfiles()
				} else {
					app.showToastMessage(R.string.ev_bms_settings_profile_exists)
				}
			}
		}
		addAction(R.string.ev_bms_settings_profile_export_action, "📤") {
			plugin.captureActiveProfile()
			exportProfileLauncher.launch(plugin.settingsProfileExportFileName())
		}
		addAction(R.string.ev_bms_settings_profile_import_action, "📥") {
			importProfileLauncher.launch(arrayOf("application/json", "*/*"))
		}
		addAction(R.string.ev_bms_settings_profile_create, "➕") {
			askProfileName(R.string.ev_bms_settings_profile_create, "") { typed ->
				if (plugin.createSettingsProfile(typed)) {
					app.showToastMessage(getString(R.string.ev_bms_settings_profile_saved, typed))
					setupPreferences()
				} else {
					app.showToastMessage(R.string.ev_bms_settings_profile_exists)
				}
			}
		}
		dialog.show()
	}

	private fun askProfileName(titleRes: Int, initial: String, onOk: (String) -> Unit) {
		val activity = activity ?: return
		val themed = UiUtilities.getThemedContext(activity, isNightMode())
		val pad = AndroidUtils.dpToPx(themed, 16f)
		val input = EditText(themed).apply {
			setText(initial)
			setSelection(text.length)
			inputType = InputType.TYPE_CLASS_TEXT
			setSingleLine()
		}
		val wrap = FrameLayout(themed).apply {
			setPadding(pad, pad / 2, pad, 0)
			addView(input)
		}
		AlertDialog.Builder(themed)
			.setTitle(titleRes)
			.setView(wrap)
			.setNegativeButton(R.string.shared_string_cancel, null)
			.setPositiveButton(R.string.shared_string_apply) { _, _ ->
				val name = plugin.sanitizeProfileName(input.text?.toString())
				if (name != null) {
					onOk(name)
				}
			}
			.show()
	}

	private fun confirmAction(titleRes: Int, messageRes: Int, onYes: () -> Unit) {
		val activity = activity ?: return
		AlertDialog.Builder(activity)
			.setTitle(titleRes)
			.setMessage(messageRes)
			.setNegativeButton(R.string.shared_string_cancel, null)
			.setPositiveButton(R.string.shared_string_yes) { _, _ -> onYes() }
			.show()
	}

	override fun onDisplayPreferenceDialog(preference: Preference) {
		if (preference.key == plugin.USE_ROUTE_PROFILE.id ||
			preference.key == plugin.TORRENT_ENABLED.id ||
			preference.key == plugin.TORRENT_SEED_ON_CHARGE.id ||
			preference.key == plugin.TORRENT_WIFI_ONLY.id ||
			preference.key == plugin.TORRENT_DOWNLOAD_NEW.id
		) {
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
		if (plugin.isAnnouncePreferenceId(prefId)) {
			(parentFragment as? EvBmsSettingsBottomSheet)?.refreshSheetTitleAnnounces()
		}
		if (prefId == plugin.SPEED_CAL_FACTOR.id || prefId == plugin.SPEED_SENSOR_CAL_FACTOR.id) {
			setupCalFactor()
		}
		if (prefId == plugin.WHEEL_CIRCUMFERENCE_MM.id) {
			setupWheelCircumference()
		}
		if (prefId == plugin.VEHICLE_MASS_KG.id || prefId == plugin.DRIVER_MASS_KG.id) {
			setupMassPrefs()
		}
		if (prefId == plugin.BMS_PASSWORD.id) {
			setupJbdPassword()
		}
		if (prefId == plugin.RANGE_FOR_RESERVE.id) {
			setupRangeForReserve()
		}
		if (prefId == plugin.RECORD_TELEMETRY.id) {
			refreshRecordingPref()
		}
		if (prefId == plugin.RECORD_GPX.id) {
			plugin.restartTelemetryIfRecording()
		}
		if (prefId == plugin.BMS_POLL_MS.id || prefId == plugin.CONTROLLER_POLL_MS.id) {
			plugin.reschedulePolling()
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
		if (prefId == plugin.SPEED_PROFILE_AUTO.id ||
			prefId == plugin.SPEED_PROFILE_KMH.id ||
			prefId == plugin.SPEED_PROFILE_SLOW.id ||
			prefId == plugin.SPEED_PROFILE_FAST.id
		) {
			setupSpeedProfile()
		}
		if (prefId == plugin.HUD_DEMO.id) {
			setupHudDemo()
		}
		if (prefId == plugin.HUD_SHOW_KMH.id ||
			prefId == plugin.HUD_HIDE_DELAY_SEC.id ||
			prefId == plugin.HUD_STROKE_PERCENT.id ||
			prefId == plugin.HUD_HEIGHT_PERCENT.id ||
			prefId == plugin.HUD_FPS.id ||
			prefId == plugin.HUD_FONT_PERCENT.id ||
			prefId == plugin.HUD_SHOW_UNITS.id ||
			prefId == plugin.HUD_STATS_MINUTES.id
		) {
			setupHudLook()
		}
		if (prefId == plugin.HUD_LIMIT1_KMH.id ||
			prefId == plugin.HUD_BUFFER1_KMH.id ||
			prefId == plugin.HUD_LIMIT2_KMH.id ||
			prefId == plugin.HUD_BUFFER2_KMH.id
		) {
			setupHudLimits()
		}
		if (prefId == plugin.TORRENT_ENABLED.id ||
			prefId == plugin.TORRENT_SEED_ON_CHARGE.id ||
			prefId == plugin.TORRENT_WIFI_ONLY.id ||
			prefId == plugin.TORRENT_DOWNLOAD_NEW.id
		) {
			plugin.syncMapTorrent()
			setupTorrentPrefs()
		}
	}

	private fun showTorrentStatusDialog(activity: Activity) {
		if (!plugin.TORRENT_ENABLED.get()) {
			app.showToastMessage(R.string.ev_bms_torrent_enable_first)
			return
		}
		plugin.startMapTorrentManual()
		val themed = UiUtilities.getThemedContext(activity, isNightMode())
		val pad = AndroidUtils.dpToPx(themed, 16f)
		val textColor = ColorUtilities.getPrimaryTextColor(themed, isNightMode())
		val statusView = TextView(themed).apply {
			setTextColor(textColor)
			textSize = 15f
			setPadding(pad, pad, pad, pad)
			setTextIsSelectable(true)
		}
		val dialog = AlertDialog.Builder(themed)
			.setTitle(R.string.ev_bms_torrent_status_title)
			.setView(statusView)
			.setNegativeButton(R.string.shared_string_close) { _, _ ->
				setupTorrentPrefs()
			}
			.setPositiveButton(R.string.shared_string_control_stop) { _, _ ->
				plugin.stopMapTorrent()
				setupTorrentPrefs()
			}
			.create()
		val refresh = object : Runnable {
			override fun run() {
				if (!dialog.isShowing) {
					return
				}
				val st = plugin.mapTorrentStatus()
				statusView.text = buildTorrentStatusText(st)
				uiHandler.postDelayed(this, 1000)
			}
		}
		dialog.setOnDismissListener {
			uiHandler.removeCallbacks(refresh)
			setupTorrentPrefs()
		}
		dialog.show()
		uiHandler.post(refresh)
	}

	private fun buildTorrentStatusText(st: EvMapTorrentStatus): String {
		val lines = ArrayList<String>()
		lines.add(getString(R.string.ev_bms_torrent_status_state, st.state.ifBlank { "—" }))
		if (!st.error.isNullOrBlank()) {
			lines.add(getString(R.string.ev_bms_torrent_status_error, st.error))
		}
		if (!st.waitingReason.isNullOrBlank()) {
			lines.add(st.waitingReason)
		}
		if (st.torrentName.isNotBlank()) {
			lines.add(getString(R.string.ev_bms_torrent_status_name, st.torrentName))
		}
		lines.add(getString(R.string.ev_bms_torrent_status_files, st.matchedFiles, st.torrentFiles))
		lines.add(getString(R.string.ev_bms_torrent_status_progress, st.progressPercent))
		lines.add(getString(R.string.ev_bms_torrent_status_peers, st.peers, st.seeds))
		lines.add(
			getString(
				R.string.ev_bms_torrent_status_rates,
				AndroidUtils.formatSize(app, st.downloadRate) + "/s",
				AndroidUtils.formatSize(app, st.uploadRate) + "/s"
			)
		)
		lines.add(
			getString(
				R.string.ev_bms_torrent_status_session,
				AndroidUtils.formatSize(app, st.sessionDownloaded),
				AndroidUtils.formatSize(app, st.sessionUploaded)
			)
		)
		lines.add(
			getString(
				R.string.ev_bms_torrent_status_total,
				AndroidUtils.formatSize(app, st.totalDownloaded),
				AndroidUtils.formatSize(app, st.totalUploaded)
			)
		)
		return lines.joinToString("\n")
	}

	private fun showTelemetryFieldsDialog(activity: Activity) {
		val themed = UiUtilities.getThemedContext(activity, isNightMode())
		val selected = plugin.selectedTelemetryFields().toMutableSet()
		val gpxSelected = plugin.selectedGpxTelemetryFields().toMutableSet()
		val inflater = LayoutInflater.from(themed)
		val content = inflater.inflate(R.layout.ev_bms_telemetry_fields_dialog, null)
		val list = content.findViewById<LinearLayout>(R.id.fields_list)
		val checkboxes = ArrayList<Pair<TelemetryField, CheckBox>>()
		val gpxButtons = ArrayList<Pair<TelemetryField, RadioButton>>()
		fieldValueViews.clear()
		fun bindChecks() {
			for ((field, box) in checkboxes) {
				box.isChecked = field in selected
			}
			for ((field, button) in gpxButtons) {
				button.isChecked = field in gpxSelected
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
			contentDescription = getString(R.string.shared_string_deselect_all)
			setOnClickListener {
				selected.clear()
				bindChecks()
			}
		}
		for ((groupRes, fields) in TelemetryField.grouped()) {
			list.addView(telemetryGroupHeader(themed, groupRes))
			for (field in fields) {
				val row = inflater.inflate(R.layout.ev_bms_telemetry_field_row, list, false)
				val box = row.findViewById<CheckBox>(R.id.compound_button)
				val gpxBtn = row.findViewById<RadioButton>(R.id.gpx_button)
				val title = row.findViewById<TextView>(R.id.title)
				val value = row.findViewById<TextView>(R.id.value)
				title.text = "${field.emoji} ${getString(field.titleRes)}"
				value.text = field.liveValue(themed, plugin.latestTelemetry)
				box.isChecked = field in selected
				gpxBtn.isChecked = field in gpxSelected
				UiUtilities.setupCompoundButton(box, isNightMode(), UiUtilities.CompoundButtonType.GLOBAL)
				UiUtilities.setupCompoundButton(gpxBtn, isNightMode(), UiUtilities.CompoundButtonType.GLOBAL)
				row.setOnClickListener {
					box.isChecked = !box.isChecked
					if (box.isChecked) selected.add(field) else selected.remove(field)
				}
				gpxBtn.setOnClickListener {
					if (field in gpxSelected) {
						gpxSelected.remove(field)
						gpxBtn.isChecked = false
					} else {
						gpxSelected.add(field)
						gpxBtn.isChecked = true
					}
				}
				checkboxes.add(field to box)
				gpxButtons.add(field to gpxBtn)
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
			plugin.setGpxTelemetryFields(TelemetryField.entries.filter { it in gpxSelected })
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
				buf.append(fmtDateTime(row.startMs)).append(" → ").append(
					if (row.isOpen()) getString(R.string.ev_bms_history_charging_now) else fmtTime(row.endMs)
				).append('\n')
				buf.append(getString(R.string.ev_bms_history_duration, fmtDuration(row.durationMs()))).append('\n')
				buf.append(getString(R.string.ev_bms_history_temp, n(row.startTempC), n(row.endTempC))).append('\n')
				buf.append(getString(R.string.ev_bms_history_charged_ah, n(row.chargedAh))).append('\n')
				buf.append(getString(R.string.ev_bms_history_charge_energy_wh, n(row.energyWh))).append("\n\n")
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
				buf.append(getString(R.string.ev_bms_history_used_ah, n(row.usedAh))).append('\n')
				buf.append(getString(R.string.ev_bms_history_energy_wh, n(row.energyWh))).append('\n')
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
		} else if (role == EvBleUartClient.Role.SPEED) {
			plugin.startSpeedSensorScan(activity)
		} else if (role == EvBleUartClient.Role.CADENCE) {
			plugin.startCadenceSensorScan(activity)
		} else {
			plugin.startControllerScan(activity)
		}
	}

	private fun showPicker(activity: Activity) {
		dismissPicker()
		val themed = UiUtilities.getThemedContext(activity, isNightMode())
		val adapter = BleDeviceAdapter(themed, found)
		pickerAdapter = adapter
		picker = AlertDialog.Builder(themed)
			.setTitle(R.string.ev_bms_select_device)
			.setAdapter(adapter) { _, which ->
				if (which in found.indices) {
					val selected = found[which]
					val role = scanningRole
					if (role == EvBleUartClient.Role.BMS) {
						plugin.connectBms(activity, selected.name, selected.address)
					} else if (role == EvBleUartClient.Role.SPEED) {
						plugin.connectSpeedSensor(activity, selected.name, selected.address)
					} else if (role == EvBleUartClient.Role.CADENCE) {
						plugin.connectCadenceSensor(activity, selected.name, selected.address)
					} else if (role == EvBleUartClient.Role.CONTROLLER) {
						plugin.connectController(activity, selected.name, selected.address)
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

	override fun onDeviceFound(
		role: EvBleUartClient.Role,
		name: String,
		address: String,
		rssi: Int?,
		serviceLabel: String
	) {
		if (role != scanningRole) {
			return
		}
		val existing = found.indexOfFirst { it.address.equals(address, ignoreCase = true) }
		if (existing >= 0) {
			val row = found[existing]
			if (rssi != null) {
				row.rssi = rssi
			}
			if (serviceLabel.isNotBlank()) {
				row.serviceLabel = serviceLabel
			}
		} else {
			found.add(ScannedBle(name = name, address = address, rssi = rssi, serviceLabel = serviceLabel))
		}
		found.sortWith(compareByDescending<ScannedBle> { it.rssi ?: Int.MIN_VALUE }.thenBy { it.displayName.lowercase(Locale.US) })
		val activity = activity ?: return
		if (picker == null) {
			showPicker(activity)
		} else {
			pickerAdapter?.notifyDataSetChanged()
		}
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

	private class ScannedBle(
		val name: String,
		val address: String,
		var rssi: Int?,
		var serviceLabel: String
	) {
		val displayName: String
			get() = name.ifBlank { address }
	}

	private class BleDeviceAdapter(
		context: Context,
		items: List<ScannedBle>
	) : ArrayAdapter<ScannedBle>(context, android.R.layout.simple_list_item_2, android.R.id.text1, items) {

		override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
			val view = super.getView(position, convertView, parent)
			val item = getItem(position) ?: return view
			val title = view.findViewById<TextView>(android.R.id.text1)
			val subtitle = view.findViewById<TextView>(android.R.id.text2)
			val emoji = when {
				item.serviceLabel.contains("JBD") || item.serviceLabel.contains("ANT") -> "🔋"
				item.serviceLabel.contains("Far") -> "🛵"
				item.serviceLabel.contains("VESC") -> "⚡"
				else -> "📡"
			}
			title.text = HtmlCompat.fromHtml(
				"$emoji <b>${TextUtils.htmlEncode(item.displayName)}</b>",
				HtmlCompat.FROM_HTML_MODE_LEGACY
			)
			val rssiPart = item.rssi?.let { context.getString(R.string.ev_bms_ble_rssi, it) }
				?: context.getString(R.string.ev_bms_ble_rssi_unknown)
			subtitle.text = "$rssiPart · ${item.serviceLabel}"
			return view
		}
	}
}
