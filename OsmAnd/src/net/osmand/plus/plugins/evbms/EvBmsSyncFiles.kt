package net.osmand.plus.plugins.evbms

import net.osmand.IndexConstants
import net.osmand.plus.OsmandApplication
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * File-level catalog for LAN telemetry sync. Serves what the plugin already writes:
 * ev_telemetry CSV/GPX, recent OsmAnd rec tracks, and a cached current-route GPX if present.
 */
class EvBmsSyncFiles(
	private val app: OsmandApplication,
	private val recorder: TelemetryRecorder
) {
	companion object {
		const val PREFIX_TELEMETRY = "ev_telemetry/"
		const val PREFIX_TRACKS = "tracks/rec/"
		const val PREFIX_NAV = "navigation/"
		const val ROUTE_NAME = "route.gpx"
		private const val REC_RECENT_MS = 30L * 24 * 3_600_000L
		private const val REC_ALWAYS_MAX = 40

		fun encodePath(path: String): String =
			URLEncoder.encode(path, StandardCharsets.UTF_8.name()).replace("+", "%20")

		fun sanitizePath(raw: String): String? {
			val decoded = try {
				URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
			} catch (_: Exception) {
				raw
			}
			val norm = decoded.replace('\\', '/').trim().trimStart('/')
			if (norm.isBlank() || ".." in norm || norm.startsWith("/")) {
				return null
			}
			if (norm.startsWith(PREFIX_TELEMETRY) ||
				norm.startsWith(PREFIX_TRACKS) ||
				norm.startsWith(PREFIX_NAV)
			) {
				return norm
			}
			return null
		}

		fun isHistoryPath(path: String): Boolean {
			val name = path.substringAfterLast('/')
			return name.equals(EvHistoryStore.CHARGE_FILE, true) ||
				name.equals(EvHistoryStore.TRIP_FILE, true)
		}
	}

	data class Entry(
		val path: String,
		val name: String,
		val size: Long,
		val mtime: Long,
		val spec: String
	)

	fun catalogJson(deviceName: String, port: Int): String {
		recorder.flush()
		val files = JSONArray()
		for (entry in listAll()) {
			files.put(
				JSONObject()
					.put("path", entry.path)
					.put("name", entry.name)
					.put("size", entry.size)
					.put("mtime", entry.mtime)
			)
		}
		return JSONObject()
			.put("device", deviceName)
			.put("port", port)
			.put("files", files)
			.toString()
	}

	fun parseCatalog(text: String): List<Entry> {
		val json = JSONObject(text)
		val arr = json.optJSONArray("files") ?: return emptyList()
		val out = ArrayList<Entry>(arr.length())
		for (i in 0 until arr.length()) {
			val obj = arr.optJSONObject(i) ?: continue
			val path = sanitizePath(obj.optString("path")) ?: continue
			out.add(
				Entry(
					path = path,
					name = obj.optString("name").ifBlank { path.substringAfterLast('/') },
					size = obj.optLong("size"),
					mtime = obj.optLong("mtime"),
					spec = path
				)
			)
		}
		return out
	}

	fun listAll(): List<Entry> {
		val out = ArrayList<Entry>()
		out.addAll(listTelemetry())
		out.addAll(listRecordedTracks())
		routeFile()?.let { file ->
			out.add(
				Entry(
					path = PREFIX_NAV + ROUTE_NAME,
					name = ROUTE_NAME,
					size = file.length(),
					mtime = file.lastModified(),
					spec = file.absolutePath
				)
			)
		}
		return out.sortedBy { it.path }
	}

	fun open(path: String): InputStream? {
		val clean = sanitizePath(path) ?: return null
		when {
			clean.startsWith(PREFIX_TELEMETRY) -> {
				val name = clean.removePrefix(PREFIX_TELEMETRY)
				val hit = recorder.listFiles().firstOrNull { it.name == name } ?: return null
				return recorder.openLog(hit.spec)
			}
			clean.startsWith(PREFIX_TRACKS) -> {
				val file = File(app.getAppPath(IndexConstants.GPX_RECORDED_INDEX_DIR), clean.removePrefix(PREFIX_TRACKS))
				return if (file.isFile) file.inputStream() else null
			}
			clean.startsWith(PREFIX_NAV) -> {
				val file = routeFile() ?: return null
				if (clean.removePrefix(PREFIX_NAV) != ROUTE_NAME) {
					return null
				}
				return file.inputStream()
			}
			else -> return null
		}
	}

	fun localSize(path: String): Long {
		val clean = sanitizePath(path) ?: return 0L
		return when {
			clean.startsWith(PREFIX_TELEMETRY) -> recorder.existingSize(clean.removePrefix(PREFIX_TELEMETRY))
			clean.startsWith(PREFIX_TRACKS) -> {
				val file = File(app.getAppPath(IndexConstants.GPX_RECORDED_INDEX_DIR), clean.removePrefix(PREFIX_TRACKS))
				if (file.isFile) file.length() else 0L
			}
			clean.startsWith(PREFIX_NAV) -> routeFile()?.length() ?: 0L
			else -> 0L
		}
	}

	/** Additive slave write: never replace a local file that already has data. */
	fun shouldSkipIncoming(path: String): Boolean = localSize(path) > 0L

	fun writeNew(path: String, input: InputStream): Boolean {
		val clean = sanitizePath(path) ?: return false
		if (shouldSkipIncoming(clean) || isHistoryPath(clean)) {
			return false
		}
		return when {
			clean.startsWith(PREFIX_TELEMETRY) ->
				recorder.writeNewFile(clean.removePrefix(PREFIX_TELEMETRY), input)
			clean.startsWith(PREFIX_TRACKS) ->
				writePlain(
					File(app.getAppPath(IndexConstants.GPX_RECORDED_INDEX_DIR), clean.removePrefix(PREFIX_TRACKS)),
					input
				)
			clean.startsWith(PREFIX_NAV) -> {
				if (clean.removePrefix(PREFIX_NAV) != ROUTE_NAME) {
					return false
				}
				val dir = File(app.cacheDir, "share")
				writePlain(File(dir, ROUTE_NAME), input)
			}
			else -> false
		}
	}

	private fun listTelemetry(): List<Entry> {
		return recorder.listFiles().map { file ->
			Entry(
				path = PREFIX_TELEMETRY + file.name,
				name = file.name,
				size = file.sizeBytes,
				mtime = file.lastModified,
				spec = file.spec
			)
		}
	}

	private fun listRecordedTracks(): List<Entry> {
		val dir = app.getAppPath(IndexConstants.GPX_RECORDED_INDEX_DIR)
		if (!dir.isDirectory) {
			return emptyList()
		}
		val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".gpx", true) }
			?.sortedByDescending { it.lastModified() }
			?: return emptyList()
		val cutoff = System.currentTimeMillis() - REC_RECENT_MS
		val chosen = if (files.size <= REC_ALWAYS_MAX) {
			files.toList()
		} else {
			files.filter { it.lastModified() >= cutoff }
		}
		return chosen.map { file ->
			Entry(
				path = PREFIX_TRACKS + file.name,
				name = file.name,
				size = file.length(),
				mtime = file.lastModified(),
				spec = file.absolutePath
			)
		}
	}

	private fun routeFile(): File? {
		val file = File(File(app.cacheDir, "share"), ROUTE_NAME)
		return file.takeIf { it.isFile && it.length() > 0L }
	}

	private fun writePlain(dest: File, input: InputStream): Boolean {
		if (dest.exists() && dest.length() > 0L) {
			return false
		}
		return try {
			dest.parentFile?.mkdirs()
			val part = File(dest.parentFile, dest.name + ".part")
			part.outputStream().use { input.copyTo(it) }
			if (dest.exists()) {
				dest.delete()
			}
			part.renameTo(dest)
		} catch (_: Exception) {
			false
		}
	}
}
