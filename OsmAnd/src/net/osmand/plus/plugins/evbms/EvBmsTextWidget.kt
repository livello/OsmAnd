package net.osmand.plus.plugins.evbms

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.helpers.AndroidUiHelper
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.settings.backend.ApplicationMode
import net.osmand.plus.settings.backend.preferences.CommonPreference
import net.osmand.plus.utils.ColorUtilities
import net.osmand.plus.utils.OsmAndFormatter
import net.osmand.plus.views.layers.base.OsmandMapLayer.DrawSettings
import net.osmand.plus.views.mapwidgets.WidgetType
import net.osmand.plus.views.mapwidgets.WidgetsPanel
import net.osmand.plus.views.mapwidgets.appearance.ResolvedPanelAppearance
import net.osmand.plus.views.mapwidgets.widgets.MapWidget
import net.osmand.plus.views.mapwidgets.widgets.SimpleWidget
import net.osmand.plus.widgets.popup.PopUpMenuItem
import net.osmand.util.Algorithms
import java.util.Locale

class EvBmsTextWidget(
	mapActivity: MapActivity,
	widgetType: WidgetType,
	private val field: Field,
	customId: String?,
	widgetsPanel: WidgetsPanel?
) : SimpleWidget(mapActivity, widgetType, customId, widgetsPanel) {

	enum class Field {
		SOC, RANGE, RANGE_WINDOW, RANGE_PNZ, RANGE_RESERVE, CONSUMPTION, FAR_TRIP, CHARGE_TRIP, CHARGE_ETA, CHARGE_TIME, CHARGE_ENERGY, VOLTAGE, MIN_CELL, CURRENT, POWER, BATTERY_TEMP, MOTOR_TEMP, CONTROLLER_TEMP, TIME, LAT, LON, GPS_SPEED, SOC_OCV, REMAINING_AH, FULL_AH, CYCLES, MAX_CELL, IMBALANCE, CTRL_VOLTAGE, CTRL_CURRENT, RPM, GEAR, ODOMETER, CTRL_SPEED, WHEEL_SPEED, WHEEL_ODO, CADENCE, USED_AH, COVERAGE, STOP_TIME;

		fun controllerLink(): Boolean {
			return this == FAR_TRIP || this == POWER || this == MOTOR_TEMP || this == CONTROLLER_TEMP ||
					this == CTRL_VOLTAGE || this == CTRL_CURRENT || this == RPM || this == GEAR ||
					this == ODOMETER || this == CTRL_SPEED
		}

		fun wheelLink(): Boolean {
			return this == WHEEL_SPEED || this == WHEEL_ODO
		}

		fun cadenceLink(): Boolean = this == CADENCE
	}

	companion object {
		private const val COMPACT_PREF_ID = "ev_bms_widget_compact_"
		private val CLOCK = java.text.SimpleDateFormat("HH:mm:ss", Locale.US)
	}

	private val plugin = PluginsHelper.requirePlugin(EvBmsPlugin::class.java)
	private val compactPref: CommonPreference<Boolean> = registerCompactPref(customId)
	private var cacheText: String? = null
	private var cacheCompact: Boolean? = null

	init {
		setText(NO_VALUE, null)
		setIcons(widgetType)
		updateWidgetView()
		EvWidgetChrome.applySideLayout(view, panel)
		applyLinkFrame()
		EvWidgetChrome.bindPanelTap(this)
	}

	override fun attachView(
		container: ViewGroup,
		panel: WidgetsPanel,
		followingWidgets: MutableList<MapWidget>
	) {
		EvWidgetChrome.attach(this, container, panel)
	}

	override fun recreateViewInternal() {
		super.recreateViewInternal()
		EvWidgetChrome.applySideLayout(view, panel)
		applyLinkFrame()
		EvWidgetChrome.bindPanelTap(this)
	}

	override fun updateValueAlign(fullRow: Boolean) {
		if (isVerticalWidget()) {
			super.updateValueAlign(fullRow)
			return
		}
		val gravity = EvWidgetChrome.sideGravity(panel) or Gravity.CENTER_VERTICAL
		textView.setGravity(gravity)
		smallTextView?.setGravity(gravity)
		EvWidgetChrome.applySideLayout(view, panel)
	}

	override fun updateSimpleWidgetInfo(drawSettings: DrawSettings?) {
		val sample = plugin.latestTelemetry
		val text: String = when (field) {
			Field.SOC -> sample?.socPercent?.toString() ?: NO_VALUE
			Field.RANGE -> formatMetricKm(sample?.remainingRangeKm)
			Field.RANGE_WINDOW -> formatMetricKm(sample?.windowRangeKm)
			Field.RANGE_PNZ -> formatMetricKm(sample?.pnzRangeKm)
			Field.RANGE_RESERVE -> formatMetricKm(plugin.rangeReserveKm())
			Field.CONSUMPTION -> sample?.energyWh?.let { String.format(Locale.US, "%.0f", it) } ?: NO_VALUE
			Field.FAR_TRIP -> formatMetricKm(sample?.farTripKm)
			Field.CHARGE_TRIP -> formatMetricKm(sample?.chargeTripKm)
			Field.CHARGE_ETA -> {
				val ms = plugin.chargeRemainingMs()
				if (!plugin.isCharging() || ms == null) {
					NO_VALUE
				} else {
					OsmAndFormatter.getFormattedDurationShort((ms / 1000L).toInt().coerceAtLeast(0))
				}
			}
			Field.CHARGE_TIME -> {
				val ms = plugin.chargeElapsedMs()
				if (!plugin.isCharging() || ms == null) {
					NO_VALUE
				} else {
					OsmAndFormatter.getFormattedDurationShort((ms / 1000L).toInt().coerceAtLeast(0))
				}
			}
			Field.CHARGE_ENERGY -> {
				if (!plugin.isCharging()) {
					NO_VALUE
				} else {
					plugin.chargeEnergyWh()?.let { String.format(Locale.US, "%.0f", it) } ?: NO_VALUE
				}
			}
			Field.VOLTAGE -> sample?.voltageV?.let { String.format(Locale.US, "%.1f", it) } ?: NO_VALUE
			Field.MIN_CELL -> sample?.minCellVoltageV?.let { String.format(Locale.US, "%.3f", it) } ?: NO_VALUE
			Field.CURRENT -> sample?.currentA?.let { String.format(Locale.US, "%.1f", it) } ?: NO_VALUE
			Field.POWER -> {
				val w = sample?.controllerPowerW ?: sample?.let { s ->
					if (s.voltageV != null && s.currentA != null) s.voltageV * s.currentA else null
				}
				w?.let { String.format(Locale.US, "%.0f", it) } ?: NO_VALUE
			}
			Field.BATTERY_TEMP -> sample?.bmsTempC?.let { String.format(Locale.US, "%.0f", it) } ?: NO_VALUE
			Field.MOTOR_TEMP -> sample?.motorTempC?.let { String.format(Locale.US, "%.0f", it) } ?: NO_VALUE
			Field.CONTROLLER_TEMP -> sample?.controllerTempC?.let { String.format(Locale.US, "%.0f", it) } ?: NO_VALUE
			Field.TIME -> sample?.timeMs?.let { CLOCK.format(java.util.Date(it)) } ?: NO_VALUE
			Field.LAT -> sample?.lat?.let { String.format(Locale.US, "%.6f", it) } ?: NO_VALUE
			Field.LON -> sample?.lon?.let { String.format(Locale.US, "%.6f", it) } ?: NO_VALUE
			Field.GPS_SPEED -> sample?.gpsSpeedKmh?.let { String.format(Locale.US, "%.1f", it) } ?: NO_VALUE
			Field.SOC_OCV -> sample?.socVoltagePercent?.toString() ?: NO_VALUE
			Field.REMAINING_AH -> sample?.remainingAh?.let { String.format(Locale.US, "%.2f", it) } ?: NO_VALUE
			Field.FULL_AH -> sample?.fullAh?.let { String.format(Locale.US, "%.2f", it) } ?: NO_VALUE
			Field.CYCLES -> sample?.cycles?.toString() ?: NO_VALUE
			Field.MAX_CELL -> sample?.maxCellVoltageV?.let { String.format(Locale.US, "%.3f", it) } ?: NO_VALUE
			Field.IMBALANCE -> sample?.cellImbalanceV?.let {
				String.format(Locale.US, "%.0f", it * 1000.0)
			} ?: NO_VALUE
			Field.CTRL_VOLTAGE -> sample?.controllerVoltageV?.let { String.format(Locale.US, "%.1f", it) } ?: NO_VALUE
			Field.CTRL_CURRENT -> sample?.controllerCurrentA?.let { String.format(Locale.US, "%.1f", it) } ?: NO_VALUE
			Field.RPM -> sample?.rpm?.toString() ?: NO_VALUE
			Field.GEAR -> sample?.gear?.toString() ?: NO_VALUE
			Field.ODOMETER -> formatMetricKm(sample?.farOdometerKm)
			Field.CTRL_SPEED -> sample?.farSpeedKmh?.let { String.format(Locale.US, "%.1f", it) } ?: NO_VALUE
			Field.WHEEL_SPEED -> sample?.wheelSpeedKmh?.let { String.format(Locale.US, "%.1f", it) } ?: NO_VALUE
			Field.WHEEL_ODO -> formatMetricKm(sample?.wheelOdometerKm)
			Field.CADENCE -> sample?.cadenceRpm?.let { String.format(Locale.US, "%.0f", it) } ?: NO_VALUE
			Field.USED_AH -> sample?.usedAh?.let { String.format(Locale.US, "%.2f", it) } ?: NO_VALUE
			Field.COVERAGE -> sample?.coverageWhPerKm?.let { String.format(Locale.US, "%.0f", it) }
				?: sample?.consumptionWhPerKm?.let { String.format(Locale.US, "%.0f", it) }
				?: NO_VALUE
			Field.STOP_TIME -> sample?.stopTimeMs?.let { formatStop(it) } ?: NO_VALUE
		}
		val compact = isCompact()
		if (text != cacheText || compact != cacheCompact) {
			setText(text, null)
			AndroidUiHelper.updateVisibility(smallTextView, false)
			AndroidUiHelper.updateVisibility(smallTextViewShadow, false)
			cacheText = text
			cacheCompact = compact
			updateWidgetView()
		}
		applyLinkFrame()
		EvWidgetChrome.applySideLayout(view, panel)
		EvWidgetChrome.bindPanelTap(this)
	}

	private fun formatMetricKm(km: Double?): String {
		if (km == null) {
			return NO_VALUE
		}
		return if (kotlin.math.abs(km) >= 10.0) {
			String.format(Locale.US, "%.0f", km)
		} else {
			String.format(Locale.US, "%.1f", km)
		}
	}

	private fun formatStop(ms: Long): String {
		val totalSec = (ms / 1000L).toInt().coerceAtLeast(0)
		val h = totalSec / 3600
		val m = (totalSec % 3600) / 60
		val s = totalSec % 60
		return if (h > 0) {
			String.format(Locale.US, "%d:%02d:%02d", h, m, s)
		} else {
			String.format(Locale.US, "%d:%02d", m, s)
		}
	}

	private fun sampleHasFix(): Boolean {
		val sample = plugin.latestTelemetry
		return sample?.lat != null && sample.lon != null
	}

	private fun applyLinkFrame() {
		val linked = when {
			field == Field.CHARGE_ETA || field == Field.CHARGE_TIME || field == Field.CHARGE_ENERGY ->
				plugin.isCharging() || plugin.isBmsConnected()
			field.controllerLink() -> plugin.isControllerConnected()
			field.wheelLink() -> plugin.isSpeedSensorConnected()
			field.cadenceLink() -> plugin.isCadenceSensorConnected()
			field == Field.TIME || field == Field.LAT || field == Field.LON || field == Field.GPS_SPEED ->
				sampleHasFix() || plugin.isBmsConnected()
			else -> plugin.isBmsConnected()
		}
		EvWidgetLinkFrame.apply(view, linked, textView, smallTextView, widgetName)
	}

	override fun applySimpleWidgetAppearance(appearance: ResolvedPanelAppearance) {
		super.applySimpleWidgetAppearance(appearance)
		applyLinkFrame()
	}

	override fun shouldShowIcon(): Boolean {
		return if (isCompact()) false else super.shouldShowIcon()
	}

	override fun updateWidgetName() {
		if (isCompact()) {
			AndroidUiHelper.updateVisibility(widgetName, false)
			return
		}
		super.updateWidgetName()
	}

	override fun getOnClickListener(): View.OnClickListener {
		return View.OnClickListener { plugin.askShowSettingsDialog(mapActivity) }
	}

	override fun getWidgetActions(): MutableList<PopUpMenuItem> {
		val iconColor = ColorUtilities.getDefaultIconColor(app, nightMode)
		val compact = isCompact()
		return mutableListOf(
			PopUpMenuItem.Builder(app)
				.setIcon(
					app.uiUtilities.getPaintedIcon(
						if (compact) R.drawable.ic_action_view else R.drawable.ic_action_hide,
						iconColor
					)
				)
				.setTitleId(if (compact) R.string.ev_bms_full_mode else R.string.ev_bms_compact_mode)
				.setOnClickListener { _: PopUpMenuItem? -> toggleCompact() }
				.create()
		)
	}

	override fun copySettingsFromMode(
		sourceAppMode: ApplicationMode,
		appMode: ApplicationMode,
		customId: String?
	) {
		super.copySettingsFromMode(sourceAppMode, appMode, customId)
		registerCompactPref(customId).setModeValue(appMode, compactPref.getModeValue(sourceAppMode))
	}

	private fun toggleCompact() {
		compactPref.set(!compactPref.get())
		cacheCompact = null
		updateSimpleWidgetInfo(null)
		updateWidgetView()
		updateIcon()
	}

	private fun isCompact(): Boolean = compactPref.get()

	private fun registerCompactPref(customId: String?): CommonPreference<Boolean> {
		val prefId = if (Algorithms.isEmpty(customId)) {
			COMPACT_PREF_ID + widgetType.id
		} else {
			COMPACT_PREF_ID + widgetType.id + customId
		}
		return settings.registerBooleanPreference(prefId, true).makeProfile().cache()
	}
}
