package net.osmand.plus.plugins.odb

import android.view.View
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.settings.backend.preferences.OsmandPreference
import net.osmand.plus.views.mapwidgets.WidgetType
import net.osmand.plus.views.mapwidgets.WidgetsPanel
import net.osmand.shared.obd.OBDDataComputer
import net.osmand.shared.obd.OBDDataComputer.OBDTypeWidget
import net.osmand.shared.settings.enums.MetricsConstants
import net.osmand.util.Algorithms

class OBDRemainingFuelWidget(
	mapActivity: MapActivity,
	widgetType: WidgetType,
	fieldType: OBDTypeWidget,
	customId: String?,
	widgetsPanel: WidgetsPanel?
) :
	OBDTextWidget(mapActivity, widgetType, fieldType, customId, widgetsPanel) {

	var remainingFuelMode: OsmandPreference<RemainingFuelMode> = registerRemainingFuelPref(customId)

	companion object {
		private const val OBD_REMAINING_FUEL_MODE = "obd_remaining_fuel_mode"
	}

	init {
		val averageTimeSeconds = 0
		val typeWidget = getFieldType()

		OBDDataComputer.removeWidget(widgetComputer)
		widgetComputer = OBDDataComputer.registerWidget(typeWidget, averageTimeSeconds)
	}

	private fun getFieldType(): OBDTypeWidget {
		return remainingFuelMode.get().fieldType
	}

	private fun registerRemainingFuelPref(customId: String?): OsmandPreference<RemainingFuelMode> {
		val prefId = if (Algorithms.isEmpty(customId))
			OBD_REMAINING_FUEL_MODE
		else OBD_REMAINING_FUEL_MODE + customId

		return settings.registerEnumStringPreference(
			prefId, RemainingFuelMode.PERCENT,
			RemainingFuelMode.entries.toTypedArray(), RemainingFuelMode::class.java
		)
			.makeProfile()
			.cache()
	}

	override fun updatePrefs() {
		super.updatePrefs()
		val averageTimeSeconds = 0
		val typeWidget = getFieldType()

		OBDDataComputer.removeWidget(widgetComputer)
		widgetComputer = OBDDataComputer.registerWidget(typeWidget, averageTimeSeconds)

		updateSimpleWidgetInfo(null)
	}

	override fun getWidgetName(): String? {
		val widgetName = if (widgetType != null) getString(widgetType.titleId) else null

		if (supportsAverageMode() && !Algorithms.isEmpty(widgetName) && averageModePref?.get() == true) {
			val formattedInterval = formatIntervals(app, measuredIntervalPref!!.get())
			return app.getString(
				R.string.ltr_or_rtl_combine_via_colon,
				widgetName,
				formattedInterval
			)
		}
		return widgetName
	}

	enum class RemainingFuelMode(
		val fieldType: OBDTypeWidget
	) {
		PERCENT(
			OBDTypeWidget.FUEL_LEFT_PERCENT,
		),
		VOLUME(
			OBDTypeWidget.FUEL_LEFT_LITER
		),
		DISTANCE(
			OBDTypeWidget.FUEL_LEFT_KM
		);

		fun getTitle(app: OsmandApplication): String {
			if (this == VOLUME) {
				val volume = app.getString(R.string.shared_string_volume)
				return app.getString(
					R.string.ltr_or_rtl_combine_with_brackets,
					volume,
					app.settings.UNIT_OF_VOLUME.get().getUnitSymbol(app)
				)
			} else if (this == DISTANCE) {
				val distance = app.getString(R.string.distance)
				val mc = app.settings.METRIC_SYSTEM.get()
				val unit = app.getString(
					when (mc) {
						MetricsConstants.KILOMETERS_AND_METERS -> R.string.km
						MetricsConstants.NAUTICAL_MILES_AND_METERS,
						MetricsConstants.NAUTICAL_MILES_AND_FEET -> R.string.nm

						else -> R.string.mile
					}
				)
				return app.getString(R.string.ltr_or_rtl_combine_with_brackets, distance, unit)
			} else {
				return app.getString(R.string.percent_unit)
			}
		}
	}
}