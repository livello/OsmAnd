package net.osmand.plus.plugins.evbms

import net.osmand.IndexConstants
import net.osmand.plus.OsmandApplication
import net.osmand.plus.shared.SharedUtil
import net.osmand.shared.gpx.GpxDataItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * File-level catalog for LAN telemetry sync. Serves telemetry CSV/GPX, every OsmAnd
 * GPX under tracks/ (rec, import, user folders, My Places), and a cached current-route
 * GPX if present.
 */
class EvBmsSyncFiles(
	private val app: OsmandApplication,
	private val recorder: TelemetryRecorder
) {
	companion object {
		const val PREFIX_TELEMETRY = "ev_telemetry/"
		const val PREFIX_TRACKS = "tracks/"
		const val PREFIX_NAV = "navigation/"
		const val ROUTE_NAME = "route.gpx"
		const val INCOMING_SUFFIX = ".sync-incoming"

		fun encodePath(path: String): String =
			URLEncoder.encode(path, StandardCharsets.UTF_8.name()).replace("+", "%20")

		fun sanitizePath(raw: String): String? {
			val decoded = try {
				URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
			} catch (_: Exception) {
				raw
			}
			val norm = decoded.replace('\\', '/').trim().trimStart('/')
			if (norm.isBlank()) {
				return null
			}
			val parts = norm.split('/')
			if (parts.any { it.isEmpty() || it == "." || it == ".." }) {
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

		fun isTrackPath(path: String): Boolean = path.startsWith(PREFIX_TRACKS)

		fun fingerprint(size: Long, mtime: Long): String = "$size:$mtime"
	}

	enum class IncomingDecision {
		COPY,
		SKIP,
		COMPARE
	}

	data class Entry(
		val path: String,
		val name: String,
		val size: Long,
		val mtime: Long,
		val spec: String
	)

	fun catalogJson(deviceName: String, port: Int, pluginId: String, peerId: String): String {
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
			.put("plugin", pluginId)
			.put("id", peerId)
			.put("role", "sync-peer")
			.put("device", deviceName)
			.put("port", port)
			.put("rev", catalogRev())
			.put("files", files)
			.toString()
	}

	fun catalogRev(): Long {
		val files = listAll()
		if (files.isEmpty()) {
			return 0L
		}
		return files.size.toLong() * 1_000_003L +
			files.sumOf { it.size.coerceAtLeast(0L) } +
			files.maxOf { it.mtime }
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
		out.addAll(listAllTracks())
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
				val file = trackFile(clean) ?: return null
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
				val file = trackFile(clean)
				if (file != null && file.isFile) file.length() else 0L
			}
			clean.startsWith(PREFIX_NAV) -> routeFile()?.length() ?: 0L
			else -> 0L
		}
	}

	fun localMtime(path: String): Long {
		val clean = sanitizePath(path) ?: return 0L
		return when {
			clean.startsWith(PREFIX_TELEMETRY) -> recorder.existingMtime(clean.removePrefix(PREFIX_TELEMETRY))
			clean.startsWith(PREFIX_TRACKS) -> {
				val file = trackFile(clean)
				if (file != null && file.isFile) file.lastModified() else 0L
			}
			clean.startsWith(PREFIX_NAV) -> routeFile()?.lastModified() ?: 0L
			else -> 0L
		}
	}

	fun isLocallyRecording(path: String): Boolean {
		val clean = sanitizePath(path) ?: return false
		if (!clean.startsWith(PREFIX_TELEMETRY)) {
			return false
		}
		return recorder.isActiveFileName(clean.removePrefix(PREFIX_TELEMETRY))
	}

	fun decideIncoming(
		path: String,
		remoteSize: Long,
		remoteMtime: Long,
		pendingPath: Boolean = false,
		rememberedFingerprint: String? = null
	): IncomingDecision {
		val clean = sanitizePath(path) ?: return IncomingDecision.SKIP
		if (isHistoryPath(clean)) {
			return IncomingDecision.COPY
		}
		if (isLocallyRecording(clean) && recorder.isRecording) {
			return IncomingDecision.SKIP
		}
		val local = localSize(clean)
		if (local <= 0L) {
			return IncomingDecision.COPY
		}
		if (isTrackPath(clean)) {
			if (pendingPath) {
				return IncomingDecision.SKIP
			}
			val remoteFp = fingerprint(remoteSize, remoteMtime)
			if (rememberedFingerprint == remoteFp) {
				return IncomingDecision.SKIP
			}
			if (remoteSize == local && remoteMtime == localMtime(clean)) {
				return IncomingDecision.SKIP
			}
			return IncomingDecision.COMPARE
		}
		if (remoteSize <= 0L) {
			val remoteTime = remoteMtime
			val localTime = localMtime(clean)
			return if (remoteTime > 0L && remoteTime <= localTime) IncomingDecision.SKIP else IncomingDecision.COPY
		}
		if (remoteSize < local) {
			return IncomingDecision.SKIP
		}
		if (remoteSize == local && remoteMtime <= localMtime(clean)) {
			return IncomingDecision.SKIP
		}
		return IncomingDecision.COPY
	}

	/**
	 * Additive merge for telemetry/history: never delete local files; never replace a
	 * non-empty local copy with a smaller or older remote one. Track conflicts are
	 * classified separately via [decideIncoming].
	 */
	fun shouldSkipIncoming(path: String, remoteSize: Long = Long.MAX_VALUE, remoteMtime: Long = Long.MAX_VALUE): Boolean {
		return decideIncoming(path, remoteSize, remoteMtime) == IncomingDecision.SKIP
	}

	fun incomingTempFile(path: String): File? {
		val dest = localFile(path) ?: return null
		return File(dest.parentFile ?: dest, dest.name + INCOMING_SUFFIX)
	}

	fun localFile(path: String): File? {
		val clean = sanitizePath(path) ?: return null
		return when {
			clean.startsWith(PREFIX_TRACKS) -> trackFile(clean)
			clean.startsWith(PREFIX_NAV) -> {
				if (clean.removePrefix(PREFIX_NAV) != ROUTE_NAME) null
				else File(File(app.cacheDir, "share"), ROUTE_NAME)
			}
			else -> null
		}
	}

	fun sameContent(local: File, remote: File): Boolean {
		if (!local.isFile || !remote.isFile) {
			return false
		}
		if (local.length() != remote.length()) {
			return false
		}
		if (local.length() == 0L) {
			return true
		}
		return digest(local).contentEquals(digest(remote))
	}

	fun alignMtime(file: File, remoteMtime: Long) {
		if (remoteMtime > 0L && file.isFile) {
			file.setLastModified(remoteMtime)
		}
	}

	fun uniqueTrackFile(dest: File): File {
		val dir = dest.parentFile ?: dest
		val name = dest.name
		val dot = name.lastIndexOf('.')
		val base = if (dot > 0) name.substring(0, dot) else name
		val ext = if (dot > 0) name.substring(dot) else ""
		var n = 2
		var candidate = File(dir, "$base ($n)$ext")
		while (candidate.exists()) {
			n++
			candidate = File(dir, "$base ($n)$ext")
		}
		return candidate
	}

	fun commitIncomingFile(temp: File, dest: File, remoteMtime: Long, index: Boolean): Boolean {
		if (!temp.isFile) {
			return false
		}
		return try {
			dest.parentFile?.mkdirs()
			if (dest.exists() && dest.absolutePath != temp.absolutePath) {
				dest.delete()
			}
			val renamed = temp.renameTo(dest)
			if (!renamed) {
				temp.inputStream().use { input ->
					dest.outputStream().use { input.copyTo(it) }
				}
				temp.delete()
			}
			alignMtime(dest, remoteMtime)
			if (index) {
				indexTrack(dest)
			}
			dest.isFile
		} catch (_: Exception) {
			false
		}
	}

	fun writeIncoming(path: String, input: InputStream, remoteSize: Long, remoteMtime: Long): Boolean {
		val clean = sanitizePath(path) ?: return false
		if (isHistoryPath(clean)) {
			return false
		}
		if (decideIncoming(clean, remoteSize, remoteMtime) != IncomingDecision.COPY) {
			return false
		}
		val replace = localSize(clean) > 0L
		return when {
			clean.startsWith(PREFIX_TELEMETRY) -> {
				val name = clean.removePrefix(PREFIX_TELEMETRY)
				val ok = recorder.writeIncomingFile(name, input, replace)
				if (ok && name.endsWith(".gpx", true)) {
					val hit = recorder.listFiles().firstOrNull { it.name == name }
					if (hit != null) {
						publishTelemetryGpx(hit.name, hit.spec, hit.lastModified, hit.sizeBytes)
					}
				}
				ok
			}
			clean.startsWith(PREFIX_TRACKS) -> {
				val dest = trackFile(clean) ?: return false
				val ok = writePlain(dest, input, replace, remoteMtime)
				if (ok) {
					indexTrack(dest)
				}
				ok
			}
			clean.startsWith(PREFIX_NAV) -> {
				if (clean.removePrefix(PREFIX_NAV) != ROUTE_NAME) {
					return false
				}
				val dir = File(app.cacheDir, "share")
				writePlain(File(dir, ROUTE_NAME), input, replace, remoteMtime)
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

	private fun listAllTracks(): List<Entry> {
		val dir = app.getAppPath(IndexConstants.GPX_INDEX_DIR)
		if (!dir.isDirectory) {
			return emptyList()
		}
		val out = ArrayList<Entry>()
		collectGpx(dir, "", out)
		return out
	}

	private fun collectGpx(dir: File, relPrefix: String, out: MutableList<Entry>) {
		val files = dir.listFiles() ?: return
		for (file in files) {
			if (file.isDirectory) {
				collectGpx(file, relPrefix + file.name + "/", out)
				continue
			}
			if (!file.isFile || !file.name.endsWith(".gpx", true)) {
				continue
			}
			if (file.name.endsWith(INCOMING_SUFFIX, true) || file.name.endsWith(".part", true)) {
				continue
			}
			val rel = relPrefix + file.name
			out.add(
				Entry(
					path = PREFIX_TRACKS + rel,
					name = file.name,
					size = file.length(),
					mtime = file.lastModified(),
					spec = file.absolutePath
				)
			)
		}
	}

	private fun trackFile(cleanPath: String): File? {
		val rel = cleanPath.removePrefix(PREFIX_TRACKS)
		if (rel.isBlank() || rel.endsWith("/")) {
			return null
		}
		return File(app.getAppPath(IndexConstants.GPX_INDEX_DIR), rel)
	}

	fun alreadyHaveRemoteCopy(dest: File, incoming: File): Boolean {
		if (dest.isFile && sameContent(dest, incoming)) {
			return true
		}
		val size = incoming.length()
		if (size <= 0L) {
			return false
		}
		val files = dest.parentFile?.listFiles() ?: return false
		for (file in files) {
			if (!file.isFile || file.length() != size) {
				continue
			}
			if (!file.name.endsWith(".gpx", true) ||
				file.name.endsWith(INCOMING_SUFFIX, true) ||
				file.name.endsWith(".part", true)
			) {
				continue
			}
			if (file.absolutePath == incoming.absolutePath) {
				continue
			}
			if (sameContent(file, incoming)) {
				return true
			}
		}
		return false
	}

	private fun routeFile(): File? {
		val file = File(File(app.cacheDir, "share"), ROUTE_NAME)
		return file.takeIf { it.isFile && it.length() > 0L }
	}

	private fun writePlain(dest: File, input: InputStream, replace: Boolean = false, remoteMtime: Long = 0L): Boolean {
		if (!replace && dest.exists() && dest.length() > 0L) {
			return false
		}
		return try {
			dest.parentFile?.mkdirs()
			val part = File(dest.parentFile, dest.name + ".part")
			part.outputStream().use { input.copyTo(it) }
			if (!replace && dest.exists() && part.length() < dest.length()) {
				part.delete()
				return false
			}
			if (dest.exists()) {
				dest.delete()
			}
			val ok = part.renameTo(dest)
			if (ok) {
				alignMtime(dest, remoteMtime)
			}
			ok
		} catch (_: Exception) {
			false
		}
	}

	private fun digest(file: File): ByteArray {
		val md = MessageDigest.getInstance("SHA-256")
		file.inputStream().use { input ->
			val buf = ByteArray(64 * 1024)
			while (true) {
				val n = input.read(buf)
				if (n <= 0) {
					break
				}
				md.update(buf, 0, n)
			}
		}
		return md.digest()
	}

	private fun indexTrack(file: File) {
		if (!file.isFile || !file.name.endsWith(".gpx", true)) {
			return
		}
		try {
			val kFile = SharedUtil.kFile(file)
			if (!app.gpxDbHelper.hasGpxDataItem(kFile)) {
				app.gpxDbHelper.add(GpxDataItem(kFile))
			}
			app.gpxDbHelper.getItem(kFile, null, true)
		} catch (_: Exception) {
		}
	}

	fun publishVisibleGpx() {
		for (entry in recorder.listFiles()) {
			if (entry.name.endsWith(".gpx", true)) {
				publishTelemetryGpx(entry.name, entry.spec, entry.lastModified, entry.sizeBytes)
			}
		}
		indexTree(app.getAppPath(IndexConstants.GPX_INDEX_DIR))
		try {
			app.smartFolderHelper.notifyUpdateListeners()
		} catch (_: Exception) {
		}
	}

	private fun placesTelemetryDir(): File {
		return File(app.getAppPath(IndexConstants.GPX_INDEX_DIR), "ev_telemetry")
	}

	private fun publishTelemetryGpx(name: String, spec: String, mtime: Long, size: Long) {
		if (name.isBlank() || name.contains("..") || name.contains('/') || name.contains('\\')) {
			return
		}
		val dest = File(placesTelemetryDir(), name)
		try {
			dest.parentFile?.mkdirs()
			if (!dest.isFile || dest.length() != size || size <= 0L) {
				val input = recorder.openLog(spec) ?: return
				input.use { stream ->
					val part = File(dest.parentFile, dest.name + ".part")
					part.outputStream().use { stream.copyTo(it) }
					if (dest.exists()) {
						dest.delete()
					}
					if (!part.renameTo(dest)) {
						part.delete()
						return
					}
				}
				if (mtime > 0L) {
					dest.setLastModified(mtime)
				}
			}
			indexTrack(dest)
		} catch (_: Exception) {
		}
	}

	private fun indexTree(dir: File) {
		if (!dir.isDirectory) {
			return
		}
		val files = dir.listFiles() ?: return
		for (file in files) {
			if (file.isDirectory) {
				indexTree(file)
			} else if (file.isFile && file.name.endsWith(".gpx", true) &&
				!file.name.endsWith(INCOMING_SUFFIX, true) &&
				!file.name.endsWith(".part", true)
			) {
				indexTrack(file)
			}
		}
	}
}
