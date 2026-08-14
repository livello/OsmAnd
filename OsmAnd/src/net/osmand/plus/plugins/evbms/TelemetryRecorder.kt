package net.osmand.plus.plugins.evbms

import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import net.osmand.PlatformUtil
import net.osmand.plus.OsmandApplication
import net.osmand.plus.utils.AndroidUtils
import java.io.File
import java.io.FileWriter
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
					"""<gpx version="1.1" creator="OsmAnd EV BMS" xmlns="http://www.topografix.com/GPX/1/1">""" +
					"\n<trk>\n<trkseg>\n"
		private const val GPX_FOOTER = "</trkseg>\n</trk>\n</gpx>\n"
	}

	data class CsvEntry(val name: String, val uri: Uri, val lastModified: Long)

	private var csvWriter: Writer? = null
	private var gpxWriter: Writer? = null
	private var folderUri: String? = null
	private var fields: List<TelemetryField> = TelemetryField.parse(null)
	private var writeGpx = false
	private var lastFingerprint: String? = null

	fun setFolderUri(uri: String?) {
		folderUri = uri?.takeIf { it.isNotBlank() }
	}

	fun setFields(selected: List<TelemetryField>) {
		if (selected.isNotEmpty()) {
			fields = selected
		}
	}

	fun setWriteGpx(enabled: Boolean) {
		writeGpx = enabled
	}

	@Synchronized
	fun start(): Boolean {
		stop()
		val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
		val csvOk = openCsv("$stamp.csv")
		if (!csvOk) {
			return false
		}
		if (writeGpx) {
			openGpx("$stamp.gpx")
		}
		lastFingerprint = null
		return true
	}

	private fun openCsv(name: String): Boolean {
		return try {
			val tree = folderTree()
			val writer = if (tree != null) {
				val created = tree.createFile("text/csv", name) ?: return startAppCsv(name)
				val os = app.contentResolver.openOutputStream(created.uri) ?: return startAppCsv(name)
				OutputStreamWriter(os, StandardCharsets.UTF_8)
			} else {
				return startAppCsv(name)
			}
			beginCsv(writer)
			true
		} catch (e: Exception) {
			LOG.error("Cannot start CSV log", e)
			false
		}
	}

	private fun startAppCsv(name: String): Boolean {
		val dir = app.getAppPath(DIR_NAME)
		if (!dir.exists() && !dir.mkdirs()) {
			return false
		}
		beginCsv(FileWriter(File(dir, name), true))
		return true
	}

	private fun beginCsv(out: Writer) {
		out.append(fields.joinToString(";") { it.id }).append('\n')
		out.flush()
		csvWriter = out
	}

	private fun openGpx(name: String) {
		try {
			val tree = folderTree()
			val writer = if (tree != null) {
				val created = tree.createFile("application/gpx+xml", name) ?: return startAppGpx(name)
				val os = app.contentResolver.openOutputStream(created.uri) ?: return startAppGpx(name)
				OutputStreamWriter(os, StandardCharsets.UTF_8)
			} else {
				return startAppGpx(name)
			}
			beginGpx(writer)
		} catch (e: Exception) {
			LOG.error("Cannot start GPX log", e)
		}
	}

	private fun startAppGpx(name: String) {
		val dir = app.getAppPath(DIR_NAME)
		if (!dir.exists() && !dir.mkdirs()) {
			return
		}
		beginGpx(FileWriter(File(dir, name), true))
	}

	private fun beginGpx(out: Writer) {
		out.append(GPX_HEADER)
		out.flush()
		gpxWriter = out
	}

	@Synchronized
	fun append(sample: EvTelemetry) {
		val fingerprint = fields.joinToString("\u001f") { it.fingerprint(sample) }
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

	private fun appendCsv(sample: EvTelemetry) {
		val w = csvWriter ?: return
		try {
			w.append(fields.joinToString(";") { it.csvValue(sample) }).append('\n')
			w.flush()
		} catch (e: Exception) {
			LOG.error("Cannot write CSV", e)
		}
	}

	private fun appendGpx(sample: EvTelemetry, name: String?, description: String?) {
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
			val extras = fields.filter { it != TelemetryField.LAT && it != TelemetryField.LON && it != TelemetryField.TIME_MS }
			val body = extras.map { it.id to it.csvValue(sample) }.filter { it.second.isNotEmpty() }
			if (body.isNotEmpty()) {
				sb.append("<extensions>\n")
				for ((id, value) in body) {
					sb.append('<').append(id).append('>').append(xml(value))
						.append("</").append(id).append(">\n")
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

	@Synchronized
	fun stop() {
		try {
			gpxWriter?.append(GPX_FOOTER)
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
		lastFingerprint = null
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
				out.add(CsvEntry(name, file.uri, file.lastModified()))
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
							file.lastModified()
						)
					)
				}
			}
		}
		return out.sortedByDescending { it.lastModified }
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
