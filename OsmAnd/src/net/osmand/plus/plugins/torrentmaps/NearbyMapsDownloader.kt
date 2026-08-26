package net.osmand.plus.plugins.torrentmaps

import android.os.Build
import android.util.Log
import net.osmand.plus.OsmandApplication
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads map files from a peer's local HTTP server into the OsmAnd maps directory.
 */
class NearbyMapsDownloader(
	private val app: OsmandApplication,
	private val catalog: NearbyMapsCatalog
) {
	companion object {
		private const val TAG = "NearbyMapsDl"
	}

	private val executor = Executors.newSingleThreadExecutor { r ->
		Thread(r, "nearby-maps-dl").apply { isDaemon = true }
	}
	private val cancelled = AtomicBoolean(false)

	@Volatile
	var progressPath: String? = null
		private set

	@Volatile
	var progressBytes: Long = 0L
		private set

	@Volatile
	var progressTotal: Long = 0L
		private set

	@Volatile
	var busy: Boolean = false
		private set

	fun cancel() {
		cancelled.set(true)
	}

	fun download(
		peer: NearbyPeer,
		entry: NearbyMapEntry,
		onProgress: ((Long, Long) -> Unit)? = null,
		onDone: (Boolean, String) -> Unit
	) {
		executor.execute {
			busy = true
			cancelled.set(false)
			progressPath = entry.path
			progressBytes = 0L
			progressTotal = entry.sizeBytes
			try {
				val ok = downloadLocked(peer, entry, onProgress)
				val msg = if (ok) {
					app.getString(
						net.osmand.plus.R.string.torrent_maps_nearby_download_ok,
						entry.fileName
					)
				} else {
					app.getString(
						net.osmand.plus.R.string.torrent_maps_nearby_download_failed,
						entry.fileName
					)
				}
				onDone(ok, msg)
			} catch (e: Exception) {
				Log.e(TAG, "download", e)
				TorrentMapsLog.append("nearby download error: ${e.message}")
				onDone(false, e.message ?: "error")
			} finally {
				busy = false
				progressPath = null
				progressBytes = 0L
				progressTotal = 0L
			}
		}
	}

	private fun downloadLocked(
		peer: NearbyPeer,
		entry: NearbyMapEntry,
		onProgress: ((Long, Long) -> Unit)?
	): Boolean {
		val encoded = URLEncoder.encode(entry.path, StandardCharsets.UTF_8.name())
			.replace("+", "%20")
		val url = URL("${peer.baseUrl}/file/$encoded?token=${URLEncoder.encode(peer.token, "UTF-8")}")
		TorrentMapsLog.append("nearby download ${entry.path} from ${peer.deviceName}")
		val conn = (url.openConnection() as HttpURLConnection).apply {
			connectTimeout = 15_000
			readTimeout = 120_000
			instanceFollowRedirects = false
			requestMethod = "GET"
		}
		try {
			val code = conn.responseCode
			if (code != 200) {
				TorrentMapsLog.append("nearby download HTTP $code")
				return false
			}
			val total = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				conn.contentLengthLong.let { if (it > 0) it else entry.sizeBytes }
			} else {
				conn.contentLength.toLong().let { if (it > 0) it else entry.sizeBytes }
			}
			progressTotal = total
			val dest = catalog.destinationFor(entry)
			val tmp = File(dest.absolutePath + ".nearby.tmp")
			tmp.parentFile?.mkdirs()
			BufferedInputStream(conn.inputStream).use { input ->
				FileOutputStream(tmp).use { output ->
					val buf = ByteArray(64 * 1024)
					var done = 0L
					while (true) {
						if (cancelled.get()) {
							tmp.delete()
							TorrentMapsLog.append("nearby download cancelled")
							return false
						}
						val n = input.read(buf)
						if (n <= 0) break
						output.write(buf, 0, n)
						done += n
						progressBytes = done
						onProgress?.invoke(done, total)
					}
				}
			}
			if (total > 0L && tmp.length() != total && entry.sizeBytes > 0L && tmp.length() != entry.sizeBytes) {
				TorrentMapsLog.append("nearby size mismatch got=${tmp.length()} expect=$total")
				tmp.delete()
				return false
			}
			try {
				app.resourceManager.closeFile(dest.name)
			} catch (_: Exception) {
			}
			if (dest.exists()) {
				dest.delete()
			}
			if (!tmp.renameTo(dest)) {
				tmp.copyTo(dest, overwrite = true)
				tmp.delete()
			}
			TorrentMapsLog.append("nearby saved ${dest.name}")
			try {
				app.resourceManager.reloadIndexesAsync(null, null)
			} catch (_: Exception) {
			}
			return true
		} finally {
			conn.disconnect()
		}
	}
}
