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
			"start_time;end_time;duration_min;start_temp_c;end_temp_c;charged_ah;energy_wh;avg_current_a;start_lat;start_lon;end_lat;end_lon"
		private const val TRIP_HEADER =
			"start_time;end_time;date;duration_min;moving_min;stop_min;distance_km;energy_wh;used_ah;wh_per_km;avg_moving_kmh;start_voltage_v;end_voltage_v;min_cell_v;start_temp_c;end_temp_c;start_lat;start_lon;end_lat;end_lon"
		private const val REAL_TRIP_KM = 0.2
		private const val REAL_TRIP_MOVING_MS = 60_000L
		private const val MERGE_GAP_MS = 30 * 60_000L
		private const val MIN_CHARGE_PIECE_MS = 5 * 60_000L
		private const val KEEP_TAIL_MIN_MS = 4 * 60 * 60_000L
		private const val FOLLOW_TRIP_MAX_GAP_MS = 4 * 60 * 60_000L
	}

	data class ChargeRecord(
		val startMs: Long,
		val endMs: Long,
		val startTempC: Double?,
		val endTempC: Double?,
		val chargedAh: Double?,
		val avgCurrentA: Double? = null,
		val startMinCellV: Double? = null,
		val endMinCellV: Double? = null,
		val stopMs: Long? = null,
		val energyWh: Double? = null,
		val startLat: Double?,
		val startLon: Double?,
		val endLat: Double?,
		val endLon: Double?
	) {
		fun durationMs(): Long {
			val end = if (endMs > 0L) endMs else System.currentTimeMillis()
			return (end - startMs).coerceAtLeast(0L)
		}

		fun isOpen(): Boolean = endMs <= 0L

		fun overlapsRide(ride: ChargeTripRecord, nowMs: Long = System.currentTimeMillis()): Boolean {
			val end = if (endMs > 0L) endMs else nowMs
			return ride.startMs < end && ride.endMs > startMs
		}

		fun toJson(): JSONObject {
			return JSONObject()
				.put("startMs", startMs)
				.put("endMs", endMs)
				.putOpt("startTempC", startTempC)
				.putOpt("endTempC", endTempC)
				.putOpt("chargedAh", chargedAh)
				.putOpt("avgCurrentA", avgCurrentA)
				.putOpt("startMinCellV", startMinCellV)
				.putOpt("endMinCellV", endMinCellV)
				.putOpt("stopMs", stopMs)
				.putOpt("energyWh", energyWh)
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
					avgCurrentA = json.optNullableDouble("avgCurrentA"),
					startMinCellV = json.optNullableDouble("startMinCellV"),
					endMinCellV = json.optNullableDouble("endMinCellV"),
					stopMs = if (json.has("stopMs") && !json.isNull("stopMs")) json.optLong("stopMs") else null,
					energyWh = json.optNullableDouble("energyWh"),
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
		val startMotorTempC: Double? = null,
		val endMotorTempC: Double? = null,
		val distanceKm: Double?,
		val movingMs: Long,
		val energyWh: Double? = null,
		val usedAh: Double? = null,
		val specificWhKm: Double? = null,
		val avgMovingKmh: Double? = null,
		val stopMs: Long? = null,
		val startLat: Double?,
		val startLon: Double?,
		val endLat: Double?,
		val endLon: Double?
	) {
		fun durationMs(): Long = (endMs - startMs).coerceAtLeast(0L)

		fun isRealRide(): Boolean =
			(distanceKm ?: 0.0) >= REAL_TRIP_KM || movingMs >= REAL_TRIP_MOVING_MS

		fun toJson(): JSONObject {
			return JSONObject()
				.put("startMs", startMs)
				.put("endMs", endMs)
				.putOpt("startVoltageV", startVoltageV)
				.putOpt("endVoltageV", endVoltageV)
				.putOpt("minCellV", minCellV)
				.putOpt("startTempC", startTempC)
				.putOpt("endTempC", endTempC)
				.putOpt("startMotorTempC", startMotorTempC)
				.putOpt("endMotorTempC", endMotorTempC)
				.putOpt("distanceKm", distanceKm)
				.put("movingMs", movingMs)
				.putOpt("energyWh", energyWh)
				.putOpt("usedAh", usedAh)
				.putOpt("specificWhKm", specificWhKm)
				.putOpt("avgMovingKmh", avgMovingKmh)
				.putOpt("stopMs", stopMs)
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
					startMotorTempC = json.optNullableDouble("startMotorTempC"),
					endMotorTempC = json.optNullableDouble("endMotorTempC"),
					distanceKm = json.optNullableDouble("distanceKm"),
					movingMs = json.optLong("movingMs"),
					energyWh = json.optNullableDouble("energyWh"),
					usedAh = json.optNullableDouble("usedAh"),
					specificWhKm = json.optNullableDouble("specificWhKm"),
					avgMovingKmh = json.optNullableDouble("avgMovingKmh"),
					stopMs = if (json.has("stopMs") && !json.isNull("stopMs")) json.optLong("stopMs") else null,
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

	fun mergeChargesWithoutTrip(
		charges: List<ChargeRecord>,
		trips: List<ChargeTripRecord>
	): List<ChargeRecord> {
		if (charges.size < 2) {
			return charges
		}
		val rides = trips.filter { it.isRealRide() }
		val sorted = charges.sortedBy { it.startMs }
		val out = ArrayList<ChargeRecord>(sorted.size)
		var acc = sorted[0]
		for (i in 1 until sorted.size) {
			val next = sorted[i]
			val accEnd = if (acc.endMs > 0L) acc.endMs else acc.startMs
			val mergedEnd = maxOf(
				if (acc.endMs > 0L) acc.endMs else acc.startMs,
				if (next.endMs > 0L) next.endMs else next.startMs
			)
			val hasRide = rides.any { ride ->
				ride.startMs < next.startMs && ride.endMs > acc.startMs && ride.startMs < mergedEnd
			}
			val gap = next.startMs - accEnd
			val close = gap <= MERGE_GAP_MS || acc.isOpen() || next.isOpen()
			if (hasRide || !close) {
				out.add(acc)
				acc = next
			} else {
				acc = mergeChargePair(acc, next)
			}
		}
		out.add(acc)
		return out
	}

	fun splitChargesAroundTrips(
		charges: List<ChargeRecord>,
		trips: List<ChargeTripRecord>,
		nowMs: Long = System.currentTimeMillis()
	): List<ChargeRecord> {
		val rides = trips.filter { it.isRealRide() }.sortedBy { it.startMs }
		if (rides.isEmpty() || charges.isEmpty()) {
			return charges
		}
		val out = ArrayList<ChargeRecord>(charges.size)
		for (charge in charges) {
			if (charge.isOpen()) {
				val superseded = charges.any { later ->
					later.startMs > charge.startMs && !later.isOpen()
				} || rides.any { it.startMs > charge.startMs }
				if (!superseded) {
					out.add(charge)
				}
				continue
			}
			val chargeEnd = charge.endMs
			val overlapping = rides.filter { charge.overlapsRide(it, nowMs) }
			if (overlapping.isEmpty()) {
				out.add(charge)
				continue
			}
			val pieces = ArrayList<Pair<Long, Long>>()
			var cursor = charge.startMs
			for (ride in overlapping) {
				val cut = minOf(ride.startMs, chargeEnd)
				if (cut - cursor >= MIN_CHARGE_PIECE_MS) {
					pieces.add(cursor to cut)
				}
				cursor = maxOf(cursor, ride.endMs)
			}
			if (chargeEnd - cursor >= MIN_CHARGE_PIECE_MS) {
				val tailDur = chargeEnd - cursor
				val followedSoon = rides.any { ride ->
					ride.startMs >= cursor - 5_000L &&
						ride.startMs <= chargeEnd + FOLLOW_TRIP_MAX_GAP_MS &&
						ride.startMs != charge.endMs
				}
				if (charge.isOpen() || tailDur >= KEEP_TAIL_MIN_MS || followedSoon) {
					pieces.add(cursor to if (charge.isOpen()) 0L else chargeEnd)
				}
			}
			if (pieces.isEmpty()) {
				continue
			}
			val keptMs = pieces.sumOf { (start, end) ->
				(if (end > 0L) end else chargeEnd) - start
			}.coerceAtLeast(1L).toDouble()
			for ((index, piece) in pieces.withIndex()) {
				val start = piece.first
				val end = piece.second
				val dur = (if (end > 0L) end else chargeEnd) - start
				val ratio = dur / keptMs
				val first = index == 0
				val last = index == pieces.lastIndex
				out.add(
					charge.copy(
						startMs = start,
						endMs = end,
						startTempC = if (first) charge.startTempC else charge.endTempC,
						endTempC = if (last) charge.endTempC else charge.startTempC,
						chargedAh = charge.chargedAh?.times(ratio),
						energyWh = charge.energyWh?.times(ratio),
						avgCurrentA = charge.avgCurrentA,
						startMinCellV = if (first) charge.startMinCellV else charge.endMinCellV,
						endMinCellV = if (last) charge.endMinCellV else charge.startMinCellV,
						startLat = if (first) charge.startLat else charge.endLat,
						startLon = if (first) charge.startLon else charge.endLon,
						endLat = if (last) charge.endLat else charge.startLat,
						endLon = if (last) charge.endLon else charge.startLon
					)
				)
			}
		}
		return out.sortedBy { it.startMs }
	}

	fun mergeChargePair(a: ChargeRecord, b: ChargeRecord): ChargeRecord {
		val first = if (a.startMs <= b.startMs) a else b
		val last = if (a.startMs <= b.startMs) b else a
		val durA = a.durationMs().coerceAtLeast(1L).toDouble()
		val durB = b.durationMs().coerceAtLeast(1L).toDouble()
		val avg = when {
			a.avgCurrentA != null && b.avgCurrentA != null ->
				(a.avgCurrentA * durA + b.avgCurrentA * durB) / (durA + durB)
			else -> last.avgCurrentA ?: first.avgCurrentA
		}
		val energy = sumNullable(a.energyWh, b.energyWh)
		val charged = sumNullable(a.chargedAh, b.chargedAh)
		val open = first.isOpen() || last.isOpen()
		return ChargeRecord(
			startMs = first.startMs,
			endMs = if (open) 0L else maxOf(first.endMs, last.endMs),
			startTempC = first.startTempC,
			endTempC = last.endTempC ?: first.endTempC,
			chargedAh = charged,
			avgCurrentA = avg,
			startMinCellV = first.startMinCellV,
			endMinCellV = last.endMinCellV ?: first.endMinCellV,
			stopMs = last.stopMs ?: first.stopMs,
			energyWh = energy,
			startLat = first.startLat,
			startLon = first.startLon,
			endLat = last.endLat ?: first.endLat,
			endLon = last.endLon ?: first.endLon
		)
	}

	fun appendChargeCsv(row: ChargeRecord) {
		appendCsv(CHARGE_FILE, CHARGE_HEADER, chargeCells(row))
	}

	fun rewriteChargeCsv(rows: List<ChargeRecord>) {
		writeCsv(CHARGE_FILE, CHARGE_HEADER, rows.filter { !it.isOpen() }.map { chargeCells(it) })
	}

	fun rewriteMergedChargeCsv(trips: List<ChargeTripRecord>) {
		val fromFile = readChargeCsv()
		if (fromFile.isEmpty()) {
			return
		}
		rewriteChargeCsv(mergeChargesWithoutTrip(fromFile, trips))
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
				n(row.stopMs?.div(60000.0), "%.1f"),
				n(row.distanceKm, "%.3f"),
				n(row.energyWh, "%.1f"),
				n(row.usedAh, "%.3f"),
				n(row.specificWhKm, "%.1f"),
				n(row.avgMovingKmh, "%.1f"),
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

	private fun chargeCells(row: ChargeRecord): List<String> {
		return listOf(
			fmtTime(row.startMs),
			if (row.endMs > 0L) fmtTime(row.endMs) else "",
			n(row.durationMs() / 60000.0, "%.1f"),
			n(row.startTempC, "%.1f"),
			n(row.endTempC, "%.1f"),
			n(row.chargedAh, "%.3f"),
			n(row.energyWh, "%.1f"),
			n(row.avgCurrentA, "%.2f"),
			n(row.startLat, "%.8f"),
			n(row.startLon, "%.8f"),
			n(row.endLat, "%.8f"),
			n(row.endLon, "%.8f")
		)
	}

	fun readChargeCsv(): List<ChargeRecord> {
		val file = File(app.getAppPath(TelemetryRecorder.DIR_NAME), CHARGE_FILE)
		if (!file.isFile) {
			return emptyList()
		}
		return try {
			val lines = file.readLines()
			if (lines.size < 2) {
				return emptyList()
			}
			lines.drop(1).mapNotNull { parseChargeCsvLine(it) }
		} catch (e: Exception) {
			LOG.error("Cannot read $CHARGE_FILE", e)
			emptyList()
		}
	}

	private fun parseChargeCsvLine(line: String): ChargeRecord? {
		val p = line.split(';')
		if (p.size < 6) {
			return null
		}
		val startMs = parseTime(p[0]) ?: return null
		val endMs = parseTime(p[1]) ?: 0L
		val startTemp = p.getOrNull(3)?.toDoubleOrNull()
		val endTemp = p.getOrNull(4)?.toDoubleOrNull()
		val chargedAh = p.getOrNull(5)?.toDoubleOrNull()
		var energyWh: Double? = null
		var avgCurrentA: Double? = null
		var latIdx = 6
		fun looksLikeLat(s: String?): Boolean {
			val v = s?.toDoubleOrNull() ?: return false
			return v in -90.0..90.0 && kotlin.math.abs(v) >= 1.0
		}
		when {
			p.size >= 12 && looksLikeLat(p.getOrNull(8)) -> {
				energyWh = p.getOrNull(6)?.toDoubleOrNull()
				avgCurrentA = p.getOrNull(7)?.toDoubleOrNull()
				latIdx = 8
			}
			p.size >= 11 && looksLikeLat(p.getOrNull(7)) -> {
				avgCurrentA = p.getOrNull(6)?.toDoubleOrNull()
				latIdx = 7
			}
			looksLikeLat(p.getOrNull(6)) -> latIdx = 6
		}
		return ChargeRecord(
			startMs = startMs,
			endMs = endMs,
			startTempC = startTemp,
			endTempC = endTemp,
			chargedAh = chargedAh,
			avgCurrentA = avgCurrentA,
			energyWh = energyWh,
			startLat = p.getOrNull(latIdx)?.toDoubleOrNull(),
			startLon = p.getOrNull(latIdx + 1)?.toDoubleOrNull(),
			endLat = p.getOrNull(latIdx + 2)?.toDoubleOrNull(),
			endLon = p.getOrNull(latIdx + 3)?.toDoubleOrNull()
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

	private fun writeCsv(name: String, header: String, rows: List<List<String>>) {
		try {
			val dir = app.getAppPath(TelemetryRecorder.DIR_NAME)
			if (!dir.exists() && !dir.mkdirs()) {
				return
			}
			val file = File(dir, name)
			FileWriter(file, false).use { out ->
				out.append(header).append('\n')
				for (cells in rows) {
					out.append(cells.joinToString(";")).append('\n')
				}
			}
		} catch (e: Exception) {
			LOG.error("Cannot write $name", e)
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

	private fun parseTime(raw: String): Long? {
		if (raw.isBlank()) {
			return null
		}
		return try {
			SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(raw)?.time
		} catch (_: Exception) {
			null
		}
	}

	private fun n(v: Double?, fmt: String): String {
		if (v == null || v.isNaN() || v.isInfinite()) {
			return ""
		}
		return String.format(Locale.US, fmt, v)
	}

	private fun sumNullable(a: Double?, b: Double?): Double? {
		if (a == null && b == null) {
			return null
		}
		return (a ?: 0.0) + (b ?: 0.0)
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
