package net.osmand.plus.plugins.evbms

import net.osmand.plus.R
import java.util.Locale

enum class TelemetryField(val id: String, val titleRes: Int) {
	TIME_MS("time_ms", R.string.ev_bms_field_time),
	LAT("lat", R.string.ev_bms_field_lat),
	LON("lon", R.string.ev_bms_field_lon),
	GPS_SPEED("gps_speed_kmh", R.string.ev_bms_field_gps_speed),
	SOC("soc_percent", R.string.ev_bms_widget_soc),
	VOLTAGE("voltage_v", R.string.ev_bms_widget_voltage),
	CURRENT("current_a", R.string.ev_bms_widget_current),
	REMAINING_AH("remaining_ah", R.string.ev_bms_field_remaining_ah),
	FULL_AH("full_ah", R.string.ev_bms_field_full_ah),
	BMS_TEMP("bms_temp_c", R.string.ev_bms_widget_battery_temp),
	CYCLES("cycles", R.string.ev_bms_field_cycles),
	MIN_CELL("min_cell_v", R.string.ev_bms_field_min_cell),
	CTRL_VOLTAGE("controller_voltage_v", R.string.ev_bms_field_ctrl_voltage),
	CTRL_CURRENT("controller_current_a", R.string.ev_bms_field_ctrl_current),
	POWER("power_w", R.string.ev_bms_widget_power),
	RPM("rpm", R.string.ev_bms_field_rpm),
	GEAR("gear", R.string.ev_bms_field_gear),
	MOTOR_TEMP("motor_temp_c", R.string.ev_bms_widget_motor_temp),
	CTRL_TEMP("controller_temp_c", R.string.ev_bms_widget_controller_temp),
	RANGE("range_km", R.string.ev_bms_widget_range),
	CONSUMPTION("consumption_wh_km", R.string.ev_bms_widget_consumption),
	COVERAGE("coverage_wh_km", R.string.ev_bms_field_coverage),
	ODOMETER("odometer_km", R.string.ev_bms_field_odometer),
	FAR_TRIP("controller_trip_km", R.string.ev_bms_widget_far_trip),
	CHARGE_TRIP("charge_trip_km", R.string.ev_bms_widget_charge_trip),
	CTRL_SPEED("controller_speed_kmh", R.string.ev_bms_field_ctrl_speed);

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

	companion object {
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

		private fun n(v: Double?, fmt: String): String {
			if (v == null || v.isNaN() || v.isInfinite()) {
				return ""
			}
			return String.format(Locale.US, fmt, v)
		}
	}
}
