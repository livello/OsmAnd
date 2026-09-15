package net.osmand.plus.plugins.evbms

import android.app.Activity
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.settings.enums.ThemeUsageContext
import net.osmand.plus.utils.AndroidUtils
import net.osmand.plus.utils.ColorUtilities
import net.osmand.plus.utils.UiUtilities
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Proxy
import java.net.URL
import java.util.ArrayDeque
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
		val timeMs: Long = System.currentTimeMillis(),
		val conflicts: Int = 0
	)

	private enum class ConflictChoice {
		REPLACE,
		IGNORE,
		RENAME
	}

	private data class TrackConflict(
		val path: String,
		val name: String,
		val temp: File,
		val dest: File,
		val remoteSize: Long,
		val remoteMtime: Long
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
	private val pendingConflicts = ArrayDeque<TrackConflict>()
	private val conflictLock = Any()
	private val rememberedTracks = ConcurrentHashMap<String, String>()
	private val showingConflict = AtomicBoolean(false)
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
			val pending = pendingConflictCount()
			val message = if (copied == 0 && errors > 0 && lastFail != null && pending == 0) {
				lastFail
			} else {
				resultMessage(copied, skipped, errors, pending)
			}
			val result = SyncResult(copied, skipped, errors, message, conflicts = pending)
			lastResult = result
			pulling.set(false)
			ui.post {
				listeners.forEach { it.onSyncFinished(result) }
				showPendingTrackConflicts()
			}
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
		var conflicts = 0
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
			val pending = isPendingConflict(entry.path)
			val remembered = rememberedTracks[entry.path]
			when (catalog.decideIncoming(entry.path, entry.size, entry.mtime, pending, remembered)) {
				EvBmsSyncFiles.IncomingDecision.SKIP -> {
					skipped++
					continue
				}
				EvBmsSyncFiles.IncomingDecision.COPY -> {
					try {
						if (downloadAndWrite(base, catalog, entry, downloadedCsv)) {
							copied++
						} else {
							skipped++
						}
					} catch (e: Exception) {
						Log.w(TAG, "file ${entry.path}", e)
						errors++
					}
				}
				EvBmsSyncFiles.IncomingDecision.COMPARE -> {
					try {
						when (compareAndQueueTrack(base, catalog, entry)) {
							IncomingOutcome.COPIED -> copied++
							IncomingOutcome.SKIPPED -> skipped++
							IncomingOutcome.CONFLICT -> conflicts++
							IncomingOutcome.ERROR -> errors++
						}
					} catch (e: Exception) {
						Log.w(TAG, "track ${entry.path}", e)
						errors++
					}
				}
			}
		}
		plugin.mergeIncomingHistory(incomingCharges, incomingTrips)
		plugin.forgetTelemetryHistoryDone(downloadedCsv)
		plugin.repairChargeHistory()
		if (errors == 0) {
			lastPeerRev[revKey] = remoteRev
		}
		catalog.publishVisibleGpx()
		ui.post {
			listeners.forEach { it.onSyncProgress(remote.size, total, "") }
		}
		val pendingCount = pendingConflictCount()
		return SyncResult(
			copied,
			skipped,
			errors,
			resultMessage(copied, skipped, errors, pendingCount),
			conflicts = pendingCount
		)
	}

	private enum class IncomingOutcome {
		COPIED, SKIPPED, CONFLICT, ERROR
	}

	private fun downloadAndWrite(
		base: String,
		catalog: EvBmsSyncFiles,
		entry: EvBmsSyncFiles.Entry,
		downloadedCsv: ArrayList<String>
	): Boolean {
		val conn = open(URL("$base/file/${EvBmsSyncFiles.encodePath(entry.path)}"), 15_000, 300_000)
		try {
			if (conn.responseCode !in 200..299) {
				throw IllegalStateException("HTTP ${conn.responseCode}")
			}
			return conn.inputStream.use { input ->
				val ok = catalog.writeIncoming(entry.path, input, entry.size, entry.mtime)
				if (ok &&
					entry.path.startsWith(EvBmsSyncFiles.PREFIX_TELEMETRY) &&
					entry.name.endsWith(".csv", true)
				) {
					downloadedCsv.add(entry.name)
				}
				ok
			}
		} finally {
			conn.disconnect()
		}
	}

	private fun compareAndQueueTrack(
		base: String,
		catalog: EvBmsSyncFiles,
		entry: EvBmsSyncFiles.Entry
	): IncomingOutcome {
		val dest = catalog.localFile(entry.path) ?: return IncomingOutcome.ERROR
		val temp = catalog.incomingTempFile(entry.path) ?: return IncomingOutcome.ERROR
		if (!downloadToFile(base, entry.path, temp)) {
			temp.delete()
			return IncomingOutcome.ERROR
		}
		if (dest.isFile && catalog.sameContent(dest, temp)) {
			catalog.alignMtime(dest, entry.mtime)
			temp.delete()
			return IncomingOutcome.SKIPPED
		}
		if (!dest.isFile || dest.length() <= 0L) {
			return if (catalog.commitIncomingFile(temp, dest, entry.mtime, index = true)) {
				IncomingOutcome.COPIED
			} else {
				temp.delete()
				IncomingOutcome.ERROR
			}
		}
		queueTrackConflict(
			TrackConflict(entry.path, entry.name, temp, dest, entry.size, entry.mtime)
		)
		return IncomingOutcome.CONFLICT
	}

	private fun downloadToFile(base: String, path: String, dest: File): Boolean {
		dest.parentFile?.mkdirs()
		val conn = open(URL("$base/file/${EvBmsSyncFiles.encodePath(path)}"), 15_000, 300_000)
		try {
			if (conn.responseCode !in 200..299) {
				return false
			}
			conn.inputStream.use { input ->
				dest.outputStream().use { input.copyTo(it) }
			}
			return dest.isFile
		} finally {
			conn.disconnect()
		}
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

	private fun resultMessage(copied: Int, skipped: Int, errors: Int, conflicts: Int): String {
		return if (conflicts > 0) {
			app.getString(R.string.ev_bms_sync_result_conflicts, copied, skipped, conflicts, errors)
		} else {
			app.getString(R.string.ev_bms_sync_result, copied, skipped, errors)
		}
	}

	private fun pendingConflictCount(): Int = synchronized(conflictLock) { pendingConflicts.size }

	private fun isPendingConflict(path: String): Boolean = synchronized(conflictLock) {
		pendingConflicts.any { it.path == path }
	}

	private fun queueTrackConflict(conflict: TrackConflict) {
		synchronized(conflictLock) {
			if (pendingConflicts.any { it.path == conflict.path }) {
				conflict.temp.delete()
				return
			}
			pendingConflicts.add(conflict)
		}
	}

	fun showPendingTrackConflicts() {
		if (!showingConflict.compareAndSet(false, true)) {
			return
		}
		val activity = plugin.mapActivityOrNull()
		val next = synchronized(conflictLock) { pendingConflicts.firstOrNull() }
		val remaining = pendingConflictCount()
		if (next == null || activity == null || !AndroidUtils.isActivityNotDestroyed(activity)) {
			showingConflict.set(false)
			return
		}
		showConflictDialog(activity, next, remaining)
	}

	private fun showConflictDialog(activity: Activity, conflict: TrackConflict, remaining: Int) {
		val nightMode = app.daynightHelper.isNightMode(ThemeUsageContext.OVER_MAP)
		val themed = UiUtilities.getThemedContext(activity, nightMode)
		val pad = AndroidUtils.dpToPx(themed, 24f)
		val content = LinearLayout(themed).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(pad, AndroidUtils.dpToPx(themed, 8f), pad, AndroidUtils.dpToPx(themed, 12f))
		}
		val message = TextView(themed).apply {
			text = themed.getString(R.string.ev_bms_sync_conflict_message, conflict.name)
			setTextColor(ColorUtilities.getPrimaryTextColor(themed, nightMode))
			textSize = 16f
		}
		content.addView(message)
		val applyAll = CheckBox(themed).apply {
			text = themed.getString(R.string.ev_bms_sync_conflict_apply_all, remaining)
			setTextColor(ColorUtilities.getPrimaryTextColor(themed, nightMode))
			visibility = if (remaining > 1) android.view.View.VISIBLE else android.view.View.GONE
		}
		if (remaining > 1) {
			val lp = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT
			)
			lp.topMargin = AndroidUtils.dpToPx(themed, 12f)
			content.addView(applyAll, lp)
			UiUtilities.setupCompoundButton(applyAll, nightMode, UiUtilities.CompoundButtonType.GLOBAL)
		}
		var decided = false
		lateinit var dialog: AlertDialog
		fun addChoice(label: Int, choice: ConflictChoice) {
			val button = Button(themed)
			button.setText(label)
			val lp = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT
			)
			lp.topMargin = AndroidUtils.dpToPx(themed, 8f)
			button.setOnClickListener {
				if (decided) {
					return@setOnClickListener
				}
				decided = true
				dialog.dismiss()
				resolveConflicts(choice, applyAll.isChecked)
			}
			content.addView(button, lp)
		}
		addChoice(R.string.ev_bms_sync_conflict_replace, ConflictChoice.REPLACE)
		addChoice(R.string.ev_bms_sync_conflict_ignore, ConflictChoice.IGNORE)
		addChoice(R.string.ev_bms_sync_conflict_rename, ConflictChoice.RENAME)
		dialog = AlertDialog.Builder(themed)
			.setTitle(R.string.ev_bms_sync_conflict_title)
			.setView(content)
			.setOnDismissListener {
				if (!decided) {
					showingConflict.set(false)
				}
			}
			.create()
		dialog.setCanceledOnTouchOutside(false)
		dialog.show()
	}

	private fun resolveConflicts(choice: ConflictChoice, applyAll: Boolean) {
		val batch = synchronized(conflictLock) {
			if (applyAll) {
				ArrayList(pendingConflicts).also { pendingConflicts.clear() }
			} else if (pendingConflicts.isNotEmpty()) {
				arrayListOf(pendingConflicts.removeFirst())
			} else {
				emptyList()
			}
		}
		io.execute {
			val catalog = files()
			var copied = 0
			var skipped = 0
			var errors = 0
			for (conflict in batch) {
				when (applyConflict(catalog, conflict, choice)) {
					IncomingOutcome.COPIED -> copied++
					IncomingOutcome.SKIPPED -> skipped++
					else -> errors++
				}
			}
			ui.post {
				val prev = lastResult
				if (prev != null && (copied > 0 || skipped > 0 || errors > 0)) {
					val nextCopied = prev.copied + copied
					val nextSkipped = prev.skipped + skipped
					val nextErrors = prev.errors + errors
					val pending = pendingConflictCount()
					val updated = SyncResult(
						nextCopied,
						nextSkipped,
						nextErrors,
						resultMessage(nextCopied, nextSkipped, nextErrors, pending),
						conflicts = pending
					)
					lastResult = updated
					listeners.forEach { it.onSyncFinished(updated) }
				}
				showingConflict.set(false)
				showPendingTrackConflicts()
			}
		}
	}

	private fun applyConflict(
		catalog: EvBmsSyncFiles,
		conflict: TrackConflict,
		choice: ConflictChoice
	): IncomingOutcome {
		return try {
			val outcome = when (choice) {
				ConflictChoice.REPLACE -> {
					if (catalog.commitIncomingFile(conflict.temp, conflict.dest, conflict.remoteMtime, index = true)) {
						IncomingOutcome.COPIED
					} else {
						conflict.temp.delete()
						IncomingOutcome.ERROR
					}
				}
				ConflictChoice.IGNORE -> {
					conflict.temp.delete()
					IncomingOutcome.SKIPPED
				}
				ConflictChoice.RENAME -> {
					val renamed = catalog.uniqueTrackFile(conflict.dest)
					if (catalog.commitIncomingFile(conflict.temp, renamed, conflict.remoteMtime, index = true)) {
						IncomingOutcome.COPIED
					} else {
						conflict.temp.delete()
						IncomingOutcome.ERROR
					}
				}
			}
			if (outcome != IncomingOutcome.ERROR) {
				rememberedTracks[conflict.path] =
					EvBmsSyncFiles.fingerprint(conflict.remoteSize, conflict.remoteMtime)
			}
			outcome
		} catch (e: Exception) {
			Log.w(TAG, "conflict ${conflict.path}", e)
			conflict.temp.delete()
			IncomingOutcome.ERROR
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
