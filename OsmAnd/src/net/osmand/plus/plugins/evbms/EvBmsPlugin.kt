package net.osmand.plus.plugins.evbms

import android.app.Activity
import android.util.Log
import androidx.core.text.HtmlCompat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.github.mikephil.charting.charts.LineChart
import net.osmand.Location
import net.osmand.aidlapi.OsmAndCustomizationConstants
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.charts.GPXDataSetAxisType
import net.osmand.plus.charts.GPXDataSetType
import net.osmand.plus.charts.GpxDataSetTypeGroup
import net.osmand.plus.charts.OrderedLineDataSet
import net.osmand.plus.plugins.OsmandPlugin
import net.osmand.plus.plugins.evbms.ble.EvBleUartClient
import net.osmand.plus.plugins.evbms.protocol.AntBmsProtocol
import net.osmand.plus.plugins.evbms.protocol.BmsSnapshot
import net.osmand.plus.plugins.evbms.protocol.CscCadenceTracker
import net.osmand.plus.plugins.evbms.protocol.CscWheelTracker
import net.osmand.plus.plugins.evbms.protocol.FarDriverProtocol
import net.osmand.plus.plugins.evbms.protocol.JbdBmsProtocol
import net.osmand.plus.plugins.evbms.protocol.JbdBleModuleProtocol
import net.osmand.plus.plugins.evbms.protocol.VescProtocol
import net.osmand.plus.settings.backend.ApplicationMode
import net.osmand.plus.settings.backend.preferences.CommonPreference
import net.osmand.plus.settings.enums.ScreenLayoutMode
import net.osmand.plus.settings.fragments.BaseSettingsFragment
import net.osmand.plus.settings.fragments.SettingsScreenType
import net.osmand.plus.utils.OsmAndFormatter
import net.osmand.plus.views.mapwidgets.MapWidgetInfo
import net.osmand.plus.views.mapwidgets.WidgetInfoCreator
import net.osmand.plus.views.mapwidgets.WidgetType
import net.osmand.plus.views.mapwidgets.WidgetsPanel
import net.osmand.plus.views.mapwidgets.widgets.MapWidget
import net.osmand.plus.widgets.ctxmenu.ContextMenuAdapter
import net.osmand.plus.widgets.ctxmenu.callback.OnDataChangeUiAdapter
import net.osmand.plus.widgets.ctxmenu.data.ContextMenuItem
import net.osmand.shared.gpx.GpxTrackAnalysis
import org.json.JSONException
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

class EvBmsPlugin(app: OsmandApplication) : OsmandPlugin(app), EvBleUartClient.Listener {

	companion object {
		const val DEFAULT_POLL_MS = 2000
		const val MIN_POLL_MS = 200
		const val DEFAULT_BMS_POLL_MS = 500
		const val DEFAULT_CTRL_POLL_MS = 200
		const val DEFAULT_RECORD_INTERVAL_MS = 1000
		const val RECORD_INTERVAL_SAME = 0
		val POLL_MS_VALUES = intArrayOf(200, 500, 1000, 2000, 5000, 10000)
		val RECORD_INTERVAL_MS_VALUES = intArrayOf(RECORD_INTERVAL_SAME, 500, 1000, 2000, 5000)
		const val DEFAULT_STOP_SPEED = 3
		const val REST_CURRENT_A = 5.0
		const val CHARGE_HOLD_SAMPLES = 3
		const val CHARGE_FINISH_KM = 1.0
		const val DATA_STALE_MS = 5000L
		private const val LINK_DEAD_MS = 12_000L
		private const val JBD_MODULE_RANDOM_WAIT_MS = 1_200L
		private const val JBD_MODULE_VERIFY_WAIT_MS = 1_500L
		private const val JBD_MODULE_RANDOM_TRIES = 3
		private val JBD_APPKEYS = arrayOf("000000", "765890")
		const val DEFAULT_CHARGE_STILL_SEC = 20
		const val DEFAULT_CHARGE_STILL_KMH = 5
		const val DEFAULT_CHARGE_CURRENT_A = 2
		const val DEFAULT_CHARGE_REARM_M = 200
		const val DEFAULT_CHARGE_REARM_MAH = 300
		private const val CONTROLLER_CHARGE_IDLE_A = 0.8
		private const val EV_DISCHARGE_IDLE_A = 2.0
		private const val EV_RPM_IDLE = 30
		private const val EV_MOTION_GRACE_MS = 8_000L
		private const val TAG = "EvBms"
		private const val CHART_HISTORY_MAX = 480
		private const val CHART_SAMPLE_MIN_MS = 500L
		private const val RANGE_SAMPLE_MIN_MS = 1000L
		const val DEFAULT_CHARGE_VOLT_STEP_MV = 1000
		const val DEFAULT_STOP_ANNOUNCE_REPEATS = 2
		const val DEFAULT_SPEED_CAL_DISTANCE_M = 1000
		const val DEFAULT_WHEEL_CIRCUMFERENCE_MM = 2000
		private const val MIN_WHEEL_REVS_FOR_CAL = 20L
		const val DEFAULT_CTRL_ODO_EXCESS_PERCENT = 120
		const val DEFAULT_VEHICLE_MASS_KG = 200f
		const val DEFAULT_DRIVER_MASS_KG = 80f
		const val DEFAULT_RESERVE_SMALL_KM = 5
		const val DEFAULT_RESERVE_LOW_KM = 15
		const val DEFAULT_SPEED_PROFILE_KMH = 25
		const val DEFAULT_HUD_SHOW_KMH = 40
		const val DEFAULT_HUD_LIMIT1_KMH = 40
		const val DEFAULT_HUD_BUFFER1_KMH = 50
		const val DEFAULT_HUD_LIMIT2_KMH = 60
		const val DEFAULT_HUD_BUFFER2_KMH = 70
		const val DEFAULT_HUD_STROKE_PERCENT = 30
		const val DEFAULT_HUD_HEIGHT_PERCENT = 90
		const val DEFAULT_HUD_FONT_PERCENT = 39
		const val DEFAULT_HUD_STATS_MINUTES = 5
		const val DEFAULT_HUD_FPS = 15
		const val DEFAULT_HUD_HIDE_DELAY_SEC = 5
		val HUD_FPS_VALUES = intArrayOf(5, 10, 15, 30, 60)
		val HUD_HIDE_DELAY_SEC_VALUES = IntArray(30) { it + 1 }
		private const val HUD_SPEED_SAMPLE_MS = 200L
		const val HUD_DEMO_MAX_KMH = 90.0
		const val HUD_DEMO_HALF_MS = 10_000L
		private const val SPEED_PROFILE_HYSTERESIS_KMH = 3.0
		private const val SPEED_PROFILE_HOLD_MS = 2000L
		private const val HISTORY_SAMPLE_MIN_MS = 2000L
		const val RANGE_SOURCE_10KM = "rolling_10km"
		const val RANGE_SOURCE_5MIN = "window_5min"
		const val RANGE_SOURCE_PNZ = "pnz"
		const val SESSION_IDLE = "idle"
		const val SESSION_RECORDING = "recording"
		const val SESSION_PAUSED = "paused"
	}

	val BMS_ADDRESS: CommonPreference<String> =
		registerStringPreference("ev_bms_address", "").makeGlobal().makeShared()
	val BMS_NAME: CommonPreference<String> =
		registerStringPreference("ev_bms_name", "").makeGlobal().makeShared()
	val CONTROLLER_ADDRESS: CommonPreference<String> =
		registerStringPreference("ev_controller_address", "").makeGlobal().makeShared()
	val CONTROLLER_NAME: CommonPreference<String> =
		registerStringPreference("ev_controller_name", "").makeGlobal().makeShared()
	val POLL_INTERVAL_MS: CommonPreference<Int> =
		registerIntPreference("ev_bms_poll_interval_ms", DEFAULT_POLL_MS).makeGlobal().makeShared()
	val BMS_POLL_MS: CommonPreference<Int> =
		registerIntPreference("ev_bms_bms_poll_ms", DEFAULT_BMS_POLL_MS).makeGlobal().makeShared()
	val CONTROLLER_POLL_MS: CommonPreference<Int> =
		registerIntPreference("ev_bms_ctrl_poll_ms", DEFAULT_CTRL_POLL_MS).makeGlobal().makeShared()
	val RECORD_INTERVAL_MS: CommonPreference<Int> =
		registerIntPreference("ev_bms_record_interval_ms", DEFAULT_RECORD_INTERVAL_MS).makeGlobal().makeShared()
	val RECORD_TELEMETRY: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_record_telemetry", false).makeGlobal().makeShared()
	val RECORD_GPX: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_record_gpx", true).makeGlobal().makeShared()
	val TELEMETRY_FIELDS: CommonPreference<String> =
		registerStringPreference("ev_bms_telemetry_fields", TelemetryField.DEFAULT_IDS).makeGlobal().makeShared()
	val TELEMETRY_GPX_FIELDS: CommonPreference<String> =
		registerStringPreference("ev_bms_telemetry_gpx_fields", "").makeGlobal().makeShared()
	val SHEET_TAB: CommonPreference<Int> =
		registerIntPreference("ev_bms_sheet_tab", 0).makeGlobal().makeShared()
	val DEBUG_JOURNAL: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_debug_journal", false).makeGlobal().makeShared()
	val SOC_CAL_STORE: CommonPreference<String> =
		registerStringPreference("ev_bms_soc_cal_store", "").makeGlobal().makeShared()
	val CHARGE_STILL_SEC: CommonPreference<Int> =
		registerIntPreference("ev_bms_charge_still_sec", DEFAULT_CHARGE_STILL_SEC).makeGlobal().makeShared()
	val CHARGE_STILL_KMH: CommonPreference<Int> =
		registerIntPreference("ev_bms_charge_still_kmh", DEFAULT_CHARGE_STILL_KMH).makeGlobal().makeShared()
	val CHARGE_CURRENT_A: CommonPreference<Int> =
		registerIntPreference("ev_bms_charge_current_a", DEFAULT_CHARGE_CURRENT_A).makeGlobal().makeShared()
	val CHARGE_REARM_M: CommonPreference<Int> =
		registerIntPreference("ev_bms_charge_rearm_m", DEFAULT_CHARGE_REARM_M).makeGlobal().makeShared()
	val CHARGE_REARM_MAH: CommonPreference<Int> =
		registerIntPreference("ev_bms_charge_rearm_mah", DEFAULT_CHARGE_REARM_MAH).makeGlobal().makeShared()
	val ANNOUNCE_SOC: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_announce_soc", true).makeGlobal().makeShared()
	val CHARGE_VOLT_STEP_MV: CommonPreference<Int> =
		registerIntPreference("ev_bms_charge_volt_step_mv", DEFAULT_CHARGE_VOLT_STEP_MV).makeGlobal().makeShared()
	val ANNOUNCE_RANGE_ON_STOP: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_announce_range_on_stop", true).makeGlobal().makeShared()
	val STOP_ANNOUNCE_REPEATS: CommonPreference<Int> =
		registerIntPreference("ev_bms_stop_announce_repeats", DEFAULT_STOP_ANNOUNCE_REPEATS).makeGlobal().makeShared()
	val STOP_SPEED_KMH: CommonPreference<Int> =
		registerIntPreference("ev_bms_stop_speed_kmh", DEFAULT_STOP_SPEED).makeGlobal().makeShared()
	val USE_ROUTE_PROFILE: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_use_route_profile", false).makeGlobal().makeShared()
	val BMS_PROTOCOL: CommonPreference<String> =
		registerStringPreference("ev_bms_protocol", "auto").makeGlobal().makeShared()
	val BMS_PASSWORD: CommonPreference<String> =
		registerStringPreference("ev_bms_jbd_password", "").makeGlobal().makeShared()
	val CSV_FOLDER_URI: CommonPreference<String> =
		registerStringPreference("ev_bms_csv_folder_uri", "").makeGlobal().makeShared()
	val ANNOUNCE_RANGE_VS_ROUTE: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_announce_range_vs_route", true).makeGlobal().makeShared()
	val ANNOUNCE_RANGE_RESERVE: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_announce_range_reserve", true).makeGlobal().makeShared()
	val ANNOUNCE_RANGE_RESERVE_SMALL: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_announce_range_reserve_small", true).makeGlobal().makeShared()
	val ANNOUNCE_RANGE_RESERVE_LOW: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_announce_range_reserve_low", true).makeGlobal().makeShared()
	val RANGE_RESERVE_SMALL_KM: CommonPreference<Int> =
		registerIntPreference("ev_bms_range_reserve_small_km", DEFAULT_RESERVE_SMALL_KM).makeGlobal().makeShared()
	val RANGE_RESERVE_LOW_KM: CommonPreference<Int> =
		registerIntPreference("ev_bms_range_reserve_low_km", DEFAULT_RESERVE_LOW_KM).makeGlobal().makeShared()
	val RANGE_FOR_RESERVE: CommonPreference<String> =
		registerStringPreference("ev_bms_range_for_reserve", RANGE_SOURCE_10KM).makeGlobal().makeShared()
	val CHARTS_LIVE: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_charts_live", true).makeGlobal().makeShared()
	val CHART_ORDER: CommonPreference<String> =
		registerStringPreference("ev_bms_chart_order", "").makeGlobal().makeShared()
	val CHART_PAUSED: CommonPreference<String> =
		registerStringPreference("ev_bms_chart_paused", "").makeGlobal().makeShared()
	val CHARGE_STOP_PENDING: CommonPreference<String> =
		registerStringPreference("ev_bms_charge_stop_pending", "").makeGlobal().makeShared()
	val CONTROLLER_PROTOCOL: CommonPreference<String> =
		registerStringPreference("ev_controller_protocol", "auto").makeGlobal().makeShared()
	val ANNOUNCE_CELL_VOLTAGE: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_announce_cell_voltage", true).makeGlobal().makeShared()
	val LOW_CELL_MV: CommonPreference<Int> =
		registerIntPreference("ev_bms_low_cell_mv", 3500).makeGlobal().makeShared()
	val CRITICAL_CELL_MV: CommonPreference<Int> =
		registerIntPreference("ev_bms_critical_cell_mv", 3300).makeGlobal().makeShared()
	val CELL_ALERT_INTERVAL_SEC: CommonPreference<Int> =
		registerIntPreference("ev_bms_cell_alert_interval_sec", 60).makeGlobal().makeShared()
	val ANNOUNCE_MOTOR_HEAT: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_announce_motor_heat", true).makeGlobal().makeShared()
	val MOTOR_HEAT_C: CommonPreference<Int> =
		registerIntPreference("ev_bms_motor_heat_c", 90).makeGlobal().makeShared()
	val ANNOUNCE_BATTERY_OVERHEAT: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_announce_battery_overheat", true).makeGlobal().makeShared()
	val BATTERY_OVERHEAT_C: CommonPreference<Int> =
		registerIntPreference("ev_bms_battery_overheat_c", 50).makeGlobal().makeShared()
	val ANNOUNCE_BATTERY_FREEZE: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_announce_battery_freeze", true).makeGlobal().makeShared()
	val BATTERY_FREEZE_C: CommonPreference<Int> =
		registerIntPreference("ev_bms_battery_freeze_c", 0).makeGlobal().makeShared()
	val ANNOUNCE_LINK: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_announce_link", true).makeGlobal().makeShared()
	val ANNOUNCE_CHARGE_ETA: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_announce_charge_eta", true).makeGlobal().makeShared()
	val HIKE_MODE: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_hike_mode", false).makeGlobal().makeShared()
	val HIKE_SNAPSHOT: CommonPreference<String> =
		registerStringPreference("ev_bms_hike_snapshot", "").makeGlobal()
	val SPEED_CAL_DISTANCE_M: CommonPreference<Int> =
		registerIntPreference("ev_bms_speed_cal_distance_m", DEFAULT_SPEED_CAL_DISTANCE_M).makeGlobal().makeShared()
	val SPEED_CAL_FACTOR: CommonPreference<Float> =
		registerFloatPreference("ev_bms_speed_cal_factor", 1f).makeGlobal().makeShared()
	val SPEED_SENSOR_ADDRESS: CommonPreference<String> =
		registerStringPreference("ev_speed_sensor_address", "").makeGlobal().makeShared()
	val SPEED_SENSOR_NAME: CommonPreference<String> =
		registerStringPreference("ev_speed_sensor_name", "").makeGlobal().makeShared()
	val WHEEL_CIRCUMFERENCE_MM: CommonPreference<Int> =
		registerIntPreference("ev_bms_wheel_circumference_mm", DEFAULT_WHEEL_CIRCUMFERENCE_MM).makeGlobal().makeShared()
	val SPEED_SENSOR_CAL_FACTOR: CommonPreference<Float> =
		registerFloatPreference("ev_bms_speed_sensor_cal_factor", 1f).makeGlobal().makeShared()
	val SPEED_SENSOR_ODO_KM: CommonPreference<Float> =
		registerFloatPreference("ev_bms_speed_sensor_odo_km", 0f).makeGlobal().makeShared()
	val SPEED_SENSOR_TRIP_KM: CommonPreference<Float> =
		registerFloatPreference("ev_bms_speed_sensor_trip_km", 0f).makeGlobal().makeShared()
	val CTRL_TRIP_START_KM: CommonPreference<Float> =
		registerFloatPreference("ev_bms_ctrl_trip_start_km", -1f).makeGlobal().makeShared()
	val CADENCE_SENSOR_ADDRESS: CommonPreference<String> =
		registerStringPreference("ev_cadence_sensor_address", "").makeGlobal().makeShared()
	val CADENCE_SENSOR_NAME: CommonPreference<String> =
		registerStringPreference("ev_cadence_sensor_name", "").makeGlobal().makeShared()
	val FILTER_CTRL_ODO: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_filter_ctrl_odo", true).makeGlobal().makeShared()
	val CTRL_ODO_EXCESS_PERCENT: CommonPreference<Int> =
		registerIntPreference("ev_bms_ctrl_odo_excess_percent", DEFAULT_CTRL_ODO_EXCESS_PERCENT).makeGlobal().makeShared()
	val SPEED_PROFILE_AUTO: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_speed_profile_auto", false).makeGlobal().makeShared()
	val SPEED_PROFILE_KMH: CommonPreference<Int> =
		registerIntPreference("ev_bms_speed_profile_kmh", DEFAULT_SPEED_PROFILE_KMH).makeGlobal().makeShared()
	val SPEED_PROFILE_SLOW: CommonPreference<String> =
		registerStringPreference("ev_bms_speed_profile_slow", "").makeGlobal().makeShared()
	val SPEED_PROFILE_FAST: CommonPreference<String> =
		registerStringPreference("ev_bms_speed_profile_fast", "").makeGlobal().makeShared()
	val HUD_SHOW_KMH: CommonPreference<Int> =
		registerIntPreference("ev_bms_hud_show_kmh", DEFAULT_HUD_SHOW_KMH).makeGlobal().makeShared()
	val HUD_LIMIT1_KMH: CommonPreference<Int> =
		registerIntPreference("ev_bms_hud_limit1_kmh", DEFAULT_HUD_LIMIT1_KMH).makeGlobal().makeShared()
	val HUD_BUFFER1_KMH: CommonPreference<Int> =
		registerIntPreference("ev_bms_hud_buffer1_kmh", DEFAULT_HUD_BUFFER1_KMH).makeGlobal().makeShared()
	val HUD_LIMIT2_KMH: CommonPreference<Int> =
		registerIntPreference("ev_bms_hud_limit2_kmh", DEFAULT_HUD_LIMIT2_KMH).makeGlobal().makeShared()
	val HUD_BUFFER2_KMH: CommonPreference<Int> =
		registerIntPreference("ev_bms_hud_buffer2_kmh", DEFAULT_HUD_BUFFER2_KMH).makeGlobal().makeShared()
	val HUD_DEMO: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_hud_demo", false).makeGlobal()
	val HUD_STROKE_PERCENT: CommonPreference<Int> =
		registerIntPreference("ev_bms_hud_stroke_percent", DEFAULT_HUD_STROKE_PERCENT).makeGlobal().makeShared()
	val HUD_HEIGHT_PERCENT: CommonPreference<Int> =
		registerIntPreference("ev_bms_hud_height_percent", DEFAULT_HUD_HEIGHT_PERCENT).makeGlobal().makeShared()
	val HUD_FONT_PERCENT: CommonPreference<Int> =
		registerIntPreference("ev_bms_hud_font_percent", DEFAULT_HUD_FONT_PERCENT).makeGlobal().makeShared()
	val HUD_SHOW_UNITS: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_hud_show_units", false).makeGlobal().makeShared()
	val HUD_STATS_MINUTES: CommonPreference<Int> =
		registerIntPreference("ev_bms_hud_stats_minutes", DEFAULT_HUD_STATS_MINUTES).makeGlobal().makeShared()
	val HUD_FPS: CommonPreference<Int> =
		registerIntPreference("ev_bms_hud_fps", DEFAULT_HUD_FPS).makeGlobal().makeShared()
	val HUD_HIDE_DELAY_SEC: CommonPreference<Int> =
		registerIntPreference("ev_bms_hud_hide_delay_sec", DEFAULT_HUD_HIDE_DELAY_SEC).makeGlobal().makeShared()
	val VEHICLE_MASS_KG: CommonPreference<Float> =
		registerFloatPreference("ev_bms_vehicle_mass_kg", DEFAULT_VEHICLE_MASS_KG).makeGlobal().makeShared()
	val DRIVER_MASS_KG: CommonPreference<Float> =
		registerFloatPreference("ev_bms_driver_mass_kg", DEFAULT_DRIVER_MASS_KG).makeGlobal().makeShared()
	val TORRENT_ENABLED: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_torrent_enabled", false).makeGlobal().makeShared()
	val TORRENT_PATH: CommonPreference<String> =
		registerStringPreference("ev_bms_torrent_path", "").makeGlobal().makeShared()
	val TORRENT_NAME: CommonPreference<String> =
		registerStringPreference("ev_bms_torrent_name", "").makeGlobal().makeShared()
	val TORRENT_SEED_ON_CHARGE: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_torrent_seed_charge", true).makeGlobal().makeShared()
	val TORRENT_WIFI_ONLY: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_torrent_wifi_only", true).makeGlobal().makeShared()
	val TORRENT_DOWNLOAD_NEW: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_torrent_download_new", false).makeGlobal().makeShared()
	val TORRENT_DOWNLOADED: CommonPreference<Long> =
		registerLongPreference("ev_bms_torrent_downloaded", 0L).makeGlobal()
	val TORRENT_UPLOADED: CommonPreference<Long> =
		registerLongPreference("ev_bms_torrent_uploaded", 0L).makeGlobal()
	private val CHARGE_END_TRACK_M: CommonPreference<Int> =
		registerIntPreference("ev_bms_charge_end_track_m", 0).makeGlobal()
	private val CHARGE_CYCLE_ACTIVE: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_charge_cycle_active", false).makeGlobal()
	private val CHARGE_SESSION: CommonPreference<String> =
		registerStringPreference("ev_bms_charge_session", "").makeGlobal()
	private val TRIP_SESSION: CommonPreference<String> =
		registerStringPreference("ev_bms_trip_session", "").makeGlobal()
	private val CHARGE_HISTORY: CommonPreference<String> =
		registerStringPreference("ev_bms_charge_history", "").makeGlobal().makeShared()
	private val TRIP_HISTORY: CommonPreference<String> =
		registerStringPreference("ev_bms_trip_history", "").makeGlobal().makeShared()
	val SETTINGS_PROFILES: CommonPreference<String> =
		registerStringPreference("ev_bms_settings_profiles", "").makeGlobal().makeShared()
	val SETTINGS_PROFILE: CommonPreference<String> =
		registerStringPreference("ev_bms_settings_profile", EvBmsProfileStore.DEFAULT_NAME).makeGlobal().makeShared()
	private val CHARGE_REARM: CommonPreference<String> =
		registerStringPreference("ev_bms_charge_rearm", "").makeGlobal()
	private val TELEMETRY_SESSION_STATE: CommonPreference<String> =
		registerStringPreference("ev_bms_telemetry_session_state", SESSION_IDLE).makeGlobal()
	private val TELEMETRY_SESSION_CSV: CommonPreference<String> =
		registerStringPreference("ev_bms_telemetry_session_csv", "").makeGlobal()
	private val TELEMETRY_SESSION_GPX: CommonPreference<String> =
		registerStringPreference("ev_bms_telemetry_session_gpx", "").makeGlobal()
	private val TELEMETRY_SESSION_FIELDS: CommonPreference<String> =
		registerStringPreference("ev_bms_telemetry_session_fields", "").makeGlobal()

