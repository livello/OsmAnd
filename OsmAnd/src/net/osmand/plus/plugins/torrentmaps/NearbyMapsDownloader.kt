package net.osmand.plus.plugins.torrentmaps

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import net.osmand.plus.OsmandApplication
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Downloads map files from a peer's local HTTP server into the OsmAnd maps directory.
 */
class NearbyMapsDownloader(
	private val app: OsmandApplication,
	private val catalog: NearbyMapsCatalog
) {
	companion object {
		private const val TAG = "NearbyMapsDl"
		const val IO_BUFFER = 256 * 1024
		private const val PROGRESS_MIN_MS = 250L

		fun formatRate(bytesPerSec: Long): String {
			if (bytesPerSec <= 0L) return "—"
			val mb = bytesPerSec / (1024.0 * 1024.0)
			return if (mb >= 0.1) {
				String.format(Locale.US, "%.1f MB/s", mb)
			} else {
				String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
			}
		}
	}

	private val executor = Executors.newSingleThreadExecutor { r ->
		Thread(r, "nearby-maps-dl").apply { isDaemon = true }
	}
	private val ui = Handler(Looper.getMainLooper())
	private val cancelled = AtomicBoolean(false)
	private val activeConn = AtomicReference<HttpURLConnection?>(null)
	private val queueLock = Any()
	private val queue = ArrayList<NearbyQueueItem>()
	private val pumpScheduled = AtomicBoolean(false)
	@Volatile
	private var cancelMode = CancelMode.NONE
	@Volatile
	var queuePaused: Boolean = false
		private set

	private enum class CancelMode { NONE, PAUSE, REMOVE, CLEAR }

	@Volatile
	var progressPath: String? = null
		private set

	@Volatile
	var progressBytes: Long = 0L
		private set

	@Volatile
	var progressTotal: Long = 0L
		private set

	/** Instantaneous-ish rate in bytes/sec over the last progress window. */
	@Volatile
	var progressBytesPerSec: Long = 0L
		private set

	@Volatile
	var busy: Boolean = false
		private set

	@Volatile
	var wasCancelled: Boolean = false
		private set

	fun cancel() {
		cancelMode = CancelMode.CLEAR
		cancelled.set(true)
		wasCancelled = true
		TorrentMapsLog.append("nearby download cancel requested")
		try {
			activeConn.get()?.disconnect()
		} catch (_: Exception) {
		}
	}

	fun queueSnapshot(): List<NearbyQueueItem> = synchronized(queueLock) { queue.toList() }

	fun queuedCount(): Int = synchronized(queueLock) {
		queue.count { it.state == NearbyQueueState.QUEUED || it.state == NearbyQueueState.RUNNING }
	}

	fun progressPercentFor(path: String): Int {
		if (progressPath == path && progressTotal > 0L) {
			return ((progressBytes * 100L) / progressTotal).toInt().coerceIn(0, 100)
		}
		val item = synchronized(queueLock) { queue.firstOrNull { it.entry.path == path } }
		return when (item?.state) {
			NearbyQueueState.RUNNING -> if (progressTotal > 0L) {
				((progressBytes * 100L) / progressTotal).toInt().coerceIn(0, 100)
			} else 1
			NearbyQueueState.QUEUED -> 0
			NearbyQueueState.PAUSED -> 0
			else -> 0
		}
	}

	fun enqueue(
		peer: NearbyPeer,
		entries: List<NearbyMapEntry>,
		browsePathFor: (NearbyMapEntry) -> String = { "" },
		onChanged: () -> Unit
	): Int {
		var added = 0
		synchronized(queueLock) {
			for (entry in entries) {
				if (queue.any { it.entry.path == entry.path && it.peer.host == peer.host }) {
					continue
				}
				queue.add(
					NearbyQueueItem(
						id = java.util.UUID.randomUUID().toString(),
						peer = peer,
						entry = entry,
						browsePath = browsePathFor(entry),
						state = if (queuePaused) NearbyQueueState.PAUSED else NearbyQueueState.QUEUED
					)
				)
				added++
			}
		}
		if (added > 0) {
			TorrentMapsLog.append("nearby queue +$added (size ${queuedCount()})")
			ensurePump(onChanged)
		}
		return added
	}

	fun pauseQueue() {
		queuePaused = true
		synchronized(queueLock) {
			queue.filter { it.state == NearbyQueueState.QUEUED }.forEach {
				it.state = NearbyQueueState.PAUSED
			}
		}
		if (busy) {
			cancelMode = CancelMode.PAUSE
			cancelled.set(true)
			wasCancelled = true
			try {
				activeConn.get()?.disconnect()
			} catch (_: Exception) {
			}
		}
		TorrentMapsLog.append("nearby queue paused")
	}

	fun resumeQueue(onChanged: () -> Unit) {
		queuePaused = false
		synchronized(queueLock) {
			queue.filter { it.state == NearbyQueueState.PAUSED }.forEach {
				it.state = NearbyQueueState.QUEUED
			}
		}
		TorrentMapsLog.append("nearby queue resumed")
		ensurePump(onChanged)
	}

	fun removeItem(id: String, onChanged: () -> Unit) {
		val running = synchronized(queueLock) {
			val item = queue.firstOrNull { it.id == id } ?: return
			if (item.state == NearbyQueueState.RUNNING) {
				true
			} else {
				queue.removeAll { it.id == id }
				false
			}
		}
		if (running) {
			cancelMode = CancelMode.REMOVE
			cancelled.set(true)
			wasCancelled = true
			try {
				activeConn.get()?.disconnect()
			} catch (_: Exception) {
			}
		}
		onChanged()
	}

	fun clearQueue(onChanged: () -> Unit) {
		synchronized(queueLock) {
			queue.removeAll { it.state != NearbyQueueState.RUNNING }
		}
		if (busy) {
			cancelMode = CancelMode.CLEAR
			cancelled.set(true)
			wasCancelled = true
			try {
				activeConn.get()?.disconnect()
			} catch (_: Exception) {
			}
		} else {
			synchronized(queueLock) { queue.clear() }
		}
		queuePaused = false
		TorrentMapsLog.append("nearby queue cleared")
		onChanged()
	}

	private fun ensurePump(onChanged: () -> Unit) {
		if (!pumpScheduled.compareAndSet(false, true)) {
			return
		}
		executor.execute {
			try {
				while (true) {
					if (queuePaused) break
					val item = synchronized(queueLock) {
						queue.firstOrNull { it.state == NearbyQueueState.QUEUED }?.also {
							it.state = NearbyQueueState.RUNNING
						}
					} ?: break
					runQueuedItem(item, onChanged)
				}
			} finally {
				pumpScheduled.set(false)
				val more = synchronized(queueLock) {
					!queuePaused && queue.any { it.state == NearbyQueueState.QUEUED }
				}
				if (more) {
					ensurePump(onChanged)
				}
			}
		}
	}

	private fun runQueuedItem(item: NearbyQueueItem, onChanged: () -> Unit) {
		busy = true
		cancelled.set(false)
		wasCancelled = false
		cancelMode = CancelMode.NONE
		progressPath = item.entry.path
		progressBytes = 0L
		progressTotal = item.entry.sizeBytes
		progressBytesPerSec = 0L
		NearbyTransferLocks.acquire(app, "download")
		var ok = false
		try {
			ok = downloadLocked(item.peer, item.entry) { _, _, _ -> ui.post(onChanged) }
			val msg = when {
				cancelMode == CancelMode.PAUSE ->
					app.getString(net.osmand.plus.R.string.torrent_maps_nearby_queue_paused)
				wasCancelled || cancelled.get() ->
					app.getString(
						net.osmand.plus.R.string.torrent_maps_nearby_download_cancelled,
						item.entry.fileName
					)
				ok ->
					app.getString(
						net.osmand.plus.R.string.torrent_maps_nearby_download_ok,
						item.entry.fileName
					)
				else ->
					app.getString(
						net.osmand.plus.R.string.torrent_maps_nearby_download_failed,
						item.entry.fileName
					)
			}
			if (ok && !wasCancelled && cancelMode == CancelMode.NONE) {
				ui.post { app.showToastMessage(msg) }
			}
		} catch (e: Exception) {
			if (cancelMode == CancelMode.NONE && !cancelled.get()) {
				Log.e(TAG, "download", e)
				TorrentMapsLog.append("nearby download error: ${e.message}")
				ui.post { app.showToastMessage(e.message ?: "error") }
			}
		} finally {
			activeConn.set(null)
			NearbyTransferLocks.release("download")
			busy = false
			progressPath = null
			progressBytes = 0L
			progressTotal = 0L
			progressBytesPerSec = 0L
			synchronized(queueLock) {
				when (cancelMode) {
					CancelMode.PAUSE -> {
						queue.find { it.id == item.id }?.state = NearbyQueueState.PAUSED
					}
					CancelMode.REMOVE, CancelMode.CLEAR -> {
						queue.removeAll { it.id == item.id }
						if (cancelMode == CancelMode.CLEAR) {
							queue.clear()
						}
					}
					CancelMode.NONE -> {
						if (ok && !wasCancelled) {
							queue.removeAll { it.id == item.id }
						} else {
							queue.find { it.id == item.id }?.state = NearbyQueueState.FAILED
						}
					}
				}
			}
			cancelMode = CancelMode.NONE
			ui.post(onChanged)
		}
	}

	private fun downloadLocked(
		peer: NearbyPeer,
		entry: NearbyMapEntry,
		onProgress: ((Long, Long, Long) -> Unit)?
	): Boolean {
		val pathEnc = URLEncoder.encode(entry.path, StandardCharsets.UTF_8.name())
			.replace("+", "%20")
		val tokenEnc = URLEncoder.encode(peer.token, StandardCharsets.UTF_8.name())
		// Prefer query form so '/' in relative paths never breaks the URL path.
		val url = URL("${peer.baseUrl}/file?path=$pathEnc&token=$tokenEnc")
		TorrentMapsLog.append("nearby download ${entry.path} from ${peer.deviceName} (${peer.host}:${peer.port})")
		if (!probeQuick(peer)) {
			TorrentMapsLog.append("nearby download unreachable ${peer.host}:${peer.port}")
			throw java.net.ConnectException(
				app.getString(
					net.osmand.plus.R.string.torrent_maps_nearby_unreachable,
					peer.deviceName,
					"${peer.host}:${peer.port}"
				)
			)
		}
		if (cancelled.get()) {
			wasCancelled = true
			return false
		}
		val conn = (url.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
			connectTimeout = 15_000
			readTimeout = 120_000
			instanceFollowRedirects = false
			useCaches = false
			requestMethod = "GET"
			setRequestProperty("Connection", "close")
			setRequestProperty("Accept-Encoding", "identity")
		}
		activeConn.set(conn)
		val t0 = SystemClock.elapsedRealtime()
		try {
			val code = conn.responseCode
			if (cancelled.get()) {
				wasCancelled = true
				return false
			}
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
			if (tmp.exists()) {
				tmp.delete()
			}
			BufferedInputStream(conn.inputStream, IO_BUFFER).use { input ->
				BufferedOutputStream(FileOutputStream(tmp), IO_BUFFER).use { output ->
					val buf = ByteArray(IO_BUFFER)
					var done = 0L
					var lastUi = 0L
					var windowStart = SystemClock.elapsedRealtime()
					var windowBytes = 0L
					while (true) {
						if (cancelled.get()) {
							wasCancelled = true
							tmp.delete()
							TorrentMapsLog.append("nearby download cancelled at $done B")
							return false
						}
						val n = input.read(buf)
						if (n <= 0) break
						output.write(buf, 0, n)
						done += n
						windowBytes += n
						progressBytes = done
						val now = SystemClock.elapsedRealtime()
						val windowDt = now - windowStart
						if (windowDt >= PROGRESS_MIN_MS) {
							progressBytesPerSec = (windowBytes * 1000L) / windowDt.coerceAtLeast(1L)
							windowStart = now
							windowBytes = 0L
						}
						if (now - lastUi >= PROGRESS_MIN_MS) {
							lastUi = now
							onProgress?.invoke(done, total, progressBytesPerSec)
						}
					}
					output.flush()
				}
			}
			if (cancelled.get()) {
				wasCancelled = true
				tmp.delete()
				return false
			}
			if (total > 0L && tmp.length() != total && entry.sizeBytes > 0L && tmp.length() != entry.sizeBytes) {
				TorrentMapsLog.append("nearby size mismatch got=${tmp.length()} expect=$total")
				tmp.delete()
				return false
			}
			if (entry.sha256.isNotBlank()) {
				val got = TorrentPieceVerifier.sha256Hex(tmp)
				if (!got.equals(entry.sha256, ignoreCase = true)) {
					TorrentMapsLog.append(
						"nearby sha256 mismatch ${entry.fileName} got=$got expect=${entry.sha256}"
					)
					tmp.delete()
					return false
				}
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
			val elapsed = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
			val avgBps = (dest.length() * 1000L) / elapsed
			progressBytesPerSec = avgBps
			TorrentMapsLog.append(
				"nearby saved ${dest.name} ${dest.length()} B in ${elapsed} ms " +
					"(${formatRate(avgBps)})"
			)
			try {
				app.resourceManager.reloadIndexesAsync(null, object :
					net.osmand.plus.resources.ReloadIndexesTask.ReloadIndexesListener {
					override fun reloadIndexesFinished(warnings: MutableList<String>) {
						try {
							app.downloadThread.updateLoadedFiles()
						} catch (_: Exception) {
						}
					}
				})
			} catch (_: Exception) {
			}
			return true
		} finally {
			activeConn.compareAndSet(conn, null)
			try {
				conn.disconnect()
			} catch (_: Exception) {
			}
		}
	}

	private fun probeQuick(peer: NearbyPeer): Boolean {
		return try {
			val health = URL("${peer.baseUrl}/health")
			val conn = (health.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
				connectTimeout = 4_000
				readTimeout = 4_000
				requestMethod = "GET"
				instanceFollowRedirects = false
				useCaches = false
			}
			try {
				conn.responseCode in 200..299
			} finally {
				conn.disconnect()
			}
		} catch (_: Exception) {
			false
		}
	}
}
