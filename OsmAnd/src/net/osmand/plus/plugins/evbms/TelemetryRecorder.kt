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

class TelemetryRecorder(private val app: OsmandApplication) {

	companion object {
		private val LOG = PlatformUtil.getLog(TelemetryRecorder::class.java)
		const val DIR_NAME = "ev_telemetry"
	}

	data class CsvEntry(val name: String, val uri: Uri, val lastModified: Long)

	private var writer: Writer? = null
	private var folderUri: String? = null

	fun setFolderUri(uri: String?) {
		folderUri = uri?.takeIf { it.isNotBlank() }
	}

	@Synchronized
	fun start(): Boolean {
		stop()
		val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".csv"
		return try {
			val tree = folderTree()
			if (tree != null) {
				val created = tree.createFile("text/csv", name) ?: return startAppDir(name)
				val os = app.contentResolver.openOutputStream(created.uri) ?: return startAppDir(name)
				openWriter(OutputStreamWriter(os, StandardCharsets.UTF_8))
				true
			} else {
				startAppDir(name)
			}
		} catch (e: Exception) {
			LOG.error("Cannot start telemetry log", e)
			false
		}
	}

	private fun startAppDir(name: String): Boolean {
		val dir = app.getAppPath(DIR_NAME)
		if (!dir.exists() && !dir.mkdirs()) {
			return false
		}
		openWriter(FileWriter(File(dir, name), true))
		return true
	}

	private fun openWriter(out: Writer) {
		out.append(EvTelemetry.csvHeader()).append('\n')
		out.flush()
		writer = out
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
	}

	@get:Synchronized
	val isRecording: Boolean
		get() = writer != null

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
				if (!name.endsWith(".csv", true) || !file.isFile) {
					continue
				}
				out.add(CsvEntry(name, file.uri, file.lastModified()))
				seen.add(name)
			}
		}
		val dir = app.getAppPath(DIR_NAME)
		if (dir.isDirectory) {
			dir.listFiles()?.forEach { file ->
				if (file.isFile && file.name.endsWith(".csv", true) && file.name !in seen) {
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
				type = "text/csv"
				putExtra(Intent.EXTRA_STREAM, uris[0])
			}
		} else {
			Intent(Intent.ACTION_SEND_MULTIPLE).apply {
				type = "text/csv"
				putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
			}
		}
		intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
		AndroidUtils.startActivityIfSafe(activity, Intent.createChooser(intent, null))
	}

	private fun folderTree(): DocumentFile? {
		val uri = folderUri ?: return null
		return try {
			DocumentFile.fromTreeUri(app, Uri.parse(uri))
		} catch (_: Exception) {
			null
		}
	}
}