	private val handler = Handler(Looper.getMainLooper())
	private val rangeEstimator = RangeEstimator()
	private val recorder = TelemetryRecorder(app)
	private val journal = EvDebugJournal(app)
	private val voice = EvVoiceAnnouncer(app)
	private val historyStore = EvHistoryStore(app)
	private val historyCharts = EvHistoryChartStore(app)
	private val profileStore by lazy {
		EvBmsProfileStore(app, this, SETTINGS_PROFILES, SETTINGS_PROFILE)
	}
	private val mapTorrent by lazy { EvMapTorrentEngine(app, this) }
	private var torrentNetworkCallback: android.net.ConnectivityManager.NetworkCallback? = null
	private var torrentPowerReceiver: android.content.BroadcastReceiver? = null
	private val hikeMode = HikeModeController(app, this)
	private val farSnapshot = FarDriverProtocol.FarDriverSnapshot()
	private val vescSnapshot = VescProtocol.VescSnapshot()
	private var farStatusStarted = false
	private var vescPollSetup = false

	private var bmsClient: EvBleUartClient? = null
	private var controllerClient: EvBleUartClient? = null
	private var speedSensorClient: EvBleUartClient? = null
	private var cadenceSensorClient: EvBleUartClient? = null
	private val wheelTracker = CscWheelTracker()
	private val cadenceTracker = CscCadenceTracker()
	private var lastSpeedSensorRxMs = 0L
	private var lastCadenceRxMs = 0L
	private var speedCalWheelStartRevs: Long? = null
	private var speedCalUseWheel = false
	private var bmsBuffer = ByteArray(0)
	private var controllerBuffer = ByteArray(0)
	private var lastBms: BmsSnapshot? = null
	private var lastCells: List<Double>? = null
	private var lastLocation: Location? = null
	@Volatile
	var minCellVoltageV: Double? = null
		private set
	@Volatile
	var restPackVoltageV: Double? = null
		private set
	private var farTripStartKm: Double? = null
	private var speedCalRunning = false
	private var speedCalGpsM = 0.0
	private var speedCalCtrlStartKm: Double? = null
	private var speedCalCtrlM = 0.0
	private var speedCalLastLoc: Location? = null
	private var speedCalLastMs = 0L
	private var speedProfileWantFast: Boolean? = null
	private var speedProfileSinceMs = 0L
	private val hudSpeedWindow = ArrayDeque<Pair<Long, Double>>()
	private var lastHudSampleMs = 0L
	private var hudWantVisible = false
	private var hudHideDeadlineMs = 0L
	private var lastChargeAh: Double? = null
	private var stillSinceMs: Long? = null
	private var charging = false
	private var chargeSessionOpen = false
	private var chargeHold = 0
	private var chargeExitHold = 0
	private var chargeStartMs = 0L
	private var chargeStartAh: Double? = null
	private var chargeStartTempC: Double? = null
	private var chargeStartMinCellV: Double? = null
	private var chargeParkedSinceMs = 0L
	private var pendingStopRecordStartMs = 0L
	private var pendingStopParkedSinceMs = 0L
	private var lastStopMs: Long? = null
	private var chargeStartLat: Double? = null
	private var chargeStartLon: Double? = null
	private var chargeFrozenCurrentA: Double? = null
	private var chargeFullAh: Double? = null
	private var chargeLastAh: Double? = null
	private var chargeLastAhMs = 0L
	private var chargeEnergyWhAcc = 0.0
	private var chargeEnergyLastMs = 0L
	private var chargeCurrentIntegralAms = 0.0
	private var chargeCurrentDurationMs = 0L
	private var chargeCurrentStoppedMs = 0L
	private var postChargeDistanceKm = 0.0
	private var postChargeLastOdoKm: Double? = null
	private var postChargeLastWheelKm: Double? = null
	private var postChargeLastLoc: Location? = null
	private var postChargeLastMs = 0L
	private val socCalibrator = SocCalibrator()
	private var calibratedSocPercent: Int? = null
	private var socVoltagePercent: Int? = null
	private var tripStartMs = 0L
	private var tripStartRemainingAh: Double? = null
	private var tripStartVoltageV: Double? = null
	private var tripStartTempC: Double? = null
	private var tripStartLat: Double? = null
	private var tripStartLon: Double? = null
	private var tripMinCellV: Double? = null
	private var tripLastVoltageV: Double? = null
	private var tripLastTempC: Double? = null
	private var tripStartMotorTempC: Double? = null
	private var tripLastMotorTempC: Double? = null
	private var tripMovingMs = 0L
	private var tripLastMoveMs = 0L
	private var chargeTripKmAcc = 0.0
	private var chargeTripLastWheelKm: Double? = null
	private var chargeTripLastCtrlKm: Double? = null
	private var chargeTripLastLoc: Location? = null
	private var chargeTripLastMs = 0L
	private var rearmReady = true
	private var rearmGpsKm = 0.0
	private var rearmStartOdoKm: Double? = null
	private var rearmStartAh: Double? = null
	private var rearmStartSoc: Int? = null
	private var rearmLastLoc: Location? = null
	private var lastEvMotionMs = 0L
	private val pendingGpxEvents = ArrayList<GpxEvent>()

	private data class GpxEvent(
		val name: String,
		val description: String,
		val lat: Double?,
		val lon: Double?,
		val timeMs: Long,
		val waypoint: Boolean
	)
	private val historySamples = ArrayList<EvHistoryChartStore.Sample>()
	private var historySampleLastMs = 0L
	private var lastBmsRxMs = 0L
	private var lastCtrlRxMs = 0L
	private var pollCellsNext = false
	private var lastBmsPollMs = 0L
	private var lastCtrlPollMs = 0L
	private var lastRangeSampleMs = 0L
	private var lastChartSampleMs = 0L
	private var lastRecordMs = 0L
	private var jbdPasswordSentMs = 0L
	private var jbdPasswordToastMs = 0L
	private var jbdPasswordRejected = false
	private var jbdModuleAuth = JbdModuleAuth.IDLE
	private var jbdModuleAuthMs = 0L
	private var jbdModuleRandomTries = 0
	private var jbdModuleUsedNewKey = false
	private var jbdTriedOldAppKey = false
	private var jbdAppKeyIndex = 0
	@Volatile
	var latestTelemetry: EvTelemetry? = null
		private set
	private val chartHistory = ArrayDeque<EvTelemetry>()
	private val chartLock = Any()

	private var mapActivity: MapActivity? = null
	private var pollRunning = false

	private val pollRunnable = object : Runnable {
		override fun run() {
			if (!pollRunning) {
				return
			}
			ensureBleLinks()
			val now = android.os.SystemClock.uptimeMillis()
			if (bmsClient?.connected == true && now - lastBmsPollMs >= activeBmsPollMs()) {
				lastBmsPollMs = now
				pollBms()
			}
			if (controllerClient?.connected == true && now - lastCtrlPollMs >= activeCtrlPollMs()) {
				lastCtrlPollMs = now
				pollController()
			}
			publishSample()
			handler.postDelayed(this, activeTickMs())
		}
	}

	private fun pollBms() {
		if (preferAntProtocol()) {
			bmsClient?.write(AntBmsProtocol.statusRequest())
		} else if (advanceJbdAuth()) {
			// BLE-module unlock and UART password before telemetry reads.
		} else if (pollCellsNext) {
			bmsClient?.write(JbdBmsProtocol.readCellVoltages())
			pollCellsNext = false
		} else {
			bmsClient?.write(JbdBmsProtocol.readBasicInfo())
			pollCellsNext = true
		}
	}

	private fun pollController() {
		val unknown = controllerClient?.detectedControllerKind ==
				EvBleUartClient.ControllerKind.UNKNOWN
		if (preferVescProtocol() || (!preferFarProtocol() && unknown)) {
			if (vescSnapshot.hwName == null && vescSnapshot.fwMajor == null) {
				controllerClient?.write(VescProtocol.fwVersion())
			} else if (vescPollSetup) {
				controllerClient?.write(VescProtocol.getValuesSetup())
			} else {
				controllerClient?.write(VescProtocol.getValues())
			}
			vescPollSetup = !vescPollSetup
		}
		if (!farStatusStarted && (preferFarProtocol() || (!preferVescProtocol() && unknown))) {
			controllerClient?.write(FarDriverProtocol.startStatusCommand())
			farStatusStarted = true
		}
	}

	override fun getId(): String {
		return OsmAndCustomizationConstants.PLUGIN_EV_BMS
	}

	override fun getName(): String {
		return app.getString(R.string.ev_bms_plugin_name)
	}

	fun descriptionHtml(): String {
		return app.getString(R.string.ev_bms_plugin_description) +
				app.getString(R.string.ev_bms_plugin_range_method) +
				app.getString(R.string.ev_bms_plugin_architecture) +
				app.getString(R.string.ev_bms_changelog, EvBmsRevision.GIT_HASH)
	}

	override fun getDescription(linksEnabled: Boolean): CharSequence {
		return HtmlCompat.fromHtml(descriptionHtml(), HtmlCompat.FROM_HTML_MODE_LEGACY)
	}

	override fun getLogoResourceId(): Int {
		return R.drawable.ic_action_car_info
	}

	override fun getAssetResourceImage(): Drawable? {
		return app.uiUtilities.getIcon(R.drawable.osmand_development)
	}

	override fun getSettingsScreenType(): SettingsScreenType {
		return SettingsScreenType.EV_BMS_SETTINGS
	}

	override fun init(app: OsmandApplication, activity: Activity?): Boolean {
		bmsClient = EvBleUartClient(app, EvBleUartClient.Role.BMS, this, journal)
		controllerClient = EvBleUartClient(app, EvBleUartClient.Role.CONTROLLER, this, journal)
		speedSensorClient = EvBleUartClient(app, EvBleUartClient.Role.SPEED, this, journal)
		cadenceSensorClient = EvBleUartClient(app, EvBleUartClient.Role.CADENCE, this, journal)
		configureWheelTracker()
		wheelTracker.restoreOdometerKm(SPEED_SENSOR_ODO_KM.get().toDouble())
		wheelTracker.restoreTripKm(SPEED_SENSOR_TRIP_KM.get().toDouble())
		val savedCtrlStart = CTRL_TRIP_START_KM.get().toDouble()
		if (savedCtrlStart >= 0.0) {
			farTripStartKm = savedCtrlStart
		}
		journal.enabled = DEBUG_JOURNAL.get()
		if (journal.enabled) {
			journal.i("plugin", "init hash=${EvBmsRevision.GIT_HASH}")
		}
		Log.i(TAG, "plugin init hash=${EvBmsRevision.GIT_HASH} journal=${journal.enabled}")
		voice.init()
		socCalibrator.decode(SOC_CAL_STORE.get())
		migratePollPreferences()
		migrateCadenceTelemetryField()
		restoreSessions()
		mergeSplitChargeHistory()
		profileStore.ensureDefault()
		restoreTelemetrySession()
		registerTorrentWatchers()
		// Defer so MapActivity can finish first frame; torrent JNI runs on a worker thread.
		handler.postDelayed({ syncMapTorrent() }, 2000)
		return true
	}

	override fun disable(app: OsmandApplication) {
		super.disable(app)
		unregisterTorrentWatchers()
		mapTorrent.stop()
		stopPolling()
		recorder.detach()
		stopSpeedCalibration(notify = false)
		persistSocCal()
		voice.shutdown()
		bmsClient?.disconnect()
		controllerClient?.disconnect()
		speedSensorClient?.disconnect()
		cadenceSensorClient?.disconnect()
		persistWheelOdometer()
	}

	override fun mapActivityResume(activity: MapActivity) {
		mapActivity = activity
		journal.enabled = DEBUG_JOURNAL.get()
		journal.i("plugin", "map resume hash=${EvBmsRevision.GIT_HASH}, reconnect saved devices")
		connectSavedDevices(activity)
		startPolling()
		applyHikeTelemetryState()
		restoreTelemetrySessionIfNeeded()
		syncMapTorrent()
	}

	override fun mapActivityPause(activity: MapActivity) {
		if (mapActivity === activity) {
			mapActivity = null
		}
	}

	override fun updateLocation(location: Location?) {
		lastLocation = location
	}

	fun fusedSpeedKmh(location: Location? = lastLocation): Double? {
		val wheel = wheelSpeedKmh()
		if (wheel != null) {
			return wheel
		}
		val gpsSpeed = if (location != null && location.hasSpeed()) location.speed * 3.6 else null
		val farSpeed = controllerSpeedKmh()
		if (rangeEstimator.gpsUnreliable) {
			return farSpeed ?: gpsSpeed
		}
		if (gpsSpeed != null && gpsSpeed <= 160.0 &&
			(location == null || !location.hasAccuracy() || location.accuracy <= 40f)
		) {
			return gpsSpeed
		}
		return farSpeed ?: gpsSpeed
	}

	fun batteryAnnounceTempC(): Double? {
		val temps = lastBms?.temperaturesC
		if (temps.isNullOrEmpty()) {
			return null
		}
		val month = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
		return if (month in 5..9) {
			temps.maxOrNull()?.toDouble()
		} else {
			temps.minOrNull()?.toDouble()
		}
	}

	fun farTripKm(): Double? = sessionControllerTripKm()

	private fun sessionControllerTripKm(): Double? {
		val odo = rawCtrlOdometerKm() ?: return null
		val start = farTripStartKm
		if (start == null) {
			setControllerTripStart(odo)
			return 0.0
		}
		return ((odo - start) * controllerCalFactor()).coerceAtLeast(0.0)
	}

	private fun sessionOdometerKm(): Double? {
		return sessionControllerTripKm() ?: wheelTracker.tripKm.takeIf { it >= 0.0 }
	}

	private fun setControllerTripStart(odoKm: Double?) {
		farTripStartKm = odoKm
		CTRL_TRIP_START_KM.set((odoKm ?: -1.0).toFloat())
	}

	fun isCharging(): Boolean = charging

	fun chargeRemainingMs(): Long? {
		if (!charging) {
			return null
		}
		val current = lastBms?.currentA.let { live ->
			when {
				live != null && live >= 0.4 -> live
				live != null -> return null
				else -> chargeFrozenCurrentA
			}
		}
		val lastAh = chargeLastAh ?: estimatedRemainingAh()
		val full = chargeFullAh?.takeIf { it > 0.1 } ?: lastBms?.fullMah?.div(1000.0)?.takeIf { it > 0.1 }
		if (current == null || current < 0.4 || full == null || lastAh == null) {
			return null
		}
		val elapsedH = if (chargeLastAhMs > 0L) {
			(System.currentTimeMillis() - chargeLastAhMs).coerceAtLeast(0L) / 3_600_000.0
		} else {
			0.0
		}
		val estimatedAh = lastAh + current * elapsedH
		val leftAh = (full - estimatedAh).coerceAtLeast(0.0)
		if (leftAh <= 0.05) {
			return 5 * 60_000L
		}
		return (leftAh / current * 3_600_000.0).toLong()
	}

	fun chargeElapsedMs(): Long? {
		if (!charging || chargeStartMs <= 0L) {
			return null
		}
		return (System.currentTimeMillis() - chargeStartMs).coerceAtLeast(0L)
	}

	fun chargeEnergyWh(): Double? {
		if (!charging) {
			return null
		}
		return chargeEnergyWhAcc.takeIf { it >= 0.0 }
	}

	fun chargeHistory(): List<EvHistoryStore.ChargeRecord> = historyStore.parseCharges(CHARGE_HISTORY.get())

	fun tripHistory(): List<EvHistoryStore.ChargeTripRecord> = historyStore.parseTrips(TRIP_HISTORY.get())

	fun chargeTripKm(): Double? {
		if (charging) {
			return 0.0
		}
		if (tripStartMs <= 0L) {
			return null
		}
		val fromRange = rangeEstimator.tripDistanceKm()
		if (fromRange != null && fromRange > 0.0) {
			return fromRange
		}
		return chargeTripKmAcc.takeIf { it > 0.0 || hasTelemetrySession() }
	}

	private fun tripUsedAh(remainingAh: Double?): Double? {
		if (charging || tripStartMs <= 0L) {
			return null
		}
		val start = tripStartRemainingAh
		if (start != null && remainingAh != null) {
			return (start - remainingAh).coerceAtLeast(0.0)
		}
		return rangeEstimator.tripUsedAh()
	}

	private fun isVehicleMoving(): Boolean = hasEvMotionEvidence()

	private fun hasEvMotionEvidence(now: Long = System.currentTimeMillis()): Boolean {
		if (evMotionNow()) {
			lastEvMotionMs = now
			return true
		}
		return lastEvMotionMs > 0L && now - lastEvMotionMs <= EV_MOTION_GRACE_MS
	}

	private fun evMotionNow(): Boolean {
		val limit = CHARGE_STILL_KMH.get().toDouble().coerceAtLeast(1.0)
		val ctrlRotating = isControllerFresh() && (
			(controllerSpeedKmh() ?: 0.0) >= limit || (ctrlRpm() ?: 0) >= EV_RPM_IDLE
		)
		val wheelRotating = isSpeedSensorFresh() &&
			(wheelTracker.currentSpeedKmh() ?: 0.0) >= limit
		val bmsDriving = isBmsFresh() && (lastBms?.currentA ?: 0.0) <= -EV_DISCHARGE_IDLE_A
		return ctrlRotating || wheelRotating || bmsDriving
	}

	private fun updateChargeCycle(
		bmsCurrentA: Double?,
		ctrlCurrentA: Double?,
		remainingAh: Double?,
		fullAh: Double?,
		voltageV: Double?,
		tempC: Double?,
		minCellV: Double?,
		loc: Location?,
		bmsFresh: Boolean,
		ctrlFresh: Boolean
	) {
		val now = System.currentTimeMillis()
		val moving = isVehicleMoving()
		if (moving) {
			stillSinceMs?.let { lastStopMs = (now - it).coerceAtLeast(0L) }
			stillSinceMs = null
		} else if (stillSinceMs == null) {
			stillSinceMs = now
		}
		val stillMs = CHARGE_STILL_SEC.get().toLong().coerceAtLeast(10L) * 1000L
		val minA = CHARGE_CURRENT_A.get().toDouble().coerceAtLeast(0.5)
		val packI = if (bmsFresh) bmsCurrentA else null
		val ctrlI = if (ctrlFresh) ctrlCurrentA else null
		val ctrlIdle = ctrlI == null || kotlin.math.abs(ctrlI) < CONTROLLER_CHARGE_IDLE_A
		val packing = packI != null && packI >= minA
		val ahRising = remainingAh != null && lastChargeAh != null && remainingAh - lastChargeAh!! >= 0.03
		val stillNeeded = if (packing || ahRising) {
			minOf(stillMs, 15_000L).coerceAtLeast(8_000L)
		} else {
			stillMs
		}
		val stillLongEnough = stillSinceMs != null && now - stillSinceMs!! >= stillNeeded
		val intoPack = packing && ctrlIdle
		val chargeLike = !moving && stillLongEnough && intoPack
		if (chargeLike || charging || (packI != null && kotlin.math.abs(packI) >= 0.4)) {
			Log.i(
				TAG,
				"charge detect moving=$moving still=${stillSinceMs?.let { now - it }}ms " +
						"bmsI=$packI ctrlI=$ctrlI ctrlIdle=$ctrlIdle packing=$packing minA=$minA " +
						"ah=$remainingAh lastAh=$lastChargeAh rising=$ahRising like=$chargeLike " +
						"charging=$charging hold=$chargeHold fresh=$bmsFresh"
			)
			journal.d(
				"charge",
				"detect moving=$moving still=${stillSinceMs?.let { now - it }}ms " +
						"bmsI=$packI ctrlI=$ctrlI ctrlIdle=$ctrlIdle packing=$packing minA=$minA " +
						"ah=$remainingAh lastAh=$lastChargeAh rising=$ahRising like=$chargeLike " +
						"charging=$charging hold=$chargeHold fresh=$bmsFresh rearm=$rearmReady"
			)
		}
		if (chargeLike) {
			chargeHold++
			chargeExitHold = 0
			if (chargeSessionOpen && !charging) {
				resumeChargeCurrent(packI, remainingAh)
			} else if (!chargeSessionOpen && chargeHold >= CHARGE_HOLD_SAMPLES) {
				if (reopenPendingCharge(now, remainingAh, fullAh, packI ?: minA, tempC, loc)) {
					journal.i("charge", "reopen pending I=$packI ah=$remainingAh")
				} else if (canStartNextCharge()) {
					beginCharge(now, remainingAh, fullAh, packI ?: minA, tempC, loc)
				}
			}
		} else if (chargeSessionOpen) {
			chargeHold = 0
			if (charging && moving) {
				chargeExitHold++
				if (chargeExitHold >= CHARGE_HOLD_SAMPLES) {
					pauseChargeCurrent(now, remainingAh, tempC, loc)
				}
			} else {
				chargeExitHold = 0
			}
			if (!charging) {
				accumulatePostChargeDistance(now, moving, loc)
				if (postChargeDistanceKm >= CHARGE_FINISH_KM) {
					finishCharge(now, remainingAh, tempC, loc)
				}
			}
		} else {
			chargeHold = 0
			chargeExitHold = 0
		}
		if (charging || chargeSessionOpen) {
			if (bmsFresh) {
				if (remainingAh != null) {
					chargeLastAh = remainingAh
					chargeLastAhMs = now
				}
				if (packI != null && packI >= 0.4) {
					chargeFrozenCurrentA = packI
				}
				if (fullAh != null && fullAh > 0) {
					chargeFullAh = fullAh
				}
			}
			val energyA = if (packI != null && packI >= 0.15) packI else 0.0
			if (charging && voltageV != null && voltageV > 0 && energyA >= 0.15 && chargeEnergyLastMs > 0L) {
				val dtMs = (now - chargeEnergyLastMs).coerceAtLeast(0L)
				val hours = dtMs / 3_600_000.0
				chargeEnergyWhAcc += voltageV * energyA * hours
				chargeCurrentIntegralAms += energyA * dtMs
				chargeCurrentDurationMs += dtMs
			}
			chargeEnergyLastMs = now
			upsertOpenChargeRecord(now, remainingAh, tempC, loc)
			persistChargeSession()
		} else {
			if (isTelemetryRecording() && moving && tripStartMs <= 0L) {
				startTripSession(now, loc)
			}
			updateTripSession(now, moving, voltageV, tempC, minCellV)
			tickChargeRearm(remainingAh, packI, loc)
		}
		maybeCloseChargeStop(now, moving, remainingAh, packI)
		if (remainingAh != null) {
			lastChargeAh = remainingAh
		}
	}

