package net.osmand.plus.plugins.evbms

import net.osmand.PlatformUtil
import net.osmand.plus.OsmandApplication
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

class EvHistoryChartStore(private val app: OsmandApplication) {

	companion object {
		private val LOG = PlatformUtil.getLog(EvHistoryChartStore::class.java)
		const val DIR = "history_charts"
		const val MAX_POINTS = 160
		const val KIND_CHARGE = "charge"
		const val KIND_TRIP = "trip"
		const val S_CURRENT = "current"
		const val S_TEMP = "temp"
		const val S_MIN_V = "minV"
		const val S_MAX_V = "maxV"
		const val S_POWER = "power"
		const val S_CONS = "cons"
		const val S_MOTOR = "motor"
	}

	data class Sample(
		val t: Long,
		val currentA: Double? = null,
		val battTempC: Double? = null,
		val minCellV: Double? = null,
		val maxCellV: Double? = null,
		val powerW: Double? = null,
		val consWhKm: Double? = null,
		val motorTempC: Double? = null
	)

	data class Chart(
		val kind: String,
		val t0: Long,
		val xs: FloatArray,
		val series: Map<String, FloatArray>
	) {
		fun last(id: String): Float? {
			val ys = series[id] ?: return null
			for (i in ys.indices.reversed()) {
				if (!ys[i].isNaN()) {
					return ys[i]
				}
			}
			return null
		}
	}

	fun save(kind: String, startMs: Long, endMs: Long, samples: List<Sample>) {
		val chart = downsample(kind, samples) ?: return
		try {
			file(kind, startMs, endMs).writeText(encode(chart), StandardCharsets.UTF_8)
		} catch (e: Exception) {
			LOG.error("Cannot save history chart", e)
		}
	}

	fun load(kind: String, startMs: Long, endMs: Long): Chart? {
		val f = file(kind, startMs, endMs)
		if (!f.isFile) {
			return null
		}
		return try {
			decode(JSONObject(f.readText(StandardCharsets.UTF_8)))
		} catch (e: Exception) {
			LOG.error("Cannot load history chart", e)
			null
		}
	}

	fun delete(kind: String, startMs: Long, endMs: Long) {
		try {
			file(kind, startMs, endMs).delete()
		} catch (_: Exception) {
		}
	}

	fun loadOrBuild(kind: String, startMs: Long, endMs: Long): Chart? {
		load(kind, startMs, endMs)?.let { return it }
		val samples = readTelemetryWindow(startMs, endMs)
		if (samples.size < 2) {
			return null
		}
		save(kind, startMs, endMs, samples)
		return load(kind, startMs, endMs)
	}

	fun downsample(kind: String, samples: List<Sample>): Chart? {
		if (samples.size < 2) {
			return null
		}
		val sorted = samples.sortedBy { it.t }
		val t0 = sorted.first().t
		val t1 = sorted.last().t
		val span = (t1 - t0).coerceAtLeast(1L)
		val buckets = minOf(MAX_POINTS, sorted.size)
		val step = span.toDouble() / (buckets - 1).coerceAtLeast(1)
		val xs = FloatArray(buckets)
		val keys = if (kind == KIND_CHARGE) {
			listOf(S_CURRENT, S_TEMP, S_MIN_V, S_MAX_V)
		} else {
			listOf(S_POWER, S_CONS, S_MOTOR, S_MIN_V)
		}
		val acc = keys.associateWith { FloatArray(buckets) { Float.NaN } }
		val counts = IntArray(buckets)
		for (s in sorted) {
			val idx = (((s.t - t0) / step).toInt()).coerceIn(0, buckets - 1)
			counts[idx]++
			put(acc[S_CURRENT], idx, s.currentA)
			put(acc[S_TEMP], idx, s.battTempC)
			put(acc[S_MIN_V], idx, s.minCellV)
			put(acc[S_MAX_V], idx, s.maxCellV)
			put(acc[S_POWER], idx, s.powerW)
			put(acc[S_CONS], idx, s.consWhKm)
			put(acc[S_MOTOR], idx, s.motorTempC)
		}
		for (i in 0 until buckets) {
			xs[i] = ((i * step) / 1000.0).toFloat()
		}
		val series = LinkedHashMap<String, FloatArray>()
		for (key in keys) {
			series[key] = acc[key]!!
		}
		if (counts.all { it == 0 }) {
			return null
		}
		return Chart(kind, t0, xs, series)
	}

