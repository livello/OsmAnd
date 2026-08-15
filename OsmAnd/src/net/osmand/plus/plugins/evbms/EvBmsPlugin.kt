package net.osmand.plus.plugins.evbms

import android.app.Activity
import androidx.core.text.HtmlCompat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.fragment.app.FragmentActivity
import net.osmand.Location
import net.osmand.aidlapi.OsmAndCustomizationConstants
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.plugins.OsmandPlugin
import net.osmand.plus.plugins.evbms.ble.EvBleUartClient
import net.osmand.plus.plugins.evbms.protocol.AntBmsProtocol
import net.osmand.plus.plugins.evbms.protocol.BmsSnapshot
import net.osmand.plus.plugins.evbms.protocol.FarDriverProtocol
import net.osmand.plus.plugins.evbms.protocol.JbdBmsProtocol
import net.osmand.plus.plugins.evbms.protocol.VescProtocol
import net.osmand.plus.settings.backend.ApplicationMode
import net.osmand.plus.settings.backend.preferences.CommonPreference
import net.osmand.plus.settings.enums.ScreenLayoutMode
import net.osmand.plus.settings.fragments.BaseSettingsFragment
import net.osmand.plus.settings.fragments.SettingsScreenType
import net.osmand.plus.views.mapwidgets.MapWidgetInfo
import net.osmand.plus.views.mapwidgets.WidgetInfoCreator
import net.osmand.plus.views.mapwidgets.WidgetType
import net.osmand.plus.views.mapwidgets.WidgetsPanel
import net.osmand.plus.views.mapwidgets.widgets.MapWidget
import net.osmand.plus.widgets.ctxmenu.ContextMenuAdapter
import net.osmand.plus.widgets.ctxmenu.callback.OnDataChangeUiAdapter
import net.osmand.plus.widgets.ctxmenu.data.ContextMenuItem
import org.json.JSONObject
import java.util.Locale

class EvBmsPlugin(app: OsmandApplication) : OsmandPlugin(app), EvBleUartClient.Listener {