	private fun beginCharge(
		now: Long,
		remainingAh: Double?,
		fullAh: Double?,
		currentA: Double,
		tempC: Double?,
		loc: Location?
	) {
		if (CHARGE_CYCLE_ACTIVE.get() || tripStartMs > 0L) {
			finishTrip(now, loc)
		}
		journal.i("charge", "begin ah=$remainingAh full=$fullAh I=$currentA temp=$tempC")
		syncMapTorrent()
		historySamples.clear()
		historySampleLastMs = 0L
		charging = true
		chargeSessionOpen = true
		chargeCurrentStoppedMs = 0L
		postChargeDistanceKm = 0.0
		postChargeLastOdoKm = null
		postChargeLastWheelKm = null
		postChargeLastLoc = null
		postChargeLastMs = 0L
		CHARGE_CYCLE_ACTIVE.set(false)
		chargeStartMs = now
		chargeStartAh = remainingAh
		chargeStartTempC = tempC
		chargeStartMinCellV = minCellVoltageV
		chargeParkedSinceMs = stillSinceMs ?: now
		chargeStartLat = loc?.latitude
		chargeStartLon = loc?.longitude
		chargeFrozenCurrentA = currentA.takeIf { it >= 0.4 }
		chargeFullAh = fullAh
		chargeLastAh = remainingAh
		chargeLastAhMs = now
		chargeEnergyWhAcc = 0.0
		chargeEnergyLastMs = now
		chargeCurrentIntegralAms = 0.0
		chargeCurrentDurationMs = 0L
		persistChargeSession()
		upsertOpenChargeRecord(now, remainingAh, tempC, loc)
		markGpxEvent(
			name = app.getString(R.string.ev_bms_gpx_charge_start),
			description = chargeEventDescription(start = true, remainingAh, tempC, null),
			lat = loc?.latitude,
			lon = loc?.longitude,
			timeMs = now,
			waypoint = false
		)
	}

	private fun pauseChargeCurrent(now: Long, remainingAh: Double?, tempC: Double?, loc: Location?) {
		if (!charging) {
			return
		}
		journal.i("charge", "current stopped ah=$remainingAh I wait ${CHARGE_FINISH_KM} km")
		charging = false
		syncMapTorrent()
		chargeExitHold = 0
		chargeCurrentStoppedMs = now
		postChargeDistanceKm = 0.0
		postChargeLastOdoKm = controllerOdometerKm()
		postChargeLastWheelKm = wheelOdometerKm()
		postChargeLastLoc = loc?.let { Location(it) }
		postChargeLastMs = now
		upsertOpenChargeRecord(now, remainingAh, tempC, loc)
		persistChargeSession()
	}

	private fun finishCharge(now: Long, remainingAh: Double?, tempC: Double?, loc: Location?) {
		val startAh = chargeStartAh
		val chargedAh = if (startAh != null && remainingAh != null) {
			(remainingAh - startAh).coerceAtLeast(0.0)
		} else {
			null
		}
		val parkedSince = chargeParkedSinceMs.takeIf { it > 0L } ?: (if (chargeStartMs > 0L) chargeStartMs else now)
		val moving = isVehicleMoving()
		val avgCurrentA = if (chargeCurrentDurationMs > 0L) {
			chargeCurrentIntegralAms / chargeCurrentDurationMs
		} else {
			chargeFrozenCurrentA
		}
		val energyWh = chargeEnergyWhAcc.takeIf { it >= 1.0 }
			?: chargedAh?.let { ah ->
				(lastBms?.voltageV ?: ctrlVoltageV())?.let { v -> ah * v }
			}?.takeIf { it >= 1.0 }
		val record = EvHistoryStore.ChargeRecord(
			startMs = if (chargeStartMs > 0L) chargeStartMs else now,
			endMs = now,
			startTempC = chargeStartTempC,
			endTempC = tempC,
			chargedAh = chargedAh,
			avgCurrentA = avgCurrentA,
			startMinCellV = chargeStartMinCellV,
			endMinCellV = minCellVoltageV,
			stopMs = (now - parkedSince).coerceAtLeast(0L),
			energyWh = energyWh,
			startLat = chargeStartLat,
			startLon = chargeStartLon,
			endLat = loc?.latitude,
			endLon = loc?.longitude
		)
		saveChargeRecord(record, appendCsv = true)
		if (!moving) {
			pendingStopRecordStartMs = record.startMs
			pendingStopParkedSinceMs = parkedSince
			persistChargeStopPending()
		} else {
			lastStopMs = record.stopMs
			clearChargeStopPending()
		}
		historyCharts.save(EvHistoryChartStore.KIND_CHARGE, record.startMs, record.endMs, ArrayList(historySamples))
		historySamples.clear()
		historySampleLastMs = 0L
		val markerLat = chargeStartLat ?: loc?.latitude
		val markerLon = chargeStartLon ?: loc?.longitude
		val markerName = if (energyWh != null) {
			app.getString(R.string.ev_bms_gpx_charge_wh, kotlin.math.round(energyWh).toInt())
		} else {
			app.getString(R.string.ev_bms_gpx_charge_end)
		}
		markGpxEvent(
			name = markerName,
			description = chargeEventDescription(
				start = false,
				remainingAh,
				tempC,
				chargedAh,
				if (chargeStartMs > 0L) now - chargeStartMs else null,
				energyWh
			),
			lat = markerLat,
			lon = markerLon,
			timeMs = now,
			waypoint = markerLat != null && markerLon != null
		)
		journal.i("charge", "finish chargedAh=$chargedAh remaining=$remainingAh temp=$tempC")
		charging = false
		chargeSessionOpen = false
		chargeExitHold = 0
		CHARGE_CYCLE_ACTIVE.set(true)
		CHARGE_SESSION.set("")
		clearChargeRuntime()
		armChargeRearm(remainingAh, loc)
		startTripSession(now, loc)
		if (ANNOUNCE_CHARGE_ETA.get()) {
			voice.onChargeFinished()
		}
	}

	private fun startTripSession(now: Long, loc: Location?) {
		tripStartMs = now
		tripStartRemainingAh = lastBms?.remainingMah?.div(1000.0)
		tripStartVoltageV = lastBms?.voltageV ?: ctrlVoltageV()
		tripStartTempC = batteryAnnounceTempC()
		tripStartMotorTempC = ctrlMotorTempC()
		tripStartLat = loc?.latitude
		tripStartLon = loc?.longitude
		tripMinCellV = minCellVoltageV
		tripLastVoltageV = tripStartVoltageV
		tripLastTempC = tripStartTempC
		tripLastMotorTempC = tripStartMotorTempC
		tripMovingMs = 0L
		tripLastMoveMs = 0L
		chargeTripKmAcc = 0.0
		chargeTripLastWheelKm = wheelOdometerForRange()
		chargeTripLastCtrlKm = controllerOdometerKm()
		chargeTripLastLoc = loc?.let { Location(it) }
		chargeTripLastMs = now
		historySamples.clear()
		historySampleLastMs = 0L
		CHARGE_END_TRACK_M.set(app.savingTrackHelper.distance.toInt().coerceAtLeast(0))
		rangeEstimator.markTripBoundary()
		persistTripSession()
	}

	private fun updateTripSession(
		now: Long,
		moving: Boolean,
		voltageV: Double?,
		tempC: Double?,
		minCellV: Double?
	) {
		if (tripStartMs <= 0L) {
			return
		}
		accumulateChargeTripDistance(moving)
		if (moving) {
			if (tripLastMoveMs > 0L) {
				tripMovingMs += (now - tripLastMoveMs).coerceAtLeast(0L)
			}
			tripLastMoveMs = now
		} else {
			tripLastMoveMs = 0L
		}
		if (voltageV != null) {
			tripLastVoltageV = voltageV
		}
		if (tempC != null) {
			tripLastTempC = tempC
		}
		if (minCellV != null) {
			tripMinCellV = minOf(tripMinCellV ?: minCellV, minCellV)
		}
		if (tripStartRemainingAh == null) {
			tripStartRemainingAh = lastBms?.remainingMah?.div(1000.0)
		}
		val motor = ctrlMotorTempC()
		if (motor != null) {
			tripLastMotorTempC = motor
		}
		persistTripSession()
	}

	private fun finishTrip(now: Long, loc: Location?) {
		if (tripStartMs <= 0L && !CHARGE_CYCLE_ACTIVE.get()) {
			return
		}
		val start = if (tripStartMs > 0L) tripStartMs else now
		val distanceKm = chargeTripKm()
		val energyWh = rangeEstimator.tripEnergyWh()
		val specificWhKm = if (energyWh != null && distanceKm != null && distanceKm > 0.05) {
			energyWh / distanceKm
		} else {
			null
		}
		val avgMovingKmh = if (tripMovingMs > 5_000L && distanceKm != null && distanceKm > 0.02) {
			distanceKm / (tripMovingMs / 3_600_000.0)
		} else {
			null
		}
		val stopMs = (now - start - tripMovingMs).coerceAtLeast(0L)
		val endAh = lastBms?.remainingMah?.div(1000.0)
		val usedAh = when {
			tripStartRemainingAh != null && endAh != null ->
				(tripStartRemainingAh!! - endAh).coerceAtLeast(0.0)
			else -> rangeEstimator.tripUsedAh()
		}
		val record = EvHistoryStore.ChargeTripRecord(
			startMs = start,
			endMs = now,
			startVoltageV = tripStartVoltageV,
			endVoltageV = tripLastVoltageV ?: lastBms?.voltageV,
			minCellV = tripMinCellV ?: minCellVoltageV,
			startTempC = tripStartTempC,
			endTempC = tripLastTempC ?: batteryAnnounceTempC(),
			startMotorTempC = tripStartMotorTempC,
			endMotorTempC = tripLastMotorTempC ?: ctrlMotorTempC(),
			distanceKm = distanceKm,
			movingMs = tripMovingMs,
			energyWh = energyWh,
			usedAh = usedAh,
			specificWhKm = specificWhKm,
			avgMovingKmh = avgMovingKmh,
			stopMs = stopMs,
			startLat = tripStartLat,
			startLon = tripStartLon,
			endLat = loc?.latitude,
			endLon = loc?.longitude
		)
		if (record.distanceKm != null && record.distanceKm > 0.02 || record.movingMs > 30_000L) {
			saveTripRecord(record)
			historyCharts.save(EvHistoryChartStore.KIND_TRIP, record.startMs, record.endMs, ArrayList(historySamples))
			markGpxEvent(
				name = app.getString(R.string.ev_bms_gpx_charge_trip),
				description = tripEventDescription(record),
				lat = loc?.latitude,
				lon = loc?.longitude,
				timeMs = now,
				waypoint = false
			)
		}
		TRIP_SESSION.set("")
		tripStartMs = 0L
		tripStartRemainingAh = null
		tripMovingMs = 0L
		tripLastMoveMs = 0L
		tripStartMotorTempC = null
		tripLastMotorTempC = null
		chargeTripKmAcc = 0.0
		chargeTripLastWheelKm = null
		chargeTripLastCtrlKm = null
		chargeTripLastLoc = null
		chargeTripLastMs = 0L
		historySamples.clear()
		historySampleLastMs = 0L
	}

	private fun accumulateChargeTripDistance(moving: Boolean) {
		val now = System.currentTimeMillis()
		val loc = lastLocation
		val wheel = wheelOdometerForRange()
		val ctrl = controllerOdometerKm()
		if (chargeTripLastMs <= 0L) {
			if (loc != null) {
				chargeTripLastLoc = Location(loc)
			}
			chargeTripLastWheelKm = wheel ?: chargeTripLastWheelKm
			chargeTripLastCtrlKm = ctrl ?: chargeTripLastCtrlKm
			chargeTripLastMs = if (moving) now else 0L
			return
		}
		val dtMs = now - chargeTripLastMs
		val ev = hasEvMotionEvidence()
		val gpsKm = if (ev && moving && loc != null && chargeTripLastLoc != null) {
			RangeEstimator.haversineKm(
				chargeTripLastLoc!!.latitude, chargeTripLastLoc!!.longitude,
				loc.latitude, loc.longitude
			)
		} else {
			null
		}
		val wheelDelta = RangeEstimator.odometerDeltaKm(chargeTripLastWheelKm, wheel, dtMs)
		val ctrlDelta = RangeEstimator.odometerDeltaKm(chargeTripLastCtrlKm, ctrl, dtMs)
		val accuracy = loc?.takeIf { it.hasAccuracy() }?.accuracy
		val gpsBad = RangeEstimator.isGpsUnreliable(
			accuracy, chargeTripLastMs, now, gpsKm, wheelDelta ?: ctrlDelta
		)
		val speedKm = if (ev && moving && chargeTripLastMs > 0L) {
			val kmh = ctrlSpeedKmh()
			if (kmh != null && kmh >= 2.0) kmh * dtMs / 3_600_000.0 else null
		} else {
			null
		}
		val step = RangeEstimator.chooseDistanceKm(dtMs, gpsKm, gpsBad, wheelDelta, ctrlDelta, speedKm, allowGps = ev)
		if (step != null) {
			chargeTripKmAcc += step.km
		}
		if (loc != null) {
			chargeTripLastLoc = Location(loc)
		}
		chargeTripLastWheelKm = wheel ?: chargeTripLastWheelKm
		chargeTripLastCtrlKm = ctrl ?: chargeTripLastCtrlKm
		chargeTripLastMs = if (moving) now else 0L
	}

	private fun accumulatePostChargeDistance(now: Long, moving: Boolean, loc: Location?) {
		if (!moving) {
			postChargeLastMs = 0L
			if (loc != null) {
				postChargeLastLoc = Location(loc)
			}
			postChargeLastOdoKm = controllerOdometerKm() ?: postChargeLastOdoKm
			postChargeLastWheelKm = wheelOdometerForRange() ?: postChargeLastWheelKm
			return
		}
		if (postChargeLastMs <= 0L) {
			if (loc != null) {
				postChargeLastLoc = Location(loc)
			}
			postChargeLastOdoKm = controllerOdometerKm() ?: postChargeLastOdoKm
			postChargeLastWheelKm = wheelOdometerForRange() ?: postChargeLastWheelKm
			postChargeLastMs = now
			return
		}
		val dtMs = now - postChargeLastMs
		val wheel = wheelOdometerForRange()
		val ctrl = controllerOdometerKm()
		val prevLoc = postChargeLastLoc
		val ev = hasEvMotionEvidence()
		val gpsKm = if (ev && loc != null && prevLoc != null) {
			RangeEstimator.haversineKm(prevLoc.latitude, prevLoc.longitude, loc.latitude, loc.longitude)
		} else {
			null
		}
		val wheelDelta = RangeEstimator.odometerDeltaKm(postChargeLastWheelKm, wheel, dtMs)
		val ctrlDelta = RangeEstimator.odometerDeltaKm(postChargeLastOdoKm, ctrl, dtMs)
		val accuracy = loc?.takeIf { it.hasAccuracy() }?.accuracy
		val gpsBad = RangeEstimator.isGpsUnreliable(
			accuracy, postChargeLastMs, now, gpsKm, wheelDelta ?: ctrlDelta
		)
		val speedKm = if (ev && postChargeLastMs > 0L) {
			val kmh = ctrlSpeedKmh()
			if (kmh != null && kmh >= 2.0) kmh * dtMs / 3_600_000.0 else null
		} else {
			null
		}
		val step = RangeEstimator.chooseDistanceKm(dtMs, gpsKm, gpsBad, wheelDelta, ctrlDelta, speedKm, allowGps = false)
		if (step != null && step.source != RangeEstimator.DistanceSource.GPS) {
			postChargeDistanceKm += step.km
		}
		if (loc != null) {
			postChargeLastLoc = Location(loc)
		}
		postChargeLastWheelKm = wheel ?: postChargeLastWheelKm
		postChargeLastOdoKm = ctrl ?: postChargeLastOdoKm
		postChargeLastMs = now
	}

	private fun estimatedRemainingAh(): Double? {
		val lastAh = chargeLastAh ?: return null
		val current = chargeFrozenCurrentA ?: return lastAh
		if (chargeLastAhMs <= 0L) {
			return lastAh
		}
		val elapsedH = (System.currentTimeMillis() - chargeLastAhMs).coerceAtLeast(0L) / 3_600_000.0
		return lastAh + current * elapsedH
	}

	private fun clearChargeRuntime() {
		chargeStartMs = 0L
		chargeStartAh = null
		chargeStartTempC = null
		chargeStartMinCellV = null
		chargeParkedSinceMs = 0L
		chargeStartLat = null
		chargeStartLon = null
		chargeFrozenCurrentA = null
		chargeFullAh = null
		chargeLastAh = null
		chargeLastAhMs = 0L
		chargeEnergyWhAcc = 0.0
		chargeEnergyLastMs = 0L
		chargeCurrentIntegralAms = 0.0
		chargeCurrentDurationMs = 0L
		chargeSessionOpen = false
		chargeCurrentStoppedMs = 0L
		postChargeDistanceKm = 0.0
		postChargeLastOdoKm = null
		postChargeLastWheelKm = null
		postChargeLastLoc = null
		postChargeLastMs = 0L
	}

	private fun persistChargeSession() {
		if (!chargeSessionOpen || chargeStartMs <= 0L) {
			return
		}
		val json = JSONObject()
		json.put("startMs", chargeStartMs)
		json.put("charging", charging)
		json.put("sessionOpen", true)
		json.put("currentStoppedMs", chargeCurrentStoppedMs)
		json.put("postChargeKm", postChargeDistanceKm)
		json.putD("startAh", chargeStartAh)
		json.putD("startTempC", chargeStartTempC)
		json.putD("startMinCellV", chargeStartMinCellV)
		json.put("parkedSinceMs", chargeParkedSinceMs)
		json.putD("startLat", chargeStartLat)
		json.putD("startLon", chargeStartLon)
		json.putD("currentA", chargeFrozenCurrentA)
		json.putD("fullAh", chargeFullAh)
		json.putD("lastAh", chargeLastAh)
		json.put("lastAhMs", chargeLastAhMs)
		json.put("energyWh", chargeEnergyWhAcc)
		json.put("energyLastMs", chargeEnergyLastMs)
		json.put("currentIntegralAms", chargeCurrentIntegralAms)
		json.put("currentDurationMs", chargeCurrentDurationMs)
		CHARGE_SESSION.set(json.toString())
	}

	private fun persistTripSession() {
		if (tripStartMs <= 0L) {
			return
		}
		val json = JSONObject()
		json.put("startMs", tripStartMs)
		json.putD("startAh", tripStartRemainingAh)
		json.putD("startVoltageV", tripStartVoltageV)
		json.putD("startTempC", tripStartTempC)
		json.putD("startLat", tripStartLat)
		json.putD("startLon", tripStartLon)
		json.putD("minCellV", tripMinCellV)
		json.putD("lastVoltageV", tripLastVoltageV)
		json.putD("lastTempC", tripLastTempC)
		json.putD("startMotorTempC", tripStartMotorTempC)
		json.putD("lastMotorTempC", tripLastMotorTempC)
		json.put("movingMs", tripMovingMs)
		json.put("tripKm", chargeTripKmAcc)
		json.putD("lastWheelKm", chargeTripLastWheelKm)
		json.putD("lastCtrlKm", chargeTripLastCtrlKm)
		TRIP_SESSION.set(json.toString())
	}

	private fun restoreSessions() {
		val chargeRaw = CHARGE_SESSION.get()
		if (!chargeRaw.isNullOrBlank()) {
			try {
				val json = JSONObject(chargeRaw)
				chargeStartMs = json.optLong("startMs")
				chargeStartAh = json.optNullableDouble("startAh")
				chargeStartTempC = json.optNullableDouble("startTempC")
				chargeStartMinCellV = json.optNullableDouble("startMinCellV")
				chargeParkedSinceMs = json.optLong("parkedSinceMs")
				chargeStartLat = json.optNullableDouble("startLat")
				chargeStartLon = json.optNullableDouble("startLon")
				chargeFrozenCurrentA = json.optNullableDouble("currentA")
				chargeFullAh = json.optNullableDouble("fullAh")
				chargeLastAh = json.optNullableDouble("lastAh")
				chargeLastAhMs = json.optLong("lastAhMs")
				chargeEnergyWhAcc = json.optDouble("energyWh", 0.0).coerceAtLeast(0.0)
				chargeEnergyLastMs = json.optLong("energyLastMs")
				chargeCurrentIntegralAms = json.optDouble("currentIntegralAms", 0.0).coerceAtLeast(0.0)
				chargeCurrentDurationMs = json.optLong("currentDurationMs").coerceAtLeast(0L)
				chargeCurrentStoppedMs = json.optLong("currentStoppedMs")
				postChargeDistanceKm = json.optDouble("postChargeKm", 0.0).coerceAtLeast(0.0)
				if (chargeStartMs > 0L) {
					chargeSessionOpen = json.optBoolean("sessionOpen", true)
					charging = json.optBoolean("charging", true)
					CHARGE_CYCLE_ACTIVE.set(false)
				}
			} catch (_: Exception) {
			}
		}
		val tripRaw = TRIP_SESSION.get()
		if (!chargeSessionOpen && !tripRaw.isNullOrBlank()) {
			try {
				val json = JSONObject(tripRaw)
				tripStartMs = json.optLong("startMs")
				tripStartRemainingAh = json.optNullableDouble("startAh")
				tripStartVoltageV = json.optNullableDouble("startVoltageV")
				tripStartTempC = json.optNullableDouble("startTempC")
				tripStartLat = json.optNullableDouble("startLat")
				tripStartLon = json.optNullableDouble("startLon")
				tripMinCellV = json.optNullableDouble("minCellV")
				tripLastVoltageV = json.optNullableDouble("lastVoltageV")
				tripLastTempC = json.optNullableDouble("lastTempC")
				tripStartMotorTempC = json.optNullableDouble("startMotorTempC")
				tripLastMotorTempC = json.optNullableDouble("lastMotorTempC")
				tripMovingMs = json.optLong("movingMs")
				chargeTripKmAcc = json.optDouble("tripKm", 0.0).let { stored ->
					if (stored > 0.0) stored else maxOf(
						json.optDouble("gpsKm", 0.0),
						json.optDouble("odoKm", 0.0),
						json.optDouble("speedKm", 0.0)
					).coerceAtLeast(0.0)
				}
				chargeTripLastWheelKm = json.optNullableDouble("lastWheelKm")
				chargeTripLastCtrlKm = json.optNullableDouble("lastCtrlKm")
					?: json.optNullableDouble("lastOdoKm")
			} catch (_: Exception) {
			}
		}
		restoreChargeRearm()
		restoreChargeStopPending()
	}

