package net.osmand.plus.plugins.evbms

import org.json.JSONObject
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
	val consumptionWhPerKm100m: Double? = null,
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

		fun fromJson(raw: String?): EvTelemetry? {
			if (raw.isNullOrBlank()) {
				return null
			}
			return try {
				val json = JSONObject(raw)
				fun d(key: String): Double? = if (json.has(key) && !json.isNull(key)) json.optDouble(key) else null
				fun i(key: String): Int? = if (json.has(key) && !json.isNull(key)) json.optInt(key) else null
				fun l(key: String): Long? = if (json.has(key) && !json.isNull(key)) json.optLong(key) else null
				EvTelemetry(
					timeMs = json.optLong("timeMs"),
					lat = d("lat"),
					lon = d("lon"),
					gpsSpeedKmh = d("gpsSpeedKmh"),
					socPercent = i("socPercent"),
					socVoltagePercent = i("socVoltagePercent"),
					voltageV = d("voltageV"),
					currentA = d("currentA"),
					remainingAh = d("remainingAh"),
					fullAh = d("fullAh"),
					bmsTempC = d("bmsTempC"),
					cycles = i("cycles"),
					minCellVoltageV = d("minCellVoltageV"),
					maxCellVoltageV = d("maxCellVoltageV"),
					cellImbalanceV = d("cellImbalanceV"),
					controllerVoltageV = d("controllerVoltageV"),
					controllerCurrentA = d("controllerCurrentA"),
					controllerPowerW = d("controllerPowerW"),
					rpm = i("rpm"),
					gear = i("gear"),
					motorTempC = d("motorTempC"),
					controllerTempC = d("controllerTempC"),
					remainingRangeKm = d("remainingRangeKm"),
					windowRangeKm = d("windowRangeKm"),
					pnzRangeKm = d("pnzRangeKm"),
					consumptionAhPerKm = d("consumptionAhPerKm"),
					energyWh = d("energyWh"),
					usedAh = d("usedAh"),
					consumptionWhPerKm = d("consumptionWhPerKm"),
					consumptionWhPerKm100m = d("consumptionWhPerKm100m"),
					coverageWhPerKm = d("coverageWhPerKm"),
					weakCellFactor = d("weakCellFactor"),
					farOdometerKm = d("farOdometerKm"),
					farTripKm = d("farTripKm"),
					chargeTripKm = d("chargeTripKm"),
					farSpeedKmh = d("farSpeedKmh"),
					wheelSpeedKmh = d("wheelSpeedKmh"),
					wheelOdometerKm = d("wheelOdometerKm"),
					cadenceRpm = d("cadenceRpm"),
					farAvgWhPerKm = d("farAvgWhPerKm"),
					gpsUnreliable = json.optBoolean("gpsUnreliable"),
					usedFarDriverDistance = json.optBoolean("usedFarDriverDistance"),
					rangeReserveKm = d("rangeReserveKm"),
					stopTimeMs = l("stopTimeMs")
				)
			} catch (_: Exception) {
				null
			}
		}
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

	fun toJson(): JSONObject {
		val json = JSONObject()
		json.put("timeMs", timeMs)
		json.putOpt("lat", lat)
		json.putOpt("lon", lon)
		json.putOpt("gpsSpeedKmh", gpsSpeedKmh)
		json.putOpt("socPercent", socPercent)
		json.putOpt("voltageV", voltageV)
		json.putOpt("currentA", currentA)
		json.putOpt("remainingAh", remainingAh)
		json.putOpt("fullAh", fullAh)
		json.putOpt("bmsTempC", bmsTempC)
		json.putOpt("cycles", cycles)
		json.putOpt("minCellVoltageV", minCellVoltageV)
		json.putOpt("maxCellVoltageV", maxCellVoltageV)
		json.putOpt("cellImbalanceV", cellImbalanceV)
		json.putOpt("controllerVoltageV", controllerVoltageV)
		json.putOpt("controllerCurrentA", controllerCurrentA)
		json.putOpt("controllerPowerW", controllerPowerW)
		json.putOpt("rpm", rpm)
		json.putOpt("gear", gear)
		json.putOpt("motorTempC", motorTempC)
		json.putOpt("controllerTempC", controllerTempC)
		json.putOpt("remainingRangeKm", remainingRangeKm)
		json.putOpt("windowRangeKm", windowRangeKm)
		json.putOpt("pnzRangeKm", pnzRangeKm)
		json.putOpt("socVoltagePercent", socVoltagePercent)
		json.putOpt("consumptionAhPerKm", consumptionAhPerKm)
		json.putOpt("energyWh", energyWh)
		json.putOpt("usedAh", usedAh)
		json.putOpt("consumptionWhPerKm", consumptionWhPerKm)
		json.putOpt("consumptionWhPerKm100m", consumptionWhPerKm100m)
		json.putOpt("coverageWhPerKm", coverageWhPerKm)
		json.putOpt("weakCellFactor", weakCellFactor)
		json.putOpt("farOdometerKm", farOdometerKm)
		json.putOpt("farTripKm", farTripKm)
		json.putOpt("chargeTripKm", chargeTripKm)
		json.putOpt("farSpeedKmh", farSpeedKmh)
		json.putOpt("wheelSpeedKmh", wheelSpeedKmh)
		json.putOpt("wheelOdometerKm", wheelOdometerKm)
		json.putOpt("cadenceRpm", cadenceRpm)
		json.putOpt("farAvgWhPerKm", farAvgWhPerKm)
		json.putOpt("rangeReserveKm", rangeReserveKm)
		json.putOpt("stopTimeMs", stopTimeMs)
		json.put("gpsUnreliable", gpsUnreliable)
		json.put("usedFarDriverDistance", usedFarDriverDistance)
		return json
	}
}
