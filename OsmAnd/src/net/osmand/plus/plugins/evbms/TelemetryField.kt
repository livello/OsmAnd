package net.osmand.plus.plugins.evbms

import android.content.Context
import net.osmand.plus.R
import net.osmand.shared.gpx.PointAttributes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TelemetryField(val id: String, val titleRes: Int, val groupRes: Int, val emoji: String) {
	TIME_MS("time_ms", R.string.ev_bms_field_time, R.string.ev_bms_field_group_gps, "🕒"),
	LAT("lat", R.string.ev_bms_field_lat, R.string.ev_bms_field_group_gps, "🌐"),
	LON("lon", R.string.ev_bms_field_lon, R.string.ev_bms_field_group_gps, "🌐"),
	GPS_SPEED("gps_speed_kmh", R.string.ev_bms_field_gps_speed, R.string.ev_bms_field_group_gps, "🛰️"),
	SOC("soc_percent", R.string.ev_bms_widget_soc, R.string.ev_bms_field_group_battery, "🔋"),
	SOC_OCV("soc_ocv_percent", R.string.ev_bms_field_soc_ocv, R.string.ev_bms_field_group_battery, "🔋"),
	VOLTAGE("voltage_v", R.string.ev_bms_widget_voltage, R.string.ev_bms_field_group_battery, "⚡"),
	CURRENT("current_a", R.string.ev_bms_widget_current, R.string.ev_bms_field_group_battery, "🔌"),
	REMAINING_AH("remaining_ah", R.string.ev_bms_field_remaining_ah, R.string.ev_bms_field_group_battery, "📉"),
	FULL_AH("full_ah", R.string.ev_bms_field_full_ah, R.string.ev_bms_field_group_battery, "📦"),
	BMS_TEMP("bms_temp_c", R.string.ev_bms_widget_battery_temp, R.string.ev_bms_field_group_battery, "🌡️"),
	CYCLES("cycles", R.string.ev_bms_field_cycles, R.string.ev_bms_field_group_battery, "🔁"),
	MIN_CELL("min_cell_v", R.string.ev_bms_field_min_cell, R.string.ev_bms_field_group_battery, "🔻"),
	MAX_CELL("max_cell_v", R.string.ev_bms_field_max_cell, R.string.ev_bms_field_group_battery, "🔺"),
	CELL_IMBALANCE("cell_imbalance_v", R.string.ev_bms_field_cell_imbalance, R.string.ev_bms_field_group_battery, "⚖️"),
	RANGE("range_km", R.string.ev_bms_field_range, R.string.ev_bms_field_group_battery, "📏"),
	RANGE_WINDOW("range_window_km", R.string.ev_bms_field_range_window, R.string.ev_bms_field_group_ride, "📏"),
	RANGE_PNZ("range_pnz_km", R.string.ev_bms_field_range_pnz, R.string.ev_bms_field_group_ride, "📏"),
	CTRL_VOLTAGE("controller_voltage_v", R.string.ev_bms_field_ctrl_voltage, R.string.ev_bms_field_group_controller, "⚡"),
	CTRL_CURRENT("controller_current_a", R.string.ev_bms_field_ctrl_current, R.string.ev_bms_field_group_controller, "🔌"),
	POWER("power_w", R.string.ev_bms_widget_power, R.string.ev_bms_field_group_controller, "⚙️"),
	RPM("rpm", R.string.ev_bms_field_rpm, R.string.ev_bms_field_group_controller, "🌀"),
	GEAR("gear", R.string.ev_bms_field_gear, R.string.ev_bms_field_group_controller, "⚙️"),
	MOTOR_TEMP("motor_temp_c", R.string.ev_bms_widget_motor_temp, R.string.ev_bms_field_group_controller, "🔥"),
	CTRL_TEMP("controller_temp_c", R.string.ev_bms_widget_controller_temp, R.string.ev_bms_field_group_controller, "🌡️"),
	ODOMETER("odometer_km", R.string.ev_bms_field_odometer, R.string.ev_bms_field_group_controller, "🛣️"),
	FAR_TRIP("controller_trip_km", R.string.ev_bms_widget_far_trip, R.string.ev_bms_field_group_controller, "🛵"),
	CTRL_SPEED("controller_speed_kmh", R.string.ev_bms_field_ctrl_speed, R.string.ev_bms_field_group_controller, "🚀"),
	WHEEL_SPEED("wheel_speed_kmh", R.string.ev_bms_field_wheel_speed, R.string.ev_bms_field_group_ride, "🚲"),
	WHEEL_ODOMETER("wheel_odometer_km", R.string.ev_bms_field_wheel_odometer, R.string.ev_bms_field_group_ride, "🚲"),
	CADENCE("cadence_rpm", R.string.ev_bms_field_cadence, R.string.ev_bms_field_group_ride, "🚴"),
	CONSUMPTION("consumption_wh_km", R.string.ev_bms_widget_consumption, R.string.ev_bms_field_group_ride, "📊"),
	USED_AH("used_ah", R.string.ev_bms_field_used_ah, R.string.ev_bms_field_group_ride, "🔋"),
	COVERAGE("coverage_wh_km", R.string.ev_bms_field_coverage, R.string.ev_bms_field_group_ride, "📊"),
	CHARGE_TRIP("charge_trip_km", R.string.ev_bms_widget_charge_trip, R.string.ev_bms_field_group_ride, "🔌"),
	RANGE_RESERVE("range_reserve_km", R.string.ev_bms_widget_range_reserve, R.string.ev_bms_field_group_ride, "🛣️"),
	STOP_TIME("stop_time_ms", R.string.ev_bms_field_stop_time, R.string.ev_bms_field_group_ride, "⏸️");

	fun csvValue(sample: EvTelemetry): String {
		return when (this) {
			TIME_MS -> sample.timeMs.toString()
			LAT -> n(sample.lat, "%.8f")
			LON -> n(sample.lon, "%.8f")
			GPS_SPEED -> n(sample.gpsSpeedKmh, "%.2f")
			SOC -> sample.socPercent?.toString().orEmpty()
			SOC_OCV -> sample.socVoltagePercent?.toString().orEmpty()
			VOLTAGE -> n(sample.voltageV, "%.2f")
			CURRENT -> n(sample.currentA, "%.2f")
			REMAINING_AH -> n(sample.remainingAh, "%.3f")
			FULL_AH -> n(sample.fullAh, "%.3f")
			BMS_TEMP -> n(sample.bmsTempC, "%.1f")
			CYCLES -> sample.cycles?.toString().orEmpty()
			MIN_CELL -> n(sample.minCellVoltageV, "%.3f")
			MAX_CELL -> n(sample.maxCellVoltageV, "%.3f")
			CELL_IMBALANCE -> n(sample.cellImbalanceV, "%.4f")
			CTRL_VOLTAGE -> n(sample.controllerVoltageV, "%.2f")
			CTRL_CURRENT -> n(sample.controllerCurrentA, "%.2f")
			POWER -> n(sample.controllerPowerW, "%.0f")
			RPM -> sample.rpm?.toString().orEmpty()
			GEAR -> sample.gear?.toString().orEmpty()
			MOTOR_TEMP -> n(sample.motorTempC, "%.1f")
			CTRL_TEMP -> n(sample.controllerTempC, "%.1f")
			RANGE -> n(sample.remainingRangeKm, "%.2f")
			RANGE_WINDOW -> n(sample.windowRangeKm, "%.2f")
			RANGE_PNZ -> n(sample.pnzRangeKm, "%.2f")
			CONSUMPTION -> n(sample.energyWh, "%.1f")
			USED_AH -> n(sample.usedAh, "%.3f")
			COVERAGE -> n(sample.consumptionWhPerKm ?: sample.coverageWhPerKm, "%.1f")
			ODOMETER -> n(sample.farOdometerKm, "%.3f")
			FAR_TRIP -> n(sample.farTripKm, "%.3f")
			CHARGE_TRIP -> n(sample.chargeTripKm, "%.3f")
			CTRL_SPEED -> n(sample.farSpeedKmh, "%.2f")
			WHEEL_SPEED -> n(sample.wheelSpeedKmh, "%.2f")
			WHEEL_ODOMETER -> n(sample.wheelOdometerKm, "%.3f")
			CADENCE -> n(sample.cadenceRpm, "%.1f")
			RANGE_RESERVE -> n(sample.rangeReserveKm, "%.2f")
			STOP_TIME -> sample.stopTimeMs?.toString().orEmpty()
		}
	}

	fun liveValue(ctx: Context, sample: EvTelemetry?): String {
		val empty = ctx.getString(R.string.ev_bms_value_none)
		if (sample == null) {
			return empty
		}
		return when (this) {
			TIME_MS -> CLOCK.format(Date(sample.timeMs))
			LAT -> n(sample.lat, "%.6f").ifEmpty { empty }
			LON -> n(sample.lon, "%.6f").ifEmpty { empty }
			GPS_SPEED -> unit(sample.gpsSpeedKmh, "%.1f", "km/h", empty)
			SOC -> sample.socPercent?.let { "$it %" } ?: empty
			SOC_OCV -> sample.socVoltagePercent?.let { "$it %" } ?: empty
			VOLTAGE -> unit(sample.voltageV, "%.2f", "V", empty)
			CURRENT -> unit(sample.currentA, "%.2f", "A", empty)
			REMAINING_AH -> unit(sample.remainingAh, "%.2f", "Ah", empty)
			FULL_AH -> unit(sample.fullAh, "%.2f", "Ah", empty)
			BMS_TEMP -> unit(sample.bmsTempC, "%.1f", "°C", empty)
			CYCLES -> sample.cycles?.toString() ?: empty
			MIN_CELL -> unit(sample.minCellVoltageV, "%.3f", "V", empty)
			MAX_CELL -> unit(sample.maxCellVoltageV, "%.3f", "V", empty)
			CELL_IMBALANCE -> sample.cellImbalanceV?.let {
				String.format(Locale.US, "%.0f mV", it * 1000.0)
			} ?: empty
			CTRL_VOLTAGE -> unit(sample.controllerVoltageV, "%.2f", "V", empty)
			CTRL_CURRENT -> unit(sample.controllerCurrentA, "%.2f", "A", empty)
			POWER -> unit(sample.controllerPowerW, "%.0f", "W", empty)
			RPM -> sample.rpm?.toString() ?: empty
			GEAR -> sample.gear?.toString() ?: empty
			MOTOR_TEMP -> unit(sample.motorTempC, "%.1f", "°C", empty)
			CTRL_TEMP -> unit(sample.controllerTempC, "%.1f", "°C", empty)
			RANGE -> unit(sample.remainingRangeKm, "%.1f", "km", empty)
			RANGE_WINDOW -> unit(sample.windowRangeKm, "%.1f", "km", empty)
			RANGE_PNZ -> unit(sample.pnzRangeKm, "%.1f", "km", empty)
			CONSUMPTION -> unit(sample.energyWh, "%.0f", "Wh", empty)
			USED_AH -> unit(sample.usedAh, "%.2f", "Ah", empty)
			COVERAGE -> unit(sample.consumptionWhPerKm ?: sample.coverageWhPerKm, "%.0f", "Wh/km", empty)
			ODOMETER -> unit(sample.farOdometerKm, "%.2f", "km", empty)
			FAR_TRIP -> unit(sample.farTripKm, "%.2f", "km", empty)
			CHARGE_TRIP -> unit(sample.chargeTripKm, "%.2f", "km", empty)
			CTRL_SPEED -> unit(sample.farSpeedKmh, "%.1f", "km/h", empty)
			WHEEL_SPEED -> unit(sample.wheelSpeedKmh, "%.1f", "km/h", empty)
			WHEEL_ODOMETER -> unit(sample.wheelOdometerKm, "%.2f", "km", empty)
			CADENCE -> unit(sample.cadenceRpm, "%.0f", "rpm", empty)
			RANGE_RESERVE -> unit(sample.rangeReserveKm, "%.1f", "km", empty)
			STOP_TIME -> formatDuration(sample.stopTimeMs, empty)
		}
	}

	fun fingerprint(sample: EvTelemetry): String {
		return when (this) {
			TIME_MS -> ""
			LAT -> n(sample.lat, "%.5f")
			LON -> n(sample.lon, "%.5f")
			GPS_SPEED -> n(sample.gpsSpeedKmh?.let { kotlin.math.round(it * 2.0) / 2.0 }, "%.1f")
			CTRL_SPEED -> n(sample.farSpeedKmh?.let { kotlin.math.round(it * 2.0) / 2.0 }, "%.1f")
			WHEEL_SPEED -> n(sample.wheelSpeedKmh?.let { kotlin.math.round(it * 2.0) / 2.0 }, "%.1f")
			CADENCE -> n(sample.cadenceRpm?.let { kotlin.math.round(it) }, "%.0f")
			else -> csvValue(sample)
		}
	}

	fun isChartable(): Boolean = this != TIME_MS && this != LAT && this != LON

	fun gpxAliases(): List<String> = when (this) {
		SOC -> listOf(PointAttributes.EV_TAG_SOC)
		VOLTAGE -> listOf(PointAttributes.EV_TAG_VOLTAGE)
		CURRENT -> listOf(PointAttributes.EV_TAG_CURRENT)
		CHARGE_TRIP -> listOf(PointAttributes.EV_TAG_CHARGE_TRIP)
		COVERAGE -> listOf(PointAttributes.EV_TAG_CONSUMPTION)
		CONSUMPTION -> listOf(PointAttributes.EV_TAG_ENERGY)
		else -> emptyList()
	}

	fun chartUnit(): String = when (this) {
		GPS_SPEED, CTRL_SPEED, WHEEL_SPEED -> "km/h"
		SOC, SOC_OCV -> "%"
		VOLTAGE, CTRL_VOLTAGE, MIN_CELL, MAX_CELL -> "V"
		CURRENT, CTRL_CURRENT -> "A"
		REMAINING_AH, FULL_AH, USED_AH -> "Ah"
		BMS_TEMP, MOTOR_TEMP, CTRL_TEMP -> "°C"
		CELL_IMBALANCE -> "V"
		RANGE, RANGE_WINDOW, RANGE_PNZ, ODOMETER, FAR_TRIP, CHARGE_TRIP, WHEEL_ODOMETER, RANGE_RESERVE -> "km"
		POWER -> "W"
		RPM, CADENCE -> "rpm"
		CONSUMPTION -> "Wh"
		COVERAGE -> "Wh/km"
		STOP_TIME -> "ms"
		else -> ""
	}

	fun allowsNegativeChart(): Boolean =
		this == CURRENT || this == CTRL_CURRENT || this == RANGE_RESERVE

	fun chartValue(sample: EvTelemetry): Double? {
		val raw = when (this) {
			TIME_MS, LAT, LON -> return null
			GPS_SPEED -> sample.gpsSpeedKmh
			SOC -> sample.socPercent?.toDouble()
			SOC_OCV -> sample.socVoltagePercent?.toDouble()
			VOLTAGE -> sample.voltageV
			CURRENT -> sample.currentA
			REMAINING_AH -> sample.remainingAh
			FULL_AH -> sample.fullAh
			BMS_TEMP -> sample.bmsTempC
			CYCLES -> sample.cycles?.toDouble()
			MIN_CELL -> sample.minCellVoltageV
			MAX_CELL -> sample.maxCellVoltageV
			CELL_IMBALANCE -> sample.cellImbalanceV?.times(1000.0)
			RANGE -> sample.remainingRangeKm
			RANGE_WINDOW -> sample.windowRangeKm
			RANGE_PNZ -> sample.pnzRangeKm
			CTRL_VOLTAGE -> sample.controllerVoltageV
			CTRL_CURRENT -> sample.controllerCurrentA
			POWER -> sample.controllerPowerW
			RPM -> sample.rpm?.toDouble()
			GEAR -> sample.gear?.toDouble()
			MOTOR_TEMP -> sample.motorTempC
			CTRL_TEMP -> sample.controllerTempC
			ODOMETER -> sample.farOdometerKm
			FAR_TRIP -> sample.farTripKm
			CTRL_SPEED -> sample.farSpeedKmh
			WHEEL_SPEED -> sample.wheelSpeedKmh
			WHEEL_ODOMETER -> sample.wheelOdometerKm
			CADENCE -> sample.cadenceRpm
			CONSUMPTION -> sample.energyWh
			USED_AH -> sample.usedAh
			COVERAGE -> sample.consumptionWhPerKm ?: sample.coverageWhPerKm
			CHARGE_TRIP -> sample.chargeTripKm
			RANGE_RESERVE -> sample.rangeReserveKm
			STOP_TIME -> sample.stopTimeMs?.div(1000.0)
		}
		return if (raw == null || raw.isNaN() || raw.isInfinite()) null else raw
	}

	companion object {
		private val CLOCK = SimpleDateFormat("HH:mm:ss", Locale.US)
		val DEFAULT_IDS = listOf(
			TIME_MS, LAT, LON, GPS_SPEED, SOC, VOLTAGE, CURRENT,
			BMS_TEMP, MOTOR_TEMP, CTRL_TEMP, REMAINING_AH, FULL_AH, MIN_CELL,
			POWER, RANGE, RANGE_WINDOW, RANGE_PNZ, CONSUMPTION, CHARGE_TRIP, WHEEL_SPEED, WHEEL_ODOMETER, CADENCE
		).joinToString(",") { it.id }

		fun parse(raw: String?): List<TelemetryField> {
			val parsed = parseExact(raw)
			return parsed.ifEmpty {
				parseExact(DEFAULT_IDS)
			}
		}

		fun parseExact(raw: String?): List<TelemetryField> {
			if (raw.isNullOrBlank()) {
				return emptyList()
			}
			val byId = entries.associateBy { it.id }
			return raw.split(',').mapNotNull { byId[it.trim()] }
		}

		fun grouped(): List<Pair<Int, List<TelemetryField>>> {
			return entries.groupBy { it.groupRes }.toList()
		}

		fun groupEmoji(groupRes: Int): String {
			return when (groupRes) {
				R.string.ev_bms_field_group_gps -> "📍"
				R.string.ev_bms_field_group_battery -> "🔋"
				R.string.ev_bms_field_group_controller -> "🛵"
				R.string.ev_bms_field_group_ride -> "🛣️"
				else -> "•"
			}
		}

		private fun n(v: Double?, fmt: String): String {
			if (v == null || v.isNaN() || v.isInfinite()) {
				return ""
			}
			return String.format(Locale.US, fmt, v)
		}

		private fun unit(v: Double?, fmt: String, suffix: String, empty: String): String {
			val raw = n(v, fmt)
			return if (raw.isEmpty()) empty else "$raw $suffix"
		}

		private fun formatDuration(ms: Long?, empty: String): String {
			if (ms == null || ms < 0L) {
				return empty
			}
			val totalSec = (ms / 1000L).toInt()
			val h = totalSec / 3600
			val m = (totalSec % 3600) / 60
			val s = totalSec % 60
			return when {
				h > 0 -> String.format(Locale.US, "%d:%02d:%02d", h, m, s)
				else -> String.format(Locale.US, "%d:%02d", m, s)
			}
		}
	}
}