	private fun persistSocCal() {
		SOC_CAL_STORE.set(socCalibrator.encode())
	}

	private fun JSONObject.putD(key: String, value: Double?): JSONObject {
		if (value == null || value.isNaN() || value.isInfinite()) {
			put(key, JSONObject.NULL)
		} else {
			put(key, value)
		}
		return this
	}

	private fun JSONObject.optNullableDouble(key: String): Double? {
		if (!has(key) || isNull(key)) {
			return null
		}
		val value = optDouble(key, Double.NaN)
		return if (value.isNaN()) null else value
	}

	fun deleteChargeRecord(startMs: Long, endMs: Long) {
		val rows = chargeHistory().filterNot { it.startMs == startMs && (it.endMs == endMs || (endMs <= 0L && it.endMs <= 0L)) }
		CHARGE_HISTORY.set(historyStore.encodeCharges(rows))
		historyCharts.delete(EvHistoryChartStore.KIND_CHARGE, startMs, endMs)
	}

	fun deleteTripRecord(startMs: Long, endMs: Long) {
		val rows = tripHistory().filterNot { it.startMs == startMs && it.endMs == endMs }
		TRIP_HISTORY.set(historyStore.encodeTrips(rows))
		historyCharts.delete(EvHistoryChartStore.KIND_TRIP, startMs, endMs)
	}

	fun historyChart(kind: String, startMs: Long, endMs: Long): EvHistoryChartStore.Chart? =
		historyCharts.loadOrBuild(kind, startMs, endMs)

	private fun canStartNextCharge(): Boolean = rearmReady && pendingChargeToResume() == null

	private fun pendingChargeToResume(): EvHistoryStore.ChargeRecord? {
		val last = chargeHistory().maxByOrNull { it.startMs } ?: return null
		if (last.isOpen()) {
			return last
		}
		val hasRide = tripHistory().any { trip ->
			trip.isRealRide() && trip.startMs >= last.startMs
		}
		return if (hasRide) null else last
	}

	private fun resumeChargeCurrent(packI: Double?, remainingAh: Double?) {
		charging = true
		chargeCurrentStoppedMs = 0L
		postChargeDistanceKm = 0.0
		postChargeLastOdoKm = null
		postChargeLastWheelKm = null
		postChargeLastLoc = null
		postChargeLastMs = 0L
		clearChargeStopPending()
		journal.i("charge", "resume I=$packI ah=$remainingAh")
	}

	private fun reopenPendingCharge(
		now: Long,
		remainingAh: Double?,
		fullAh: Double?,
		currentA: Double,
		tempC: Double?,
		loc: Location?
	): Boolean {
		val last = pendingChargeToResume() ?: return false
		discardPhantomTrip(now, loc)
		charging = true
		chargeSessionOpen = true
		chargeCurrentStoppedMs = 0L
		postChargeDistanceKm = 0.0
		postChargeLastOdoKm = null
		postChargeLastWheelKm = null
		postChargeLastLoc = null
		postChargeLastMs = 0L
		CHARGE_CYCLE_ACTIVE.set(false)
		chargeStartMs = last.startMs
		chargeStartAh = last.chargedAh?.let { ch -> remainingAh?.minus(ch)?.coerceAtLeast(0.0) }
			?: remainingAh
		chargeStartTempC = last.startTempC ?: tempC
		chargeStartMinCellV = last.startMinCellV ?: minCellVoltageV
		chargeParkedSinceMs = last.startMs
		chargeStartLat = last.startLat ?: loc?.latitude
		chargeStartLon = last.startLon ?: loc?.longitude
		chargeFrozenCurrentA = currentA.takeIf { it >= 0.4 } ?: last.avgCurrentA
		chargeFullAh = fullAh
		chargeLastAh = remainingAh
		chargeLastAhMs = now
		chargeEnergyWhAcc = last.energyWh?.coerceAtLeast(0.0) ?: 0.0
		chargeEnergyLastMs = now
		if (last.avgCurrentA != null && last.durationMs() > 0L) {
			chargeCurrentDurationMs = last.durationMs()
			chargeCurrentIntegralAms = last.avgCurrentA * chargeCurrentDurationMs
		}
		clearChargeStopPending()
		rearmReady = true
		CHARGE_REARM.set("")
		persistChargeSession()
		upsertOpenChargeRecord(now, remainingAh, tempC, loc)
		return true
	}

	private fun discardPhantomTrip(now: Long, loc: Location?) {
		if (tripStartMs <= 0L) {
			return
		}
		val km = chargeTripKm() ?: 0.0
		if (km < 0.2 && tripMovingMs < 60_000L) {
			TRIP_SESSION.set("")
			tripStartMs = 0L
			tripStartRemainingAh = null
			tripMovingMs = 0L
			tripLastMoveMs = 0L
			chargeTripKmAcc = 0.0
			CHARGE_CYCLE_ACTIVE.set(false)
			return
		}
		finishTrip(now, loc)
	}

	private fun mergeSplitChargeHistory() {
		val charges = chargeHistory()
		val trips = tripHistory()
		val merged = historyStore.mergeChargesWithoutTrip(charges, trips)
		if (merged != charges) {
			val keptStarts = merged.map { it.startMs }.toSet()
			for (old in charges) {
				if (old.startMs !in keptStarts) {
					historyCharts.delete(EvHistoryChartStore.KIND_CHARGE, old.startMs, old.endMs)
				}
			}
			CHARGE_HISTORY.set(historyStore.encodeCharges(merged))
			val leftoverTrips = trips.filter { trip ->
				trip.isRealRide() || merged.any { charge ->
					!charge.isOpen() && kotlin.math.abs(charge.endMs - trip.startMs) < 5_000L
				}
			}
			if (leftoverTrips != trips) {
				TRIP_HISTORY.set(historyStore.encodeTrips(leftoverTrips))
			}
			journal.i("charge", "merged ${charges.size} charge rows into ${merged.size}")
		}
		historyStore.rewriteMergedChargeCsv(tripHistory())
		val last = merged.maxByOrNull { it.startMs } ?: return
		if (chargeSessionOpen && last.startMs < chargeStartMs) {
			chargeStartMs = last.startMs
			chargeStartTempC = last.startTempC ?: chargeStartTempC
			chargeStartMinCellV = last.startMinCellV ?: chargeStartMinCellV
			chargeStartLat = last.startLat ?: chargeStartLat
			chargeStartLon = last.startLon ?: chargeStartLon
			chargeEnergyWhAcc = maxOf(chargeEnergyWhAcc, last.energyWh ?: 0.0)
			chargeParkedSinceMs = last.startMs
			persistChargeSession()
			upsertOpenChargeRecord(System.currentTimeMillis(), lastBms?.remainingMah?.div(1000.0), last.endTempC, lastLocation)
		}
	}

	fun isChargeStopPending(startMs: Long): Boolean {
		return pendingStopRecordStartMs == startMs ||
			chargeHistory().any { it.startMs == startMs && it.isOpen() }
	}

	fun profileNames(): List<String> = profileStore.profileNames()

	fun activeProfileName(): String = profileStore.activeName()

	fun captureActiveProfile() {
		profileStore.captureActive()
	}

	fun selectSettingsProfile(name: String): Boolean = profileStore.select(name)

	fun createSettingsProfile(name: String): Boolean = profileStore.create(name)

	fun renameSettingsProfile(name: String): Boolean = profileStore.rename(name)

	fun exportSettingsProfile(uri: android.net.Uri): Boolean = profileStore.exportToUri(uri)

	fun importSettingsProfile(uri: android.net.Uri): String? = profileStore.importFromUri(uri)

	fun settingsProfileExportFileName(): String = profileStore.exportFileName()

	fun sanitizeProfileName(raw: String?): String? = profileStore.sanitizeName(raw)

	private fun armChargeRearm(remainingAh: Double?, loc: Location?) {
		rearmReady = false
		rearmGpsKm = 0.0
		rearmStartOdoKm = ctrlOdometerKm()
		rearmStartAh = remainingAh
		rearmStartSoc = calibratedSocPercent ?: lastBms?.socPercent
		rearmLastLoc = loc?.let { Location(it) }
		persistChargeRearm()
	}

	private fun tickChargeRearm(remainingAh: Double?, currentA: Double?, loc: Location?) {
		if (rearmReady) {
			return
		}
		if (rearmStartOdoKm == null) {
			rearmStartOdoKm = ctrlOdometerKm()
		}
		val odoKm = rearmStartOdoKm?.let { start ->
			ctrlOdometerKm()?.let { now -> (now - start).coerceAtLeast(0.0) }
		} ?: 0.0
		if (hasEvMotionEvidence() && loc != null && rearmLastLoc != null) {
			rearmLastLoc = Location(loc)
		} else if (loc != null) {
			rearmLastLoc = Location(loc)
		}
		val distKm = odoKm
		val needKm = CHARGE_REARM_M.get().coerceAtLeast(0) / 1000.0
		val needAh = CHARGE_REARM_MAH.get().coerceAtLeast(0) / 1000.0
		val discharging = currentA != null && currentA <= -EV_DISCHARGE_IDLE_A
		val usedAh = if (discharging && rearmStartAh != null && remainingAh != null) {
			(rearmStartAh!! - remainingAh).coerceAtLeast(0.0)
		} else {
			0.0
		}
		val soc = calibratedSocPercent ?: lastBms?.socPercent
		val usedSoc = if (discharging && rearmStartSoc != null && soc != null) {
			(rearmStartSoc!! - soc).coerceAtLeast(0)
		} else {
			0
		}
		val moved = distKm + 1e-6 >= needKm
		val consumed = usedAh + 1e-6 >= needAh || usedSoc >= 2
		if (moved && consumed) {
			rearmReady = true
		}
		persistChargeRearm()
	}

	private fun persistChargeRearm() {
		if (rearmReady) {
			CHARGE_REARM.set("")
			return
		}
		val json = JSONObject()
		json.put("ready", false)
		json.put("gpsKm", rearmGpsKm)
		json.putD("startOdoKm", rearmStartOdoKm)
		json.putD("startAh", rearmStartAh)
		if (rearmStartSoc != null) {
			json.put("startSoc", rearmStartSoc)
		}
		CHARGE_REARM.set(json.toString())
	}

	private fun restoreChargeRearm() {
		val raw = CHARGE_REARM.get()
		if (raw.isNullOrBlank()) {
			rearmReady = charging || !CHARGE_CYCLE_ACTIVE.get()
			return
		}
		try {
			val json = JSONObject(raw)
			rearmReady = json.optBoolean("ready", true)
			rearmGpsKm = json.optDouble("gpsKm", 0.0).coerceAtLeast(0.0)
			rearmStartOdoKm = json.optNullableDouble("startOdoKm")
			rearmStartAh = json.optNullableDouble("startAh")
			rearmStartSoc = if (json.has("startSoc")) json.optInt("startSoc") else null
		} catch (_: Exception) {
			rearmReady = true
		}
	}

	private fun maybeCloseChargeStop(now: Long, moving: Boolean, remainingAh: Double?, currentA: Double?) {
		if (pendingStopRecordStartMs <= 0L || pendingStopParkedSinceMs <= 0L) {
			return
		}
		if (charging || !moving) {
			return
		}
		val discharging = remainingAh != null && lastChargeAh != null &&
				lastChargeAh!! - remainingAh >= 0.02
		val currentOut = currentA != null && currentA < -0.3
		if (!discharging && !currentOut) {
			return
		}
		val stopMs = (now - pendingStopParkedSinceMs).coerceAtLeast(0L)
		lastStopMs = stopMs
		updateChargeStopMs(pendingStopRecordStartMs, stopMs)
		clearChargeStopPending()
	}

	private fun updateChargeStopMs(startMs: Long, stopMs: Long) {
		val rows = chargeHistory().map { row ->
			if (row.startMs == startMs) row.copy(stopMs = stopMs) else row
		}
		CHARGE_HISTORY.set(historyStore.encodeCharges(rows))
	}

	private fun persistChargeStopPending() {
		if (pendingStopRecordStartMs <= 0L) {
			CHARGE_STOP_PENDING.set("")
			return
		}
		val json = JSONObject()
		json.put("startMs", pendingStopRecordStartMs)
		json.put("parkedSinceMs", pendingStopParkedSinceMs)
		CHARGE_STOP_PENDING.set(json.toString())
	}

	private fun restoreChargeStopPending() {
		val raw = CHARGE_STOP_PENDING.get()
		if (raw.isNullOrBlank()) {
			return
		}
		try {
			val json = JSONObject(raw)
			pendingStopRecordStartMs = json.optLong("startMs")
			pendingStopParkedSinceMs = json.optLong("parkedSinceMs")
		} catch (_: Exception) {
			clearChargeStopPending()
		}
	}

	private fun clearChargeStopPending() {
		pendingStopRecordStartMs = 0L
		pendingStopParkedSinceMs = 0L
		CHARGE_STOP_PENDING.set("")
	}

	private fun upsertOpenChargeRecord(now: Long, remainingAh: Double?, tempC: Double?, loc: Location?) {
		if (!chargeSessionOpen || chargeStartMs <= 0L) {
			return
		}
		upsertChargeRecord(liveChargeRecord(now, remainingAh, tempC, loc, open = true), appendCsv = false)
	}

	private fun liveChargeRecord(
		now: Long,
		remainingAh: Double?,
		tempC: Double?,
		loc: Location?,
		open: Boolean
	): EvHistoryStore.ChargeRecord {
		val startAh = chargeStartAh
		val chargedAh = if (startAh != null && remainingAh != null) {
			(remainingAh - startAh).coerceAtLeast(0.0)
		} else {
			null
		}
		val parkedSince = chargeParkedSinceMs.takeIf { it > 0L } ?: (if (chargeStartMs > 0L) chargeStartMs else now)
		val avgCurrentA = if (chargeCurrentDurationMs > 0L) {
			chargeCurrentIntegralAms / chargeCurrentDurationMs
		} else {
			chargeFrozenCurrentA
		}
		return EvHistoryStore.ChargeRecord(
			startMs = if (chargeStartMs > 0L) chargeStartMs else now,
			endMs = if (open) 0L else now,
			startTempC = chargeStartTempC,
			endTempC = tempC,
			chargedAh = chargedAh,
			avgCurrentA = avgCurrentA,
			startMinCellV = chargeStartMinCellV,
			endMinCellV = minCellVoltageV,
			stopMs = (now - parkedSince).coerceAtLeast(0L),
			energyWh = chargeEnergyWhAcc.takeIf { it >= 0.5 },
			startLat = chargeStartLat,
			startLon = chargeStartLon,
			endLat = loc?.latitude,
			endLon = loc?.longitude
		)
	}

	private fun saveChargeRecord(row: EvHistoryStore.ChargeRecord, appendCsv: Boolean) {
		upsertChargeRecord(row, appendCsv)
	}

	private fun upsertChargeRecord(row: EvHistoryStore.ChargeRecord, appendCsv: Boolean) {
		val rows = chargeHistory().toMutableList()
		val idx = rows.indexOfFirst { it.startMs == row.startMs }
		if (idx >= 0) {
			rows[idx] = row
		} else {
			rows.add(row)
		}
		CHARGE_HISTORY.set(historyStore.encodeCharges(rows))
		if (appendCsv && !row.isOpen()) {
			historyStore.appendChargeCsv(row)
		}
	}

	private fun saveTripRecord(row: EvHistoryStore.ChargeTripRecord) {
		val rows = tripHistory() + row
		TRIP_HISTORY.set(historyStore.encodeTrips(rows))
		historyStore.appendTripCsv(row)
	}

	private fun markGpxEvent(
		name: String,
		description: String,
		lat: Double? = null,
		lon: Double? = null,
		timeMs: Long = System.currentTimeMillis(),
		waypoint: Boolean = false
	) {
		pendingGpxEvents.add(GpxEvent(name, description, lat, lon, timeMs, waypoint))
	}

	private fun chargeEventDescription(
		start: Boolean,
		remainingAh: Double?,
		tempC: Double?,
		chargedAh: Double?,
		durationMs: Long? = null,
		energyWh: Double? = null
	): String {
		val parts = ArrayList<String>()
		parts.add(app.getString(if (start) R.string.ev_bms_gpx_charge_start else R.string.ev_bms_gpx_charge_end))
		if (durationMs != null && durationMs > 0L) {
			parts.add(OsmAndFormatter.getFormattedDurationShort((durationMs / 1000L).toInt()))
		}
		if (energyWh != null && energyWh >= 1.0) {
			parts.add(String.format(Locale.US, "%.0f Wh", energyWh))
		}
		if (tempC != null) {
			parts.add(String.format(Locale.US, "%.1f °C", tempC))
		}
		if (remainingAh != null) {
			parts.add(String.format(Locale.US, "%.2f Ah", remainingAh))
		}
		if (chargedAh != null) {
			parts.add(String.format(Locale.US, "+%.2f Ah", chargedAh))
		}
		return parts.joinToString(" · ")
	}

	private fun tripEventDescription(row: EvHistoryStore.ChargeTripRecord): String {
		val parts = ArrayList<String>()
		parts.add(app.getString(R.string.ev_bms_gpx_charge_trip))
		if (row.distanceKm != null) {
			parts.add(String.format(Locale.US, "%.2f km", row.distanceKm))
		}
		if (row.energyWh != null) {
			parts.add(String.format(Locale.US, "%.0f Wh", row.energyWh))
		}
		if (row.usedAh != null) {
			parts.add(String.format(Locale.US, "%.2f Ah", row.usedAh))
		}
		if (row.specificWhKm != null) {
			parts.add(String.format(Locale.US, "%.0f Wh/km", row.specificWhKm))
		}
		if (row.movingMs > 0L) {
			parts.add(OsmAndFormatter.getFormattedDurationShort((row.movingMs / 1000L).toInt()))
		}
		if (row.startVoltageV != null && row.endVoltageV != null) {
			parts.add(String.format(Locale.US, "%.1f→%.1f V", row.startVoltageV, row.endVoltageV))
		}
		if (row.minCellV != null) {
			parts.add(String.format(Locale.US, "min %.3f V", row.minCellV))
		}
		if (row.startTempC != null || row.endTempC != null) {
			parts.add(String.format(Locale.US, "batt %.0f→%.0f °C", row.startTempC ?: Double.NaN, row.endTempC ?: Double.NaN))
		}
		if (row.startMotorTempC != null || row.endMotorTempC != null) {
			parts.add(String.format(Locale.US, "mot %.0f→%.0f °C", row.startMotorTempC ?: Double.NaN, row.endMotorTempC ?: Double.NaN))
		}
		return parts.joinToString(" · ")
	}

	private fun buildStopReport(bmsFresh: Boolean, ctrlFresh: Boolean): EvVoiceAnnouncer.StopReport? {
		if (!bmsFresh) {
			return null
		}
		val range = primaryRangeKm() ?: return null
		return EvVoiceAnnouncer.StopReport(
			rangeKm = range,
			routeLeftKm = getRouteLeftKm(),
			rangeReserveKm = if (ANNOUNCE_RANGE_RESERVE.get()) rangeReserveKm() else null,
			minCellV = minCellVoltageV,
			motorTempC = if (ctrlFresh) ctrlMotorTempC() else null,
			batteryTempC = batteryAnnounceTempC(),
			controllerTempC = if (ctrlFresh) ctrlTempC() else null
		)
	}

	private fun updateRestMetrics(currentA: Double?, packVoltageV: Double?, cells: List<Double>?) {
		val minCell = cells?.minOrNull()
		if (minCell != null && minCell > 0) {
			minCellVoltageV = minCell
		}
		if (charging || chargeSessionOpen) {
			return
		}
		if (currentA == null || kotlin.math.abs(currentA) > REST_CURRENT_A) {
			return
		}
		if (packVoltageV != null && packVoltageV > 0) {
			restPackVoltageV = packVoltageV
		}
	}

	fun remainingRouteElevation(): RangeEstimator.RouteElevation? {
		val helper = app.routingHelper
		if (!helper.isRouteCalculated) {
			return null
		}
		val capKm = getRouteLeftKm() ?: return null
		var dist = 0.0
		var climb = 0.0
		var descent = 0.0
		var prev: Location? = null
		for (point in helper.route.routeLocations) {
			val previous = prev
			if (previous != null) {
				dist += RangeEstimator.haversineKm(
					previous.latitude, previous.longitude, point.latitude, point.longitude
				)
				if (previous.hasAltitude() && point.hasAltitude()) {
					val delta = point.altitude - previous.altitude
					if (delta > 0) {
						climb += delta
					} else {
						descent += -delta
					}
				}
				if (dist >= capKm) {
					break
				}
			}
			prev = point
		}
		return RangeEstimator.RouteElevation(capKm, climb, descent)
	}

	fun getRouteLeftKm(): Double? {
		val helper = app.routingHelper
		if (!helper.isRouteCalculated) {
			return null
		}
		val next = helper.leftDistanceNextIntermediate
		val meters = if (next > 0) next else helper.leftDistance
		if (meters <= 0) {
			return null
		}
		return meters / 1000.0
	}

	fun primaryRangeKm(): Double? {
		return rangeEstimator.remainingRangeKm ?: latestTelemetry?.remainingRangeKm
	}

	fun selectedRangeKm(): Double? {
		return when (RANGE_FOR_RESERVE.get()) {
			RANGE_SOURCE_5MIN -> rangeEstimator.windowRangeKm ?: latestTelemetry?.windowRangeKm
			RANGE_SOURCE_PNZ -> rangeEstimator.pnzRangeKm ?: latestTelemetry?.pnzRangeKm
			else -> primaryRangeKm()
		}
	}

	fun rangeReserveKm(): Double? {
		val route = getRouteLeftKm() ?: return null
		val range = primaryRangeKm() ?: return null
		return range - route
	}

	fun stopTimeMs(): Long? {
		val still = stillSinceMs
		if (still != null) {
			return (System.currentTimeMillis() - still).coerceAtLeast(0L)
		}
		val pending = pendingStopParkedSinceMs
		if (pending > 0L) {
			return (System.currentTimeMillis() - pending).coerceAtLeast(0L)
		}
		return lastStopMs
	}

	enum class ChartMove { UP, DOWN, START, END }

	fun chartFields(): List<TelemetryField> {
		val selected = selectedTelemetryFields().filter { it.isChartable() }
		val order = CHART_ORDER.get().orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
		val byId = selected.associateBy { it.id }
		val ordered = order.mapNotNull { byId[it] }
		val rest = selected.filter { it.id !in order.toSet() }
		return ordered + rest
	}

