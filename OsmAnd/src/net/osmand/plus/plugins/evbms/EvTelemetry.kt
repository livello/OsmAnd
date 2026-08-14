package net.osmand.plus.plugins.evbms

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
	val controllerVoltageV: Double? = null,
	val controllerCurrentA: Double? = null,
	val controllerPowerW: Double? = null,
	val rpm: Int? = null,
	val gear: Int? = null,
	val motorTempC: Double? = null,
	val controllerTempC: Double? = null,
	val remainingRangeKm: Double? = null,
	val consumptionAhPerKm: Double? = null,
	val consumptionWhPerKm: Double? = null
) {
	companion object {
		fun csvHeader(): String {
			return "time_ms,lat,lon,gps_speed_kmh,soc_percent,voltage_v,current_a,remaining_ah,full_ah," +
					"bms_temp_c,cycles,controller_voltage_v,controller_current_a,controller_power_w," +
					"rpm,gear,motor_temp_c,controller_temp_c,remaining_range_km,consumption_ah_per_km," +
					"consumption_wh_per_km"
		}
	}

	fun toCsvRow(): String {
		return listOf(
			timeMs, n(lat), n(lon), n(gpsSpeedKmh), n(socPercent), n(voltageV), n(currentA),
			n(remainingAh), n(fullAh), n(bmsTempC), n(cycles), n(controllerVoltageV),
			n(controllerCurrentA), n(controllerPowerW), n(rpm), n(gear), n(motorTempC),
			n(controllerTempC), n(remainingRangeKm), n(consumptionAhPerKm), n(consumptionWhPerKm)
		).joinToString(",")
	}

	private fun n(v: Any?): String = v?.toString() ?: ""
}
