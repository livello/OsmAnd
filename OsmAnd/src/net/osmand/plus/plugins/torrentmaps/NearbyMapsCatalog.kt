package net.osmand.plus.plugins.torrentmaps

import android.util.Log
import net.osmand.IndexConstants
import net.osmand.plus.OsmandApplication
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Builds and serves the local installable-maps catalog for offline peer exchange.
 */
class NearbyMapsCatalog(private val app: OsmandApplication) {

	companion object {
		private const val TAG = "NearbyMapsCatalog"
		private const val HASH_CACHE_FILE = "nearby_maps_sha256.tsv"
		private val SKIP_DIRS = setOf(
			"tracks", "favorites", "avnotes", "voice", "fonts", "tiles",
			"hidden", "backup", "rec", "import", "media", "help",
			"telemetry", "torrent_maps", "ev_torrent"
		)
		private val HEX = "0123456789abcdef".toCharArray()
	}

	private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.US)
	private val hashCache = ConcurrentHashMap<String, String>()
	private val hashing = ConcurrentHashMap.newKeySet<String>()
	private val hashExecutor = Executors.newSingleThreadExecutor { r ->
		Thread(r, "nearby-maps-hash").apply { isDaemon = true }
	}
	@Volatile
	private var cacheLoaded = false

	fun mapsRoot(): File = app.getAppPath(IndexConstants.MAPS_PATH)

	fun buildEntries(computeMissingHashes: Boolean = true): List<NearbyMapEntry> {
		ensureHashCacheLoaded()
		val root = mapsRoot()
		val out = ArrayList<NearbyMapEntry>()
		scanDir(root, root, out)
		out.sortBy { it.path.lowercase(Locale.US) }
		if (computeMissingHashes) {
			for (entry in out) {
				if (entry.sha256.isBlank()) {
					scheduleHash(entry)
				}
			}
		}
		return out
	}

	fun findByPath(relativePath: String): Pair<NearbyMapEntry, File>? {
		val normalized = TorrentBrowser.normalizePath(relativePath)
		if (normalized.isEmpty() || normalized.contains("..")) {
			return null
		}
		val file = File(mapsRoot(), normalized)
		val rootCanonical = mapsRoot().canonicalFile
		val fileCanonical = try {
			file.canonicalFile
		} catch (_: Exception) {
			return null
		}
		if (!fileCanonical.path.startsWith(rootCanonical.path) || !fileCanonical.isFile) {
			return null
		}
		if (!MapTorrentEngine.isMapFile(fileCanonical.name)) {
			return null
		}
		val entry = toEntry(rootCanonical, fileCanonical) ?: return null
		return entry to fileCanonical
	}

	fun findLocalByMapKey(mapKey: String): NearbyMapEntry? {
		return buildEntries(computeMissingHashes = false).firstOrNull { it.mapKey == mapKey }
	}

	fun toJson(
		entries: List<NearbyMapEntry>,
		deviceName: String,
		torrent: NearbyTorrentOffer? = null
	): String {
		val root = JSONObject()
		root.put("deviceName", deviceName)
		root.put("tokenRequired", true)
		if (torrent != null) {
			root.put("magnet", torrent.magnet)
			root.put("torrentName", torrent.torrentName)
			root.put("torrentDate", torrent.torrentDateMs)
			root.put("torrentAvailable", torrent.torrentAvailable)
		}
		val arr = JSONArray()
		for (e in entries) {
			arr.put(
				JSONObject()
					.put("id", e.id)
					.put("path", e.path)
					.put("fileName", e.fileName)
					.put("displayName", e.displayName)
					.put("mapKey", e.mapKey)
					.put("size", e.sizeBytes)
					.put("dateCreated", e.dateCreated)
					.put("sha256", e.sha256)
					.put("mtime", e.mtimeMs)
			)
		}
		root.put("maps", arr)
		return root.toString()
	}

	fun parseCatalog(json: String): NearbyCatalogResponse {
		val root = JSONObject(json)
		val maps = ArrayList<NearbyMapEntry>()
		val arr = root.optJSONArray("maps") ?: JSONArray()
		for (i in 0 until arr.length()) {
			val o = arr.getJSONObject(i)
			val path = TorrentBrowser.normalizePath(o.optString("path"))
			val fileName = o.optString("fileName").ifBlank { path.substringAfterLast('/') }
			maps.add(
				NearbyMapEntry(
					id = o.optString("id").ifBlank { path },
					path = path,
					fileName = fileName,
					displayName = o.optString("displayName").ifBlank {
						fileName.substringBeforeLast('.')
					},
					mapKey = o.optString("mapKey").ifBlank { MapTorrentEngine.mapKey(fileName) },
					sizeBytes = o.optLong("size"),
					dateCreated = o.optLong("dateCreated"),
					sha256 = o.optString("sha256"),
					mtimeMs = o.optLong("mtime")
				)
			)
		}
		return NearbyCatalogResponse(
			deviceName = root.optString("deviceName"),
			tokenRequired = root.optBoolean("tokenRequired", true),
			maps = maps,
			torrent = NearbyTorrentOffer(
				magnet = root.optString("magnet"),
				torrentName = root.optString("torrentName"),
				torrentDateMs = root.optLong("torrentDate"),
				torrentAvailable = root.optBoolean("torrentAvailable")
			).takeIf {
				it.magnet.isNotBlank() || it.torrentAvailable || it.torrentName.isNotBlank()
			}
		)
	}

	fun destinationFor(peerEntry: NearbyMapEntry): File {
		val dest = MapTorrentEngine.osmandDestFile(app, peerEntry.fileName.ifBlank { peerEntry.path })
		dest.parentFile?.mkdirs()
		return dest
	}

	private fun scanDir(root: File, dir: File, out: MutableList<NearbyMapEntry>) {
		val files = dir.listFiles() ?: return
		for (file in files) {
			if (file.isDirectory) {
				if (file.name.lowercase(Locale.US) in SKIP_DIRS) {
					continue
				}
				scanDir(root, file, out)
				continue
			}
			if (!MapTorrentEngine.isMapFile(file.name)) {
				continue
			}
			toEntry(root, file)?.let { out.add(it) }
		}
	}

	private fun toEntry(root: File, file: File): NearbyMapEntry? {
		val relative = try {
			TorrentBrowser.normalizePath(file.relativeTo(root).path)
		} catch (_: Exception) {
			return null
		}
		if (relative.isEmpty()) {
			return null
		}
		val size = file.length()
		val mtime = file.lastModified()
		val cacheKey = cacheKey(relative, size, mtime)
		val sha = hashCache[cacheKey].orEmpty()
		return NearbyMapEntry(
			id = relative,
			path = relative,
			fileName = file.name,
			displayName = file.name.substringBeforeLast('.'),
			mapKey = MapTorrentEngine.mapKey(file.name),
			sizeBytes = size,
			dateCreated = localMapDateMs(file),
			sha256 = sha,
			mtimeMs = mtime
		)
	}

	private fun localMapDateMs(file: File): Long {
		try {
			for (resource in app.resourceManager.fileReaders) {
				if (resource.fileName.equals(file.name, ignoreCase = true)) {
					val created = resource.shallowReader?.dateCreated ?: 0L
					if (created > 0L) {
						return created
					}
				}
			}
		} catch (_: Exception) {
		}
		try {
			val formatted = app.resourceManager.indexFileNames[file.name]
			if (!formatted.isNullOrBlank()) {
				synchronized(dateFormat) {
					val parsed = dateFormat.parse(formatted)?.time ?: 0L
					if (parsed > 0L) {
						return parsed
					}
				}
			}
		} catch (_: Exception) {
		}
		return file.lastModified().coerceAtLeast(0L)
	}

	private fun scheduleHash(entry: NearbyMapEntry) {
		val found = findByPath(entry.path) ?: return
		val file = found.second
		val key = cacheKey(entry.path, entry.sizeBytes, entry.mtimeMs)
		if (!hashing.add(key)) {
			return
		}
		hashExecutor.execute {
			try {
				val digest = sha256Of(file)
				if (digest.isNotBlank()) {
					hashCache[key] = digest
					persistHashCache()
				}
			} catch (e: Exception) {
				Log.w(TAG, "hash ${entry.path}", e)
			} finally {
				hashing.remove(key)
			}
		}
	}

	private fun sha256Of(file: File): String {
		val md = MessageDigest.getInstance("SHA-256")
		FileInputStream(file).use { input ->
			val buf = ByteArray(1024 * 256)
			while (true) {
				val n = input.read(buf)
				if (n <= 0) break
				md.update(buf, 0, n)
			}
		}
		return toHex(md.digest())
	}

	private fun toHex(bytes: ByteArray): String {
		val out = CharArray(bytes.size * 2)
		var i = 0
		for (b in bytes) {
			val v = b.toInt() and 0xff
			out[i++] = HEX[v ushr 4]
			out[i++] = HEX[v and 0x0f]
		}
		return String(out)
	}

	private fun cacheKey(path: String, size: Long, mtime: Long): String =
		"$path|$size|$mtime"

	private fun hashCacheFile(): File = File(app.getAppPath("torrent_maps"), HASH_CACHE_FILE)

	private fun ensureHashCacheLoaded() {
		if (cacheLoaded) return
		synchronized(this) {
			if (cacheLoaded) return
			try {
				val file = hashCacheFile()
				if (file.isFile) {
					file.forEachLine { line ->
						val parts = line.split('\t')
						if (parts.size >= 2) {
							hashCache[parts[0]] = parts[1]
						}
					}
				}
			} catch (e: Exception) {
				Log.w(TAG, "load hash cache", e)
			}
			cacheLoaded = true
		}
	}

	private fun persistHashCache() {
		try {
			val file = hashCacheFile()
			file.parentFile?.mkdirs()
			val sb = StringBuilder()
			for ((k, v) in hashCache) {
				sb.append(k).append('\t').append(v).append('\n')
			}
			file.writeText(sb.toString())
		} catch (e: Exception) {
			Log.w(TAG, "persist hash cache", e)
		}
	}
}