	fun moveChart(id: String, move: ChartMove) {
		val list = chartFields().map { it.id }.toMutableList()
		val i = list.indexOf(id)
		if (i < 0) {
			return
		}
		when (move) {
			ChartMove.UP -> if (i > 0) {
				list[i] = list[i - 1].also { list[i - 1] = id }
			}
			ChartMove.DOWN -> if (i < list.lastIndex) {
				list[i] = list[i + 1].also { list[i + 1] = id }
			}
			ChartMove.START -> {
				list.removeAt(i)
				list.add(0, id)
			}
			ChartMove.END -> {
				list.removeAt(i)
				list.add(id)
			}
		}
		CHART_ORDER.set(list.joinToString(","))
	}

	fun isChartPaused(id: String): Boolean {
		return id in chartPausedIds()
	}

	fun setChartPaused(id: String, paused: Boolean) {
		val set = chartPausedIds().toMutableSet()
		if (paused) set.add(id) else set.remove(id)
		CHART_PAUSED.set(set.joinToString(","))
	}

	private fun chartPausedIds(): Set<String> =
		CHART_PAUSED.get().orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

	fun startBmsScan(activity: Activity) {
		bmsClient?.startScan(activity)
	}

	fun startControllerScan(activity: Activity) {
		controllerClient?.startScan(activity)
	}

	fun startSpeedSensorScan(activity: Activity) {
		speedSensorClient?.startScan(activity)
	}

	fun startCadenceSensorScan(activity: Activity) {
		cadenceSensorClient?.startScan(activity)
	}

	fun stopScans() {
		bmsClient?.stopScan()
		controllerClient?.stopScan()
		speedSensorClient?.stopScan()
		cadenceSensorClient?.stopScan()
	}

	fun connectBms(activity: Activity, name: String, address: String) {
		journal.i("link", "connect BMS name=$name addr=$address proto=${BMS_PROTOCOL.get()}")
		BMS_NAME.set(name)
		BMS_ADDRESS.set(address)
		bmsClient?.preferredBmsKind = preferredBmsKind()
		bmsClient?.connect(activity, address)
	}

	fun connectController(activity: Activity, name: String, address: String) {
		journal.i("link", "connect CTRL name=$name addr=$address proto=${CONTROLLER_PROTOCOL.get()}")
		CONTROLLER_NAME.set(name)
		if (address.isNotBlank()) {
			CONTROLLER_ADDRESS.set(address)
		}
		controllerClient?.preferredControllerKind = preferredControllerKind()
		controllerClient?.preferredDeviceName = name.takeIf { it.isNotBlank() }
		controllerClient?.connect(activity, address, name)
	}

	fun connectSpeedSensor(activity: Activity, name: String, address: String) {
		journal.i("link", "connect SPEED name=$name addr=$address")
		SPEED_SENSOR_NAME.set(name)
		SPEED_SENSOR_ADDRESS.set(address)
		configureWheelTracker()
		speedSensorClient?.preferredDeviceName = name.takeIf { it.isNotBlank() }
		speedSensorClient?.connect(activity, address, name)
	}

	fun connectCadenceSensor(activity: Activity, name: String, address: String) {
		journal.i("link", "connect CADENCE name=$name addr=$address")
		CADENCE_SENSOR_NAME.set(name)
		CADENCE_SENSOR_ADDRESS.set(address)
		cadenceSensorClient?.preferredDeviceName = name.takeIf { it.isNotBlank() }
		cadenceSensorClient?.connect(activity, address, name)
	}

	fun disconnectBms() {
		bmsClient?.disconnect()
	}

	fun disconnectController() {
		controllerClient?.disconnect()
	}

	fun disconnectSpeedSensor() {
		speedSensorClient?.disconnect()
		lastSpeedSensorRxMs = 0L
		wheelTracker.resetBaseline()
	}

	fun disconnectCadenceSensor() {
		cadenceSensorClient?.disconnect()
		lastCadenceRxMs = 0L
		cadenceTracker.resetBaseline()
	}

	private fun isJbdAuthInProgress(): Boolean {
		return !preferAntProtocol() &&
				!jbdPasswordRejected &&
				JbdBmsProtocol.normalizePassword(BMS_PASSWORD.get()) != null &&
				jbdModuleAuth != JbdModuleAuth.DONE
	}

	fun bleLinkStats(role: EvBleUartClient.Role): EvBleUartClient.LinkStats? {
		val client = when (role) {
			EvBleUartClient.Role.BMS -> bmsClient
			EvBleUartClient.Role.CONTROLLER -> controllerClient
			EvBleUartClient.Role.SPEED -> speedSensorClient
			EvBleUartClient.Role.CADENCE -> cadenceSensorClient
		}
		return client?.linkStats()
	}

	fun isBmsConnected(): Boolean = bmsClient?.connected == true

	fun isControllerConnected(): Boolean = controllerClient?.connected == true

	fun isSpeedSensorConnected(): Boolean = speedSensorClient?.connected == true

	fun isCadenceSensorConnected(): Boolean = cadenceSensorClient?.connected == true

	fun isCadenceSensorSelected(): Boolean {
		return !CADENCE_SENSOR_ADDRESS.get().isNullOrBlank() || !CADENCE_SENSOR_NAME.get().isNullOrBlank()
	}

	fun isSpeedSensorSelected(): Boolean {
		return !SPEED_SENSOR_ADDRESS.get().isNullOrBlank() || !SPEED_SENSOR_NAME.get().isNullOrBlank()
	}

	fun isBmsFresh(): Boolean {
		return isBmsConnected() && lastBmsRxMs > 0L &&
				System.currentTimeMillis() - lastBmsRxMs <= dataStaleMs(activeBmsPollMs())
	}

	fun isControllerFresh(): Boolean {
		return isControllerConnected() && lastCtrlRxMs > 0L &&
				System.currentTimeMillis() - lastCtrlRxMs <= dataStaleMs(activeCtrlPollMs())
	}

	fun isSpeedSensorFresh(): Boolean {
		if (!isSpeedSensorConnected() || !wheelTracker.hasWheelData) {
			return false
		}
		val last = wheelTracker.lastRxMs.takeIf { it > 0L } ?: lastSpeedSensorRxMs
		return last > 0L && System.currentTimeMillis() - last <= 4_000L
	}

	fun connectSavedDevices(activity: Activity) {
		val bms = BMS_ADDRESS.get()
		if (!bms.isNullOrEmpty() && bmsClient?.connected != true) {
			bmsClient?.preferredBmsKind = preferredBmsKind()
			bmsClient?.connect(activity, bms)
		}
		val ctrlName = CONTROLLER_NAME.get()
		val ctrl = CONTROLLER_ADDRESS.get()
		if ((!ctrl.isNullOrEmpty() || !ctrlName.isNullOrEmpty()) && controllerClient?.connected != true) {
			controllerClient?.preferredControllerKind = preferredControllerKind()
			controllerClient?.preferredDeviceName = ctrlName?.takeIf { it.isNotBlank() }
			controllerClient?.connect(activity, ctrl.orEmpty(), ctrlName)
		}
		val speedName = SPEED_SENSOR_NAME.get()
		val speed = SPEED_SENSOR_ADDRESS.get()
		if ((!speed.isNullOrEmpty() || !speedName.isNullOrEmpty()) && speedSensorClient?.connected != true) {
			speedSensorClient?.preferredDeviceName = speedName?.takeIf { it.isNotBlank() }
			speedSensorClient?.connect(activity, speed.orEmpty(), speedName)
		}
		val cadenceName = CADENCE_SENSOR_NAME.get()
		val cadence = CADENCE_SENSOR_ADDRESS.get()
		if ((!cadence.isNullOrEmpty() || !cadenceName.isNullOrEmpty()) && cadenceSensorClient?.connected != true) {
			cadenceSensorClient?.preferredDeviceName = cadenceName?.takeIf { it.isNotBlank() }
			cadenceSensorClient?.connect(activity, cadence.orEmpty(), cadenceName)
		}
	}

	private fun startPolling() {
		if (pollRunning) {
			return
		}
		pollRunning = true
		handler.removeCallbacks(pollRunnable)
		handler.post(pollRunnable)
	}

	fun reschedulePolling() {
		if (!pollRunning) {
			return
		}
		handler.removeCallbacks(pollRunnable)
		handler.post(pollRunnable)
	}

	private fun stopPolling() {
		pollRunning = false
		handler.removeCallbacks(pollRunnable)
	}

	override fun onBoundAddress(role: EvBleUartClient.Role, name: String?, address: String) {
		if (address.isBlank()) {
			return
		}
		if (role == EvBleUartClient.Role.CONTROLLER) {
			val old = CONTROLLER_ADDRESS.get()
			if (old != address) {
				journal.i("link", "CTRL MAC ${old.orEmpty()} → $address name=${name.orEmpty()}")
				CONTROLLER_ADDRESS.set(address)
			}
			if (!name.isNullOrBlank() && CONTROLLER_NAME.get().isNullOrBlank()) {
				CONTROLLER_NAME.set(name)
			}
		} else if (role == EvBleUartClient.Role.SPEED) {
			val old = SPEED_SENSOR_ADDRESS.get()
			if (old != address) {
				journal.i("link", "SPEED MAC ${old.orEmpty()} → $address name=${name.orEmpty()}")
				SPEED_SENSOR_ADDRESS.set(address)
			}
			if (!name.isNullOrBlank() && SPEED_SENSOR_NAME.get().isNullOrBlank()) {
				SPEED_SENSOR_NAME.set(name)
			}
		} else if (role == EvBleUartClient.Role.CADENCE) {
			val old = CADENCE_SENSOR_ADDRESS.get()
			if (old != address) {
				journal.i("link", "CADENCE MAC ${old.orEmpty()} → $address name=${name.orEmpty()}")
				CADENCE_SENSOR_ADDRESS.set(address)
			}
			if (!name.isNullOrBlank() && CADENCE_SENSOR_NAME.get().isNullOrBlank()) {
				CADENCE_SENSOR_NAME.set(name)
			}
		}
	}

	override fun onConnectionChanged(role: EvBleUartClient.Role, connected: Boolean, name: String?) {
		val label = name ?: role.name
		journal.i("link", "$role connected=$connected name=$label")
		if (connected) {
			app.showToastMessage(app.getString(R.string.ev_bms_connected, label))
			if (role == EvBleUartClient.Role.CONTROLLER) {
				farStatusStarted = false
				vescPollSetup = false
				vescSnapshot.reset()
			}
			if (role == EvBleUartClient.Role.BMS) {
				resetJbdAuth()
			}
			if (role == EvBleUartClient.Role.SPEED) {
				configureWheelTracker()
				wheelTracker.resetBaseline()
			}
			if (role == EvBleUartClient.Role.CADENCE) {
				cadenceTracker.resetBaseline()
			}
			startPolling()
			applyHikeTelemetryState()
		} else {
			when (role) {
				EvBleUartClient.Role.BMS -> lastBmsRxMs = 0L
				EvBleUartClient.Role.CONTROLLER -> lastCtrlRxMs = 0L
				EvBleUartClient.Role.SPEED -> {
					lastSpeedSensorRxMs = 0L
					persistWheelOdometer()
					wheelTracker.resetBaseline()
				}
				EvBleUartClient.Role.CADENCE -> {
					lastCadenceRxMs = 0L
					cadenceTracker.resetBaseline()
				}
			}
			val auto = when (role) {
				EvBleUartClient.Role.BMS -> bmsClient?.isAutoReconnectEnabled() == true
				EvBleUartClient.Role.CONTROLLER -> controllerClient?.isAutoReconnectEnabled() == true
				EvBleUartClient.Role.SPEED -> speedSensorClient?.isAutoReconnectEnabled() == true
				EvBleUartClient.Role.CADENCE -> cadenceSensorClient?.isAutoReconnectEnabled() == true
			}
			if (!auto) {
				app.showToastMessage(app.getString(R.string.ev_bms_disconnected, label))
			}
		}
	}

	private fun ensureBleLinks() {
		val bms = BMS_ADDRESS.get()
		if (!bms.isNullOrEmpty()) {
			bmsClient?.preferredBmsKind = preferredBmsKind()
			ensureBleLink(bms, bmsClient, lastBmsRxMs) { lastBmsRxMs = 0L }
		}
		val ctrlName = CONTROLLER_NAME.get()
		val ctrl = CONTROLLER_ADDRESS.get()
		if (!ctrl.isNullOrEmpty() || !ctrlName.isNullOrEmpty()) {
			controllerClient?.preferredControllerKind = preferredControllerKind()
			controllerClient?.preferredDeviceName = ctrlName?.takeIf { it.isNotBlank() }
			ensureBleLink(ctrl, controllerClient, lastCtrlRxMs, ctrlName) { lastCtrlRxMs = 0L }
		}
		val speedName = SPEED_SENSOR_NAME.get()
		val speed = SPEED_SENSOR_ADDRESS.get()
		if (!speed.isNullOrEmpty() || !speedName.isNullOrEmpty()) {
			speedSensorClient?.preferredDeviceName = speedName?.takeIf { it.isNotBlank() }
			ensureBleLink(speed, speedSensorClient, lastSpeedSensorRxMs, speedName) {
				lastSpeedSensorRxMs = 0L
			}
		}
		val cadenceName = CADENCE_SENSOR_NAME.get()
		val cadence = CADENCE_SENSOR_ADDRESS.get()
		if (!cadence.isNullOrEmpty() || !cadenceName.isNullOrEmpty()) {
			cadenceSensorClient?.preferredDeviceName = cadenceName?.takeIf { it.isNotBlank() }
			ensureBleLink(cadence, cadenceSensorClient, lastCadenceRxMs, cadenceName) {
				lastCadenceRxMs = 0L
			}
		}
	}

	private fun ensureBleLink(
		address: String?,
		client: EvBleUartClient?,
		lastRxMs: Long,
		preferredName: String? = null,
		clearRx: () -> Unit
	) {
		if (client == null) {
			return
		}
		if (address.isNullOrEmpty() && preferredName.isNullOrBlank()) {
			return
		}
		if (client.connected) {
			if (!client.notifyReady) {
				val deadMs = linkDeadMsFor(client.role)
				if (client.millisSinceConnected() > deadMs) {
					journal.w("link", "${client.role} no notify ${client.millisSinceConnected()}ms, forceReconnect")
					clearRx()
					client.forceReconnect("no-notify")
				}
				return
			}
			if (client.role == EvBleUartClient.Role.BMS) {
				// Module still answers FF AA 17; UART silence is not a dead radio.
				return
			}
			if (client.role == EvBleUartClient.Role.SPEED || client.role == EvBleUartClient.Role.CADENCE) {
				// CSC sensors stop notifying at rest. Do not tear GATT down here.
				return
			}
			val now = System.currentTimeMillis()
			val deadMs = linkDeadMsFor(client.role)
			val dead = if (lastRxMs == 0L) {
				client.millisSinceConnected() > deadMs
			} else {
				now - lastRxMs > deadMs
			}
			if (dead) {
				journal.w("link", "${client.role} stale ${now - lastRxMs}ms, forceReconnect")
				clearRx()
				client.forceReconnect("stale")
			}
		} else {
			client.ensureConnected(address.orEmpty(), preferredName)
		}
	}

	override fun onBytes(role: EvBleUartClient.Role, data: ByteArray) {
		when (role) {
			EvBleUartClient.Role.BMS -> {
				bmsBuffer += data
				drainBmsBuffer()
			}
			EvBleUartClient.Role.CONTROLLER -> {
				controllerBuffer += data
				drainControllerBuffer()
			}
			EvBleUartClient.Role.SPEED -> {
				configureWheelTracker()
				lastSpeedSensorRxMs = System.currentTimeMillis()
				if (wheelTracker.ingest(data)) {
					persistWheelOdometer()
					handler.post { publishWheelLive() }
				}
				if (cadenceTracker.ingest(data)) {
					lastCadenceRxMs = cadenceTracker.lastRxMs
					handler.post { publishWheelLive() }
				}
			}
			EvBleUartClient.Role.CADENCE -> {
				lastCadenceRxMs = System.currentTimeMillis()
				if (cadenceTracker.ingest(data)) {
					handler.post { publishWheelLive() }
				}
			}
		}
	}

	private fun drainControllerBuffer() {
		while (controllerBuffer.isNotEmpty()) {
			val sizeBefore = controllerBuffer.size
			val useVesc = when {
				preferVescProtocol() -> true
				preferFarProtocol() -> false
				else -> {
					val vescAt = controllerBuffer.indexOfFirst {
						it == 0x02.toByte() || it == 0x03.toByte()
					}
					val farAt = controllerBuffer.indexOf(FarDriverProtocol.MAGIC)
					vescAt >= 0 && (farAt < 0 || vescAt <= farAt)
				}
			}
			if (useVesc) {
				val (frames, rest) = VescProtocol.extractFrames(controllerBuffer)
				controllerBuffer = rest
				for (payload in frames) {
					if (VescProtocol.parsePayload(payload, vescSnapshot)) {
						lastCtrlRxMs = System.currentTimeMillis()
						controllerClient?.noteControllerKind(EvBleUartClient.ControllerKind.VESC)
					}
				}
			} else {
				val (frames, rest) = FarDriverProtocol.extractFrames(controllerBuffer)
				controllerBuffer = rest
				for (frame in frames) {
					if (FarDriverProtocol.parseFrame(frame, farSnapshot)) {
						lastCtrlRxMs = System.currentTimeMillis()
						controllerClient?.noteControllerKind(EvBleUartClient.ControllerKind.FARDRIVER)
					}
				}
			}
			if (controllerBuffer.size >= sizeBefore) {
				if (controllerBuffer.size > 1024) {
					controllerBuffer = ByteArray(0)
				}
				break
			}
		}
	}

	private fun drainBmsBuffer() {
		while (bmsBuffer.isNotEmpty()) {
			val sizeBefore = bmsBuffer.size
			if (bmsBuffer.size >= 2 &&
				bmsBuffer[0] == JbdBleModuleProtocol.SOF0 &&
				bmsBuffer[1] == JbdBleModuleProtocol.SOF1
			) {
				val (frames, rest) = JbdBleModuleProtocol.extractFrames(bmsBuffer)
				bmsBuffer = rest
				for (raw in frames) {
					val frame = JbdBleModuleProtocol.parse(raw) ?: continue
					lastBmsRxMs = System.currentTimeMillis()
					handleJbdModuleFrame(frame)
				}
				if (bmsBuffer.size >= sizeBefore) {
					if (bmsBuffer.size > 1024) {
						bmsBuffer = ByteArray(0)
					}
					break
				}
				continue
			}
			val useAnt = when {
				preferAntProtocol() -> true
				preferJbdProtocol() -> false
				else -> firstSofIsAnt(bmsBuffer)
			}
			if (useAnt == null) {
				break
			}
			if (useAnt) {
				val (frames, rest) = AntBmsProtocol.extractFrames(bmsBuffer)
				bmsBuffer = rest
				for (frame in frames) {
					val info = AntBmsProtocol.parseStatus(frame) ?: continue
					lastBms = info
					lastBmsRxMs = System.currentTimeMillis()
					if (!info.cells.isNullOrEmpty()) {
						lastCells = info.cells
						updateRestMetrics(info.currentA, info.voltageV, info.cells)
					} else {
						updateRestMetrics(info.currentA, info.voltageV, lastCells)
					}
					voice.onChargeVoltage(info.voltageV, CHARGE_VOLT_STEP_MV.get() / 1000.0, ANNOUNCE_SOC.get() && !charging)
				}
			} else {
				val (frames, rest) = JbdBmsProtocol.extractFrames(bmsBuffer)
				bmsBuffer = rest
				for (frame in frames) {
					handleJbdAuthStatus(frame)
					val info = JbdBmsProtocol.parseBasicInfo(frame)
					if (info != null) {
						lastBms = info.toSnapshot()
						lastBmsRxMs = System.currentTimeMillis()
						updateRestMetrics(info.currentA, info.voltageV, lastCells)
						voice.onChargeVoltage(info.voltageV, CHARGE_VOLT_STEP_MV.get() / 1000.0, ANNOUNCE_SOC.get() && !charging)
						continue
					}
					val cells = JbdBmsProtocol.parseCellVoltages(frame) ?: continue
					lastCells = cells
					lastBmsRxMs = System.currentTimeMillis()
					updateRestMetrics(lastBms?.currentA, lastBms?.voltageV, cells)
				}
			}
			if (bmsBuffer.size >= sizeBefore) {
				if (bmsBuffer.size > 1024) {
					bmsBuffer = ByteArray(0)
				}
				break
			}
		}
	}

	private fun firstSofIsAnt(buffer: ByteArray): Boolean? {
		var i = 0
		while (i < buffer.size) {
			if (buffer[i] == AntBmsProtocol.START1) {
				if (i + 1 >= buffer.size) {
					return null
				}
				if (buffer[i + 1] == AntBmsProtocol.START2) {
					return true
				}
			}
			if (buffer[i] == JbdBmsProtocol.START) {
				return false
			}
			i++
		}
		return false
	}

	private fun preferredBmsKind(): EvBleUartClient.BmsKind {
		return when (BMS_PROTOCOL.get()) {
			"ant" -> EvBleUartClient.BmsKind.ANT
			"jbd" -> EvBleUartClient.BmsKind.JBD
			else -> EvBleUartClient.BmsKind.UNKNOWN
		}
	}

	private fun preferAntProtocol(): Boolean {
		return BMS_PROTOCOL.get() == "ant" ||
				(BMS_PROTOCOL.get() != "jbd" && bmsClient?.detectedBmsKind == EvBleUartClient.BmsKind.ANT)
	}

	private fun preferJbdProtocol(): Boolean {
		return BMS_PROTOCOL.get() == "jbd" ||
				(BMS_PROTOCOL.get() != "ant" && bmsClient?.detectedBmsKind == EvBleUartClient.BmsKind.JBD)
	}

	fun parseJbdPassword(raw: String?): String? {
		val text = raw?.trim().orEmpty()
		if (text.isEmpty()) {
			return ""
		}
		return JbdBmsProtocol.normalizePassword(text)
	}

	fun onJbdPasswordChanged() {
		resetJbdAuth()
		if (isBmsConnected() && !preferAntProtocol()) {
			advanceJbdAuth()
		}
	}

	private fun resetJbdAuth() {
		jbdPasswordSentMs = 0L
		jbdPasswordToastMs = 0L
		jbdPasswordRejected = false
		jbdModuleAuth = JbdModuleAuth.IDLE
		jbdModuleAuthMs = 0L
		jbdModuleRandomTries = 0
		jbdModuleUsedNewKey = false
		jbdTriedOldAppKey = false
		jbdAppKeyIndex = 0
	}

	override fun onNotifyReady(role: EvBleUartClient.Role) {
		if (role != EvBleUartClient.Role.BMS || preferAntProtocol()) {
			return
		}
		journal.i("link", "BMS notify ready, unlocking module")
		Log.i(TAG, "BMS notify ready, unlocking module")
		advanceJbdAuth()
	}

