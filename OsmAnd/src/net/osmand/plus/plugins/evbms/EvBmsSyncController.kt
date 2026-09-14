package net.osmand.plus.plugins.evbms

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Proxy
import java.net.URL
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class EvBmsSyncController(
	private val app: OsmandApplication,
	private val plugin: EvBmsPlugin
) {
	companion object {
		private const val TAG = "EvBmsSync"
		const val DEFAULT_PORT = 8742
		const val MIN_PORT = 1024
		const val MAX_PORT = 65535
		private const val LIVE_SYNC_MS = 12_000L
		private const val LIVE_SYNC_INITIAL_MS = 3_000L
	}

	interface Listener {
		fun onMasterChanged()
		fun onPeersChanged()
		fun onSyncProgress(done: Int, total: Int, name: String)
		fun onSyncFinished(result: SyncResult)
	}

	data class SyncResult(
		val copied: Int,
		val skipped: Int,
		val errors: Int,
		val message: String,
		val timeMs: Long = System.currentTimeMillis()
	)

	private val ui = Handler(Looper.getMainLooper())
	private val io = Executors.newSingleThreadExecutor { r ->
		Thread(r, "ev-bms-sync").apply { isDaemon = true }
	}
	private val live = Executors.newSingleThreadScheduledExecutor { r ->
		Thread(r, "ev-bms-live-sync").apply { isDaemon = true }
	}
	private val listeners = CopyOnWriteArrayList<Listener>()
	private val pulling = AtomicBoolean(false)
	private val lastPeerRev = ConcurrentHashMap<String, Long>()
	private var server: EvBmsSyncHttpServer? = null
	private var discovery: EvBmsSyncDiscovery? = null
	private var liveTask: ScheduledFuture<*>? = null
	@Volatile
	var serving: Boolean = false
		private set
	@Volatile
	var lastServeName: String? = null
		private set
	@Volatile
	var lastServeMs: Long = 0L
		private set
	@Volatile
	var lastResult: SyncResult? = null
		private set
	@Volatile
	private var clientCount: Int = 0

	fun addListener(listener: Listener) {
		listeners.add(listener)
		listener.onMasterChanged()
		listener.onPeersChanged()
	}

	fun removeListener(listener: Listener) {
		listeners.remove(listener)
	}

	fun clientCount(): Int = clientCount

	fun discoveredPeers(): List<EvBmsSyncDiscovery.Peer> = discovery?.snapshot().orEmpty()

	fun files(): EvBmsSyncFiles {
		val recorder = plugin.telemetryRecorder()
		recorder.setFolderUri(plugin.CSV_FOLDER_URI.get())
		return EvBmsSyncFiles(app, recorder)
	}

	fun startMaster(): Boolean {
		if (serving) {
			return true
		}
		val port = plugin.syncPort()
		val catalog = files()
		val http = EvBmsSyncHttpServer(
			catalog,
			port,
			deviceName(),
			plugin.getId(),
			plugin.syncPeerId(),
			onClient = { delta ->
				clientCount = (clientCount + delta).coerceAtLeast(0)
				ui.post { listeners.forEach { it.onMasterChanged() } }
			},
			onServed = { name, _ ->
				lastServeName = name
				lastServeMs = System.currentTimeMillis()
				ui.post { listeners.forEach { it.onMasterChanged() } }
			}
		)
		return try {
			http.start()
			server = http
			serving = true
			plugin.SYNC_MASTER.set(true)
			try {
				startDiscoveryLocked()
				scheduleLiveSync()
			} catch (e: Exception) {
				Log.w(TAG, "discovery", e)
			}
			EvBmsSyncService.sync(app, true)
			ui.post {
				listeners.forEach { it.onMasterChanged() }
				app.showToastMessage(R.string.ev_bms_sync_started)
			}
			true
		} catch (e: Exception) {
			Log.w(TAG, "start peer", e)
			http.stop()
			serving = false
			ui.post {
				app.showToastMessage(R.string.ev_bms_sync_bind_failed)
				listeners.forEach { it.onMasterChanged() }
			}
			false
		}
	}

	fun stopMaster() {
		serving = false
		plugin.SYNC_MASTER.set(false)
		liveTask?.cancel(false)
		liveTask = null
		discovery?.stop()
		discovery = null
		server?.stop()
		server = null
		clientCount = 0
		lastPeerRev.clear()
		EvBmsSyncService.sync(app, false)
		ui.post {
			listeners.forEach { it.onMasterChanged() }
			listeners.forEach { it.onPeersChanged() }
		}
		app.showToastMessage(R.string.ev_bms_sync_stopped)
	}

	fun restartMasterIfEnabled() {
		if (!plugin.SYNC_MASTER.get()) {
			return
		}
		if (serving) {
			return
		}
		startMaster()
	}

	fun shutdown() {
		serving = false
		liveTask?.cancel(false)
		liveTask = null
		discovery?.stop()
		discovery = null
		server?.stop()
		server = null
		clientCount = 0
		EvBmsSyncService.sync(app, false)
	}

	fun isPulling(): Boolean = pulling.get()

	fun pullFromMaster(rawHost: String) {
		syncNow(rawHost, manual = true)
	}

	fun syncNow(rawHost: String, manual: Boolean = true) {
		if (!pulling.compareAndSet(false, true)) {
			if (manual) {
				app.showToastMessage(R.string.ev_bms_sync_busy)
			}
			return
		}
		val targets = ArrayList<Pair<String, Int>>()
		val seen = HashSet<String>()
		fun addTarget(host: String, port: Int) {
			val key = "$host:$port"
			if (host.isBlank() || key in seen || host in localIpv4Addresses()) {
				return
			}
			seen.add(key)
			targets.add(host to port)
		}
		parseEndpoint(rawHost, plugin.syncPort())?.let { addTarget(it.first, it.second) }
		if (manual || rawHost.isBlank()) {
			for (peer in discoveredPeers()) {
				addTarget(peer.host, peer.port)
			}
		}
		if (targets.isEmpty()) {
			pulling.set(false)
			if (manual) {
				app.showToastMessage(R.string.ev_bms_sync_host_empty)
			}
			return
		}
		if (rawHost.isNotBlank()) {
			plugin.SYNC_HOST.set(rawHost.trim())
		}
		io.execute {
			var copied = 0
			var skipped = 0
			var errors = 0
			var lastFail: String? = null
			for (target in targets) {
				val result = try {
					pullLocked(target.first, target.second, force = manual)
				} catch (e: Exception) {
					Log.w(TAG, "pull ${target.first}", e)
					SyncResult(
						0, 0, 1,
						app.getString(R.string.ev_bms_sync_failed, "${target.first}:${target.second}")
					)
				}
				copied += result.copied
				skipped += result.skipped
				errors += result.errors
				if (result.errors > 0 && result.copied == 0) {
					lastFail = result.message
				}
			}
			val message = if (copied == 0 && errors > 0 && lastFail != null) {
				lastFail
			} else {
				app.getString(R.string.ev_bms_sync_result, copied, skipped, errors)
			}
			val result = SyncResult(copied, skipped, errors, message)
			lastResult = result
			pulling.set(false)
			ui.post { listeners.forEach { it.onSyncFinished(result) } }
		}
	}

	private fun startDiscoveryLocked() {
		discovery?.stop()
		val disco = EvBmsSyncDiscovery(
			app,
			plugin.getId(),
			{ plugin.syncPeerId() },
			{ deviceName() },
			{ plugin.syncPort() },
			{ localIpv4Addresses() },
			{ files().catalogRev() },
			onPeersChanged = {
				ui.post { listeners.forEach { it.onPeersChanged() } }
				if (serving) {
					live.execute { syncNow("", manual = false) }
				}
			}
		)
		discovery = disco
		disco.start()
	}

	private fun scheduleLiveSync() {
		liveTask?.cancel(false)
		liveTask = live.scheduleAtFixedRate({
			try {
				if (serving) {
					syncNow("", manual = false)
				}
			} catch (e: Exception) {
				Log.w(TAG, "live", e)
			}
		}, LIVE_SYNC_INITIAL_MS, LIVE_SYNC_MS, TimeUnit.MILLISECONDS)
	}

	private fun pullLocked(host: String, port: Int, force: Boolean): SyncResult {
		val base = "http://$host:$port"
		val catalogConn = open(URL("$base/files"), 15_000, 30_000)
		val catalogText = try {
			if (catalogConn.responseCode !in 200..299) {
				return SyncResult(
					0, 0, 1,
					app.getString(R.string.ev_bms_sync_failed, "$host:$port")
				)
			}
			catalogConn.inputStream.bufferedReader().use { it.readText() }
		} finally {
			catalogConn.disconnect()
		}
		val json = try {
			JSONObject(catalogText)
		} catch (_: Exception) {
			return SyncResult(
				0, 0, 1,
				app.getString(R.string.ev_bms_sync_failed, "$host:$port")
			)
		}
		val remotePlugin = json.optString("plugin")
		if (remotePlugin.isNotBlank() && remotePlugin != plugin.getId()) {
			return SyncResult(0, 0, 1, app.getString(R.string.ev_bms_sync_failed, "$host:$port"))
		}
		val remoteId = json.optString("id")
		if (remoteId.isNotBlank() && remoteId == plugin.syncPeerId()) {
			return SyncResult(0, 0, 0, app.getString(R.string.ev_bms_sync_result, 0, 0, 0))
		}
		val remoteRev = json.optLong("rev")
		val revKey = remoteId.ifBlank { "$host:$port" }
		if (!force && remoteRev > 0L && lastPeerRev[revKey] == remoteRev) {
			return SyncResult(0, 0, 0, app.getString(R.string.ev_bms_sync_result, 0, 0, 0))
		}
		val catalog = files()
		val remote = catalog.parseCatalog(catalogText)
		var copied = 0
		var skipped = 0
		var errors = 0
		val downloadedCsv = ArrayList<String>()
		var incomingCharges = emptyList<EvHistoryStore.ChargeRecord>()
		var incomingTrips = emptyList<EvHistoryStore.ChargeTripRecord>()
		val total = remote.size.coerceAtLeast(1)
		for ((index, entry) in remote.withIndex()) {
			ui.post {
				listeners.forEach { it.onSyncProgress(index, remote.size, entry.name) }
			}
			if (EvBmsSyncFiles.isHistoryPath(entry.path)) {
				try {
					val text = downloadText(base, entry.path)
					if (entry.name.equals(EvHistoryStore.CHARGE_FILE, true)) {
						incomingCharges = plugin.historyCsvStore().parseChargeCsvText(text)
					} else {
						incomingTrips = plugin.historyCsvStore().parseTripCsvText(text)
					}
					copied++
				} catch (e: Exception) {
					Log.w(TAG, "history ${entry.path}", e)
					errors++
				}
				continue
			}
			if (catalog.shouldSkipIncoming(entry.path, entry.size, entry.mtime)) {
				skipped++
				continue
			}
			try {
				val conn = open(URL("$base/file/${EvBmsSyncFiles.encodePath(entry.path)}"), 15_000, 300_000)
				try {
					if (conn.responseCode !in 200..299) {
						errors++
						continue
					}
					conn.inputStream.use { input ->
						if (catalog.writeIncoming(entry.path, input, entry.size, entry.mtime)) {
							copied++
							if (entry.path.startsWith(EvBmsSyncFiles.PREFIX_TELEMETRY) &&
								entry.name.endsWith(".csv", true)
							) {
								downloadedCsv.add(entry.name)
							}
						} else {
							skipped++
						}
					}
				} finally {
					conn.disconnect()
				}
			} catch (e: Exception) {
				Log.w(TAG, "file ${entry.path}", e)
				errors++
			}
		}
		plugin.mergeIncomingHistory(incomingCharges, incomingTrips)
		plugin.forgetTelemetryHistoryDone(downloadedCsv)
		plugin.repairChargeHistory()
		if (errors == 0) {
			lastPeerRev[revKey] = remoteRev
		}
		ui.post {
			listeners.forEach { it.onSyncProgress(remote.size, total, "") }
		}
		return SyncResult(
			copied,
			skipped,
			errors,
			app.getString(R.string.ev_bms_sync_result, copied, skipped, errors)
		)
	}

	private fun downloadText(base: String, path: String): String {
		val conn = open(URL("$base/file/${EvBmsSyncFiles.encodePath(path)}"), 15_000, 60_000)
		try {
			if (conn.responseCode !in 200..299) {
				throw IllegalStateException("HTTP ${conn.responseCode}")
			}
			return conn.inputStream.bufferedReader().use { it.readText() }
		} finally {
			conn.disconnect()
		}
	}

	private fun open(url: URL, connectMs: Int, readMs: Int): HttpURLConnection {
		return (url.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
			connectTimeout = connectMs
			readTimeout = readMs
			requestMethod = "GET"
			useCaches = false
			instanceFollowRedirects = false
		}
	}

	fun parseEndpoint(raw: String, defaultPort: Int): Pair<String, Int>? {
		val trimmed = raw.trim().removePrefix("http://").removePrefix("https://")
			.substringBefore('/').trim()
		if (trimmed.isBlank()) {
			return null
		}
		val host: String
		val port: Int
		if (trimmed.startsWith("[")) {
			val end = trimmed.indexOf(']')
			if (end <= 1) return null
			host = trimmed.substring(1, end)
			val rest = trimmed.substring(end + 1)
			port = if (rest.startsWith(":")) rest.drop(1).toIntOrNull() ?: defaultPort else defaultPort
		} else {
			val colon = trimmed.lastIndexOf(':')
			if (colon > 0 && trimmed.indexOf(':') == colon) {
				host = trimmed.substring(0, colon).trim()
				port = trimmed.substring(colon + 1).toIntOrNull() ?: defaultPort
			} else {
				host = trimmed
				port = defaultPort
			}
		}
		if (host.isBlank() || port !in MIN_PORT..MAX_PORT) {
			return null
		}
		return host to port
	}

	fun localIpv4Addresses(): List<String> {
		val out = LinkedHashSet<String>()
		try {
			val cm = app.getSystemService(ConnectivityManager::class.java)
			if (cm != null) {
				for (network in cm.allNetworks) {
					val caps = cm.getNetworkCapabilities(network) ?: continue
					if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
						!caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) &&
						!(Build.VERSION.SDK_INT >= 26 && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE))
					) {
						continue
					}
					val lp: LinkProperties = cm.getLinkProperties(network) ?: continue
					for (link in lp.linkAddresses) {
						val addr = link.address
						if (addr is Inet4Address && !addr.isLoopbackAddress) {
							out.add(addr.hostAddress ?: continue)
						}
					}
				}
			}
		} catch (_: Exception) {
		}
		out.addAll(findIpv4 { name ->
			name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") ||
				name.contains("ap0") || name.startsWith("wlan") || name.startsWith("eth") ||
				name.contains("wlan")
		})
		return out.toList()
	}

	private fun findIpv4(predicate: (String) -> Boolean): List<String> {
		val out = ArrayList<String>()
		try {
			val interfaces = NetworkInterface.getNetworkInterfaces() ?: return out
			for (nif in interfaces) {
				if (!nif.isUp || nif.isLoopback) continue
				if (!predicate(nif.name.lowercase())) continue
				for (addr in nif.inetAddresses) {
					if (addr is Inet4Address && !addr.isLoopbackAddress) {
						out.add(addr.hostAddress ?: continue)
					}
				}
			}
		} catch (_: Exception) {
		}
		return out
	}

	fun deviceName(): String {
		val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
			try {
				Settings.Global.getString(app.contentResolver, "device_name")
			} catch (_: Exception) {
				null
			}
		} else {
			null
		}
		return name?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "OsmAnd"
	}
}
