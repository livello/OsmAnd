package net.osmand

import android.content.Context
import net.osmand.plus.OsmandApplication
import net.osmand.plus.settings.enums.MetricsConstants
import net.osmand.shared.api.OsmAndContext
import net.osmand.shared.data.SpeedConstants
import net.osmand.plus.settings.enums.SpeedConstants.KILOMETERS_PER_HOUR
import net.osmand.plus.settings.enums.SpeedConstants.MILES_PER_HOUR
import net.osmand.plus.settings.enums.SpeedConstants.METERS_PER_SECOND
import net.osmand.plus.settings.enums.SpeedConstants.MINUTES_PER_MILE
import net.osmand.plus.settings.enums.SpeedConstants.MINUTES_PER_KILOMETER
import net.osmand.plus.settings.enums.SpeedConstants.NAUTICALMILES_PER_HOUR
import net.osmand.shared.filters.KMetricsConstants
import java.lang.ref.WeakReference

object OsmAndContextImpl: OsmAndContext {
	private var app: WeakReference<OsmandApplication>? = null

	fun initialize(application: OsmandApplication) {
		app = WeakReference(application)
	}

	override fun isGpxFileVisible(path: String): Boolean {
		app?.get()?.let {
			val helper = it.selectedGpxHelper
			helper.getSelectedFileByPath(path) != null
		}
		return false
	}

	override fun getSpeedSystem(): SpeedConstants? {
		app?.get()?.let {
			val settings = it.settings
			val mode = settings.applicationMode
			return when(settings.SPEED_SYSTEM.getModeValue(mode)) {
				KILOMETERS_PER_HOUR -> SpeedConstants.KILOMETERS_PER_HOUR
				MILES_PER_HOUR -> SpeedConstants.MILES_PER_HOUR
				METERS_PER_SECOND -> SpeedConstants.METERS_PER_SECOND
				MINUTES_PER_MILE -> SpeedConstants.MINUTES_PER_MILE
				MINUTES_PER_KILOMETER -> SpeedConstants.MINUTES_PER_KILOMETER
				NAUTICALMILES_PER_HOUR -> SpeedConstants.NAUTICALMILES_PER_HOUR
				else -> null
			}
		}
		return null
	}

	override fun getMetricSystem(): KMetricsConstants? {
		app?.get()?.let {
			val metricsConstants = it.settings.METRIC_SYSTEM.get()
			return when(metricsConstants) {
				MetricsConstants.KILOMETERS_AND_METERS -> KMetricsConstants.KILOMETERS_AND_METERS
				MetricsConstants.MILES_AND_FEET -> KMetricsConstants.MILES_AND_FEET
				MetricsConstants.MILES_AND_METERS -> KMetricsConstants.MILES_AND_METERS
				MetricsConstants.MILES_AND_YARDS -> KMetricsConstants.MILES_AND_YARDS
				MetricsConstants.NAUTICAL_MILES_AND_METERS -> KMetricsConstants.NAUTICAL_MILES_AND_METERS
				MetricsConstants.NAUTICAL_MILES_AND_FEET -> KMetricsConstants.NAUTICAL_MILES_AND_FEET
				else -> null
			}
		}
		return null
	}
}