package net.osmand.plus.plugins.evbms

import android.content.Context
import net.osmand.plus.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TelemetryField(val id: String, val titleRes: Int, val groupRes: Int) {
	TIME_MS("time_ms", R.string.ev_bms_field_time, R.string.ev_bms_field_group_gps),
	LAT("lat", R.string.ev_bms_field_lat, R.string.ev_bms_field_group_gps),
	LON("lon", R.string.ev_bms_field_lon, R.string.ev_bms_field_group_gps),
	GPS_SPEED("gps_speed_kmh", R.string.ev_bms_field_gps_speed, R.string.ev_bms_field_group_gps),
	SOC("soc_percent", R.string.ev_bms_widget_soc, R.string.ev_bms_field_group_battery),
	VOLTAGE("voltage_v", R.string.ev_bms_widget_voltage, R.string.ev_bms_field_group_battery),
	CURRENT("current_a", R.string.ev_bms_widget_current, R.string.ev_bms_field_group_battery),
	REMAINING_AH("remaining_ah", R.string.ev_bms_field_remaining_ah, R.string.ev_bms_field_group_battery),
	FULL_AH("full_ah", R.string.ev_bms_field_full_ah, R.string.ev_bms_field_group_battery),
	BMS_TEMP("bms_temp_c", R.string.ev_bms_widget_battery_temp, R.string.ev_bms_field_group_battery),
	CYCLES("cycles", R.string.ev_bms_field_cycles, R.string.ev_bms_field_group_battery),
	MIN_CELL("min_cell_v", R.string.ev_bms_field_min_cell, R.string.ev_bms_field_group_battery),
	RANGE("range_km", R.string.ev_bms_field_range, R.string.ev_bms_field_group_battery),
	CTRL_VOLTAGE("controller_voltage_v", R.string.ev_bms_field_ctrl_voltage, R.string.ev_bms_field_group_controller),
	CTRL_CURRENT("controller_current_a", R.string.ev_bms_field_ctrl_current, R.string.ev_bms_field_group_controller),
	POWER("power_w", R.string.ev_bms_widget_power, R.string.ev_bms_field_group_controller),
	RPM("rpm", R.string.ev_bms_field_rpm, R.string.ev_bms_field_group_controller),
	GEAR("gear", R.string.ev_bms_field_gear, R.string.ev_bms_field_group_controller),
	MOTOR_TEMP("motor_temp_c", R.string.ev_bms_widget_motor_temp, R.string.ev_bms_field_group_controller),
	CTRL_TEMP("controller_temp_c", R.string.ev_bms_widget_controller_temp, R.string.ev_bms_field_group_controller),
	ODOMETER("odometer_km", R.string.ev_bms_field_odometer, R.string.ev_bms_field_group_controller),
	FAR_TRIP("controller_trip_km", R.string.ev_bms_widget_far_trip, R.string.ev_bms_field_group_controller),
	CTRL_SPEED("controller_speed_kmh", R.string.ev_bms_field_ctrl_speed, R.string.ev_bms_field_group_controller),
	CONSUMPTION("consumption_wh_km", R.string.ev_bms_widget_consumption, R.string.ev_bms_field_group_ride),
	COVERAGE("coverage_wh_km", R.string.ev_bms_field_coverage, R.string.ev_bms_field_group_ride),
	CHARGE_TRIP("charge_trip_km", R.string.ev_bms_widget_charge_trip, R.string.ev_bms_field_group_ride);

	fun csvValue(sample: EvTelemetry): String {
		return when (this) {
			TIME_MS -> sample.timeMs.toString()
			LAT -> n(sample.lat, "%.8f")
			LON -> n(sample.lon, "%.8f")
			GPS_SPEED -> n(sample.gpsSpeedKmh, "%.2f")
			SOC -> sample.socPercent?.toString().orEmpty()
			VOLTAGE -> n(sample.voltageV, "%.2f")
			CURRENT -> n(sample.currentA, "%.2f")
			REMAINING_AH -> n(sample.remainingAh, "%.3f")
			FULL_AH -> n(sample.fullAh, "%.3f")
			BMS_TEMP -> n(sample.bmsTempC, "%.1f")
			CYCLES -> sample.cycles?.toString().orEmpty()
			MIN_CELL -> n(sample.minCellVoltageV, "%.3f")
			CTRL_VOLTAGE -> n(sample.controllerVoltageV, "%.2f")
			CTRL_CURRENT -> n(sample.controllerCurrentA, "%.2f")
			POWER -> n(sample.controllerPowerW, "%.0f")
			RPM -> sample.rpm?.toString().orEmpty()
			GEAR -> sample.gear?.toString().orEmpty()
			MOTOR_TEMP -> n(sample.motorTempC, "%.1f")
			CTRL_TEMP -> n(sample.controllerTempC, "%.1f")
			RANGE -> n(sample.remainingRangeKm, "%.2f")
			CONSUMPTION -> n(sample.consumptionWhPerKm, "%.1f")
			COVERAGE -> n(sample.coverageWhPerKm, "%.1f")
			ODOMETER -> n(sample.farOdometerKm, "%.3f")
			FAR_TRIP -> n(sample.farTripKm, "%.3f")
			CHARGE_TRIP -> n(sample.chargeTripKm, "%.3f")
			CTRL_SPEED -> n(sample.farSpeedKmh, "%.2f")
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
			VOLTAGE -> unit(sample.voltageV, "%.2f", "V", empty)
			CURRENT -> unit(sample.currentA, "%.2f", "A", empty)
			REMAINING_AH -> unit(sample.remainingAh, "%.2f", "Ah", empty)
			FULL_AH -> unit(sample.fullAh, "%.2f", "Ah", empty)
			BMS_TEMP -> unit(sample.bmsTempC, "%.1f", "°C", empty)
			CYCLES -> sample.cycles?.toString() ?: empty
			MIN_CELL -> unit(sample.minCellVoltageV, "%.3f", "V", empty)
			CTRL_VOLTAGE -> unit(sample.controllerVoltageV, "%.2f", "V", empty)
			CTRL_CURRENT -> unit(sample.controllerCurrentA, "%.2f", "A", empty)
			POWER -> unit(sample.controllerPowerW, "%.0f", "W", empty)
			RPM -> sample.rpm?.toString() ?: empty
			GEAR -> sample.gear?.toString() ?: empty
			MOTOR_TEMP -> unit(sample.motorTempC, "%.1f", "°C", empty)
			CTRL_TEMP -> unit(sample.controllerTempC, "%.1f", "°C", empty)
			RANGE -> unit(sample.remainingRangeKm, "%.1f", "km", empty)
			CONSUMPTION -> unit(sample.consumptionWhPerKm, "%.0f", "Wh/km", empty)
			COVERAGE -> unit(sample.coverageWhPerKm, "%.0f", "Wh/km", empty)
			ODOMETER -> unit(sample.farOdometerKm, "%.2f", "km", empty)
			FAR_TRIP -> unit(sample.farTripKm, "%.2f", "km", empty)
			CHARGE_TRIP -> unit(sample.chargeTripKm, "%.2f", "km", empty)
			CTRL_SPEED -> unit(sample.farSpeedKmh, "%.1f", "km/h", empty)
		}
	}

	fun fingerprint(sample: EvTelemetry): String {
		return when (this) {
			TIME_MS -> ""
			LAT -> n(sample.lat, "%.5f")
			LON -> n(sample.lon, "%.5f")
			GPS_SPEED -> n(sample.gpsSpeedKmh?.let { kotlin.math.round(it * 2.0) / 2.0 }, "%.1f")
			CTRL_SPEED -> n(sample.farSpeedKmh?.let { kotlin.math.round(it * 2.0) / 2.0 }, "%.1f")
			else -> csvValue(sample)
		}
	}

	companion object {
		private val CLOCK = SimpleDateFormat("HH:mm:ss", Locale.US)
		val DEFAULT_IDS = listOf(
			TIME_MS, LAT, LON, GPS_SPEED, SOC, VOLTAGE, CURRENT,
			BMS_TEMP, MOTOR_TEMP, CTRL_TEMP, REMAINING_AH, MIN_CELL,
			POWER, RANGE, CONSUMPTION, CHARGE_TRIP
		).joinToString(",") { it.id }

		fun parse(raw: String?): List<TelemetryField> {
			val byId = entries.associateBy { it.id }
			val source = if (raw.isNullOrBlank()) DEFAULT_IDS else raw
			val parsed = source.split(',').mapNotNull { byId[it.trim()] }
			return parsed.ifEmpty {
				DEFAULT_IDS.split(',').mapNotNull { byId[it.trim()] }
			}
		}

		fun grouped(): List<Pair<Int, List<TelemetryField>>> {
			return entries.groupBy { it.groupRes }.toList()
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
	}
}