	companion object {
		const val DEFAULT_POLL_MS = 2000
		const val DEFAULT_SOC_STEP = 5
		const val DEFAULT_STOP_SPEED = 3
		const val REST_CURRENT_A = 5.0
		const val CHARGE_HOLD_SAMPLES = 3
		const val DATA_STALE_MS = 5000L
		const val DEFAULT_CHARGE_STILL_SEC = 60
		const val DEFAULT_CHARGE_STILL_KMH = 3
		const val DEFAULT_CHARGE_CURRENT_A = 5
		const val CHARGE_ETA_REPEAT_MS = 300_000L
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
	val RECORD_TELEMETRY: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_record_telemetry", true).makeGlobal().makeShared()
	val RECORD_GPX: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_record_gpx", true).makeGlobal().makeShared()
	val TELEMETRY_FIELDS: CommonPreference<String> =
		registerStringPreference("ev_bms_telemetry_fields", TelemetryField.DEFAULT_IDS).makeGlobal().makeShared()
	val CHARGE_STILL_SEC: CommonPreference<Int> =
		registerIntPreference("ev_bms_charge_still_sec", DEFAULT_CHARGE_STILL_SEC).makeGlobal().makeShared()
	val CHARGE_STILL_KMH: CommonPreference<Int> =
		registerIntPreference("ev_bms_charge_still_kmh", DEFAULT_CHARGE_STILL_KMH).makeGlobal().makeShared()
	val CHARGE_CURRENT_A: CommonPreference<Int> =
		registerIntPreference("ev_bms_charge_current_a", DEFAULT_CHARGE_CURRENT_A).makeGlobal().makeShared()
	val ANNOUNCE_SOC: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_announce_soc", true).makeGlobal().makeShared()
	val SOC_STEP_PERCENT: CommonPreference<Int> =
		registerIntPreference("ev_bms_soc_step_percent", DEFAULT_SOC_STEP).makeGlobal().makeShared()
	val ANNOUNCE_RANGE_ON_STOP: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_announce_range_on_stop", true).makeGlobal().makeShared()
	val STOP_SPEED_KMH: CommonPreference<Int> =
		registerIntPreference("ev_bms_stop_speed_kmh", DEFAULT_STOP_SPEED).makeGlobal().makeShared()
	val USE_ROUTE_PROFILE: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_use_route_profile", false).makeGlobal().makeShared()
	val BMS_PROTOCOL: CommonPreference<String> =
		registerStringPreference("ev_bms_protocol", "auto").makeGlobal().makeShared()
	val CSV_FOLDER_URI: CommonPreference<String> =
		registerStringPreference("ev_bms_csv_folder_uri", "").makeGlobal().makeShared()
	val ANNOUNCE_RANGE_VS_ROUTE: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_announce_range_vs_route", true).makeGlobal().makeShared()
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

	private val handler = Handler(Looper.getMainLooper())
	private val rangeEstimator = RangeEstimator()
	private val recorder = TelemetryRecorder(app)
	private val voice = EvVoiceAnnouncer(app)
	private val historyStore = EvHistoryStore(app)
	private val hikeMode = HikeModeController(app, this)
	private val farSnapshot = FarDriverProtocol.FarDriverSnapshot()
	private val vescSnapshot = VescProtocol.VescSnapshot()
	private var farStatusStarted = false
	private var vescPollSetup = false

	private var bmsClient: EvBleUartClient? = null
	private var controllerClient: EvBleUartClient? = null
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
	private var lastChargeAh: Double? = null
	private var stillSinceMs: Long? = null
	private var charging = false
	private var chargeHold = 0
	private var chargeExitHold = 0
	private var chargeStartMs = 0L
	private var chargeStartAh: Double? = null
	private var chargeStartTempC: Double? = null
	private var chargeStartLat: Double? = null
	private var chargeStartLon: Double? = null
	private var chargeFrozenCurrentA: Double? = null
	private var chargeFullAh: Double? = null
	private var chargeLastAh: Double? = null
	private var chargeLastAhMs = 0L
	private var tripStartMs = 0L
	private var tripStartVoltageV: Double? = null
	private var tripStartTempC: Double? = null
	private var tripStartLat: Double? = null
	private var tripStartLon: Double? = null
	private var tripMinCellV: Double? = null
	private var tripLastVoltageV: Double? = null
	private var tripLastTempC: Double? = null
	private var tripMovingMs = 0L
	private var tripLastMoveMs = 0L
	private val pendingGpxEvents = ArrayList<Pair<String, String>>()
	private var lastBmsRxMs = 0L
	private var lastCtrlRxMs = 0L
	private var pollCellsNext = false
	@Volatile
	var latestTelemetry: EvTelemetry? = null
		private set

	private var mapActivity: MapActivity? = null
	private var pollRunning = false

	private val pollRunnable = object : Runnable {
		override fun run() {
			if (!pollRunning) {
				return
			}
			if (bmsClient?.connected == true) {
				if (preferAntProtocol()) {
					bmsClient?.write(AntBmsProtocol.statusRequest())
				} else if (pollCellsNext) {
					bmsClient?.write(JbdBmsProtocol.readCellVoltages())
				} else {
					bmsClient?.write(JbdBmsProtocol.readBasicInfo())
				}
				pollCellsNext = !pollCellsNext
			}
			if (controllerClient?.connected == true) {
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
			publishSample()
			handler.postDelayed(this, activePollIntervalMs())
		}
	}

	override fun getId(): String {
		return OsmAndCustomizationConstants.PLUGIN_EV_BMS
	}

	override fun getName(): String {
		return app.getString(R.string.ev_bms_plugin_name)
	}

	override fun getDescription(linksEnabled: Boolean): CharSequence {
		val html = app.getString(R.string.ev_bms_plugin_description) +
				app.getString(R.string.ev_bms_changelog, EvBmsRevision.GIT_HASH)
		return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
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
		bmsClient = EvBleUartClient(app, EvBleUartClient.Role.BMS, this)
		controllerClient = EvBleUartClient(app, EvBleUartClient.Role.CONTROLLER, this)
		voice.init()
		restoreSessions()
		return true
	}

	override fun disable(app: OsmandApplication) {
		super.disable(app)
		stopPolling()
		recorder.stop()
		voice.shutdown()
		bmsClient?.disconnect()
		controllerClient?.disconnect()
	}

	override fun mapActivityResume(activity: MapActivity) {
		mapActivity = activity
		connectSavedDevices(activity)
		startPolling()
		applyHikeTelemetryState()
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
		val gpsSpeed = if (location != null && location.hasSpeed()) location.speed * 3.6 else null
		val farSpeed = ctrlSpeedKmh()
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

	fun farTripKm(): Double? {
		val odo = ctrlOdometerKm() ?: return null
		val start = farTripStartKm
		if (start == null) {
			farTripStartKm = odo
			return 0.0
		}
		return (odo - start).coerceAtLeast(0.0)
	}

	fun isCharging(): Boolean = charging

	fun chargeRemainingMs(): Long? {
		if (!charging) {
			return null
		}
		val current = chargeFrozenCurrentA ?: return null
		val full = chargeFullAh ?: return null
		val lastAh = chargeLastAh ?: return null
		if (current < 0.4 || full <= 0) {
			return null
		}
		val elapsedH = if (chargeLastAhMs > 0L) {
			(System.currentTimeMillis() - chargeLastAhMs).coerceAtLeast(0L) / 3_600_000.0
		} else {
			0.0
		}
		val estimatedAh = lastAh + current * elapsedH
		val leftAh = (full - estimatedAh).coerceAtLeast(0.0)
		return (leftAh / current * 3_600_000.0).toLong()
	}

	fun chargeHistory(): List<EvHistoryStore.ChargeRecord> = historyStore.parseCharges(CHARGE_HISTORY.get())

	fun tripHistory(): List<EvHistoryStore.ChargeTripRecord> = historyStore.parseTrips(TRIP_HISTORY.get())

	fun chargeTripKm(): Double? {
		if (charging) {
			return 0.0
		}
		if (!CHARGE_CYCLE_ACTIVE.get()) {
			return null
		}
		val trackM = app.savingTrackHelper.distance
		var baseline = CHARGE_END_TRACK_M.get()
		if (trackM + 1f < baseline) {
			baseline = 0
			CHARGE_END_TRACK_M.set(0)
		}
		return ((trackM - baseline).coerceAtLeast(0f) / 1000.0)
	}

	private fun isVehicleMoving(): Boolean {
		val limit = CHARGE_STILL_KMH.get().toDouble()
		val gps = if (lastLocation != null && lastLocation!!.hasSpeed()) {
			lastLocation!!.speed * 3.6
		} else {
			null
		}
		val ctrl = ctrlSpeedKmh()
		return (gps ?: 0.0) >= limit || (ctrl ?: 0.0) >= limit
	}

	private fun updateChargeCycle(
		currentA: Double?,
		remainingAh: Double?,
		fullAh: Double?,
		voltageV: Double?,
		tempC: Double?,
		minCellV: Double?,
		loc: Location?,
		bmsFresh: Boolean
	) {
		val now = System.currentTimeMillis()
		val moving = isVehicleMoving()
		if (moving) {
			stillSinceMs = null
		} else if (stillSinceMs == null) {
			stillSinceMs = now
		}
		val stillMs = CHARGE_STILL_SEC.get().toLong().coerceAtLeast(15L) * 1000L
		val stillLongEnough = stillSinceMs != null && now - stillSinceMs!! >= stillMs
		val minA = CHARGE_CURRENT_A.get().toDouble().coerceAtLeast(1.0)
		val absI = currentA?.let { kotlin.math.abs(it) } ?: 0.0
		val intoPack = bmsFresh && currentA != null && absI >= minA &&
				(lastChargeAh == null || remainingAh == null || remainingAh >= lastChargeAh!! - 0.02)
		val chargeLike = stillLongEnough && intoPack
		if (chargeLike) {
			chargeHold++
			chargeExitHold = 0
			if (!charging && chargeHold >= CHARGE_HOLD_SAMPLES) {
				beginCharge(now, remainingAh, fullAh, absI, tempC, loc)
			}
		} else if (charging) {
			chargeHold = 0
			if (bmsFresh) {
				val idle = currentA == null || absI < minA * 0.4
				val discharging = remainingAh != null && lastChargeAh != null &&
						lastChargeAh!! - remainingAh >= 0.02
				val full = remainingAh != null && fullAh != null && remainingAh >= fullAh - 0.05
				if (idle || discharging || moving || full) {
					chargeExitHold++
					if (chargeExitHold >= CHARGE_HOLD_SAMPLES) {
						finishCharge(now, remainingAh, tempC, loc)
					}
				} else {
					chargeExitHold = 0
				}
			} else {
				chargeExitHold = 0
				val eta = chargeRemainingMs()
				if (eta != null && eta <= 0L) {
					finishCharge(now, estimatedRemainingAh(), tempC, loc)
				}
			}
		} else {
			chargeHold = 0
			chargeExitHold = 0
		}
		if (charging) {
			if (bmsFresh) {
				if (remainingAh != null) {
					chargeLastAh = remainingAh
					chargeLastAhMs = now
				}
				if (absI >= 0.4) {
					chargeFrozenCurrentA = absI
				}
				if (fullAh != null && fullAh > 0) {
					chargeFullAh = fullAh
				}
			}
			persistChargeSession()
		} else {
			updateTripSession(now, moving, voltageV, tempC, minCellV)
		}
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
		charging = true
		CHARGE_CYCLE_ACTIVE.set(false)
		chargeStartMs = now
		chargeStartAh = remainingAh
		chargeStartTempC = tempC
		chargeStartLat = loc?.latitude
		chargeStartLon = loc?.longitude
		chargeFrozenCurrentA = currentA.takeIf { it >= 0.4 }
		chargeFullAh = fullAh
		chargeLastAh = remainingAh
		chargeLastAhMs = now
		persistChargeSession()
		markGpxEvent(
			app.getString(R.string.ev_bms_gpx_charge_start),
			chargeEventDescription(start = true, remainingAh, tempC, null)
		)
	}

	private fun finishCharge(now: Long, remainingAh: Double?, tempC: Double?, loc: Location?) {
		val startAh = chargeStartAh
		val chargedAh = if (startAh != null && remainingAh != null) {
			(remainingAh - startAh).coerceAtLeast(0.0)
		} else {
			null
		}
		val record = EvHistoryStore.ChargeRecord(
			startMs = if (chargeStartMs > 0L) chargeStartMs else now,
			endMs = now,
			startTempC = chargeStartTempC,
			endTempC = tempC,
			chargedAh = chargedAh,
			startLat = chargeStartLat,
			startLon = chargeStartLon,
			endLat = loc?.latitude,
			endLon = loc?.longitude
		)
		saveChargeRecord(record)
		markGpxEvent(
			app.getString(R.string.ev_bms_gpx_charge_end),
			chargeEventDescription(start = false, remainingAh, tempC, chargedAh)
		)
		charging = false
		chargeExitHold = 0
		CHARGE_CYCLE_ACTIVE.set(true)
		CHARGE_END_TRACK_M.set(app.savingTrackHelper.distance.toInt().coerceAtLeast(0))
		CHARGE_SESSION.set("")
		clearChargeRuntime()
		startTripSession(now, loc)
		if (ANNOUNCE_CHARGE_ETA.get()) {
			voice.onChargeFinished()
		}
	}

	private fun startTripSession(now: Long, loc: Location?) {
		tripStartMs = now
		tripStartVoltageV = lastBms?.voltageV ?: ctrlVoltageV()
		tripStartTempC = batteryAnnounceTempC()
		tripStartLat = loc?.latitude
		tripStartLon = loc?.longitude
		tripMinCellV = minCellVoltageV
		tripLastVoltageV = tripStartVoltageV
		tripLastTempC = tripStartTempC
		tripMovingMs = 0L
		tripLastMoveMs = 0L
		persistTripSession()
	}

	private fun updateTripSession(
		now: Long,
		moving: Boolean,
		voltageV: Double?,
		tempC: Double?,
		minCellV: Double?
	) {
		if (!CHARGE_CYCLE_ACTIVE.get() || tripStartMs <= 0L) {
			return
		}
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
		persistTripSession()
	}

	private fun finishTrip(now: Long, loc: Location?) {
		if (tripStartMs <= 0L && !CHARGE_CYCLE_ACTIVE.get()) {
			return
		}
		val start = if (tripStartMs > 0L) tripStartMs else now
		val record = EvHistoryStore.ChargeTripRecord(
			startMs = start,
			endMs = now,
			startVoltageV = tripStartVoltageV,
			endVoltageV = tripLastVoltageV ?: lastBms?.voltageV,
			minCellV = tripMinCellV ?: minCellVoltageV,
			startTempC = tripStartTempC,
			endTempC = tripLastTempC ?: batteryAnnounceTempC(),
			distanceKm = chargeTripKm(),
			movingMs = tripMovingMs,
			startLat = tripStartLat,
			startLon = tripStartLon,
			endLat = loc?.latitude,
			endLon = loc?.longitude
		)
		if (record.distanceKm != null && record.distanceKm > 0.02 || record.movingMs > 30_000L) {
			saveTripRecord(record)
			markGpxEvent(
				app.getString(R.string.ev_bms_gpx_charge_trip),
				tripEventDescription(record)
			)
		}
		TRIP_SESSION.set("")
		tripStartMs = 0L
		tripMovingMs = 0L
		tripLastMoveMs = 0L
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
		chargeStartLat = null
		chargeStartLon = null
		chargeFrozenCurrentA = null
		chargeFullAh = null
		chargeLastAh = null
		chargeLastAhMs = 0L
	}

	private fun persistChargeSession() {
		if (!charging || chargeStartMs <= 0L) {
			return
		}
		val json = JSONObject()
		json.put("startMs", chargeStartMs)
		json.putD("startAh", chargeStartAh)
		json.putD("startTempC", chargeStartTempC)
		json.putD("startLat", chargeStartLat)
		json.putD("startLon", chargeStartLon)
		json.putD("currentA", chargeFrozenCurrentA)
		json.putD("fullAh", chargeFullAh)
		json.putD("lastAh", chargeLastAh)
		json.put("lastAhMs", chargeLastAhMs)
		CHARGE_SESSION.set(json.toString())
	}

	private fun persistTripSession() {
		if (tripStartMs <= 0L) {
			return
		}
		val json = JSONObject()
		json.put("startMs", tripStartMs)
		json.putD("startVoltageV", tripStartVoltageV)
		json.putD("startTempC", tripStartTempC)
		json.putD("startLat", tripStartLat)
		json.putD("startLon", tripStartLon)
		json.putD("minCellV", tripMinCellV)
		json.putD("lastVoltageV", tripLastVoltageV)
		json.putD("lastTempC", tripLastTempC)
		json.put("movingMs", tripMovingMs)
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
				chargeStartLat = json.optNullableDouble("startLat")
				chargeStartLon = json.optNullableDouble("startLon")
				chargeFrozenCurrentA = json.optNullableDouble("currentA")
				chargeFullAh = json.optNullableDouble("fullAh")
				chargeLastAh = json.optNullableDouble("lastAh")
				chargeLastAhMs = json.optLong("lastAhMs")
				if (chargeStartMs > 0L) {
					charging = true
					CHARGE_CYCLE_ACTIVE.set(false)
				}
			} catch (_: Exception) {
			}
		}
		val tripRaw = TRIP_SESSION.get()
		if (!charging && !tripRaw.isNullOrBlank()) {
			try {
				val json = JSONObject(tripRaw)
				tripStartMs = json.optLong("startMs")
				tripStartVoltageV = json.optNullableDouble("startVoltageV")
				tripStartTempC = json.optNullableDouble("startTempC")
				tripStartLat = json.optNullableDouble("startLat")
				tripStartLon = json.optNullableDouble("startLon")
				tripMinCellV = json.optNullableDouble("minCellV")
				tripLastVoltageV = json.optNullableDouble("lastVoltageV")
				tripLastTempC = json.optNullableDouble("lastTempC")
				tripMovingMs = json.optLong("movingMs")
			} catch (_: Exception) {
			}
		}
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

	private fun saveChargeRecord(row: EvHistoryStore.ChargeRecord) {
		val rows = chargeHistory() + row
		CHARGE_HISTORY.set(historyStore.encodeCharges(rows))
		historyStore.appendChargeCsv(row)
	}

	private fun saveTripRecord(row: EvHistoryStore.ChargeTripRecord) {
		val rows = tripHistory() + row
		TRIP_HISTORY.set(historyStore.encodeTrips(rows))
		historyStore.appendTripCsv(row)
	}

	private fun markGpxEvent(name: String, description: String) {
		pendingGpxEvents.add(name to description)
	}

	private fun chargeEventDescription(
		start: Boolean,
		remainingAh: Double?,
		tempC: Double?,
		chargedAh: Double?
	): String {
		val parts = ArrayList<String>()
		parts.add(app.getString(if (start) R.string.ev_bms_gpx_charge_start else R.string.ev_bms_gpx_charge_end))
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
		if (row.startVoltageV != null && row.endVoltageV != null) {
			parts.add(String.format(Locale.US, "%.1f→%.1f V", row.startVoltageV, row.endVoltageV))
		}
		if (row.minCellV != null) {
			parts.add(String.format(Locale.US, "min %.3f V", row.minCellV))
		}
		return parts.joinToString(" · ")
	}

	private fun buildStopReport(bmsFresh: Boolean, ctrlFresh: Boolean): EvVoiceAnnouncer.StopReport? {
		if (!bmsFresh) {
			return null
		}
		val range = rangeEstimator.remainingRangeKm ?: return null
		return EvVoiceAnnouncer.StopReport(
			rangeKm = range,
			routeLeftKm = getRouteLeftKm(),
			minCellV = minCellVoltageV,
			motorTempC = if (ctrlFresh) ctrlMotorTempC() else null,
			batteryTempC = batteryAnnounceTempC(),
			controllerTempC = if (ctrlFresh) ctrlTempC() else null
		)
	}

	private fun updateRestMetrics(currentA: Double?, packVoltageV: Double?, cells: List<Double>?) {
		if (currentA == null || kotlin.math.abs(currentA) > REST_CURRENT_A) {
			return
		}
		if (packVoltageV != null && packVoltageV > 0) {
			restPackVoltageV = packVoltageV
		}
		val minCell = cells?.minOrNull()
		if (minCell != null && minCell > 0) {
			minCellVoltageV = minCell
		}
	}

	fun remainingRouteElevation(): RangeEstimator.RouteElevation? {
		val helper = app.routingHelper
		if (!helper.isRouteCalculated) {
			return null
		}
		val remainingKm = helper.leftDistance / 1000.0
		if (remainingKm <= 0) {
			return null
		}
		var climb = 0.0
		var descent = 0.0
		var prevAlt: Double? = null
		for (point in helper.route.routeLocations) {
			if (!point.hasAltitude()) {
				continue
			}
			val alt = point.altitude
			val previous = prevAlt
			if (previous != null) {
				val delta = alt - previous
				if (delta > 0) {
					climb += delta
				} else {
					descent += -delta
				}
			}
			prevAlt = alt
		}
		return RangeEstimator.RouteElevation(remainingKm, climb, descent)
	}

	fun getRouteLeftKm(): Double? {
		val helper = app.routingHelper
		if (!helper.isRouteCalculated) {
			return null
		}
		val meters = helper.leftDistance
		if (meters <= 0) {
			return null
		}
		return meters / 1000.0
	}

	fun startBmsScan(activity: Activity) {
		bmsClient?.startScan(activity)
	}

	fun startControllerScan(activity: Activity) {
		controllerClient?.startScan(activity)
	}

	fun stopScans() {
		bmsClient?.stopScan()
		controllerClient?.stopScan()
	}

	fun connectBms(activity: Activity, name: String, address: String) {
		BMS_NAME.set(name)
		BMS_ADDRESS.set(address)
		bmsClient?.preferredBmsKind = preferredBmsKind()
		bmsClient?.connect(activity, address)
	}

	fun connectController(activity: Activity, name: String, address: String) {
		CONTROLLER_NAME.set(name)
		CONTROLLER_ADDRESS.set(address)
		controllerClient?.preferredControllerKind = preferredControllerKind()
		controllerClient?.connect(activity, address)
	}

	fun disconnectBms() {
		bmsClient?.disconnect()
	}

	fun disconnectController() {
		controllerClient?.disconnect()
	}

	fun isBmsConnected(): Boolean = bmsClient?.connected == true

	fun isControllerConnected(): Boolean = controllerClient?.connected == true

	fun isBmsFresh(): Boolean {
		return isBmsConnected() && lastBmsRxMs > 0L &&
				System.currentTimeMillis() - lastBmsRxMs <= DATA_STALE_MS
	}

	fun isControllerFresh(): Boolean {
		return isControllerConnected() && lastCtrlRxMs > 0L &&
				System.currentTimeMillis() - lastCtrlRxMs <= DATA_STALE_MS
	}

	fun connectSavedDevices(activity: Activity) {
		val bms = BMS_ADDRESS.get()
		if (!bms.isNullOrEmpty() && bmsClient?.connected != true) {
			bmsClient?.preferredBmsKind = preferredBmsKind()
			bmsClient?.connect(activity, bms)
		}
		val ctrl = CONTROLLER_ADDRESS.get()
		if (!ctrl.isNullOrEmpty() && controllerClient?.connected != true) {
			controllerClient?.preferredControllerKind = preferredControllerKind()
			controllerClient?.connect(activity, ctrl)
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

	private fun stopPolling() {
		pollRunning = false
		handler.removeCallbacks(pollRunnable)
	}

	override fun onConnectionChanged(role: EvBleUartClient.Role, connected: Boolean, name: String?) {
		val label = name ?: role.name
		if (connected) {
			app.showToastMessage(app.getString(R.string.ev_bms_connected, label))
			if (role == EvBleUartClient.Role.CONTROLLER) {
				farTripStartKm = null
				farStatusStarted = false
				vescPollSetup = false
				vescSnapshot.reset()
			}
			startPolling()
			applyHikeTelemetryState()
		} else {
			app.showToastMessage(app.getString(R.string.ev_bms_disconnected, label))
		}
	}

	override fun onBytes(role: EvBleUartClient.Role, data: ByteArray) {
		if (role == EvBleUartClient.Role.BMS) {
			bmsBuffer += data
			drainBmsBuffer()
		} else {
			controllerBuffer += data
			drainControllerBuffer()
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
					voice.onSoc(info.socPercent, info.voltageV, SOC_STEP_PERCENT.get(), ANNOUNCE_SOC.get())
				}
			} else {
				val (frames, rest) = JbdBmsProtocol.extractFrames(bmsBuffer)
				bmsBuffer = rest
				for (frame in frames) {
					val info = JbdBmsProtocol.parseBasicInfo(frame)
					if (info != null) {
						lastBms = info.toSnapshot()
						lastBmsRxMs = System.currentTimeMillis()
						updateRestMetrics(info.currentA, info.voltageV, lastCells)
						voice.onSoc(info.socPercent, info.voltageV, SOC_STEP_PERCENT.get(), ANNOUNCE_SOC.get())
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

	interface DeviceScanListener {
		fun onDeviceFound(role: EvBleUartClient.Role, name: String, address: String)
		fun onScanFinished(role: EvBleUartClient.Role)
	}

	var scanListener: DeviceScanListener? = null

	override fun onDeviceFound(role: EvBleUartClient.Role, name: String, address: String) {
		handler.post { scanListener?.onDeviceFound(role, name, address) }
	}

	override fun onScanFinished(role: EvBleUartClient.Role) {
		handler.post { scanListener?.onScanFinished(role) }
	}

	private fun publishSample() {
		val bms = lastBms
		val loc = lastLocation
		val speed = if (loc != null && loc.hasSpeed()) loc.speed * 3.6 else null
		val remainingAh = bms?.remainingMah?.div(1000.0)
		val bmsFresh = isBmsFresh()
		val ctrlFresh = isControllerFresh()
		updateRestMetrics(bms?.currentA ?: ctrlCurrentA(), bms?.voltageV ?: ctrlVoltageV(), lastCells)
		updateChargeCycle(
			currentA = bms?.currentA,
			remainingAh = remainingAh,
			fullAh = bms?.fullMah?.div(1000.0),
			voltageV = bms?.voltageV ?: ctrlVoltageV(),
			tempC = batteryAnnounceTempC(),
			minCellV = minCellVoltageV,
			loc = loc,
			bmsFresh = bmsFresh
		)
		rangeEstimator.add(
			System.currentTimeMillis(),
			remainingAh,
			bms?.voltageV ?: ctrlVoltageV(),
			restPackVoltageV,
			loc,
			ctrlOdometerKm(),
			minCellVoltageV,
			bms?.temperaturesC?.minOrNull()?.toDouble(),
			bms?.fullMah?.div(1000.0),
			ctrlAvgWhPerKm(),
			remainingRouteElevation(),
			USE_ROUTE_PROFILE.get()
		)
		val sample = EvTelemetry(
			lat = loc?.latitude,
			lon = loc?.longitude,
			gpsSpeedKmh = speed,
			socPercent = bms?.socPercent,
			voltageV = bms?.voltageV ?: ctrlVoltageV(),
			currentA = bms?.currentA ?: ctrlCurrentA(),
			remainingAh = remainingAh,
			fullAh = bms?.fullMah?.div(1000.0),
			bmsTempC = batteryAnnounceTempC(),
			cycles = bms?.cycles,
			minCellVoltageV = minCellVoltageV,
			controllerVoltageV = ctrlVoltageV(),
			controllerCurrentA = ctrlCurrentA(),
			controllerPowerW = ctrlPowerW(),
			rpm = ctrlRpm(),
			gear = farSnapshot.gear,
			motorTempC = ctrlMotorTempC(),
			controllerTempC = ctrlTempC(),
			remainingRangeKm = rangeEstimator.remainingRangeKm,
			consumptionAhPerKm = rangeEstimator.consumptionAhPerKm,
			consumptionWhPerKm = rangeEstimator.consumptionWhPerKm,
			coverageWhPerKm = rangeEstimator.coverageWhPerKm,
			weakCellFactor = rangeEstimator.weakCellFactor,
			farOdometerKm = ctrlOdometerKm(),
			farTripKm = farTripKm(),
			chargeTripKm = chargeTripKm(),
			farSpeedKmh = ctrlSpeedKmh(),
			farAvgWhPerKm = ctrlAvgWhPerKm(),
			gpsUnreliable = rangeEstimator.gpsUnreliable,
			usedFarDriverDistance = rangeEstimator.usedFarDriverDistance
		)
		val bmsFreshAfter = isBmsFresh()
		val ctrlFreshAfter = isControllerFresh()
		latestTelemetry = sample
		val events = ArrayList(pendingGpxEvents)
		pendingGpxEvents.clear()
		if (recorder.isRecording) {
			if (events.isEmpty()) {
				recorder.append(sample)
			} else {
				for ((name, desc) in events) {
					recorder.appendNamedPoint(sample, name, desc)
				}
			}
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
			ANNOUNCE_RANGE_ON_STOP.get() && bmsFresh
		)
		voice.onRangeVsRoute(
			if (bmsFresh) sample.remainingRangeKm else null,
			getRouteLeftKm(),
			ANNOUNCE_RANGE_VS_ROUTE.get() && bmsFresh
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
		voice.onChargeEta(chargeRemainingMs(), CHARGE_ETA_REPEAT_MS, ANNOUNCE_CHARGE_ETA.get() && charging)
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

	private fun ctrlOdometerKm(): Double? = vescSnapshot.odometerKm ?: farSnapshot.odometerKm

	private fun ctrlSpeedKmh(): Double? = vescSnapshot.speedKmh ?: farSnapshot.speedKmh

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
		val field = when (widgetType) {
			WidgetType.EV_BMS_SOC -> EvBmsTextWidget.Field.SOC
			WidgetType.EV_BMS_RANGE -> EvBmsTextWidget.Field.RANGE
			WidgetType.EV_BMS_CONSUMPTION -> EvBmsTextWidget.Field.CONSUMPTION
			WidgetType.EV_FAR_TRIP -> EvBmsTextWidget.Field.FAR_TRIP
			WidgetType.EV_CHARGE_TRIP -> EvBmsTextWidget.Field.CHARGE_TRIP
			WidgetType.EV_CHARGE_ETA -> EvBmsTextWidget.Field.CHARGE_ETA
			WidgetType.EV_BMS_VOLTAGE -> EvBmsTextWidget.Field.VOLTAGE
			WidgetType.EV_BMS_CURRENT -> EvBmsTextWidget.Field.CURRENT
			WidgetType.EV_BMS_POWER -> EvBmsTextWidget.Field.POWER
			WidgetType.EV_BATTERY_TEMP -> EvBmsTextWidget.Field.BATTERY_TEMP
			WidgetType.EV_MOTOR_TEMP -> EvBmsTextWidget.Field.MOTOR_TEMP
			WidgetType.EV_CONTROLLER_TEMP -> EvBmsTextWidget.Field.CONTROLLER_TEMP
			else -> return null
		}
		return EvBmsTextWidget(mapActivity, widgetType, field, customId, widgetsPanel)
	}

	fun isHikeMode(): Boolean = hikeMode.isEnabled()

	fun toggleHikeMode(mapActivity: MapActivity) {
		hikeMode.toggle(mapActivity)
	}

	fun applyHikeTelemetryState() {
		if (!RECORD_TELEMETRY.get()) {
			recorder.stop()
			return
		}
		recorder.setFolderUri(CSV_FOLDER_URI.get())
		recorder.setFields(selectedTelemetryFields())
		recorder.setWriteGpx(RECORD_GPX.get())
		if (!recorder.isRecording) {
			recorder.start()
		}
	}

	fun selectedTelemetryFields(): List<TelemetryField> = TelemetryField.parse(TELEMETRY_FIELDS.get())

	fun setTelemetryFields(selected: List<TelemetryField>) {
		if (selected.isEmpty()) {
			return
		}
		TELEMETRY_FIELDS.set(selected.joinToString(",") { it.id })
		if (recorder.isRecording) {
			recorder.stop()
			applyHikeTelemetryState()
		}
	}

	fun restartTelemetryIfRecording() {
		if (recorder.isRecording) {
			recorder.stop()
			applyHikeTelemetryState()
		}
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
		if (recorder.isRecording) {
			recorder.stop()
			applyHikeTelemetryState()
		}
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

	fun shareCsv(activity: Activity, uris: List<android.net.Uri>) {
		recorder.share(activity, uris)
	}

	fun askShowSettingsDialog(activity: FragmentActivity) {
		EvBmsSettingsBottomSheet.showInstance(activity.supportFragmentManager)
	}

	fun isTelemetryRecording(): Boolean = recorder.isRecording

	fun startTelemetryRecording(): Boolean {
		RECORD_TELEMETRY.set(true)
		applyHikeTelemetryState()
		return recorder.isRecording
	}

	fun stopTelemetryRecording() {
		RECORD_TELEMETRY.set(false)
		recorder.stop()
	}

	private fun activePollIntervalMs(): Long {
		val poll = POLL_INTERVAL_MS.get().toLong().coerceAtLeast(500L)
		return if (isHikeMode()) {
			poll.coerceAtLeast(HikeModeController.HIKE_POLL_MS.toLong())
		} else {
			poll
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
