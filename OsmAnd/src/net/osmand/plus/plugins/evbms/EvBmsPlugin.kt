package net.osmand.plus.plugins.evbms

import android.app.Activity
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
import net.osmand.plus.plugins.evbms.protocol.FarDriverProtocol
import net.osmand.plus.plugins.evbms.protocol.JbdBmsProtocol
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
	val ANNOUNCE_SOC: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_announce_soc", true).makeGlobal().makeShared()
	val SOC_STEP_PERCENT: CommonPreference<Int> =
		registerIntPreference("ev_bms_soc_step_percent", DEFAULT_SOC_STEP).makeGlobal().makeShared()
	val ANNOUNCE_RANGE_ON_STOP: CommonPreference<Boolean> =
		registerBooleanPreference("ev_bms_announce_range_on_stop", true).makeGlobal().makeShared()
	val STOP_SPEED_KMH: CommonPreference<Int> =
		registerIntPreference("ev_bms_stop_speed_kmh", DEFAULT_STOP_SPEED).makeGlobal().makeShared()

	private val handler = Handler(Looper.getMainLooper())
	private val rangeEstimator = RangeEstimator()
	private val recorder = TelemetryRecorder(app)
	private val voice = EvVoiceAnnouncer(app)
	private val farSnapshot = FarDriverProtocol.FarDriverSnapshot()

	private var bmsClient: EvBleUartClient? = null
	private var controllerClient: EvBleUartClient? = null
	private var bmsBuffer = ByteArray(0)
	private var controllerBuffer = ByteArray(0)
	private var lastBms: JbdBmsProtocol.JbdBasicInfo? = null
	private var lastLocation: Location? = null
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
				bmsClient?.write(JbdBmsProtocol.readBasicInfo())
			}
			publishSample()
			handler.postDelayed(this, POLL_INTERVAL_MS.get().toLong().coerceAtLeast(500L))
		}
	}

	override fun getId(): String {
		return OsmAndCustomizationConstants.PLUGIN_EV_BMS
	}

	override fun getName(): String {
		return app.getString(R.string.ev_bms_plugin_name)
	}

	override fun getDescription(linksEnabled: Boolean): CharSequence {
		return app.getString(R.string.ev_bms_plugin_description)
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
		if (RECORD_TELEMETRY.get() && !recorder.isRecording) {
			recorder.start()
		}
	}

	override fun mapActivityPause(activity: MapActivity) {
		if (mapActivity === activity) {
			mapActivity = null
		}
	}

	override fun updateLocation(location: Location?) {
		lastLocation = location
		val remainingAh = lastBms?.remainingMah?.div(1000.0)
		val voltageV = lastBms?.voltageV ?: farSnapshot.voltageV
		rangeEstimator.add(System.currentTimeMillis(), remainingAh, voltageV, location)
		val speed = if (location != null && location.hasSpeed()) location.speed * 3.6 else null
		voice.onMotion(
			speed,
			rangeEstimator.remainingRangeKm,
			getRouteLeftKm(),
			STOP_SPEED_KMH.get().toDouble(),
			ANNOUNCE_RANGE_ON_STOP.get()
		)
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
		bmsClient?.connect(activity, address)
	}

	fun connectController(activity: Activity, name: String, address: String) {
		CONTROLLER_NAME.set(name)
		CONTROLLER_ADDRESS.set(address)
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

	fun connectSavedDevices(activity: Activity) {
		val bms = BMS_ADDRESS.get()
		if (!bms.isNullOrEmpty() && bmsClient?.connected != true) {
			bmsClient?.connect(activity, bms)
		}
		val ctrl = CONTROLLER_ADDRESS.get()
		if (!ctrl.isNullOrEmpty() && controllerClient?.connected != true) {
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
				controllerClient?.write(FarDriverProtocol.startStatusCommand())
			}
			startPolling()
			if (RECORD_TELEMETRY.get() && !recorder.isRecording) {
				recorder.start()
			}
		} else {
			app.showToastMessage(app.getString(R.string.ev_bms_disconnected, label))
		}
	}

	override fun onBytes(role: EvBleUartClient.Role, data: ByteArray) {
		if (role == EvBleUartClient.Role.BMS) {
			bmsBuffer += data
			val (frames, rest) = JbdBmsProtocol.extractFrames(bmsBuffer)
			bmsBuffer = rest
			for (frame in frames) {
				val info = JbdBmsProtocol.parseBasicInfo(frame) ?: continue
				lastBms = info
				voice.onSoc(info.socPercent, SOC_STEP_PERCENT.get(), ANNOUNCE_SOC.get())
			}
		} else {
			controllerBuffer += data
			val (frames, rest) = FarDriverProtocol.extractFrames(controllerBuffer)
			controllerBuffer = rest
			for (frame in frames) {
				FarDriverProtocol.parseFrame(frame, farSnapshot)
			}
		}
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
		val sample = EvTelemetry(
			lat = loc?.latitude,
			lon = loc?.longitude,
			gpsSpeedKmh = speed,
			socPercent = bms?.socPercent,
			voltageV = bms?.voltageV ?: farSnapshot.voltageV,
			currentA = bms?.currentA ?: farSnapshot.lineCurrentA,
			remainingAh = remainingAh,
			fullAh = bms?.fullMah?.div(1000.0),
			bmsTempC = bms?.temperaturesC?.maxOrNull()?.toDouble(),
			cycles = bms?.cycles,
			controllerVoltageV = farSnapshot.voltageV,
			controllerCurrentA = farSnapshot.lineCurrentA,
			controllerPowerW = farSnapshot.powerW,
			rpm = farSnapshot.rawRpm,
			gear = farSnapshot.gear,
			motorTempC = farSnapshot.motorTempC,
			controllerTempC = farSnapshot.controllerTempC,
			remainingRangeKm = rangeEstimator.remainingRangeKm,
			consumptionAhPerKm = rangeEstimator.consumptionAhPerKm,
			consumptionWhPerKm = rangeEstimator.consumptionWhPerKm
		)
		latestTelemetry = sample
		if (recorder.isRecording) {
			recorder.append(sample)
		}
	}

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
		val field = when (widgetType) {
			WidgetType.EV_BMS_SOC -> EvBmsTextWidget.Field.SOC
			WidgetType.EV_BMS_RANGE -> EvBmsTextWidget.Field.RANGE
			WidgetType.EV_BMS_CONSUMPTION -> EvBmsTextWidget.Field.CONSUMPTION
			WidgetType.EV_BMS_VOLTAGE -> EvBmsTextWidget.Field.VOLTAGE
			WidgetType.EV_BMS_CURRENT -> EvBmsTextWidget.Field.CURRENT
			WidgetType.EV_BMS_POWER -> EvBmsTextWidget.Field.POWER
			WidgetType.EV_MOTOR_TEMP -> EvBmsTextWidget.Field.MOTOR_TEMP
			WidgetType.EV_CONTROLLER_TEMP -> EvBmsTextWidget.Field.CONTROLLER_TEMP
			else -> return null
		}
		return EvBmsTextWidget(mapActivity, widgetType, field, customId, widgetsPanel)
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
