package net.osmand.plus.plugins.evbms

import android.app.Activity
import androidx.core.text.HtmlCompat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
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
	val HIKE_MODE: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_hike_mode", false).makeGlobal().makeShared()
	val HIKE_SNAPSHOT: CommonPreference<String> =
		registerStringPreference("ev_bms_hike_snapshot", "").makeGlobal()
	private val CHARGE_END_TRACK_M: CommonPreference<Int> =
		registerIntPreference("ev_bms_charge_end_track_m", 0).makeGlobal()
	private val CHARGE_CYCLE_ACTIVE: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_charge_cycle_active", false).makeGlobal()

	private val handler = Handler(Looper.getMainLooper())
	private val rangeEstimator = RangeEstimator()
	private val recorder = TelemetryRecorder(app)
	private val voice = EvVoiceAnnouncer(app)
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

	private fun updateChargeCycle(currentA: Double?, remainingAh: Double?) {
		val now = System.currentTimeMillis()
		if (isVehicleMoving()) {
			stillSinceMs = null
		} else if (stillSinceMs == null) {
			stillSinceMs = now
		}
		val stillMs = CHARGE_STILL_SEC.get().toLong().coerceAtLeast(15L) * 1000L
		val stillLongEnough = stillSinceMs != null && now - stillSinceMs!! >= stillMs
		val minA = CHARGE_CURRENT_A.get().toDouble().coerceAtLeast(1.0)
		val absI = currentA?.let { kotlin.math.abs(it) } ?: 0.0
		val intoPack = currentA != null && absI >= minA &&
				(lastChargeAh == null || remainingAh == null || remainingAh >= lastChargeAh!! - 0.02)
		val chargeLike = stillLongEnough && intoPack
		if (chargeLike) {
			chargeHold++
			chargeExitHold = 0
			if (!charging && chargeHold >= CHARGE_HOLD_SAMPLES) {
				charging = true
				CHARGE_CYCLE_ACTIVE.set(false)
			}
		} else if (charging) {
			chargeHold = 0
			val idle = currentA == null || absI < minA * 0.4
			val discharging = remainingAh != null && lastChargeAh != null &&
					lastChargeAh!! - remainingAh >= 0.02
			if (idle || discharging || isVehicleMoving()) {
				chargeExitHold++
				if (chargeExitHold >= CHARGE_HOLD_SAMPLES) {
					charging = false
					chargeExitHold = 0
					CHARGE_CYCLE_ACTIVE.set(true)
					CHARGE_END_TRACK_M.set(app.savingTrackHelper.distance.toInt().coerceAtLeast(0))
				}
			} else {
				chargeExitHold = 0
			}
		} else {
			chargeHold = 0
			chargeExitHold = 0
		}
		if (remainingAh != null) {
			lastChargeAh = remainingAh
		}
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
					voice.onSoc(info.socPercent, SOC_STEP_PERCENT.get(), ANNOUNCE_SOC.get())
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
						voice.onSoc(info.socPercent, SOC_STEP_PERCENT.get(), ANNOUNCE_SOC.get())
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
		updateRestMetrics(bms?.currentA ?: ctrlCurrentA(), bms?.voltageV ?: ctrlVoltageV(), lastCells)
		updateChargeCycle(bms?.currentA, remainingAh)
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
		val bmsFresh = isBmsFresh()
		val ctrlFresh = isControllerFresh()
		latestTelemetry = sample
		if (recorder.isRecording) {
			recorder.append(sample)
		}
		voice.onLink(
			bmsUp = bmsFresh,
			controllerUp = ctrlFresh,
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