	private fun advanceJbdAuth(): Boolean {
		if (preferAntProtocol() || jbdPasswordRejected) {
			return false
		}
		if (bmsClient?.notifyReady != true) {
			return true
		}
		val password = JbdBmsProtocol.normalizePassword(BMS_PASSWORD.get())
		if (password == null) {
			return false
		}
		val now = System.currentTimeMillis()
		when (jbdModuleAuth) {
			JbdModuleAuth.IDLE -> {
				return if (!jbdModuleUsedNewKey) {
					sendJbdAppKeyVerify()
				} else if (!jbdTriedOldAppKey) {
					sendJbdOldAppKey()
				} else {
					sendJbdModuleRandom()
				}
			}
			JbdModuleAuth.WAIT_APPKEY -> {
				if (now - jbdModuleAuthMs >= JBD_MODULE_VERIFY_WAIT_MS) {
					jbdAppKeyIndex++
					return if (!jbdModuleUsedNewKey) {
						journal.w("link", "BLE appkey 0x21 timed out, next key")
						sendJbdAppKeyVerify()
					} else {
						journal.w("link", "BLE appkey 0x15 timed out, next key")
						sendJbdOldAppKey()
					}
				}
				return true
			}
			JbdModuleAuth.WAIT_RANDOM -> {
				if (now - jbdModuleAuthMs >= JBD_MODULE_RANDOM_WAIT_MS) {
					journal.w("link", "BLE module random timed out, retry ${jbdModuleRandomTries}")
					return sendJbdModuleRandom()
				}
				return true
			}
			JbdModuleAuth.WAIT_VERIFY -> {
				if (now - jbdModuleAuthMs >= JBD_MODULE_VERIFY_WAIT_MS) {
					journal.w("link", "BLE module verify timed out, retry ${jbdModuleRandomTries}")
					return if (!jbdModuleUsedNewKey) {
						sendJbdNewAppKeyVerify(password)
					} else {
						sendJbdModuleRandom()
					}
				}
				return true
			}
			JbdModuleAuth.DONE -> return sendJbdPasswordIfNeeded()
		}
	}

	private fun jbdAppKeys(): List<String> {
		val keys = ArrayList<String>()
		for (key in JBD_APPKEYS) {
			keys.add(key)
		}
		val password = JbdBmsProtocol.normalizePassword(BMS_PASSWORD.get())
		if (password != null && !keys.contains(password)) {
			keys.add(password)
		}
		return keys
	}

	private fun sendJbdAppKeyVerify(): Boolean {
		val keys = jbdAppKeys()
		if (jbdAppKeyIndex >= keys.size) {
			journal.w("link", "appkey 0x21 failed, trying old 0x15")
			Log.w(TAG, "appkey 0x21 failed, trying old 0x15")
			jbdModuleUsedNewKey = true
			jbdAppKeyIndex = 0
			jbdModuleRandomTries = 0
			jbdModuleAuth = JbdModuleAuth.IDLE
			return sendJbdOldAppKey()
		}
		val key = keys[jbdAppKeyIndex]
		val random = (1..99).random()
		sendJbdModuleVerify(key, random, newAppKey = true, cmd = JbdBleModuleProtocol.CMD_APPKEY_VERIFY)
		if (jbdModuleAuth == JbdModuleAuth.WAIT_VERIFY) {
			jbdModuleAuth = JbdModuleAuth.WAIT_APPKEY
			journal.d("link", "BLE appkey 0x21 try=${jbdAppKeyIndex + 1}/${keys.size} key=$key random=$random")
			Log.i(TAG, "BLE appkey 0x21 try=${jbdAppKeyIndex + 1} key=$key")
		}
		return true
	}

	private fun sendJbdOldAppKey(): Boolean {
		val keys = jbdAppKeys()
		if (jbdAppKeyIndex >= keys.size) {
			journal.w("link", "appkey 0x15 failed, falling back to 0x17")
			Log.w(TAG, "appkey 0x15 failed, falling back to 0x17")
			jbdTriedOldAppKey = true
			jbdModuleRandomTries = 0
			jbdModuleAuth = JbdModuleAuth.IDLE
			return sendJbdModuleRandom()
		}
		val key = keys[jbdAppKeyIndex]
		val command = JbdBleModuleProtocol.oldAppKey(key)
		if (command == null || bmsClient?.write(command) != true) {
			journal.w("link", "BLE appkey 0x15 TX failed key=$key")
			jbdModuleAuth = JbdModuleAuth.IDLE
			return true
		}
		jbdModuleAuth = JbdModuleAuth.WAIT_APPKEY
		jbdModuleAuthMs = System.currentTimeMillis()
		journal.d("link", "BLE appkey 0x15 try=${jbdAppKeyIndex + 1}/${keys.size} key=$key")
		Log.i(TAG, "BLE appkey 0x15 try=${jbdAppKeyIndex + 1} key=$key")
		return true
	}

	private fun sendJbdNewAppKeyVerify(password: String): Boolean {
		if (jbdModuleRandomTries >= JBD_MODULE_RANDOM_TRIES) {
			journal.w("link", "new appkey verify failed, falling back to UART password")
			jbdModuleAuth = JbdModuleAuth.DONE
			return sendJbdPasswordIfNeeded()
		}
		val random = (1..99).random()
		sendJbdModuleVerify(password, random, newAppKey = true)
		if (jbdModuleAuth == JbdModuleAuth.WAIT_VERIFY) {
			jbdModuleRandomTries++
		}
		return true
	}

	private fun sendJbdModuleRandom(): Boolean {
		if (jbdModuleRandomTries >= JBD_MODULE_RANDOM_TRIES) {
			journal.w("link", "BLE module random failed, falling back to UART password")
			jbdModuleAuth = JbdModuleAuth.DONE
			return sendJbdPasswordIfNeeded()
		}
		val ok = bmsClient?.write(JbdBleModuleProtocol.randomRequest()) == true
		if (!ok) {
			journal.w("link", "BLE module random TX failed, will retry")
			jbdModuleAuth = JbdModuleAuth.IDLE
			return true
		}
		jbdModuleRandomTries++
		jbdModuleAuth = JbdModuleAuth.WAIT_RANDOM
		jbdModuleAuthMs = System.currentTimeMillis()
		journal.d("link", "BLE module random request try=$jbdModuleRandomTries")
		return true
	}

	private fun sendJbdModuleVerify(
		password: String,
		random: Int,
		newAppKey: Boolean,
		cmd: Int = JbdBleModuleProtocol.CMD_VERIFY
	) {
		val mac = bmsClient?.deviceAddress
		val command = JbdBleModuleProtocol.verifyPassword(mac, password, random, newAppKey, cmd)
		if (command == null) {
			journal.w("link", "cannot build BLE module verify, mac=$mac cmd=$cmd")
			jbdModuleAuth = JbdModuleAuth.DONE
			return
		}
		if (bmsClient?.write(command) != true) {
			journal.w("link", "BLE module verify TX failed cmd=${cmd.toString(16)}")
			jbdModuleAuth = JbdModuleAuth.IDLE
			return
		}
		jbdModuleAuth = JbdModuleAuth.WAIT_VERIFY
		jbdModuleAuthMs = System.currentTimeMillis()
		journal.d("link", "BLE module verify cmd=${cmd.toString(16)} newKey=$newAppKey random=$random")
	}

	private fun handleJbdModuleFrame(frame: JbdBleModuleProtocol.Frame) {
		when (frame.cmd) {
			JbdBleModuleProtocol.CMD_OLD_APPKEY -> {
				val status = frame.payload.lastOrNull()?.toInt()?.and(0xFF)
				journal.i("link", "BLE appkey 0x15 status=$status")
				Log.i(TAG, "BLE appkey 0x15 status=$status")
				jbdTriedOldAppKey = true
				jbdModuleRandomTries = 0
				if (status == 0) {
					val password = JbdBmsProtocol.normalizePassword(BMS_PASSWORD.get())
					if (password == null) {
						jbdModuleAuth = JbdModuleAuth.DONE
						sendJbdPasswordIfNeeded()
					} else {
						sendJbdModuleRandom()
					}
				} else {
					jbdModuleAuth = JbdModuleAuth.DONE
					sendJbdPasswordIfNeeded()
				}
			}
			JbdBleModuleProtocol.CMD_APPKEY_VERIFY -> {
				if (JbdBleModuleProtocol.verifyAccepted(frame)) {
					journal.i("link", "BLE appkey 0x21 accepted")
					Log.i(TAG, "BLE appkey 0x21 accepted")
					jbdModuleUsedNewKey = false
					jbdModuleRandomTries = 0
					val password = JbdBmsProtocol.normalizePassword(BMS_PASSWORD.get())
					if (password == null) {
						jbdModuleAuth = JbdModuleAuth.DONE
						sendJbdPasswordIfNeeded()
					} else {
						sendJbdNewAppKeyVerify(password)
					}
				} else {
					journal.w("link", "BLE appkey 0x21 rejected, next key")
					jbdAppKeyIndex++
					jbdModuleAuth = JbdModuleAuth.IDLE
					sendJbdAppKeyVerify()
				}
			}
			JbdBleModuleProtocol.CMD_RANDOM -> {
				val random = JbdBleModuleProtocol.randomFrom(frame) ?: return
				val password = JbdBmsProtocol.normalizePassword(BMS_PASSWORD.get()) ?: return
				journal.d("link", "BLE module random=$random")
				sendJbdModuleVerify(password, random, newAppKey = false)
			}
			JbdBleModuleProtocol.CMD_VERIFY, JbdBleModuleProtocol.CMD_VERIFY_SECONDARY -> {
				if (JbdBleModuleProtocol.verifyAccepted(frame)) {
					journal.i("link", "BLE module unlocked")
					Log.i(TAG, "BLE module unlocked")
					jbdModuleAuth = JbdModuleAuth.DONE
					sendJbdPasswordIfNeeded()
				} else {
					jbdPasswordRejected = true
					toastJbdPassword(R.string.ev_bms_jbd_password_wrong)
					journal.w("link", "BLE module rejected password")
				}
			}
		}
	}

	private enum class JbdModuleAuth {
		IDLE, WAIT_APPKEY, WAIT_RANDOM, WAIT_VERIFY, DONE
	}

	private fun sendJbdPasswordIfNeeded(): Boolean {
		if (preferAntProtocol() || jbdPasswordRejected) {
			return false
		}
		val command = JbdBmsProtocol.usePassword(BMS_PASSWORD.get()) ?: return false
		val now = System.currentTimeMillis()
		if (jbdPasswordSentMs == 0L) {
			bmsClient?.write(command)
			jbdPasswordSentMs = now
			return true
		}
		if (now - jbdPasswordSentMs < 1_500L) {
			return true
		}
		if (isBmsFresh() || now - jbdPasswordSentMs < 10_000L) {
			return false
		}
		bmsClient?.write(command)
		jbdPasswordSentMs = now
		return true
	}

	private fun handleJbdAuthStatus(frame: ByteArray) {
		val status = JbdBmsProtocol.frameStatus(frame) ?: return
		if (status == JbdBmsProtocol.STATUS_OK) {
			return
		}
		val passwordSet = JbdBmsProtocol.normalizePassword(BMS_PASSWORD.get()) != null
		if (status == JbdBmsProtocol.STATUS_PASSWORD) {
			jbdPasswordRejected = true
			toastJbdPassword(R.string.ev_bms_jbd_password_wrong)
			return
		}
		if (status == JbdBmsProtocol.STATUS_DENIED && !passwordSet) {
			toastJbdPassword(R.string.ev_bms_jbd_password_required)
		}
	}

	private fun toastJbdPassword(resId: Int) {
		val now = System.currentTimeMillis()
		if (now - jbdPasswordToastMs < 15_000L) {
			return
		}
		jbdPasswordToastMs = now
		app.showToastMessage(resId)
	}

	interface DeviceScanListener {
		fun onDeviceFound(
			role: EvBleUartClient.Role,
			name: String,
			address: String,
			rssi: Int?,
			serviceLabel: String
		)
		fun onScanFinished(role: EvBleUartClient.Role)
	}

	var scanListener: DeviceScanListener? = null

	override fun onDeviceFound(
		role: EvBleUartClient.Role,
		name: String,
		address: String,
		rssi: Int?,
		serviceLabel: String
	) {
		handler.post { scanListener?.onDeviceFound(role, name, address, rssi, serviceLabel) }
	}

	override fun onScanFinished(role: EvBleUartClient.Role) {
		handler.post { scanListener?.onScanFinished(role) }
	}

	private fun publishWheelLive() {
		val prev = latestTelemetry
		if (prev == null) {
			publishSample()
			return
		}
		val speed = wheelSpeedKmh()
		latestTelemetry = prev.copy(
			farOdometerKm = sessionOdometerKm() ?: prev.farOdometerKm,
			farTripKm = farTripKm() ?: prev.farTripKm,
			farSpeedKmh = ctrlSpeedKmh() ?: prev.farSpeedKmh,
			wheelSpeedKmh = speed,
			wheelOdometerKm = wheelTracker.tripKm,
			cadenceRpm = cadenceRpm()
		)
	}

	private fun publishSample() {
		val bms = lastBms
		val loc = lastLocation
		val speed = if (loc != null && loc.hasSpeed()) loc.speed * 3.6 else null
		val remainingAhRaw = bms?.remainingMah?.div(1000.0)
		val bmsFresh = isBmsFresh()
		val ctrlFresh = isControllerFresh()
		updateRestMetrics(bms?.currentA ?: ctrlCurrentA(), bms?.voltageV ?: ctrlVoltageV(), lastCells)
		val liveMinCell = lastCells?.minOrNull()
		if (liveMinCell != null && liveMinCell > 0) {
			minCellVoltageV = liveMinCell
		}
		val liveMaxCell = lastCells?.maxOrNull()?.takeIf { it > 0 }
		val imbalanceV = if (liveMinCell != null && liveMaxCell != null && lastCells.orEmpty().size >= 2) {
			(liveMaxCell - liveMinCell).coerceAtLeast(0.0)
		} else {
			null
		}
		val packCurrentA = when {
			bmsFresh && bms?.currentA != null && kotlin.math.abs(bms.currentA) >= 0.2 -> bms.currentA
			ctrlFresh -> ctrlCurrentA()
			else -> bms?.currentA
		}
		updateChargeCycle(
			bmsCurrentA = bms?.currentA,
			ctrlCurrentA = ctrlCurrentA(),
			remainingAh = remainingAhRaw,
			fullAh = bms?.fullMah?.div(1000.0),
			voltageV = bms?.voltageV ?: ctrlVoltageV(),
			tempC = batteryAnnounceTempC(),
			minCellV = minCellVoltageV,
			loc = loc,
			bmsFresh = bmsFresh,
			ctrlFresh = ctrlFresh
		)
		socVoltagePercent = socCalibrator.tick(
			BMS_ADDRESS.get(),
			minCellVoltageV,
			packCurrentA ?: bms?.currentA,
			charging,
			System.currentTimeMillis()
		)
		val fullAh = bms?.fullMah?.div(1000.0)
		val remainingAh = effectiveRemainingAh(remainingAhRaw, fullAh)
		calibratedSocPercent = coulombSocPercent(remainingAh, fullAh) ?: socVoltagePercent
		if (socCalibrator.flushDue(System.currentTimeMillis())) {
			persistSocCal()
		}
		val packVoltageV = bms?.voltageV ?: ctrlVoltageV()
		val energyVoltageV = energyVoltageV(packVoltageV, packCurrentA ?: bms?.currentA)
		val nowMs = System.currentTimeMillis()
		if (lastRangeSampleMs == 0L || nowMs - lastRangeSampleMs >= RANGE_SAMPLE_MIN_MS) {
			lastRangeSampleMs = nowMs
			if (charging) {
				rangeEstimator.refreshRemaining(
					remainingAh,
					packVoltageV,
					energyVoltageV,
					minCellVoltageV,
					bms?.temperaturesC?.minOrNull()?.toDouble(),
					fullAh,
					ctrlAvgWhPerKm(),
					remainingRouteElevation(),
					USE_ROUTE_PROFILE.get(),
					totalMassKg()
				)
			} else {
				rangeEstimator.add(
					nowMs,
					remainingAh,
					packVoltageV,
					energyVoltageV,
					loc,
					wheelOdometerForRange(),
					controllerOdometerKm(),
					minCellVoltageV,
					bms?.temperaturesC?.minOrNull()?.toDouble(),
					fullAh,
					ctrlAvgWhPerKm(),
					remainingRouteElevation(),
					USE_ROUTE_PROFILE.get(),
					totalMassKg(),
					if (bmsFresh) bms?.currentA else null,
					ctrlSpeedKmh(),
					allowGpsDistance = hasEvMotionEvidence()
				)
			}
		}
		tickSpeedCalibration(loc)
		val sample = EvTelemetry(
			lat = loc?.latitude,
			lon = loc?.longitude,
			gpsSpeedKmh = speed,
			socPercent = calibratedSocPercent ?: bms?.socPercent,
			socVoltagePercent = socVoltagePercent,
			voltageV = bms?.voltageV ?: ctrlVoltageV(),
			currentA = bms?.currentA ?: ctrlCurrentA(),
			remainingAh = remainingAh,
			fullAh = bms?.fullMah?.div(1000.0),
			bmsTempC = batteryAnnounceTempC(),
			cycles = bms?.cycles,
			minCellVoltageV = minCellVoltageV,
			maxCellVoltageV = liveMaxCell,
			cellImbalanceV = imbalanceV,
			controllerVoltageV = ctrlVoltageV(),
			controllerCurrentA = ctrlCurrentA(),
			controllerPowerW = ctrlPowerW(),
			rpm = ctrlRpm(),
			gear = farSnapshot.gear,
			motorTempC = ctrlMotorTempC(),
			controllerTempC = ctrlTempC(),
			remainingRangeKm = rangeEstimator.remainingRangeKm,
			windowRangeKm = rangeEstimator.windowRangeKm,
			pnzRangeKm = rangeEstimator.pnzRangeKm,
			consumptionAhPerKm = rangeEstimator.consumptionAhPerKm,
			energyWh = rangeEstimator.tripEnergyWh(),
			usedAh = tripUsedAh(remainingAh),
			consumptionWhPerKm = rangeEstimator.consumptionWhPerKm,
			coverageWhPerKm = rangeEstimator.coverageWhPerKm,
			weakCellFactor = rangeEstimator.weakCellFactor,
			farOdometerKm = sessionOdometerKm(),
			farTripKm = farTripKm(),
			chargeTripKm = chargeTripKm(),
			farSpeedKmh = ctrlSpeedKmh(),
			wheelSpeedKmh = wheelSpeedKmh(),
			wheelOdometerKm = wheelTracker.tripKm,
			cadenceRpm = cadenceRpm(),
			farAvgWhPerKm = ctrlAvgWhPerKm(),
			gpsUnreliable = rangeEstimator.gpsUnreliable,
			usedFarDriverDistance = rangeEstimator.usedFarDriverDistance,
			rangeReserveKm = rangeReserveKm(),
			stopTimeMs = stopTimeMs()
		)
		val bmsFreshAfter = isBmsFresh()
		val ctrlFreshAfter = isControllerFresh()
		latestTelemetry = sample
		tickSpeedProfileSwitch()
		maybeCollectHistorySample(sample)
		if (journal.enabled) {
			journal.d(
				"telem",
				"bmsFresh=$bmsFreshAfter ctrlFresh=$ctrlFreshAfter soc=${sample.socPercent} " +
						"V=${sample.voltageV} I=${sample.currentA} rpm=${sample.rpm} " +
						"motC=${sample.motorTempC} charge=$charging"
			)
		}
		if (isChartsLive()) {
			val chartNow = sample.timeMs
			if (lastChartSampleMs == 0L || chartNow - lastChartSampleMs >= CHART_SAMPLE_MIN_MS) {
				lastChartSampleMs = chartNow
				synchronized(chartLock) {
					chartHistory.addLast(sample)
					while (chartHistory.size > CHART_HISTORY_MAX) {
						chartHistory.removeFirst()
					}
				}
			}
		}
		val events = ArrayList(pendingGpxEvents)
		pendingGpxEvents.clear()
		if (recorder.isRecording) {
			recorder.setWriteGpx(RECORD_GPX.get() && !isTripRecording())
			val recIv = activeRecordIntervalMs()
			if (events.isEmpty()) {
				if (lastRecordMs == 0L || sample.timeMs - lastRecordMs >= recIv) {
					lastRecordMs = sample.timeMs
					recorder.append(sample)
				}
			} else {
				lastRecordMs = sample.timeMs
				recorder.append(sample)
				for (event in events) {
					if (event.waypoint) {
						val lat = event.lat ?: continue
						val lon = event.lon ?: continue
						recorder.appendWaypoint(lat, lon, event.timeMs, event.name, event.description)
					} else {
						recorder.appendNamedPoint(sample, event.name, event.description)
					}
				}
			}
		}
		for (event in events) {
			writeTripRecordingWaypoint(event)
		}
		voice.onLink(
			bmsUp = bmsFreshAfter,
			controllerUp = ctrlFreshAfter,
			expectBms = !BMS_ADDRESS.get().isNullOrEmpty(),
			expectController = !CONTROLLER_ADDRESS.get().isNullOrEmpty(),
			enabled = ANNOUNCE_LINK.get()
		)
		voice.onMotion(
			fusedSpeedKmh(loc),
			buildStopReport(bmsFresh, ctrlFresh),
			STOP_SPEED_KMH.get().toDouble(),
			STOP_ANNOUNCE_REPEATS.get(),
			ANNOUNCE_RANGE_ON_STOP.get() && bmsFresh
		)
		voice.onRangeVsRoute(
			if (bmsFresh) primaryRangeKm() else null,
			getRouteLeftKm(),
			ANNOUNCE_RANGE_VS_ROUTE.get() && bmsFresh
		)
		voice.onRangeReserve(
			if (bmsFresh) rangeReserveKm()?.let { -it } else null,
			RANGE_RESERVE_SMALL_KM.get().toDouble(),
			RANGE_RESERVE_LOW_KM.get().toDouble(),
			ANNOUNCE_RANGE_RESERVE_SMALL.get() && bmsFresh,
			ANNOUNCE_RANGE_RESERVE_LOW.get() && bmsFresh
		)
		voice.onRestCellVoltage(
			if (bmsFresh) minCellVoltageV else null,
			if (bmsFresh) sample.currentA else null,
			REST_CURRENT_A,
			LOW_CELL_MV.get() / 1000.0,
			CRITICAL_CELL_MV.get() / 1000.0,
			CELL_ALERT_INTERVAL_SEC.get().toLong().coerceAtLeast(15L) * 1000L,
			ANNOUNCE_CELL_VOLTAGE.get() && bmsFresh
		)
		val tempIntervalMs = CELL_ALERT_INTERVAL_SEC.get().toLong().coerceAtLeast(15L) * 1000L
		voice.onMotorHeat(
			if (ctrlFresh) sample.motorTempC else null,
			MOTOR_HEAT_C.get(),
			tempIntervalMs,
			ANNOUNCE_MOTOR_HEAT.get() && ctrlFresh
		)
		voice.onBatteryOverheat(
			if (bmsFresh) bms?.temperaturesC?.maxOrNull()?.toDouble() else null,
			BATTERY_OVERHEAT_C.get(),
			tempIntervalMs,
			ANNOUNCE_BATTERY_OVERHEAT.get() && bmsFresh
		)
		voice.onBatteryFreeze(
			if (bmsFresh) bms?.temperaturesC?.minOrNull()?.toDouble() else null,
			BATTERY_FREEZE_C.get(),
			tempIntervalMs,
			ANNOUNCE_BATTERY_FREEZE.get() && bmsFresh
		)
		if (charging && bmsFreshAfter) {
			voice.onChargeProgress(
				sample.voltageV,
				sample.socPercent,
				sample.bmsTempC,
				ANNOUNCE_SOC.get(),
				ANNOUNCE_SOC.get() || ANNOUNCE_BATTERY_OVERHEAT.get()
			)
		}
		voice.onChargeEta(chargeRemainingMs(), ANNOUNCE_CHARGE_ETA.get() && charging)
	}