	private fun put(arr: FloatArray?, idx: Int, v: Double?) {
		if (arr == null || v == null || v.isNaN() || v.isInfinite()) {
			return
		}
		val y = v.toFloat()
		arr[idx] = if (arr[idx].isNaN()) y else (arr[idx] + y) / 2f
	}

	private fun file(kind: String, startMs: Long, endMs: Long): File {
		val dir = File(app.getAppPath(TelemetryRecorder.DIR_NAME), DIR)
		if (!dir.exists()) {
			dir.mkdirs()
		}
		val prefix = if (kind == KIND_CHARGE) "c" else "t"
		return File(dir, "$prefix-$startMs-$endMs.json")
	}

	private fun encode(chart: Chart): String {
		val json = JSONObject()
		json.put("kind", chart.kind)
		json.put("t0", chart.t0)
		json.put("xs", JSONArray().also { arr -> chart.xs.forEach { arr.put(it.toDouble()) } })
		val s = JSONObject()
		for ((k, ys) in chart.series) {
			s.put(k, JSONArray().also { arr ->
				ys.forEach { y ->
					if (y.isNaN()) arr.put(JSONObject.NULL) else arr.put(y.toDouble())
				}
			})
		}
		json.put("s", s)
		return json.toString()
	}

	private fun decode(json: JSONObject): Chart {
		val xsArr = json.getJSONArray("xs")
		val xs = FloatArray(xsArr.length()) { xsArr.optDouble(it).toFloat() }
		val sObj = json.getJSONObject("s")
		val series = LinkedHashMap<String, FloatArray>()
		val keys = sObj.keys()
		while (keys.hasNext()) {
			val key = keys.next()
			val arr = sObj.getJSONArray(key)
			series[key] = FloatArray(arr.length()) { i ->
				if (arr.isNull(i)) Float.NaN else arr.optDouble(i).toFloat()
			}
		}
		return Chart(json.optString("kind"), json.optLong("t0"), xs, series)
	}

	private fun readTelemetryWindow(startMs: Long, endMs: Long): List<Sample> {
		val dir = app.getAppPath(TelemetryRecorder.DIR_NAME)
		if (!dir.isDirectory) {
			return emptyList()
		}
		val out = ArrayList<Sample>()
		dir.listFiles()?.forEach { file ->
			if (!file.isFile || !file.name.endsWith(".csv", true)) {
				return@forEach
			}
			val n = file.name.lowercase()
			if (n.startsWith("charge_") || n.startsWith("ev_debug") || n == "charge_history.csv") {
				return@forEach
			}
			if (file.lastModified() + 86_400_000L < startMs) {
				return@forEach
			}
			try {
				file.bufferedReader(StandardCharsets.UTF_8).use { reader ->
					val header = reader.readLine() ?: return@use
					val cols = header.split(';')
					val ti = idx(cols, TelemetryField.TIME_MS.id)
					if (ti < 0) {
						return@use
					}
					val iCur = idx(cols, TelemetryField.CURRENT.id)
					val iTemp = idx(cols, TelemetryField.BMS_TEMP.id)
					val iMin = idx(cols, TelemetryField.MIN_CELL.id)
					val iVolt = idx(cols, TelemetryField.VOLTAGE.id)
					val iPow = idx(cols, TelemetryField.POWER.id)
					val iCons = idx(cols, TelemetryField.CONSUMPTION.id)
					val iMot = idx(cols, TelemetryField.MOTOR_TEMP.id)
					reader.lineSequence().forEach { line ->
						if (line.isBlank()) {
							return@forEach
						}
						val p = line.split(';')
						val t = p.getOrNull(ti)?.toLongOrNull() ?: return@forEach
						if (t < startMs || t > endMs) {
							return@forEach
						}
						out.add(
							Sample(
								t = t,
								currentA = num(p, iCur),
								battTempC = num(p, iTemp),
								minCellV = num(p, iMin),
								maxCellV = num(p, iVolt),
								powerW = num(p, iPow),
								consWhKm = num(p, iCons),
								motorTempC = num(p, iMot)
							)
						)
					}
				}
			} catch (e: Exception) {
				LOG.error("Cannot scan ${file.name}", e)
			}
		}
		return out
	}

	private fun idx(cols: List<String>, id: String): Int =
		cols.indexOfFirst { it.equals(id, true) }

	private fun num(parts: List<String>, i: Int): Double? {
		if (i < 0 || i >= parts.size) {
			return null
		}
		return parts[i].toDoubleOrNull()
	}
}
