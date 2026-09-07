package net.osmand.plus.plugins.evbms

import android.content.Intent
import android.location.Location
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import net.osmand.PlatformUtil
import net.osmand.plus.OsmandApplication
import net.osmand.plus.utils.AndroidUtils
import net.osmand.shared.gpx.PointAttributes
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class TelemetryRecorder(private val app: OsmandApplication) {

	companion object {
		private val LOG = PlatformUtil.getLog(TelemetryRecorder::class.java)
		const val DIR_NAME = "ev_telemetry"
		private const val GPX_HEADER =
			"""<?xml version="1.0" encoding="UTF-8"?>""" + "\n" +
					"""<gpx version="1.1" creator="OsmAnd EV BMS" xmlns="http://www.topografix.com/GPX/1/1" xmlns:osmand="https://osmand.net/docs/technical/osmand-file-formats/osmand-gpx">""" +
					"\n<trk>\n<trkseg>\n"
		private const val GPX_TRACK_CLOSE = "</trkseg>\n</trk>\n"
		private const val GPX_FOOTER = "</gpx>\n"
		private val GPX_POINT = Regex("""<trkpt\s+lat="([^"]+)"\s+lon="([^"]+)"""")
		private val GPX_TIME = Regex("""<time>([^<]+)</time>""")
	}

	data class CsvEntry(
		val name: String,
		val uri: Uri,
		val spec: String,
		val lastModified: Long,
		val sizeBytes: Long
	)

	data class LogSession(
		val stamp: String,
		val files: List<CsvEntry>,
		val sizeBytes: Long,
		val durationMs: Long?,
		val distanceM: Double?
	)

	private var csvWriter: Writer? = null
	private var gpxWriter: Writer? = null
	private var csvSpec: String? = null
	private var gpxSpec: String? = null
	private var folderUri: String? = null
	private var fields: List<TelemetryField> = TelemetryField.parse(null)
	private var gpxFields: List<TelemetryField> = fields
	private var sessionFields: List<TelemetryField> = fields
	private var writeGpx = false
	private var lastFingerprint: String? = null
	private val pendingWaypoints = ArrayList<String>()

	fun setFolderUri(uri: String?) {
		folderUri = uri?.takeIf { it.isNotBlank() }
	}

	fun setFields(selected: List<TelemetryField>) {
		if (selected.isNotEmpty()) {
			fields = selected
		}
	}

	fun setGpxFields(selected: List<TelemetryField>) {
		gpxFields = selected
	}

	@Synchronized
	fun setWriteGpx(enabled: Boolean) {
		if (writeGpx == enabled) {
			return
		}
		writeGpx = enabled
		if (!enabled) {
			try {
				gpxWriter?.append(gpxCloseXml())
				gpxWriter?.flush()
				gpxWriter?.close()
			} catch (_: Exception) {
			}
			gpxWriter = null
			gpxSpec = null
		}
	}

	fun currentCsvSpec(): String? = csvSpec

	fun currentGpxSpec(): String? = gpxSpec

	fun sessionFieldIds(): String = sessionFields.joinToString(",") { it.id }

	fun activeStamp(): String? = stampOf(csvSpec)

	@Synchronized
	fun startNewSession(): Boolean {
		detach()
		csvSpec = null
		gpxSpec = null
		pendingWaypoints.clear()
		val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
		sessionFields = fields
		val csv = createFile("$stamp.csv", "text/csv") ?: return false
		val csvOut = openWriter(csv, append = false) ?: return false
		beginCsv(csvOut)
		csvSpec = csv
		if (writeGpx) {
			val gpx = createFile("$stamp.gpx", "application/gpx+xml")
			if (gpx != null) {
				val gpxOut = openWriter(gpx, append = false)
				if (gpxOut != null) {
					beginGpx(gpxOut)
					gpxSpec = gpx
				}
			}
		}
		lastFingerprint = null
		return csvWriter != null
	}

	@Synchronized
	fun pause() {
		closeWriters(writeFooter = false)
	}

	@Synchronized
	fun resume(): Boolean {
		val csv = csvSpec ?: return false
		if (!specExists(csv)) {
			return false
		}
		val csvOut = openWriter(csv, append = true) ?: return false
		csvWriter = csvOut
		val gpx = gpxSpec
		if (!gpx.isNullOrBlank() && specExists(gpx)) {
			gpxWriter = openWriter(gpx, append = true)
		}
		lastFingerprint = null
		return true
	}

	@Synchronized
	fun restore(csv: String, gpx: String?, fieldIds: String?): Boolean {
		if (csv.isBlank() || !specExists(csv)) {
			return false
		}
		closeWriters(writeFooter = false)
		csvSpec = csv
		gpxSpec = gpx?.takeIf { it.isNotBlank() && specExists(it) }
		val parsed = TelemetryField.parse(fieldIds)
		if (parsed.isNotEmpty()) {
			sessionFields = parsed
		}
		return true
	}

	@Synchronized
	fun saveAndClose(): Boolean {
		try {
			var gpx = gpxWriter
			if (gpx == null) {
				val spec = gpxSpec
				if (!spec.isNullOrBlank() && specExists(spec)) {
					gpx = openWriter(spec, append = true)
					gpxWriter = gpx
				}
			}
			gpx?.append(gpxCloseXml())
			gpx?.flush()
		} catch (_: Exception) {
		}
		closeWriters(writeFooter = false)
		csvSpec = null
		gpxSpec = null
		lastFingerprint = null
		return true
	}

	@Synchronized
	fun detach() {
		closeWriters(writeFooter = false)
		lastFingerprint = null
	}

	private fun beginCsv(out: Writer) {
		out.append(sessionFields.joinToString(";") { it.id }).append('\n')
		out.flush()
		csvWriter = out
	}

	private fun beginGpx(out: Writer) {
		out.append(GPX_HEADER)
		out.flush()
		gpxWriter = out
	}

	@Synchronized
	fun append(sample: EvTelemetry) {
		val fingerprint = (sessionFields + gpxFields).distinct().joinToString("\u001f") {
			it.fingerprint(sample)
		}
		if (fingerprint == lastFingerprint) {
			return
		}
		lastFingerprint = fingerprint
		appendCsv(sample)
		appendGpx(sample, null, null)
	}

	@Synchronized
	fun appendNamedPoint(sample: EvTelemetry, name: String, description: String) {
		lastFingerprint = null
		appendCsv(sample)
		appendGpx(sample, name, description)
	}

	@Synchronized
	fun appendWaypoint(lat: Double, lon: Double, timeMs: Long, name: String, description: String) {
		pendingWaypoints.add(waypointXml(lat, lon, timeMs, name, description))
	}

	private fun waypointXml(
		lat: Double,
		lon: Double,
		timeMs: Long,
		name: String,
		description: String
	): String {
		val sb = StringBuilder()
		sb.append("""<wpt lat="""").append(fmt(lat, "%.8f")).append('"')
			.append(""" lon="""").append(fmt(lon, "%.8f")).append("\">\n")
		sb.append("<time>").append(isoUtc(timeMs)).append("</time>\n")
		sb.append("<name>").append(xml(name)).append("</name>\n")
		if (description.isNotBlank()) {
			sb.append("<desc>").append(xml(description)).append("</desc>\n")
		}
		sb.append("<extensions>\n")
		sb.append("<osmand:icon>charging_station</osmand:icon>\n")
		sb.append("<osmand:background>circle</osmand:background>\n")
		sb.append("<osmand:color>#43A047</osmand:color>\n")
		sb.append("</extensions>\n")
		sb.append("</wpt>\n")
		return sb.toString()
	}

	private fun gpxCloseXml(): String {
		val sb = StringBuilder()
		sb.append(GPX_TRACK_CLOSE)
		for (wpt in pendingWaypoints) {
			sb.append(wpt)
		}
		pendingWaypoints.clear()
		sb.append(GPX_FOOTER)
		return sb.toString()
	}

	private fun appendCsv(sample: EvTelemetry) {
		val w = csvWriter ?: return
		try {
			w.append(sessionFields.joinToString(";") { it.csvValue(sample) }).append('\n')
			w.flush()
		} catch (e: Exception) {
			LOG.error("Cannot write CSV", e)
		}
	}

	private fun appendGpx(sample: EvTelemetry, name: String?, description: String?) {
		if (!writeGpx) {
			return
		}
		val w = gpxWriter ?: return
		val lat = sample.lat
		val lon = sample.lon
		if (lat == null || lon == null) {
			return
		}
		try {
			val iso = isoUtc(sample.timeMs)
			val sb = StringBuilder()
			sb.append("""<trkpt lat="""").append(fmt(lat, "%.8f")).append('"')
				.append(""" lon="""").append(fmt(lon, "%.8f")).append("\">\n")
			sb.append("<time>").append(iso).append("</time>\n")
			if (!name.isNullOrBlank()) {
				sb.append("<name>").append(xml(name)).append("</name>\n")
			}
			if (!description.isNullOrBlank()) {
				sb.append("<desc>").append(xml(description)).append("</desc>\n")
			}
			val extras = gpxFields.filter {
				it != TelemetryField.LAT && it != TelemetryField.LON && it != TelemetryField.TIME_MS
			}
			val body = LinkedHashMap<String, String>()
			for (field in extras) {
				val value = field.csvValue(sample)
				if (value.isNotEmpty()) {
					body[field.id] = value
				}
			}
			fun addEv(field: TelemetryField, tag: String, value: String) {
				if (field in gpxFields && value.isNotEmpty()) {
					body[tag] = value
				}
			}
			fun addEvAlways(tag: String, value: String) {
				if (value.isNotEmpty()) {
					body[tag] = value
				}
			}
			val whKm100m = sample.consumptionWhPerKm100m?.takeIf { it.isFinite() }
				?.let { String.format(Locale.US, "%.1f", it) }
			addEvAlways(PointAttributes.EV_TAG_CONSUMPTION_100M, whKm100m.orEmpty())
			addEvAlways(PointAttributes.EV_TAG_POWER, TelemetryField.POWER.csvValue(sample))
			addEvAlways(
				PointAttributes.EV_TAG_CONSUMPTION,
				whKm100m ?: TelemetryField.COVERAGE.csvValue(sample)
			)
			addEv(TelemetryField.CONSUMPTION, PointAttributes.EV_TAG_ENERGY, TelemetryField.CONSUMPTION.csvValue(sample))
			addEv(TelemetryField.VOLTAGE, PointAttributes.EV_TAG_VOLTAGE, TelemetryField.VOLTAGE.csvValue(sample))
			addEv(TelemetryField.CURRENT, PointAttributes.EV_TAG_CURRENT, TelemetryField.CURRENT.csvValue(sample))
			addEv(TelemetryField.SOC, PointAttributes.EV_TAG_SOC, TelemetryField.SOC.csvValue(sample))
			addEv(TelemetryField.CHARGE_TRIP, PointAttributes.EV_TAG_CHARGE_TRIP, TelemetryField.CHARGE_TRIP.csvValue(sample))
			if (body.isNotEmpty()) {
				sb.append("<extensions>\n")
				for ((id, value) in body) {
					sb.append("<osmand:").append(id).append('>').append(xml(value))
						.append("</osmand:").append(id).append(">\n")
				}
				sb.append("</extensions>\n")
			}
			sb.append("</trkpt>\n")
			w.append(sb)
			w.flush()
		} catch (e: Exception) {
			LOG.error("Cannot write GPX", e)
		}
	}

	private fun closeWriters(writeFooter: Boolean) {
		try {
			if (writeFooter) {
				gpxWriter?.append(gpxCloseXml())
			}
			gpxWriter?.flush()
			gpxWriter?.close()
		} catch (_: Exception) {
		}
		gpxWriter = null
		try {
			csvWriter?.flush()
			csvWriter?.close()
		} catch (_: Exception) {
		}
		csvWriter = null
	}

	@get:Synchronized
	val isRecording: Boolean
		get() = csvWriter != null

	fun folderSummary(): String {
		val tree = folderTree()
		if (tree != null) {
			return tree.name?.takeIf { it.isNotBlank() } ?: folderUri.orEmpty()
		}
		return app.getAppPath(DIR_NAME).absolutePath
	}

	fun listFiles(): List<CsvEntry> {
		val out = ArrayList<CsvEntry>()
		val seen = HashSet<String>()
		val tree = folderTree()
		if (tree != null) {
			for (file in tree.listFiles()) {
				val name = file.name ?: continue
				if (!isLogName(name) || !file.isFile) {
					continue
				}
				out.add(
					CsvEntry(
						name,
						file.uri,
						file.uri.toString(),
						file.lastModified(),
						file.length()
					)
				)
				seen.add(name)
			}
		}
		val dir = app.getAppPath(DIR_NAME)
		if (dir.isDirectory) {
			dir.listFiles()?.forEach { file ->
				if (file.isFile && isLogName(file.name) && file.name !in seen) {
					out.add(
						CsvEntry(
							file.name,
							AndroidUtils.getUriForFile(app, file),
							file.absolutePath,
							file.lastModified(),
							file.length()
						)
					)
				}
			}
		}
		return out.sortedByDescending { it.lastModified }
	}

	fun listSessions(): List<LogSession> {
		return listFiles()
			.groupBy { it.name.substringBeforeLast('.') }
			.map { (stamp, files) ->
				val csv = files.firstOrNull { it.name.endsWith(".csv", true) }
				val gpx = files.firstOrNull { it.name.endsWith(".gpx", true) }
				val stats = when {
					csv != null -> parseCsvStats(csv.spec)
					gpx != null -> parseGpxStats(gpx.spec)
					else -> null to null
				}
				LogSession(
					stamp = stamp,
					files = files.sortedBy { it.name },
					sizeBytes = files.sumOf { it.sizeBytes },
					durationMs = stats.first,
					distanceM = stats.second
				)
			}
			.sortedByDescending { session -> session.files.maxOfOrNull { it.lastModified } ?: 0L }
	}

	fun deleteSession(session: LogSession): Boolean {
		val active = activeStamp()
		if (active != null && active == session.stamp) {
			return false
		}
		var deleted = false
		for (file in session.files) {
			deleted = deleteSpec(file.spec) || deleted
		}
		return deleted
	}

	fun share(activity: android.app.Activity, uris: List<Uri>) {
		if (uris.isEmpty()) {
			return
		}
		val intent = if (uris.size == 1) {
			Intent(Intent.ACTION_SEND).apply {
				type = mimeFor(uris[0])
				putExtra(Intent.EXTRA_STREAM, uris[0])
			}
		} else {
			Intent(Intent.ACTION_SEND_MULTIPLE).apply {
				type = "*/*"
				putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
			}
		}
		intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
		AndroidUtils.startActivityIfSafe(activity, Intent.createChooser(intent, null))
	}

	private fun createFile(name: String, mime: String): String? {
		val tree = folderTree()
		if (tree != null) {
			try {
				val created = tree.createFile(mime, name)
				if (created != null) {
					return created.uri.toString()
				}
			} catch (e: Exception) {
				LOG.error("Cannot create $name in SAF folder", e)
			}
		}
		val dir = app.getAppPath(DIR_NAME)
		if (!dir.exists() && !dir.mkdirs()) {
			return null
		}
		return File(dir, name).absolutePath
	}

	private fun openWriter(spec: String, append: Boolean): Writer? {
		return try {
			if (spec.startsWith("content:")) {
				val mode = if (append) "wa" else "wt"
				val os = app.contentResolver.openOutputStream(Uri.parse(spec), mode) ?: return null
				OutputStreamWriter(os, StandardCharsets.UTF_8)
			} else {
				val file = File(spec)
				file.parentFile?.mkdirs()
				FileWriter(file, append)
			}
		} catch (e: Exception) {
			LOG.error("Cannot open writer $spec", e)
			null
		}
	}

	private fun openInput(spec: String): InputStream? {
		return try {
			if (spec.startsWith("content:")) {
				app.contentResolver.openInputStream(Uri.parse(spec))
			} else {
				FileInputStream(File(spec))
			}
		} catch (_: Exception) {
			null
		}
	}

	private fun specExists(spec: String): Boolean {
		openInput(spec)?.use { return true }
		return false
	}

	private fun deleteSpec(spec: String): Boolean {
		return try {
			if (spec.startsWith("content:")) {
				val uri = Uri.parse(spec)
				val single = DocumentFile.fromSingleUri(app, uri)
				if (single != null && single.exists()) {
					return single.delete()
				}
				folderTree()?.listFiles()?.firstOrNull { it.uri == uri }?.delete() == true
			} else {
				File(spec).delete()
			}
		} catch (e: Exception) {
			LOG.error("Cannot delete $spec", e)
			false
		}
	}

	private fun stampOf(spec: String?): String? {
		if (spec.isNullOrBlank()) {
			return null
		}
		val name = if (spec.startsWith("content:")) {
			DocumentFile.fromSingleUri(app, Uri.parse(spec))?.name
				?: Uri.parse(spec).lastPathSegment?.substringAfterLast('/')
		} else {
			File(spec).name
		} ?: return null
		return name.substringBeforeLast('.')
	}

	private fun parseCsvStats(spec: String): Pair<Long?, Double?> {
		val stream = openInput(spec) ?: return null to null
		InputStreamReader(stream, StandardCharsets.UTF_8).buffered().use { input ->
			val header = input.readLine() ?: return null to null
			val cols = header.split(';')
			val timeIdx = cols.indexOfFirst { it.equals(TelemetryField.TIME_MS.id, true) }
			val latIdx = cols.indexOfFirst { it.equals(TelemetryField.LAT.id, true) }
			val lonIdx = cols.indexOfFirst { it.equals(TelemetryField.LON.id, true) }
			return scanTrack({ input.readLine() }) { line ->
				val parts = line.split(';')
				val time = if (timeIdx >= 0 && timeIdx < parts.size) parts[timeIdx].toLongOrNull() else null
				val lat = if (latIdx >= 0 && latIdx < parts.size) parts[latIdx].toDoubleOrNull() else null
				val lon = if (lonIdx >= 0 && lonIdx < parts.size) parts[lonIdx].toDoubleOrNull() else null
				Triple(time, lat, lon)
			}
		}
	}

	private fun parseGpxStats(spec: String): Pair<Long?, Double?> {
		val text = openInput(spec)?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
			?: return null to null
		val times = ArrayList<Long>()
		val points = ArrayList<Pair<Double, Double>>()
		for (match in GPX_POINT.findAll(text)) {
			val lat = match.groupValues[1].toDoubleOrNull() ?: continue
			val lon = match.groupValues[2].toDoubleOrNull() ?: continue
			points.add(lat to lon)
		}
		val timeFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}
		for (match in GPX_TIME.findAll(text)) {
			try {
				val parsed = timeFmt.parse(match.groupValues[1])?.time ?: continue
				times.add(parsed)
			} catch (_: Exception) {
			}
		}
		val duration = if (times.size >= 2) (times.last() - times.first()).coerceAtLeast(0L) else null
		var distance: Double? = null
		if (points.size >= 2) {
			var sum = 0.0
			for (i in 1 until points.size) {
				val out = FloatArray(1)
				Location.distanceBetween(
					points[i - 1].first,
					points[i - 1].second,
					points[i].first,
					points[i].second,
					out
				)
				sum += out[0]
			}
			distance = sum
		}
		return duration to distance
	}

	private inline fun scanTrack(
		readLine: () -> String?,
		parse: (String) -> Triple<Long?, Double?, Double?>
	): Pair<Long?, Double?> {
		var firstT: Long? = null
		var lastT: Long? = null
		var prevLat: Double? = null
		var prevLon: Double? = null
		var dist = 0.0
		var hasDist = false
		while (true) {
			val line = readLine() ?: break
			if (line.isBlank()) {
				continue
			}
			val (time, lat, lon) = parse(line)
			if (time != null) {
				if (firstT == null) {
					firstT = time
				}
				lastT = time
			}
			if (lat != null && lon != null) {
				val previousLat = prevLat
				val previousLon = prevLon
				if (previousLat != null && previousLon != null) {
					val out = FloatArray(1)
					Location.distanceBetween(previousLat, previousLon, lat, lon, out)
					dist += out[0]
					hasDist = true
				}
				prevLat = lat
				prevLon = lon
			}
		}
		val duration = if (firstT != null && lastT != null && lastT >= firstT) lastT - firstT else null
		return duration to if (hasDist) dist else null
	}

	private fun isLogName(name: String): Boolean {
		return name.endsWith(".csv", true) || name.endsWith(".gpx", true)
	}

	private fun mimeFor(uri: Uri): String {
		return if (uri.toString().endsWith(".gpx", true)) "application/gpx+xml" else "text/csv"
	}

	private fun folderTree(): DocumentFile? {
		val uri = folderUri ?: return null
		return try {
			DocumentFile.fromTreeUri(app, Uri.parse(uri))
		} catch (_: Exception) {
			null
		}
	}

	private fun isoUtc(timeMs: Long): String {
		val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
		fmt.timeZone = TimeZone.getTimeZone("UTC")
		return fmt.format(Date(timeMs))
	}

	private fun fmt(v: Double, pattern: String): String = String.format(Locale.US, pattern, v)

	private fun xml(value: String): String {
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
	}
}