	private fun coulombSocPercent(remainingAh: Double?, fullAh: Double?): Int? {
		if (remainingAh == null || fullAh == null || fullAh < 0.5 || remainingAh < 0.0) {
			return null
		}
		return kotlin.math.round(remainingAh / fullAh * 100.0).toInt().coerceIn(0, 100)
	}

	private fun effectiveRemainingAh(bmsAh: Double?, fullAh: Double?): Double? {
		if (bmsAh == null) {
			return null
		}
		val ocvAh = socCalibrator.remainingAhFromOcv(fullAh, socVoltagePercent)
		val minCell = minCellVoltageV
		val weakNotFull = minCell != null && minCell < socCalibrator.cellFullThresholdV() - 0.02
		val bmsNearFull = fullAh != null && fullAh > 0.5 && bmsAh >= fullAh - 0.15
		if (ocvAh != null && ocvAh < bmsAh - 0.05 && (charging || (bmsNearFull && weakNotFull))) {
			return ocvAh
		}
		return bmsAh
	}

	private fun energyVoltageV(packV: Double?, currentA: Double?): Double? {
		restPackVoltageV?.let { return it }
		return socCalibrator.packOcvV(
			BMS_ADDRESS.get(),
			packV,
			currentA,
			charging,
			lastCells?.size
		) ?: packV
	}

	fun isLinkHealthy(): Boolean = isBmsConnected() && isControllerConnected()

	fun controllerDisplayName(): String {
		val kind = controllerClient?.detectedControllerKind ?: EvBleUartClient.ControllerKind.UNKNOWN
		return when {
			kind == EvBleUartClient.ControllerKind.VESC || preferVescProtocol() -> {
				if (vescSnapshot.hwName != null || vescSnapshot.fwMajor != null) {
					vescSnapshot.typeLabel()
				} else {
					app.getString(R.string.ev_bms_protocol_vesc)
				}
			}
			kind == EvBleUartClient.ControllerKind.FARDRIVER -> {
				val saved = CONTROLLER_NAME.get()
				if (!saved.isNullOrBlank() && saved.contains("nd96", true)) {
					app.getString(R.string.ev_bms_controller_fardriver_nd96530)
				} else {
					app.getString(R.string.ev_bms_protocol_fardriver)
				}
			}
			!CONTROLLER_NAME.get().isNullOrBlank() -> CONTROLLER_NAME.get()
			else -> app.getString(R.string.ev_bms_controller_title)
		}
	}

	private fun preferredControllerKind(): EvBleUartClient.ControllerKind {
		return when (CONTROLLER_PROTOCOL.get()) {
			"vesc" -> EvBleUartClient.ControllerKind.VESC
			"fardriver" -> EvBleUartClient.ControllerKind.FARDRIVER
			else -> EvBleUartClient.ControllerKind.UNKNOWN
		}
	}

	private fun preferVescProtocol(): Boolean {
		return CONTROLLER_PROTOCOL.get() == "vesc" ||
				(CONTROLLER_PROTOCOL.get() != "fardriver" &&
						controllerClient?.detectedControllerKind == EvBleUartClient.ControllerKind.VESC)
	}

	private fun preferFarProtocol(): Boolean {
		return CONTROLLER_PROTOCOL.get() == "fardriver" ||
				(CONTROLLER_PROTOCOL.get() != "vesc" &&
						controllerClient?.detectedControllerKind == EvBleUartClient.ControllerKind.FARDRIVER)
	}

	private fun ctrlVoltageV(): Double? = vescSnapshot.voltageV ?: farSnapshot.voltageV

	private fun ctrlCurrentA(): Double? = vescSnapshot.currentInA ?: farSnapshot.lineCurrentA

	private fun ctrlPowerW(): Double? = vescSnapshot.powerW ?: farSnapshot.powerW

	private fun ctrlRpm(): Int? = vescSnapshot.rpm ?: farSnapshot.rawRpm

	private fun ctrlMotorTempC(): Double? = vescSnapshot.motorTempC ?: farSnapshot.motorTempC

	private fun ctrlTempC(): Double? = vescSnapshot.controllerTempC ?: farSnapshot.controllerTempC

	private fun ctrlOdometerKm(): Double? {
		return wheelOdometerKm() ?: controllerOdometerKm()
	}

	private fun wheelOdometerKm(): Double? = wheelTracker.odometerKm

	private fun wheelOdometerForRange(): Double? {
		val odo = wheelTracker.odometerKm ?: return null
		if (isSpeedSensorFresh()) {
			return odo
		}
		if (!isSpeedSensorConnected()) {
			return null
		}
		val gpsKmh = lastLocation?.takeIf { it.hasSpeed() }?.speed?.times(3.6) ?: 0.0
		return if (gpsKmh < 3.0) odo else null
	}

	private fun controllerOdometerKm(): Double? {
		if (!isControllerFresh()) {
			return null
		}
		return rawCtrlOdometerKm()?.times(controllerCalFactor())
	}

	private fun ctrlSpeedKmh(): Double? = wheelSpeedKmh() ?: controllerSpeedKmh()

	private fun controllerSpeedKmh(): Double? = rawCtrlSpeedKmh()?.times(controllerCalFactor())

	private fun wheelSpeedKmh(): Double? {
		if (!isSpeedSensorConnected() || !wheelTracker.hasWheelData) {
			return null
		}
		if (!isSpeedSensorFresh()) {
			return 0.0
		}
		return wheelTracker.currentSpeedKmh()
	}

	fun speedometerReading(): SpeedometerReading? {
		val wheel = wheelSpeedKmh()
		if (wheel != null) {
			return SpeedometerReading(wheel, true)
		}
		val ctrl = controllerSpeedKmh()
		if (isControllerFresh() && ctrl != null) {
			return SpeedometerReading(ctrl, true)
		}
		val gps = lastLocation?.takeIf { it.hasSpeed() }?.speed?.times(3.6)
		if (gps != null) {
			return SpeedometerReading(gps, false)
		}
		return null
	}

	fun hudDisplaySpeedKmh(): Double {
		if (HUD_DEMO.get()) {
			return demoHudSpeedKmh()
		}
		return speedometerReading()?.kmh ?: 0.0
	}

	fun demoHudSpeedKmh(): Double {
		val halfMs = HUD_DEMO_HALF_MS
		val elapsed = SystemClock.elapsedRealtime() % (halfMs * 2)
		val rising = elapsed < halfMs
		val u = if (rising) {
			elapsed.toDouble() / halfMs
		} else {
			(elapsed - halfMs).toDouble() / halfMs
		}
		val eased = (1.0 - cos(PI * u.coerceIn(0.0, 1.0))) / 2.0
		val speed = if (rising) HUD_DEMO_MAX_KMH * eased else HUD_DEMO_MAX_KMH * (1.0 - eased)
		return speed.coerceIn(0.0, HUD_DEMO_MAX_KMH)
	}

	fun noteHudSpeed(speedKmh: Double) {
		val windowMin = HUD_STATS_MINUTES.get()
		if (windowMin <= 0) {
			hudSpeedWindow.clear()
			return
		}
		val now = SystemClock.elapsedRealtime()
		if (hudSpeedWindow.isNotEmpty() && now - lastHudSampleMs < HUD_SPEED_SAMPLE_MS) {
			return
		}
		lastHudSampleMs = now
		hudSpeedWindow.addLast(now to speedKmh.coerceAtLeast(0.0))
		val cutoff = now - windowMin * 60_000L
		while (hudSpeedWindow.isNotEmpty() && hudSpeedWindow.first().first < cutoff) {
			hudSpeedWindow.removeFirst()
		}
	}

	fun hudWindowMaxKmh(): Float? {
		if (HUD_STATS_MINUTES.get() <= 0 || hudSpeedWindow.isEmpty()) {
			return null
		}
		return hudSpeedWindow.maxOf { it.second }.toFloat()
	}

	fun hudWindowAvgKmh(): Float? {
		if (HUD_STATS_MINUTES.get() <= 0 || hudSpeedWindow.isEmpty()) {
			return null
		}
		return hudSpeedWindow.map { it.second }.average().toFloat()
	}

	fun speedometerHudZone(speedKmh: Double): EvSpeedometerHudView.Zone {
		val l1 = HUD_LIMIT1_KMH.get()
		val b1 = HUD_BUFFER1_KMH.get().coerceAtLeast(l1)
		val l2 = HUD_LIMIT2_KMH.get().coerceAtLeast(b1)
		val b2 = HUD_BUFFER2_KMH.get().coerceAtLeast(l2)
		return when {
			speedKmh < l1 -> EvSpeedometerHudView.Zone.GREEN
			speedKmh < b1 -> EvSpeedometerHudView.Zone.YELLOW
			speedKmh < l2 -> EvSpeedometerHudView.Zone.ORANGE
			speedKmh < b2 -> EvSpeedometerHudView.Zone.STRIPE
			else -> EvSpeedometerHudView.Zone.RED
		}
	}

	fun hudFps(): Int = HUD_FPS.get().coerceIn(HUD_FPS_VALUES.first(), HUD_FPS_VALUES.last())

	fun hudFrameIntervalMs(): Long = (1000L / hudFps()).coerceAtLeast(16L)

	fun shouldShowSpeedometerHud(speedKmh: Double): Boolean {
		if (isFastSpeedProfile()) {
			hudWantVisible = true
			hudHideDeadlineMs = 0L
			return true
		}
		val showAt = HUD_SHOW_KMH.get().toDouble()
		val hideAt = (showAt - SPEED_PROFILE_HYSTERESIS_KMH).coerceAtLeast(0.0)
		val now = SystemClock.elapsedRealtime()
		val delayMs = HUD_HIDE_DELAY_SEC.get().coerceIn(1, 30) * 1000L
		if (speedKmh >= showAt) {
			hudWantVisible = true
			hudHideDeadlineMs = 0L
			return true
		}
		if (!hudWantVisible) {
			hudHideDeadlineMs = 0L
			return false
		}
		if (speedKmh >= hideAt) {
			hudHideDeadlineMs = 0L
			return true
		}
		if (hudHideDeadlineMs == 0L) {
			hudHideDeadlineMs = now + delayMs
		}
		if (now >= hudHideDeadlineMs) {
			hudWantVisible = false
			hudHideDeadlineMs = 0L
			return false
		}
		return true
	}

	fun isFastSpeedProfile(): Boolean {
		val fast = modeFromPref(SPEED_PROFILE_FAST.get()) ?: return false
		return settings.applicationMode == fast
	}

	private fun modeFromPref(key: String?): ApplicationMode? {
		val raw = key?.trim().orEmpty()
		if (raw.isEmpty()) {
			return null
		}
		ApplicationMode.valueOfStringKey(raw, null)?.let { return it }
		return ApplicationMode.values(app).firstOrNull { mode ->
			mode.stringKey.equals(raw, ignoreCase = true) ||
				mode.toHumanString().equals(raw, ignoreCase = true)
		}
	}

	fun tickSpeedProfileSwitch() {
		if (!SPEED_PROFILE_AUTO.get()) {
			speedProfileWantFast = null
			speedProfileSinceMs = 0L
			return
		}
		// Profile switches rebuild map widgets; never do that while the settings sheet is open
		// (demo crosses the threshold often and would crash preference / tab UI).
		if (isEvSettingsSheetOpen()) {
			return
		}
		val slow = modeFromPref(SPEED_PROFILE_SLOW.get()) ?: return
		val fast = modeFromPref(SPEED_PROFILE_FAST.get()) ?: return
		if (slow == fast) {
			return
		}
		val current = settings.applicationMode
		if (current != slow && current != fast) {
			speedProfileWantFast = null
			speedProfileSinceMs = 0L
			return
		}
		val speed = hudDisplaySpeedKmh()
		val threshold = SPEED_PROFILE_KMH.get().toDouble()
		val wantFast = if (current == fast) {
			speed > threshold - SPEED_PROFILE_HYSTERESIS_KMH
		} else {
			speed >= threshold
		}
		val now = System.currentTimeMillis()
		if (wantFast == (current == fast)) {
			speedProfileWantFast = wantFast
			speedProfileSinceMs = 0L
			return
		}
		if (speedProfileWantFast != wantFast) {
			speedProfileWantFast = wantFast
			speedProfileSinceMs = now
			return
		}
		if (now - speedProfileSinceMs < SPEED_PROFILE_HOLD_MS) {
			return
		}
		val target = if (wantFast) fast else slow
		if (settings.setApplicationMode(target)) {
			journal.i("speed-profile", "switch ${current.stringKey} → ${target.stringKey} speed=${"%.0f".format(speed)}")
		}
		speedProfileSinceMs = 0L
	}

	private fun isEvSettingsSheetOpen(): Boolean {
		val activity = mapActivity ?: return false
		return activity.supportFragmentManager.findFragmentByTag(EvBmsSettingsBottomSheet.TAG) != null
	}

	private fun rawCtrlOdometerKm(): Double? = vescSnapshot.odometerKm ?: farSnapshot.odometerKm

	private fun rawCtrlSpeedKmh(): Double? = vescSnapshot.speedKmh ?: farSnapshot.speedKmh

	private fun ctrlAvgWhPerKm(): Double? = vescSnapshot.avgPowerWhPerKm ?: farSnapshot.avgPowerWhPerKm

	override fun createWidgets(
		mapActivity: MapActivity,
		widgetsInfos: MutableList<MapWidgetInfo>,
		appMode: ApplicationMode,
		layoutMode: ScreenLayoutMode?
	) {
		val creator = WidgetInfoCreator(app, appMode, layoutMode)
		for (type in WidgetType.getEvBmsTypes()) {
			val widget = createMapWidgetForParams(mapActivity, type)
			val info = creator.createWidgetInfo(widget)
			if (info != null) {
				widgetsInfos.add(info)
			}
		}
	}

	override fun createMapWidgetForParams(
		mapActivity: MapActivity,
		widgetType: WidgetType,
		customId: String?,
		widgetsPanel: WidgetsPanel?
	): MapWidget? {
		if (widgetType == WidgetType.EV_HIKE) {
			return EvHikeWidget(mapActivity, customId, widgetsPanel)
		}
		if (widgetType == WidgetType.EV_SPEEDOMETER) {
			return EvSpeedometerWidget(mapActivity, customId, widgetsPanel)
		}
		val field = when (widgetType) {
			WidgetType.EV_BMS_SOC -> EvBmsTextWidget.Field.SOC
			WidgetType.EV_BMS_RANGE -> EvBmsTextWidget.Field.RANGE
			WidgetType.EV_BMS_RANGE_WINDOW -> EvBmsTextWidget.Field.RANGE_WINDOW
			WidgetType.EV_BMS_RANGE_PNZ -> EvBmsTextWidget.Field.RANGE_PNZ
			WidgetType.EV_RANGE_RESERVE -> EvBmsTextWidget.Field.RANGE_RESERVE
			WidgetType.EV_BMS_CONSUMPTION -> EvBmsTextWidget.Field.CONSUMPTION
			WidgetType.EV_FAR_TRIP -> EvBmsTextWidget.Field.FAR_TRIP
			WidgetType.EV_CHARGE_TRIP -> EvBmsTextWidget.Field.CHARGE_TRIP
			WidgetType.EV_CHARGE_ETA -> EvBmsTextWidget.Field.CHARGE_ETA
			WidgetType.EV_CHARGE_TIME -> EvBmsTextWidget.Field.CHARGE_TIME
			WidgetType.EV_CHARGE_ENERGY -> EvBmsTextWidget.Field.CHARGE_ENERGY
			WidgetType.EV_BMS_VOLTAGE -> EvBmsTextWidget.Field.VOLTAGE
			WidgetType.EV_BMS_MIN_CELL -> EvBmsTextWidget.Field.MIN_CELL
			WidgetType.EV_BMS_CURRENT -> EvBmsTextWidget.Field.CURRENT
			WidgetType.EV_BMS_POWER -> EvBmsTextWidget.Field.POWER
			WidgetType.EV_BATTERY_TEMP -> EvBmsTextWidget.Field.BATTERY_TEMP
			WidgetType.EV_MOTOR_TEMP -> EvBmsTextWidget.Field.MOTOR_TEMP
			WidgetType.EV_CONTROLLER_TEMP -> EvBmsTextWidget.Field.CONTROLLER_TEMP
			WidgetType.EV_BMS_TIME -> EvBmsTextWidget.Field.TIME
			WidgetType.EV_BMS_LAT -> EvBmsTextWidget.Field.LAT
			WidgetType.EV_BMS_LON -> EvBmsTextWidget.Field.LON
			WidgetType.EV_BMS_GPS_SPEED -> EvBmsTextWidget.Field.GPS_SPEED
			WidgetType.EV_BMS_SOC_OCV -> EvBmsTextWidget.Field.SOC_OCV
			WidgetType.EV_BMS_REMAINING_AH -> EvBmsTextWidget.Field.REMAINING_AH
			WidgetType.EV_BMS_FULL_AH -> EvBmsTextWidget.Field.FULL_AH
			WidgetType.EV_BMS_CYCLES -> EvBmsTextWidget.Field.CYCLES
			WidgetType.EV_BMS_MAX_CELL -> EvBmsTextWidget.Field.MAX_CELL
			WidgetType.EV_BMS_IMBALANCE -> EvBmsTextWidget.Field.IMBALANCE
			WidgetType.EV_BMS_CTRL_VOLTAGE -> EvBmsTextWidget.Field.CTRL_VOLTAGE
			WidgetType.EV_BMS_CTRL_CURRENT -> EvBmsTextWidget.Field.CTRL_CURRENT
			WidgetType.EV_BMS_RPM -> EvBmsTextWidget.Field.RPM
			WidgetType.EV_BMS_GEAR -> EvBmsTextWidget.Field.GEAR
			WidgetType.EV_BMS_ODOMETER -> EvBmsTextWidget.Field.ODOMETER
			WidgetType.EV_BMS_CTRL_SPEED -> EvBmsTextWidget.Field.CTRL_SPEED
			WidgetType.EV_BMS_WHEEL_SPEED -> EvBmsTextWidget.Field.WHEEL_SPEED
			WidgetType.EV_BMS_WHEEL_ODO -> EvBmsTextWidget.Field.WHEEL_ODO
			WidgetType.EV_BMS_CADENCE -> EvBmsTextWidget.Field.CADENCE
			WidgetType.EV_BMS_USED_AH -> EvBmsTextWidget.Field.USED_AH
			WidgetType.EV_BMS_COVERAGE -> EvBmsTextWidget.Field.COVERAGE
			WidgetType.EV_BMS_STOP_TIME -> EvBmsTextWidget.Field.STOP_TIME
			else -> return null
		}
		return EvBmsTextWidget(mapActivity, widgetType, field, customId, widgetsPanel)
	}

	fun isHikeMode(): Boolean = hikeMode.isEnabled()

	fun toggleHikeMode(mapActivity: MapActivity) {
		hikeMode.toggle(mapActivity)
	}

	fun applyHikeTelemetryState() {
		recorder.setFolderUri(CSV_FOLDER_URI.get())
		recorder.setFields(selectedTelemetryFields())
		recorder.setGpxFields(selectedGpxTelemetryFields())
		recorder.setWriteGpx(RECORD_GPX.get() && !isTripRecording())
	}

	fun isTripRecording(): Boolean = app.savingTrackHelper.isRecording

	@Throws(JSONException::class)
	override fun attachAdditionalInfoToRecordedTrack(location: Location, json: JSONObject) {
		val sample = latestTelemetry ?: return
		if (!isBmsFresh() && !isControllerFresh()) {
			return
		}
		EvGpx.put(json, sample, selectedGpxTelemetryFields())
	}

	override fun getTrackPointsAnalyser(): GpxTrackAnalysis.TrackPointsAnalyser {
		return EvTrackPointsAnalyser()
	}

	override fun getAvailableGPXDataSetTypes(
		analysis: GpxTrackAnalysis,
		out: MutableList<GPXDataSetType?>
	) {
		EvGpx.getAvailableGPXDataSetTypes(analysis, out)
	}

	override fun getOrderedLineDataSet(
		chart: LineChart,
		analysis: GpxTrackAnalysis,
		graphType: GPXDataSetType,
		chartAxisType: GPXDataSetAxisType,
		calcWithoutGaps: Boolean,
		useRightAxis: Boolean
	): OrderedLineDataSet? {
		if (graphType.typeGroup != GpxDataSetTypeGroup.EV_TELEMETRY) {
			return null
		}
		return EvGpx.createDataSet(
			app, chart, analysis, graphType, chartAxisType, useRightAxis, calcWithoutGaps
		)
	}

	private fun writeTripRecordingWaypoint(event: GpxEvent) {
		if (!event.waypoint || !isTripRecording()) {
			return
		}
		val lat = event.lat ?: return
		val lon = event.lon ?: return
		val isCharge = event.name.contains(app.getString(R.string.ev_bms_gpx_charge_end), true) ||
				event.name.contains("Wh", true) ||
				event.name.contains("Вт", true)
		val color = if (isCharge) 0xFF43A047.toInt() else 0xFF1E88E5.toInt()
		app.savingTrackHelper.insertPointData(
			lat,
			lon,
			event.description,
			event.name,
			app.getString(R.string.ev_bms_plugin_name),
			color,
			"charging_station",
			"circle"
		)
	}

	fun selectedTelemetryFields(): List<TelemetryField> = TelemetryField.parse(TELEMETRY_FIELDS.get())

	fun selectedGpxTelemetryFields(): List<TelemetryField> {
		return if (TELEMETRY_GPX_FIELDS.isSet()) {
			TelemetryField.parseExact(TELEMETRY_GPX_FIELDS.get())
		} else {
			selectedTelemetryFields()
		}
	}

	fun setGpxTelemetryFields(selected: List<TelemetryField>) {
		TELEMETRY_GPX_FIELDS.set(selected.joinToString(",") { it.id })
		applyHikeTelemetryState()
	}

	fun chartHistorySnapshot(): List<EvTelemetry> {
		synchronized(chartLock) {
			return ArrayList(chartHistory)
		}
	}

	fun isChartsLive(): Boolean = CHARTS_LIVE.get()

	fun setChartsLive(live: Boolean) {
		CHARTS_LIVE.set(live)
	}

