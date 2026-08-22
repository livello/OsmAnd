package net.osmand.plus.plugins.evbms

import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.formatter.IAxisValueFormatter
import net.osmand.plus.OsmandApplication
import net.osmand.plus.charts.ChartUtils
import net.osmand.plus.charts.GPXDataSetAxisType
import net.osmand.plus.charts.GPXDataSetType
import net.osmand.plus.charts.OrderedLineDataSet
import net.osmand.plus.settings.enums.ThemeUsageContext
import net.osmand.plus.utils.ColorUtilities
import net.osmand.shared.gpx.GpxTrackAnalysis
import net.osmand.shared.gpx.GpxUtilities
import net.osmand.shared.gpx.PointAttributes
import net.osmand.shared.gpx.primitives.WptPt
import net.osmand.util.Algorithms
import org.json.JSONObject
import java.util.Locale

object EvGpx {
	val TAGS: Array<String> = buildList {
		for (field in TelemetryField.entries) {
			if (!field.isChartable()) {
				continue
			}
			add(field.id)
			addAll(field.gpxAliases())
		}
	}.distinct().toTypedArray()

	fun put(
		json: JSONObject,
		sample: EvTelemetry,
		fields: List<TelemetryField> = TelemetryField.entries.filter { it.isChartable() }
	) {
		for (field in fields) {
			if (!field.isChartable()) {
				continue
			}
			val value = field.csvValue(sample)
			if (value.isNotEmpty()) {
				json.put(GpxUtilities.OSMAND_EXTENSIONS_PREFIX + field.id, value)
			}
		}
		put(json, PointAttributes.EV_TAG_CONSUMPTION, sample.consumptionWhPerKm ?: sample.coverageWhPerKm, "%.1f")
		put(json, PointAttributes.EV_TAG_ENERGY, sample.energyWh, "%.1f")
		put(json, PointAttributes.EV_TAG_VOLTAGE, sample.voltageV, "%.2f")
		put(json, PointAttributes.EV_TAG_CURRENT, sample.currentA, "%.2f")
		sample.socPercent?.let {
			json.put(GpxUtilities.OSMAND_EXTENSIONS_PREFIX + PointAttributes.EV_TAG_SOC, it)
		}
		put(json, PointAttributes.EV_TAG_CHARGE_TRIP, sample.chargeTripKm, "%.3f")
	}

	private fun put(json: JSONObject, tag: String, value: Double?, fmt: String) {
		if (value == null || value.isNaN() || value.isInfinite()) {
			return
		}
		json.put(GpxUtilities.OSMAND_EXTENSIONS_PREFIX + tag, String.format(Locale.US, fmt, value))
	}

	fun read(point: WptPt, tag: String): Float {
		val prefixed = GpxUtilities.OSMAND_EXTENSIONS_PREFIX + tag
		var raw = point.getDeferredExtensionsToRead()[tag]
			?: point.getDeferredExtensionsToRead()[prefixed]
		if (Algorithms.isEmpty(raw)) {
			raw = point.getExtensionsToRead()[tag] ?: point.getExtensionsToRead()[prefixed]
		}
		return Algorithms.parseFloatSilently(raw, Float.NaN)
	}

	fun getAvailableGPXDataSetTypes(
		analysis: GpxTrackAnalysis,
		out: MutableList<GPXDataSetType?>
	) {
		for (type in GPXDataSetType.entries) {
			if (type.typeGroup == net.osmand.plus.charts.GpxDataSetTypeGroup.EV_TELEMETRY &&
				analysis.hasData(type.dataKey)
			) {
				out.add(type)
			}
		}
	}

	fun createDataSet(
		app: OsmandApplication,
		chart: LineChart,
		analysis: GpxTrackAnalysis,
		graphType: GPXDataSetType,
		axisType: GPXDataSetAxisType,
		useRightAxis: Boolean,
		calcWithoutGaps: Boolean
	): OrderedLineDataSet {
		val nightMode = app.daynightHelper.isNightMode(ThemeUsageContext.APP)
		val divX = ChartUtils.getDivX(app, chart, analysis, axisType, calcWithoutGaps)
		val yAxis = ChartUtils.getYAxis(
			chart,
			ColorUtilities.getColor(app, graphType.getTextColorId(false)),
			useRightAxis
		)
		val field = TelemetryField.entries.find { it.id == graphType.dataKey }
		if (field?.allowsNegativeChart() != true) {
			yAxis.axisMinimum = 0f
		}
		val values = ArrayList<Entry>()
		var currentX = 0f
		for (i in analysis.pointAttributes.indices) {
			val attribute = analysis.pointAttributes[i]
			val stepX = if (axisType == GPXDataSetAxisType.TIME ||
				axisType == GPXDataSetAxisType.TIME_OF_DAY
			) {
				attribute.timeDiff
			} else {
				attribute.distance
			}
			if (i == 0 || stepX > 0) {
				if (!(calcWithoutGaps && attribute.firstPoint)) {
					currentX += stepX / divX
				}
				if (attribute.hasValidValue(graphType.dataKey)) {
					val y = attribute.getAttributeValue(graphType.dataKey)
					if (!y.isNaN() && !y.isInfinite()) {
						values.add(Entry(currentX, y))
					}
				}
			}
		}
		val dataSet = OrderedLineDataSet(values, "", graphType, axisType, !useRightAxis)
		val unit = field?.chartUnit().orEmpty()
		yAxis.valueFormatter = IAxisValueFormatter { value: Float, _: AxisBase? ->
			if (unit.isEmpty()) {
				String.format(Locale.US, "%.0f", value)
			} else {
				String.format(Locale.US, "%.0f %s", value, unit)
			}
		}
		dataSet.divX = divX
		dataSet.units = unit
		val color = ColorUtilities.getColor(app, graphType.getFillColorId(false))
		ChartUtils.setupDataSet(app, dataSet, color, color, true, false, useRightAxis, nightMode)
		return dataSet
	}
}

class EvTrackPointsAnalyser : GpxTrackAnalysis.TrackPointsAnalyser {
	override fun onAnalysePoint(
		analysis: GpxTrackAnalysis,
		point: WptPt,
		attribute: net.osmand.shared.gpx.PointAttributes
	) {
		if (point.getDeferredExtensionsToRead().isEmpty() && point.getExtensionsToRead().isEmpty()) {
			return
		}
		for (field in TelemetryField.entries) {
			if (!field.isChartable()) {
				continue
			}
			var value = EvGpx.read(point, field.id)
			if (value.isNaN()) {
				for (alias in field.gpxAliases()) {
					value = EvGpx.read(point, alias)
					if (!value.isNaN()) {
						break
					}
				}
			}
			if (value.isNaN()) {
				continue
			}
			attribute.setAttributeValue(field.id, value)
			if (!analysis.hasData(field.id) && attribute.hasValidValue(field.id)) {
				analysis.setHasData(field.id, true)
			}
			for (alias in field.gpxAliases()) {
				attribute.setAttributeValue(alias, value)
				if (!analysis.hasData(alias) && attribute.hasValidValue(alias)) {
					analysis.setHasData(alias, true)
				}
			}
		}
	}
}
