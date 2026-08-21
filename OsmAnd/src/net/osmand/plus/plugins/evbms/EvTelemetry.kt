package net.osmand.plus.plugins.evbms

import java.util.Calendar
import java.util.Locale

data class EvTelemetry(
	val timeMs: Long = System.currentTimeMillis(),
	val lat: Double? = null,
	val lon: Double? = null,
	val gpsSpeedKmh: Double? = null,
	val socPercent: Int? = null,
	val voltageV: Double? = null,
	val currentA: Double? = null,
	val remainingAh: Double? = null,
	val fullAh: Double? = null,
	val bmsTempC: Double? = null,
	val cycles: Int? = null,
	val minCellVoltageV: Double? = null,
	val maxCellVoltageV: Double? = null,
	val cellImbalanceV: Double? = null,
	val controllerVoltageV: Double? = null,
	val controllerCurrentA: Double? = null,
	val controllerPowerW: Double? = null,
	val rpm: Int? = null,
	val gear: Int? = null,
	val motorTempC: Double? = null,
	val controllerTempC: Double? = null,
	val remainingRangeKm: Double? = null,
	val windowRangeKm: Double? = null,
	val pnzRangeKm: Double? = null,
	val socVoltagePercent: Int? = null,
	val consumptionAhPerKm: Double? = null,
	val energyWh: Double? = null,
	val usedAh: Double? = null,
	val consumptionWhPerKm: Double? = null,
	val coverageWhPerKm: Double? = null,
	val weakCellFactor: Double? = null,
	val farOdometerKm: Double? = null,
	val farTripKm: Double? = null,
	val chargeTripKm: Double? = null,
	val farSpeedKmh: Double? = null,
	val wheelSpeedKmh: Double? = null,
	val wheelOdometerKm: Double? = null,
	val farAvgWhPerKm: Double? = null,
	val gpsUnreliable: Boolean = false,
	val usedFarDriverDistance: Boolean = false,
	val rangeReserveKm: Double? = null,
	val stopTimeMs: Long? = null,
	val cadenceRpm: Double? = null
) {
	companion object {
		fun csvHeader(): String = VESC_HEADER

		private const val VESC_HEADER =
			"ms_today;input_voltage;temp_mos_max;temp_mos_1;temp_mos_2;temp_mos_3;temp_motor;" +
					"current_motor;current_in;d_axis_current;q_axis_current;erpm;duty_cycle;" +
					"amp_hours_used;amp_hours_charged;watt_hours_used;watt_hours_charged;" +
					"tachometer;tachometer_abs;encoder_position;fault_code;vesc_id;" +
					"d_axis_voltage;q_axis_voltage;ms_today_setup;amp_hours_setup;" +
					"amp_hours_charged_setup;watt_hours_setup;watt_hours_charged_setup;" +
					"battery_level;battery_wh_tot;current_in_setup;current_motor_setup;" +
					"speed_meters_per_sec;tacho_meters;tacho_abs_meters;num_vescs;ms_today_imu;" +
					"roll;pitch;yaw;accX;accY;accZ;gyroX;gyroY;gyroZ;gnss_posTime;gnss_lat;" +
					"gnss_lon;gnss_alt;gnss_gVel;gnss_vVel;gnss_hAcc;gnss_vAcc;"
	}

	fun toCsvRow(): String {
		val msToday = msToday(timeMs)
		val packV = voltageV ?: controllerVoltageV
		val packI = currentA ?: controllerCurrentA
		val motorI = controllerCurrentA ?: currentA
		val speedMps = (gpsSpeedKmh ?: farSpeedKmh)?.div(3.6)
		val usedAh = if (fullAh != null && remainingAh != null) {
			(fullAh - remainingAh).coerceAtLeast(0.0)
		} else {
			null
		}
		val tachoM = farOdometerKm?.times(1000.0)
		val soc = socPercent?.div(100.0)
		return listOf(
			msToday.toString(),
			n(packV, "%.2f"),
			n(controllerTempC, "%.1f"),
			n(controllerTempC, "%.1f"),
			"",
			n(bmsTempC, "%.1f"),
			n(motorTempC, "%.1f"),
			n(motorI, "%.2f"),
			n(packI, "%.2f"),
			"",
			"",
			rpm?.toString() ?: "",
			"",
			n(usedAh, "%.3f"),
			"",
			"",
			"",
			"",
			"",
			"",
			"0",
			"0",
			"",
			"",
			msToday.toString(),
			n(usedAh, "%.3f"),
			"",
			"",
			"",
			n(soc, "%.4f"),
			"",
			n(packI, "%.2f"),
			n(motorI, "%.2f"),
			n(speedMps, "%.3f"),
			n(tachoM, "%.1f"),
			n(tachoM, "%.1f"),
			"1",
			"",
			"",
			"",
			"",
			"",
			"",
			"",
			"",
			"",
			"",
			(timeMs / 1000L).toString(),
			n(lat, "%.8f"),
			n(lon, "%.8f"),
			"",
			n(speedMps, "%.3f"),
			"",
			"",
			""
		).joinToString(";") + ";"
	}

	private fun msToday(timeMs: Long): Long {
		val cal = Calendar.getInstance()
		cal.timeInMillis = timeMs
		return cal.get(Calendar.HOUR_OF_DAY) * 3600000L +
				cal.get(Calendar.MINUTE) * 60000L +
				cal.get(Calendar.SECOND) * 1000L +
				cal.get(Calendar.MILLISECOND)
	}

	private fun n(v: Double?, fmt: String): String {
		if (v == null || v.isNaN() || v.isInfinite()) {
			return ""
		}
		return String.format(Locale.US, fmt, v)
	}
}