	private fun maybeCollectHistorySample(sample: EvTelemetry) {
		if (!charging && tripStartMs <= 0L) {
			return
		}
		val t = sample.timeMs
		if (historySampleLastMs > 0L && t - historySampleLastMs < HISTORY_SAMPLE_MIN_MS) {
			return
		}
		historySampleLastMs = t
		val power = sample.controllerPowerW ?: run {
			val v = sample.voltageV
			val i = sample.currentA
			if (v != null && i != null) v * i else null
		}
		historySamples.add(
			EvHistoryChartStore.Sample(
				t = t,
				currentA = sample.currentA?.let { if (charging) kotlin.math.abs(it) else it },
				battTempC = sample.bmsTempC,
				minCellV = sample.minCellVoltageV,
				maxCellV = lastCells?.maxOrNull(),
				powerW = power,
				consWhKm = sample.consumptionWhPerKm,
				motorTempC = sample.motorTempC
			)
		)
	}

	fun sheetTab(): EvBmsSheetTab = EvBmsSheetTab.from(SHEET_TAB.get())

	fun setSheetTab(tab: EvBmsSheetTab) {
		SHEET_TAB.set(tab.index)
	}

	fun isDebugJournalEnabled(): Boolean = DEBUG_JOURNAL.get()

	fun setDebugJournalEnabled(enabled: Boolean) {
		DEBUG_JOURNAL.set(enabled)
		if (enabled) {
			journal.enabled = true
			journal.i(
				"journal",
				"on hash=${EvBmsRevision.GIT_HASH} bms=${BMS_ADDRESS.get()} " +
						"ctrl=${CONTROLLER_ADDRESS.get()} protoBms=${BMS_PROTOCOL.get()} " +
						"protoCtrl=${CONTROLLER_PROTOCOL.get()}"
			)
		} else {
			journal.log("I", "journal", "off", force = true)
			journal.enabled = false
		}
	}

	fun debugJournalSize(): Long = journal.sizeBytes()

	fun debugJournalTail(): String = journal.tail()

	fun clearDebugJournal() {
		journal.clear()
	}

	fun shareDebugJournal(activity: Activity): Boolean = journal.share(activity)

	fun setTelemetryFields(selected: List<TelemetryField>) {
		if (selected.isEmpty()) {
			return
		}
		TELEMETRY_FIELDS.set(selected.joinToString(",") { it.id })
		val keep = selected.map { it.id }.toSet()
		val order = CHART_ORDER.get().orEmpty().split(',').map { it.trim() }.filter { it in keep }
		CHART_ORDER.set(order.joinToString(","))
		val paused = chartPausedIds().filter { it in keep }
		CHART_PAUSED.set(paused.joinToString(","))
		applyHikeTelemetryState()
	}

	fun restartTelemetryIfRecording() {
		applyHikeTelemetryState()
	}

	fun telemetryFieldsSummary(ctx: android.content.Context): String {
		val selected = selectedTelemetryFields().size
		return ctx.getString(R.string.ev_bms_telemetry_fields_summary, selected, TelemetryField.entries.size)
	}

	fun setCsvFolderUri(uri: android.net.Uri): Boolean {
		val required = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
				android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
		if (!net.osmand.plus.utils.AndroidUtils.takePersistableUriPermission(app, uri, required)) {
			return false
		}
		CSV_FOLDER_URI.set(uri.toString())
		recorder.setFolderUri(uri.toString())
		applyHikeTelemetryState()
		return true
	}

	fun csvFolderSummary(): String {
		recorder.setFolderUri(CSV_FOLDER_URI.get())
		return recorder.folderSummary()
	}

	fun listCsvFiles(): List<TelemetryRecorder.CsvEntry> {
		recorder.setFolderUri(CSV_FOLDER_URI.get())
		return recorder.listFiles()
	}

	fun listTelemetrySessions(): List<TelemetryRecorder.LogSession> {
		recorder.setFolderUri(CSV_FOLDER_URI.get())
		return recorder.listSessions()
	}

	fun isActiveTelemetrySession(session: TelemetryRecorder.LogSession): Boolean {
		return recorder.activeStamp() == session.stamp
	}

	fun deleteTelemetrySession(session: TelemetryRecorder.LogSession): Boolean {
		recorder.setFolderUri(CSV_FOLDER_URI.get())
		return recorder.deleteSession(session)
	}

	fun shareCsv(activity: Activity, uris: List<android.net.Uri>) {
		recorder.share(activity, uris)
	}

	val announcePreferences: List<CommonPreference<Boolean>> = listOf(
		ANNOUNCE_SOC,
		ANNOUNCE_RANGE_ON_STOP,
		ANNOUNCE_RANGE_VS_ROUTE,
		ANNOUNCE_RANGE_RESERVE,
		ANNOUNCE_RANGE_RESERVE_SMALL,
		ANNOUNCE_RANGE_RESERVE_LOW,
		ANNOUNCE_CELL_VOLTAGE,
		ANNOUNCE_MOTOR_HEAT,
		ANNOUNCE_BATTERY_OVERHEAT,
		ANNOUNCE_BATTERY_FREEZE,
		ANNOUNCE_LINK,
		ANNOUNCE_CHARGE_ETA
	)

	fun hasAnyAnnounceEnabled(): Boolean = announcePreferences.any { it.get() }

	fun setAllAnnouncesEnabled(enabled: Boolean) {
		for (pref in announcePreferences) {
			pref.set(enabled)
		}
	}

	fun toggleAllAnnounces(): Boolean {
		val enabled = !hasAnyAnnounceEnabled()
		setAllAnnouncesEnabled(enabled)
		if (enabled) {
			voice.speakNow(app.getString(R.string.ev_bms_voice_alerts_on))
		}
		return enabled
	}

	fun isAnnouncePreferenceId(prefId: String): Boolean {
		return announcePreferences.any { it.id == prefId }
	}

	fun mapTorrentStatus(): EvMapTorrentStatus = mapTorrent.status()

	fun torrentPathSummary(): String = mapTorrent.pathSummary()

	fun importTorrentFile(uri: android.net.Uri): Boolean {
		val ok = mapTorrent.importTorrent(uri)
		if (ok) {
			syncMapTorrent()
		}
		return ok
	}

	fun startMapTorrentManual() {
		mapTorrent.startManual()
	}

	fun stopMapTorrent() {
		mapTorrent.stop()
	}

	fun syncMapTorrent() {
		mapTorrent.sync()
	}

	private fun registerTorrentWatchers() {
		unregisterTorrentWatchers()
		val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
		if (cm != null) {
			val cb = object : android.net.ConnectivityManager.NetworkCallback() {
				override fun onAvailable(network: android.net.Network) {
					syncMapTorrent()
				}

				override fun onLost(network: android.net.Network) {
					syncMapTorrent()
				}
			}
			try {
				cm.registerDefaultNetworkCallback(cb)
				torrentNetworkCallback = cb
			} catch (_: Exception) {
			}
		}
		val receiver = object : android.content.BroadcastReceiver() {
			override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
				syncMapTorrent()
			}
		}
		val filter = android.content.IntentFilter().apply {
			addAction(android.content.Intent.ACTION_POWER_CONNECTED)
			addAction(android.content.Intent.ACTION_POWER_DISCONNECTED)
		}
		try {
			androidx.core.content.ContextCompat.registerReceiver(
				app,
				receiver,
				filter,
				androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
			)
			torrentPowerReceiver = receiver
		} catch (_: Exception) {
		}
	}

	private fun unregisterTorrentWatchers() {
		val cb = torrentNetworkCallback
		if (cb != null) {
			try {
				app.getSystemService(android.net.ConnectivityManager::class.java)
					?.unregisterNetworkCallback(cb)
			} catch (_: Exception) {
			}
			torrentNetworkCallback = null
		}
		val receiver = torrentPowerReceiver
		if (receiver != null) {
			try {
				app.unregisterReceiver(receiver)
			} catch (_: Exception) {
			}
			torrentPowerReceiver = null
		}
	}

	fun askShowSettingsDialog(activity: FragmentActivity) {
		EvBmsSettingsBottomSheet.showInstance(activity.supportFragmentManager)
	}

	fun isTelemetryRecording(): Boolean = TELEMETRY_SESSION_STATE.get() == SESSION_RECORDING

	fun isTelemetryPaused(): Boolean = TELEMETRY_SESSION_STATE.get() == SESSION_PAUSED

	fun hasTelemetrySession(): Boolean = isTelemetryRecording() || isTelemetryPaused()

	fun startTelemetryRecording(): Boolean {
		if (isTelemetryPaused()) {
			return resumeTelemetryRecording()
		}
		applyHikeTelemetryState()
		val ok = recorder.startNewSession()
		if (ok) {
			resetSessionOdometers()
			RECORD_TELEMETRY.set(true)
			persistTelemetrySession(SESSION_RECORDING)
			beginTelemetryCalculations()
			publishSample()
		}
		return ok
	}

	private fun beginTelemetryCalculations() {
		rangeEstimator.reset()
		if (!chargeSessionOpen && tripStartMs <= 0L) {
			startTripSession(System.currentTimeMillis(), lastLocation)
		}
	}

	fun pauseTelemetryRecording() {
		if (!isTelemetryRecording()) {
			return
		}
		recorder.pause()
		persistTelemetrySession(SESSION_PAUSED)
	}

	fun resumeTelemetryRecording(): Boolean {
		if (!isTelemetryPaused()) {
			return recorder.isRecording
		}
		val ok = recorder.resume()
		if (ok) {
			RECORD_TELEMETRY.set(true)
			persistTelemetrySession(SESSION_RECORDING)
		}
		return ok
	}

	fun stopTelemetryRecording() {
		recorder.saveAndClose()
		RECORD_TELEMETRY.set(false)
		clearTelemetrySession()
		app.showToastMessage(R.string.ev_bms_telemetry_saved)
	}

	private fun persistTelemetrySession(state: String) {
		TELEMETRY_SESSION_STATE.set(state)
		TELEMETRY_SESSION_CSV.set(recorder.currentCsvSpec().orEmpty())
		TELEMETRY_SESSION_GPX.set(recorder.currentGpxSpec().orEmpty())
		TELEMETRY_SESSION_FIELDS.set(recorder.sessionFieldIds())
	}

	private fun clearTelemetrySession() {
		TELEMETRY_SESSION_STATE.set(SESSION_IDLE)
		TELEMETRY_SESSION_CSV.set("")
		TELEMETRY_SESSION_GPX.set("")
		TELEMETRY_SESSION_FIELDS.set("")
	}

	private fun restoreTelemetrySessionIfNeeded() {
		val state = TELEMETRY_SESSION_STATE.get()
		if (state == SESSION_RECORDING && recorder.isRecording) {
			return
		}
		if (state == SESSION_PAUSED && recorder.currentCsvSpec() != null) {
			return
		}
		restoreTelemetrySession()
	}

	private fun restoreTelemetrySession() {
		val state = TELEMETRY_SESSION_STATE.get()
		val csv = TELEMETRY_SESSION_CSV.get()
		if (state.isNullOrBlank() || state == SESSION_IDLE || csv.isNullOrBlank()) {
			return
		}
		applyHikeTelemetryState()
		val gpx = TELEMETRY_SESSION_GPX.get()?.takeIf { it.isNotBlank() }
		val ok = recorder.restore(csv, gpx, TELEMETRY_SESSION_FIELDS.get())
		if (!ok) {
			RECORD_TELEMETRY.set(false)
			clearTelemetrySession()
			return
		}
		RECORD_TELEMETRY.set(true)
		if (state == SESSION_RECORDING && !recorder.resume()) {
			persistTelemetrySession(SESSION_PAUSED)
		}
	}

	fun isSpeedCalibrating(): Boolean = speedCalRunning

	fun speedCalTargetMeters(): Int = SPEED_CAL_DISTANCE_M.get().coerceAtLeast(100)

	fun speedCalProgressGpsM(): Double = speedCalGpsM

	fun startSpeedCalibration(): Boolean {
		if (speedCalRunning) {
			return true
		}
		speedCalRunning = true
		speedCalGpsM = 0.0
		speedCalCtrlM = 0.0
		speedCalCtrlStartKm = rawCtrlOdometerKm()
		speedCalUseWheel = isSpeedSensorFresh() || isSpeedSensorConnected()
		speedCalWheelStartRevs = wheelTracker.lastWheelRevs()
		speedCalLastLoc = lastLocation?.let { Location(it) }
		speedCalLastMs = System.currentTimeMillis()
		val target = OsmAndFormatter.getFormattedDistance(speedCalTargetMeters().toFloat(), app)
		app.showToastMessage(app.getString(R.string.ev_bms_cal_started, target))
		return true
	}

	fun stopSpeedCalibration(notify: Boolean = true) {
		if (!speedCalRunning) {
			return
		}
		speedCalRunning = false
		speedCalLastLoc = null
		if (notify) {
			app.showToastMessage(R.string.ev_bms_cal_cancelled)
		}
	}

	fun parseSpeedCalFactor(raw: String?): Float? {
		val text = raw?.trim()?.replace(',', '.') ?: return null
		val value = text.toFloatOrNull() ?: return null
		if (!value.isFinite() || value < 0.5f || value > 2.0f) {
			return null
		}
		return value
	}

	fun applySpeedCalFactor(value: Float) {
		if (isSpeedSensorSelected()) {
			SPEED_SENSOR_CAL_FACTOR.set(value)
			configureWheelTracker()
		} else {
			SPEED_CAL_FACTOR.set(value)
		}
	}

	fun formattedSpeedCalFactor(): String {
		return String.format(Locale.US, "%.3f", activeCalFactor())
	}

	fun speedCalFactorDescriptionRes(): Int {
		return if (isSpeedSensorSelected()) {
			R.string.ev_bms_cal_sensor_circ_desc
		} else {
			R.string.ev_bms_cal_factor_desc
		}
	}

	fun parseWheelCircumferenceMm(raw: String?): Int? {
		val text = raw?.trim()?.replace(',', '.') ?: return null
		val mm = text.toFloatOrNull()?.toInt() ?: return null
		if (mm !in 1200..2800) {
			return null
		}
		return mm
	}

	fun formattedWheelCircumferenceMm(): String = WHEEL_CIRCUMFERENCE_MM.get().toString()

	fun applyWheelCircumferenceMm(mm: Int) {
		WHEEL_CIRCUMFERENCE_MM.set(mm.coerceIn(1200, 2800))
		configureWheelTracker()
	}

	fun totalMassKg(): Double {
		val vehicle = VEHICLE_MASS_KG.get().toDouble().takeIf { it.isFinite() && it > 0.0 }
			?: DEFAULT_VEHICLE_MASS_KG.toDouble()
		val driver = DRIVER_MASS_KG.get().toDouble().takeIf { it.isFinite() && it >= 0.0 }
			?: DEFAULT_DRIVER_MASS_KG.toDouble()
		return (vehicle + driver).coerceAtLeast(1.0)
	}

	fun parseMassKg(raw: String?, minKg: Float, maxKg: Float): Float? {
		val text = raw?.trim()?.replace(',', '.') ?: return null
		val value = text.toFloatOrNull() ?: return null
		if (!value.isFinite() || value < minKg || value > maxKg) {
			return null
		}
		return value
	}

	fun formattedMassKg(value: Float): String {
		return if (kotlin.math.abs(value - value.toInt()) < 0.05f) {
			String.format(Locale.US, "%d", value.toInt())
		} else {
			String.format(Locale.US, "%.1f", value)
		}
	}

	private fun controllerCalFactor(): Double {
		val value = SPEED_CAL_FACTOR.get().toDouble()
		return if (value.isFinite() && value > 0.0) value.coerceIn(0.5, 2.0) else 1.0
	}

	private fun sensorCalFactor(): Double {
		val value = SPEED_SENSOR_CAL_FACTOR.get().toDouble()
		return if (value.isFinite() && value > 0.0) value.coerceIn(0.5, 2.0) else 1.0
	}

	private fun activeCalFactor(): Double {
		return if (isSpeedSensorSelected()) sensorCalFactor() else controllerCalFactor()
	}

	private fun configureWheelTracker() {
		wheelTracker.configure(
			CscWheelTracker.mmToMeters(WHEEL_CIRCUMFERENCE_MM.get()),
			sensorCalFactor()
		)
	}

	private fun persistWheelOdometer() {
		val km = wheelTracker.odometerKm
		if (km != null && km.isFinite() && km >= 0.0) {
			SPEED_SENSOR_ODO_KM.set(km.toFloat())
		}
		SPEED_SENSOR_TRIP_KM.set(wheelTracker.tripKm.toFloat())
	}

	private fun resetSessionOdometers() {
		wheelTracker.resetTripDistance()
		SPEED_SENSOR_TRIP_KM.set(0f)
		setControllerTripStart(rawCtrlOdometerKm())
	}

	private fun cadenceRpm(): Double? {
		if (!cadenceTracker.hasData) {
			return null
		}
		return cadenceTracker.rpm
	}

	private fun tickSpeedCalibration(loc: Location?) {
		if (!speedCalRunning) {
			return
		}
		if (isSpeedSensorFresh()) {
			speedCalUseWheel = true
			if (speedCalWheelStartRevs == null) {
				speedCalWheelStartRevs = wheelTracker.lastWheelRevs()
			}
		}
		val now = System.currentTimeMillis()
		if (speedCalCtrlStartKm == null) {
			speedCalCtrlStartKm = rawCtrlOdometerKm()
		}
		val rawSpeed = rawCtrlSpeedKmh()
		if (speedCalLastMs > 0L && rawSpeed != null && rawSpeed > 0.3) {
			speedCalCtrlM += rawSpeed / 3.6 * ((now - speedCalLastMs).coerceAtLeast(0L) / 1000.0)
		}
		speedCalLastMs = now
		if (loc != null && (!loc.hasAccuracy() || loc.accuracy <= 25f)) {
			val prev = speedCalLastLoc
			if (prev != null) {
				val delta = prev.distanceTo(loc)
				if (delta in 0.5f..80f) {
					speedCalGpsM += delta
				}
			}
			speedCalLastLoc = Location(loc)
		}
		if (speedCalGpsM >= speedCalTargetMeters()) {
			finishSpeedCalibration()
		}
	}

	private fun finishSpeedCalibration() {
		speedCalRunning = false
		speedCalLastLoc = null
		val gpsKm = speedCalGpsM / 1000.0
		if (!gpsKm.isFinite() || gpsKm < 0.05) {
			app.showToastMessage(R.string.ev_bms_cal_failed)
			return
		}
		if (speedCalUseWheel) {
			val dRevs = wheelTracker.revsSince(speedCalWheelStartRevs)
			if (dRevs == null || dRevs < MIN_WHEEL_REVS_FOR_CAL) {
				app.showToastMessage(R.string.ev_bms_cal_failed)
				return
			}
			val circMm = (speedCalGpsM / dRevs.toDouble() * 1000.0).roundToInt()
			if (circMm !in 1200..2800) {
				app.showToastMessage(R.string.ev_bms_cal_failed)
				return
			}
			WHEEL_CIRCUMFERENCE_MM.set(circMm)
			SPEED_SENSOR_CAL_FACTOR.set(1f)
			configureWheelTracker()
			app.showToastMessage(app.getString(R.string.ev_bms_cal_done_circ, circMm))
			return
		}
		val odoKm = speedCalCtrlStartKm?.let { start ->
			rawCtrlOdometerKm()?.minus(start)
		}
		val ctrlKm = when {
			odoKm != null && odoKm >= 0.05 -> odoKm
			speedCalCtrlM >= 50.0 -> speedCalCtrlM / 1000.0
			else -> null
		}
		if (ctrlKm == null) {
			app.showToastMessage(R.string.ev_bms_cal_failed)
			return
		}
		val factor = (gpsKm / ctrlKm).toFloat()
		if (!factor.isFinite() || factor < 0.5f || factor > 2.0f) {
			app.showToastMessage(R.string.ev_bms_cal_failed)
			return
		}
		SPEED_CAL_FACTOR.set(factor)
		app.showToastMessage(app.getString(R.string.ev_bms_cal_done, factor))
	}

	private fun migratePollPreferences() {
		if (!POLL_INTERVAL_MS.isSet()) {
			return
		}
		val old = POLL_INTERVAL_MS.get().coerceAtLeast(MIN_POLL_MS)
		if (!BMS_POLL_MS.isSet()) {
			BMS_POLL_MS.set(old)
		}
		if (!CONTROLLER_POLL_MS.isSet()) {
			CONTROLLER_POLL_MS.set(old)
		}
	}

	private fun migrateCadenceTelemetryField() {
		val raw = TELEMETRY_FIELDS.get() ?: return
		if (raw.isBlank()) {
			return
		}
		val ids = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
		if (ids.contains(TelemetryField.CADENCE.id)) {
			return
		}
		TELEMETRY_FIELDS.set((ids + TelemetryField.CADENCE.id).joinToString(","))
	}

	private fun activeBmsPollMs(): Long = coercePollMs(BMS_POLL_MS.get())

	private fun activeCtrlPollMs(): Long = coercePollMs(CONTROLLER_POLL_MS.get())

	private fun activeTickMs(): Long = minOf(activeBmsPollMs(), activeCtrlPollMs())

	private fun coercePollMs(raw: Int): Long {
		val poll = raw.toLong().coerceAtLeast(MIN_POLL_MS.toLong())
		return if (isHikeMode()) {
			poll.coerceAtLeast(HikeModeController.HIKE_POLL_MS.toLong())
		} else {
			poll
		}
	}

	private fun activeRecordIntervalMs(): Long {
		val rec = RECORD_INTERVAL_MS.get().toLong()
		val poll = activeTickMs()
		return if (rec <= RECORD_INTERVAL_SAME) poll else rec.coerceAtLeast(poll)
	}

	private fun dataStaleMs(pollMs: Long): Long = maxOf(DATA_STALE_MS, pollMs * 3)

	private fun linkDeadMs(pollMs: Long): Long = maxOf(LINK_DEAD_MS, pollMs * 4)

	private fun linkDeadMsFor(role: EvBleUartClient.Role): Long {
		return when (role) {
			EvBleUartClient.Role.BMS -> linkDeadMs(activeBmsPollMs())
			EvBleUartClient.Role.CONTROLLER -> linkDeadMs(activeCtrlPollMs())
			EvBleUartClient.Role.SPEED, EvBleUartClient.Role.CADENCE -> 12_000L
		}
	}

	override fun registerOptionsMenuItems(mapActivity: MapActivity, helper: ContextMenuAdapter) {
		if (isActive) {
			helper.addItem(
				ContextMenuItem(OsmAndCustomizationConstants.DRAWER_EV_BMS_ID)
					.setTitleId(R.string.ev_bms_plugin_name, mapActivity)
					.setIcon(R.drawable.ic_action_car_info)
					.setListener { _: OnDataChangeUiAdapter?, _: View?, _: ContextMenuItem?, _: Boolean ->
						app.logEvent("evBmsOpen")
						BaseSettingsFragment.showInstance(mapActivity, SettingsScreenType.EV_BMS_SETTINGS)
						true
					}
			)
		}
	}
}

data class SpeedometerReading(val kmh: Double, val fromController: Boolean)

