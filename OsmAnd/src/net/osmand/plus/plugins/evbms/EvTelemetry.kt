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
	val minCellVoltageV: Double? = null,
	val controllerVoltageV: Double? = null,
	val controllerCurrentA: Double? = null,
	val controllerPowerW: Double? = null,
	val rpm: Int? = null,
	val gear: Int? = null,
	val motorTempC: Double? = null,
	val controllerTempC: Double? = null,
	val remainingRangeKm: Double? = null,
	val consumptionAhPerKm: Double? = null,
	val consumptionWhPerKm: Double? = null,
	val farOdometerKm: Double? = null,
	val farTripKm: Double? = null,
	val farSpeedKmh: Double? = null,
	val farAvgWhPerKm: Double? = null,
	val gpsUnreliable: Boolean = false,
	val usedFarDriverDistance: Boolean = false
) {
	companion object {
		fun csvHeader(): String {
			return "time_ms,lat,lon,gps_speed_kmh,soc_percent,voltage_v,current_a,remaining_ah,full_ah," +
					"bms_temp_c,cycles,min_cell_v,controller_voltage_v,controller_current_a,controller_power_w," +
					"rpm,gear,motor_temp_c,controller_temp_c,remaining_range_km,consumption_ah_per_km," +
					"consumption_wh_per_km,far_odometer_km,far_trip_km,far_speed_kmh,far_avg_wh_per_km," +
					"gps_unreliable,used_far_distance"
		}
	}

	fun toCsvRow(): String {
		return listOf(
			timeMs, n(lat), n(lon), n(gpsSpeedKmh), n(socPercent), n(voltageV), n(currentA),
			n(remainingAh), n(fullAh), n(bmsTempC), n(cycles), n(minCellVoltageV),
			n(controllerVoltageV), n(controllerCurrentA), n(controllerPowerW), n(rpm), n(gear),
			n(motorTempC), n(controllerTempC), n(remainingRangeKm), n(consumptionAhPerKm),
			n(consumptionWhPerKm), n(farOdometerKm), n(farTripKm), n(farSpeedKmh), n(farAvgWhPerKm),
			if (gpsUnreliable) 1 else 0, if (usedFarDriverDistance) 1 else 0
		).joinToString(",")
	}

	private fun n(v: Any?): String = v?.toString() ?: ""
}
