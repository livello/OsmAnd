package net.osmand.plus.plugins.evbms

import net.osmand.PlatformUtil
import net.osmand.plus.OsmandApplication
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale

class EvHistoryStore(private val app: OsmandApplication) {

	companion object {
		private val LOG = PlatformUtil.getLog(EvHistoryStore::class.java)
		const val MAX_ROWS = 80
		const val CHARGE_FILE = "charge_history.csv"
		const val TRIP_FILE = "charge_trip_history.csv"
		const val REAL_TRIP_KM = 0.2
		private const val CHARGE_HEADER =
			"start_time;end_time;duration_min;start_temp_c;end_temp_c;charged_ah;energy_wh;avg_current_a;start_lat;start_lon;end_lat;end_lon"
		private const val TRIP_HEADER =
			"start_time;end_time;date;duration_min;moving_min;stop_min;distance_km;energy_wh;used_ah;wh_per_km;avg_moving_kmh;start_voltage_v;end_voltage_v;min_cell_v;start_temp_c;end_temp_c;start_lat;start_lon;end_lat;end_lon"
		private const val MERGE_GAP_MS = 30 * 60_000L
		private const val MIN_CHARGE_PIECE_MS = 5 * 60_000L
		private const val MIN_TRIP_PIECE_MS = 60_000L
		private const val MIN_DAY_PIECE_MS = 60_000L
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

		fun isRealRide(): Boolean = (distanceKm ?: 0.0) >= REAL_TRIP_KM

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

	fun realTrips(rows: List<ChargeTripRecord>): List<ChargeTripRecord> =
		rows.filter { it.isRealRide() }

	fun <T> unionByStart(
		slave: List<T>,
		incoming: List<T>,
		windowMs: Long = 120_000L,
		startMs: (T) -> Long
	): MutableList<T> {
		val byStart = LinkedHashMap<Long, T>()
		fun put(row: T) {
			val start = startMs(row)
			val hit = byStart.keys.firstOrNull { kotlin.math.abs(it - start) < windowMs }
			if (hit != null) {
				byStart.remove(hit)
			}
			byStart[start] = row
		}
		for (row in incoming) {
			put(row)
		}
		for (row in slave) {
			put(row)
		}
		return byStart.values.sortedBy(startMs).toMutableList()
	}

	fun parseChargeCsvText(text: String): List<ChargeRecord> {
		val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
		if (lines.size < 2) {
			return emptyList()
		}
		return lines.drop(1).mapNotNull { parseChargeCsvLine(it) }
	}

	fun parseTripCsvText(text: String): List<ChargeTripRecord> {
		val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
		if (lines.size < 2) {
			return emptyList()
		}
		return lines.drop(1).mapNotNull { parseTripCsvLine(it) }
	}

	fun encodeCharges(rows: List<ChargeRecord>): String {
		return JSONArray().also { arr ->
			rows.sortedBy { it.startMs }.takeLast(MAX_ROWS).forEach { arr.put(it.toJson()) }
		}.toString()
	}

	fun encodeTrips(rows: List<ChargeTripRecord>): String {
		return JSONArray().also { arr ->
			rows.sortedBy { it.startMs }.takeLast(MAX_ROWS).forEach { arr.put(it.toJson()) }
		}.toString()
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
				val ride = rides.firstOrNull { it.startMs > charge.startMs }
				if (ride != null) {
					val cut = ride.startMs
					if (cut - charge.startMs >= MIN_CHARGE_PIECE_MS) {
						out.add(scaleChargePiece(charge, charge.startMs, cut, first = true, last = true))
					}
				} else if (!charges.any { later -> later.startMs > charge.startMs && !later.isOpen() }) {
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

	fun splitTripsAroundCharges(
		trips: List<ChargeTripRecord>,
		charges: List<ChargeRecord>,
		nowMs: Long = System.currentTimeMillis()
	): List<ChargeTripRecord> {
		val chargeWindows = charges.map { charge ->
			val end = if (charge.endMs > 0L) charge.endMs else nowMs
			charge.startMs to end
		}.filter { (start, end) -> end > start }.sortedBy { it.first }
		if (chargeWindows.isEmpty() || trips.isEmpty()) {
			return trips
		}
		val out = ArrayList<ChargeTripRecord>(trips.size)
		for (trip in trips) {
			val overlapping = chargeWindows.filter { (start, end) ->
				trip.startMs < end && trip.endMs > start
			}
			if (overlapping.isEmpty()) {
				out.add(trip)
				continue
			}
			var cursor = trip.startMs
			for ((chargeStart, chargeEnd) in overlapping) {
				val cut = minOf(chargeStart, trip.endMs)
				if (cut - cursor >= MIN_TRIP_PIECE_MS) {
					scaleTripPiece(trip, cursor, cut)?.let { out.add(it) }
				}
				cursor = maxOf(cursor, chargeEnd)
			}
			if (trip.endMs - cursor >= MIN_TRIP_PIECE_MS) {
				scaleTripPiece(trip, cursor, trip.endMs)?.let { out.add(it) }
			}
		}
		return out.sortedBy { it.startMs }
	}

	fun prepareDisplay(
		charges: List<ChargeRecord>,
		trips: List<ChargeTripRecord>,
		nowMs: Long = System.currentTimeMillis()
	): Pair<List<ChargeRecord>, List<ChargeTripRecord>> {
		val splitTrips = splitTripsAroundCharges(trips, charges, nowMs).sortedBy { it.startMs }
		val clipped = splitChargesAroundTrips(charges, splitTrips, nowMs)
		val dayCharges = clipped.flatMap { splitChargeAcrossDays(it, nowMs) }
		val dayTrips = splitTrips.flatMap { splitTripAcrossDays(it) }.filter { it.isRealRide() }
		return dayCharges to dayTrips
	}

	fun splitChargeAcrossDays(row: ChargeRecord, nowMs: Long = System.currentTimeMillis()): List<ChargeRecord> {
		val end = if (row.endMs > 0L) row.endMs else nowMs
		val pieces = splitIntervalByDays(row.startMs, end)
		if (pieces.size <= 1) {
			return listOf(row)
		}
		val total = (end - row.startMs).coerceAtLeast(1L).toDouble()
		return pieces.mapIndexed { index, (start, pieceEnd) ->
			val last = index == pieces.lastIndex
			val storedEnd = if (row.isOpen() && last) 0L else pieceEnd
			val ratio = (pieceEnd - start) / total
			scaleChargePiece(row, start, storedEnd, index == 0, last, ratio)
		}
	}

	fun splitTripAcrossDays(row: ChargeTripRecord): List<ChargeTripRecord> {
		val pieces = splitIntervalByDays(row.startMs, row.endMs)
		if (pieces.size <= 1) {
			return listOf(row)
		}
		val total = row.durationMs().coerceAtLeast(1L).toDouble()
		return pieces.mapIndexed { index, (start, end) ->
			val ratio = (end - start) / total
			val first = index == 0
			val last = index == pieces.lastIndex
			row.copy(
				startMs = start,
				endMs = end,
				startVoltageV = if (first) row.startVoltageV else row.endVoltageV,
				endVoltageV = if (last) row.endVoltageV else row.startVoltageV,
				startTempC = if (first) row.startTempC else row.endTempC,
				endTempC = if (last) row.endTempC else row.startTempC,
				startMotorTempC = if (first) row.startMotorTempC else row.endMotorTempC,
				endMotorTempC = if (last) row.endMotorTempC else row.startMotorTempC,
				distanceKm = row.distanceKm?.times(ratio),
				movingMs = (row.movingMs * ratio).toLong(),
				energyWh = row.energyWh?.times(ratio),
				usedAh = row.usedAh?.times(ratio),
				stopMs = row.stopMs?.let { (it * ratio).toLong() },
				startLat = if (first) row.startLat else row.endLat,
				startLon = if (first) row.startLon else row.endLon,
				endLat = if (last) row.endLat else row.startLat,
				endLon = if (last) row.endLon else row.startLon
			)
		}
	}

	fun splitIntervalByDays(startMs: Long, endMs: Long): List<Pair<Long, Long>> {
		if (endMs <= startMs) {
			return listOf(startMs to endMs)
		}
		val out = ArrayList<Pair<Long, Long>>()
		var cursor = startMs
		while (cursor < endMs) {
			val nextDay = nextDayStartMs(cursor)
			val pieceEnd = minOf(nextDay, endMs)
			if (pieceEnd - cursor >= MIN_DAY_PIECE_MS || out.isEmpty() && pieceEnd == endMs) {
				out.add(cursor to pieceEnd)
			} else if (out.isNotEmpty()) {
				val prev = out.removeAt(out.lastIndex)
				out.add(prev.first to pieceEnd)
			} else {
				out.add(cursor to pieceEnd)
			}
			cursor = pieceEnd
		}
		return if (out.isEmpty()) listOf(startMs to endMs) else out
	}

	fun dayStartMs(ms: Long): Long {
		val cal = Calendar.getInstance()
		cal.timeInMillis = ms
		cal.set(Calendar.HOUR_OF_DAY, 0)
		cal.set(Calendar.MINUTE, 0)
		cal.set(Calendar.SECOND, 0)
		cal.set(Calendar.MILLISECOND, 0)
		return cal.timeInMillis
	}

	fun nextDayStartMs(ms: Long): Long {
		val cal = Calendar.getInstance()
		cal.timeInMillis = dayStartMs(ms)
		cal.add(Calendar.DAY_OF_MONTH, 1)
		return cal.timeInMillis
	}

	private fun scaleTripPiece(trip: ChargeTripRecord, start: Long, end: Long): ChargeTripRecord? {
		val total = trip.durationMs().coerceAtLeast(1L).toDouble()
		val ratio = (end - start).coerceAtLeast(1L) / total
		val first = start <= trip.startMs
		val last = end >= trip.endMs
		val piece = trip.copy(
			startMs = start,
			endMs = end,
			startVoltageV = if (first) trip.startVoltageV else trip.endVoltageV,
			endVoltageV = if (last) trip.endVoltageV else trip.startVoltageV,
			startTempC = if (first) trip.startTempC else trip.endTempC,
			endTempC = if (last) trip.endTempC else trip.startTempC,
			startMotorTempC = if (first) trip.startMotorTempC else trip.endMotorTempC,
			endMotorTempC = if (last) trip.endMotorTempC else trip.startMotorTempC,
			distanceKm = trip.distanceKm?.times(ratio),
			movingMs = (trip.movingMs * ratio).toLong(),
			energyWh = trip.energyWh?.times(ratio),
			usedAh = trip.usedAh?.times(ratio),
			stopMs = trip.stopMs?.let { (it * ratio).toLong() },
			startLat = if (first) trip.startLat else trip.endLat,
			startLon = if (first) trip.startLon else trip.endLon,
			endLat = if (last) trip.endLat else trip.startLat,
			endLon = if (last) trip.endLon else trip.startLon
		)
		return piece.takeIf { it.isRealRide() }
	}

	private fun scaleChargePiece(
		charge: ChargeRecord,
		start: Long,
		end: Long,
		first: Boolean,
		last: Boolean,
		ratio: Double? = null
	): ChargeRecord {
		val chargeEnd = if (charge.endMs > 0L) charge.endMs else maxOf(end, start + 1L)
		val pieceEnd = if (end > 0L) end else chargeEnd
		val pieceRatio = ratio ?: ((pieceEnd - start).coerceAtLeast(1L).toDouble() /
			(chargeEnd - charge.startMs).coerceAtLeast(1L).toDouble())
		return charge.copy(
			startMs = start,
			endMs = end,
			startTempC = if (first) charge.startTempC else charge.endTempC,
			endTempC = if (last) charge.endTempC else charge.startTempC,
			chargedAh = charge.chargedAh?.times(pieceRatio),
			energyWh = charge.energyWh?.times(pieceRatio),
			avgCurrentA = charge.avgCurrentA,
			startMinCellV = if (first) charge.startMinCellV else charge.endMinCellV,
			endMinCellV = if (last) charge.endMinCellV else charge.startMinCellV,
			startLat = if (first) charge.startLat else charge.endLat,
			startLon = if (first) charge.startLon else charge.endLon,
			endLat = if (last) charge.endLat else charge.startLat,
			endLon = if (last) charge.endLon else charge.startLon
		)
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
		appendCsv(TRIP_FILE, TRIP_HEADER, tripCells(row))
	}

	fun rewriteTripCsv(rows: List<ChargeTripRecord>) {
		writeCsv(TRIP_FILE, TRIP_HEADER, rows.map { tripCells(it) })
	}

	fun readTripCsv(): List<ChargeTripRecord> {
		val file = File(app.getAppPath(TelemetryRecorder.DIR_NAME), TRIP_FILE)
		if (!file.isFile) {
			return emptyList()
		}
		return try {
			val lines = file.readLines()
			if (lines.size < 2) {
				return emptyList()
			}
			lines.drop(1).mapNotNull { parseTripCsvLine(it) }
		} catch (e: Exception) {
			LOG.error("Cannot read $TRIP_FILE", e)
			emptyList()
		}
	}

	private fun parseTripCsvLine(line: String): ChargeTripRecord? {
		val p = line.split(';')
		if (p.size < 7) {
			return null
		}
		val startMs = parseTime(p[0]) ?: return null
		val endMs = parseTime(p.getOrNull(1) ?: "") ?: return null
		fun d(i: Int): Double? = p.getOrNull(i)?.toDoubleOrNull()
		val dateLike = p.getOrNull(2)?.matches(Regex("""\d{4}-\d{2}-\d{2}""")) == true
		return if (dateLike && p.size >= 16) {
			val movingMin = d(4) ?: 0.0
			ChargeTripRecord(
				startMs = startMs,
				endMs = endMs,
				startVoltageV = d(11),
				endVoltageV = d(12),
				minCellV = d(13),
				startTempC = d(14),
				endTempC = d(15),
				distanceKm = d(6),
				movingMs = (movingMin * 60_000.0).toLong(),
				energyWh = d(7),
				usedAh = d(8),
				specificWhKm = d(9),
				avgMovingKmh = d(10),
				stopMs = d(5)?.let { (it * 60_000.0).toLong() },
				startLat = d(16),
				startLon = d(17),
				endLat = d(18),
				endLon = d(19)
			)
		} else {
			null
		}
	}

	private fun tripCells(row: ChargeTripRecord): List<String> {
		return listOf(
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
