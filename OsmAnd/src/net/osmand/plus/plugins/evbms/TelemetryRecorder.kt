package net.osmand.plus.plugins.evbms

import net.osmand.PlatformUtil
import net.osmand.plus.OsmandApplication
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TelemetryRecorder(private val app: OsmandApplication) {

	companion object {
		private val LOG = PlatformUtil.getLog(TelemetryRecorder::class.java)
		const val DIR_NAME = "ev_telemetry"
	}

	private var writer: FileWriter? = null
	private var file: File? = null

	@Synchronized
	fun start(): File? {
		stop()
		return try {
			val dir = app.getAppPath(DIR_NAME)
			if (!dir.exists()) {
				dir.mkdirs()
			}
			val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".csv"
			val out = File(dir, name)
			val w = FileWriter(out, true)
			w.append(EvTelemetry.csvHeader()).append('\n')
			w.flush()
			writer = w
			file = out
			out
		} catch (e: Exception) {
			LOG.error("Cannot start telemetry log", e)
			null
		}
	}

	@Synchronized
	fun append(sample: EvTelemetry) {
		val w = writer ?: return
		try {
			w.append(sample.toCsvRow()).append('\n')
			w.flush()
		} catch (e: Exception) {
			LOG.error("Cannot write telemetry", e)
		}
	}

	@Synchronized
	fun stop() {
		try {
			writer?.flush()
			writer?.close()
		} catch (_: Exception) {
		}
		writer = null
		file = null
	}

	@get:Synchronized
	val isRecording: Boolean
		get() = writer != null
}
