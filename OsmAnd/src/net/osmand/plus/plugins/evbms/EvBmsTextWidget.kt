package net.osmand.plus.plugins.evbms

import android.view.View
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
		SOC, RANGE, CONSUMPTION, FAR_TRIP, VOLTAGE, CURRENT, POWER, BATTERY_TEMP, MOTOR_TEMP, CONTROLLER_TEMP
	}

	companion object {
		private const val COMPACT_PREF_ID = "ev_bms_widget_compact_"
	}

	private val plugin = PluginsHelper.requirePlugin(EvBmsPlugin::class.java)
	private val compactPref: CommonPreference<Boolean> = registerCompactPref(customId)
	private var cacheText: String? = null
	private var cacheSub: String? = null
	private var cacheCompact: Boolean? = null

	init {
		setText(NO_VALUE, null)
		setIcons(widgetType)
		updateWidgetView()
	}

	override fun updateSimpleWidgetInfo(drawSettings: DrawSettings?) {
		val sample = plugin.latestTelemetry
		var text: String
		var sub: String?
		when (field) {
			Field.SOC -> {
				text = sample?.socPercent?.toString() ?: NO_VALUE
				sub = "%"
			}
			Field.RANGE -> {
				val km = sample?.remainingRangeKm
				if (km == null) {
					text = NO_VALUE
					sub = null
				} else {
					text = OsmAndFormatter.getFormattedDistance((km * 1000).toFloat(), app)
					sub = null
				}
			}
			Field.CONSUMPTION -> {
				val wh = sample?.consumptionWhPerKm
				if (wh == null) {
					text = NO_VALUE
					sub = null
				} else {
					text = String.format(Locale.US, "%.0f", wh)
					sub = app.getString(R.string.ev_bms_unit_wh_per_km)
				}
			}
			Field.FAR_TRIP -> {
				val km = sample?.farTripKm
				if (km == null) {
					text = NO_VALUE
					sub = null
				} else {
					text = OsmAndFormatter.getFormattedDistance((km * 1000).toFloat(), app)
					sub = null
				}
			}
			Field.VOLTAGE -> {
				text = sample?.voltageV?.let { String.format(Locale.US, "%.1f", it) } ?: NO_VALUE
				sub = "V"
			}
			Field.CURRENT -> {
				text = sample?.currentA?.let { String.format(Locale.US, "%.1f", it) } ?: NO_VALUE
				sub = "A"
			}
			Field.POWER -> {
				val w = sample?.controllerPowerW ?: sample?.let { s ->
					if (s.voltageV != null && s.currentA != null) s.voltageV * s.currentA else null
				}
				text = w?.let { String.format(Locale.US, "%.0f", it) } ?: NO_VALUE
				sub = "W"
			}
			Field.BATTERY_TEMP -> {
				text = sample?.bmsTempC?.let { String.format(Locale.US, "%.0f", it) } ?: NO_VALUE
				sub = "°C"
			}
			Field.MOTOR_TEMP -> {
				text = sample?.motorTempC?.let { String.format(Locale.US, "%.0f", it) } ?: NO_VALUE
				sub = "°C"
			}
			Field.CONTROLLER_TEMP -> {
				text = sample?.controllerTempC?.let { String.format(Locale.US, "%.0f", it) } ?: NO_VALUE
				sub = "°C"
			}
		}
		val compact = isCompact()
		if (compact && text != NO_VALUE && sub != null) {
			text = glueUnit(text, sub)
			sub = null
		}
		if (text != cacheText || sub != cacheSub || compact != cacheCompact) {
			setText(text, sub)
			cacheText = text
			cacheSub = sub
			cacheCompact = compact
			updateWidgetView()
		}
		EvWidgetLinkFrame.apply(view, plugin.isLinkHealthy(), app)
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
		return View.OnClickListener { toggleCompact() }
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

	private fun glueUnit(text: String, unit: String): String {
		return if (unit.startsWith("%") || unit.startsWith("°")) text + unit else "$text $unit"
	}

	private fun registerCompactPref(customId: String?): CommonPreference<Boolean> {
		val prefId = if (Algorithms.isEmpty(customId)) {
			COMPACT_PREF_ID + widgetType.id
		} else {
			COMPACT_PREF_ID + widgetType.id + customId
		}
		return settings.registerBooleanPreference(prefId, true).makeProfile().cache()
	}
}
