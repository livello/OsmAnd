package net.osmand.plus.plugins.evbms

import net.osmand.PlatformUtil
import net.osmand.plus.OsmandApplication
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EvHistoryStore(private val app: OsmandApplication) {

	companion object {
		private val LOG = PlatformUtil.getLog(EvHistoryStore::class.java)
		const val MAX_ROWS = 80
		private const val CHARGE_FILE = "charge_history.csv"
		private const val TRIP_FILE = "charge_trip_history.csv"
		private const val CHARGE_HEADER =
			"start_time;end_time;duration_min;start_temp_c;end_temp_c;charged_ah;start_lat;start_lon;end_lat;end_lon"
		private const val TRIP_HEADER =
			"start_time;end_time;date;duration_min;moving_min;distance_km;start_voltage_v;end_voltage_v;min_cell_v;start_temp_c;end_temp_c;start_lat;start_lon;end_lat;end_lon"
	}

	data class ChargeRecord(
		val startMs: Long,
		val endMs: Long,
		val startTempC: Double?,
		val endTempC: Double?,
		val chargedAh: Double?,
		val startLat: Double?,
		val startLon: Double?,
		val endLat: Double?,
		val endLon: Double?
	) {
		fun durationMs(): Long = (endMs - startMs).coerceAtLeast(0L)

		fun toJson(): JSONObject {
			return JSONObject()
				.put("startMs", startMs)
				.put("endMs", endMs)
				.putOpt("startTempC", startTempC)
				.putOpt("endTempC", endTempC)
				.putOpt("chargedAh", chargedAh)
				.putOpt("startLat", startLat)
				.putOpt("startLon", startLon)
				.putOpt("endLat", endLat)
				.putOpt("endLon", endLon)
		}

		companion object {
			fun fromJson(json: JSONObject): ChargeRecord {
				return ChargeRecord(
					startMs = json.optLong("startMs"),
					endMs = json.optLong("endMs"),
					startTempC = json.optNullableDouble("startTempC"),
					endTempC = json.optNullableDouble("endTempC"),
					chargedAh = json.optNullableDouble("chargedAh"),
					startLat = json.optNullableDouble("startLat"),
					startLon = json.optNullableDouble("startLon"),
					endLat = json.optNullableDouble("endLat"),
					endLon = json.optNullableDouble("endLon")
				)
			}
		}
	}

	data class ChargeTripRecord(
		val startMs: Long,
		val endMs: Long,
		val startVoltageV: Double?,
		val endVoltageV: Double?,
		val minCellV: Double?,
		val startTempC: Double?,
		val endTempC: Double?,
		val distanceKm: Double?,
		val movingMs: Long,
		val startLat: Double?,
		val startLon: Double?,
		val endLat: Double?,
		val endLon: Double?
	) {
		fun durationMs(): Long = (endMs - startMs).coerceAtLeast(0L)

		fun toJson(): JSONObject {
			return JSONObject()
				.put("startMs", startMs)
				.put("endMs", endMs)
				.putOpt("startVoltageV", startVoltageV)
				.putOpt("endVoltageV", endVoltageV)
				.putOpt("minCellV", minCellV)
				.putOpt("startTempC", startTempC)
				.putOpt("endTempC", endTempC)
				.putOpt("distanceKm", distanceKm)
				.put("movingMs", movingMs)
				.putOpt("startLat", startLat)
				.putOpt("startLon", startLon)
				.putOpt("endLat", endLat)
				.putOpt("endLon", endLon)
		}

		companion object {
			fun fromJson(json: JSONObject): ChargeTripRecord {
				return ChargeTripRecord(
					startMs = json.optLong("startMs"),
					endMs = json.optLong("endMs"),
					startVoltageV = json.optNullableDouble("startVoltageV"),
					endVoltageV = json.optNullableDouble("endVoltageV"),
					minCellV = json.optNullableDouble("minCellV"),
					startTempC = json.optNullableDouble("startTempC"),
					endTempC = json.optNullableDouble("endTempC"),
					distanceKm = json.optNullableDouble("distanceKm"),
					movingMs = json.optLong("movingMs"),
					startLat = json.optNullableDouble("startLat"),
					startLon = json.optNullableDouble("startLon"),
					endLat = json.optNullableDouble("endLat"),
					endLon = json.optNullableDouble("endLon")
				)
			}
		}
	}

	fun parseCharges(raw: String?): List<ChargeRecord> {
		return parseArray(raw) { ChargeRecord.fromJson(it) }
	}

	fun parseTrips(raw: String?): List<ChargeTripRecord> {
		return parseArray(raw) { ChargeTripRecord.fromJson(it) }
	}

	fun encodeCharges(rows: List<ChargeRecord>): String {
		return JSONArray().also { arr -> rows.takeLast(MAX_ROWS).forEach { arr.put(it.toJson()) } }.toString()
	}

	fun encodeTrips(rows: List<ChargeTripRecord>): String {
		return JSONArray().also { arr -> rows.takeLast(MAX_ROWS).forEach { arr.put(it.toJson()) } }.toString()
	}

	fun appendChargeCsv(row: ChargeRecord) {
		appendCsv(
			CHARGE_FILE,
			CHARGE_HEADER,
			listOf(
				fmtTime(row.startMs),
				fmtTime(row.endMs),
				n(row.durationMs() / 60000.0, "%.1f"),
				n(row.startTempC, "%.1f"),
				n(row.endTempC, "%.1f"),
				n(row.chargedAh, "%.3f"),
				n(row.startLat, "%.8f"),
				n(row.startLon, "%.8f"),
				n(row.endLat, "%.8f"),
				n(row.endLon, "%.8f")
			)
		)
	}

	fun appendTripCsv(row: ChargeTripRecord) {
		appendCsv(
			TRIP_FILE,
			TRIP_HEADER,
			listOf(
				fmtTime(row.startMs),
				fmtTime(row.endMs),
				fmtDate(row.startMs),
				n(row.durationMs() / 60000.0, "%.1f"),
				n(row.movingMs / 60000.0, "%.1f"),
				n(row.distanceKm, "%.3f"),
				n(row.startVoltageV, "%.2f"),
				n(row.endVoltageV, "%.2f"),
				n(row.minCellV, "%.3f"),
				n(row.startTempC, "%.1f"),
				n(row.endTempC, "%.1f"),
				n(row.startLat, "%.8f"),
				n(row.startLon, "%.8f"),
				n(row.endLat, "%.8f"),
				n(row.endLon, "%.8f")
			)
		)
	}

	private fun appendCsv(name: String, header: String, cells: List<String>) {
		try {
			val dir = app.getAppPath(TelemetryRecorder.DIR_NAME)
			if (!dir.exists() && !dir.mkdirs()) {
				return
			}
			val file = File(dir, name)
			val fresh = !file.exists() || file.length() == 0L
			FileWriter(file, true).use { out ->
				if (fresh) {
					out.append(header).append('\n')
				}
				out.append(cells.joinToString(";")).append('\n')
			}
		} catch (e: Exception) {
			LOG.error("Cannot append $name", e)
		}
	}

	private fun <T> parseArray(raw: String?, map: (JSONObject) -> T): List<T> {
		if (raw.isNullOrBlank()) {
			return emptyList()
		}
		return try {
			val arr = JSONArray(raw)
			(0 until arr.length()).map { map(arr.getJSONObject(it)) }
		} catch (e: Exception) {
			LOG.error("Cannot parse history", e)
			emptyList()
		}
	}

	private fun fmtTime(ms: Long): String {
		return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
	}

	private fun fmtDate(ms: Long): String {
		return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))
	}

	private fun n(v: Double?, fmt: String): String {
		if (v == null || v.isNaN() || v.isInfinite()) {
			return ""
		}
		return String.format(Locale.US, fmt, v)
	}
}

private fun JSONObject.putOpt(key: String, value: Double?): JSONObject {
	if (value != null && !value.isNaN() && !value.isInfinite()) {
		put(key, value)
	}
	return this
}

private fun JSONObject.optNullableDouble(key: String): Double? {
	if (!has(key) || isNull(key)) {
		return null
	}
	val value = optDouble(key, Double.NaN)
	return if (value.isNaN()) null else value
}
