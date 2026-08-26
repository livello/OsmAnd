package net.osmand.plus.plugins.evbms

import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import net.osmand.IndexConstants
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.FileCompletedAlert
import org.libtorrent4j.alerts.FileRenamedAlert
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.alerts.TorrentAlert
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class EvMapTorrentEngine(
	private val app: OsmandApplication,
	private val plugin: EvBmsPlugin
) {

	companion object {
		private const val TAG = "EvMapTorrent"
		private const val DIR = "ev_torrent"
		private const val TORRENT_FILE = "maps.torrent"
		private const val RESUME_FILE = "maps.resume"
		private val MAP_EXTS = arrayOf(
			IndexConstants.BINARY_MAP_INDEX_EXT,
			IndexConstants.BINARY_MAP_INDEX_EXT_ZIP,
			IndexConstants.BINARY_WIKI_MAP_INDEX_EXT,
			IndexConstants.BINARY_ROAD_MAP_INDEX_EXT,
			IndexConstants.BINARY_SRTM_MAP_INDEX_EXT,
			IndexConstants.BINARY_SRTM_FEET_MAP_INDEX_EXT,
			IndexConstants.BINARY_DEPTH_MAP_INDEX_EXT,
			IndexConstants.BINARY_TRAVEL_GUIDE_MAP_INDEX_EXT,
			IndexConstants.SQLITE_EXT,
			IndexConstants.TIF_EXT,
			IndexConstants.TIFF_DB_EXT,
			".zip"
		)
		private val SKIP_DIRS = setOf(
			"tracks", "favorites", "avnotes", "voice", "fonts", "tiles",
			"hidden", "backup", "rec", "import", "media", "help",
			TelemetryRecorder.DIR_NAME, DIR
		)
	}

	private val torrentThread = HandlerThread("ev-map-torrent").apply { start() }
	private val torrentHandler = Handler(torrentThread.looper)
	private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
	private val persistLock = Any()

	@Volatile
	private var snapshot = EvMapTorrentStatus()
	private var session: SessionManager? = null
	private var handle: TorrentHandle? = null
	private var sessionDown0 = 0L
	private var sessionUp0 = 0L
	private var lastPersistDown = 0L
	private var lastPersistUp = 0L
	private var baseDown = 0L
	private var baseUp = 0L
	private var matchedIndexes = IntArray(0)
	private val pendingSwaps = HashMap<Int, File>()
	private val renamed = AtomicBoolean(false)
	private var manualRun = false
	private val started = AtomicBoolean(false)

	private val poll = object : Runnable {
		override fun run() {
			refreshStatus()
			persistCounters()
			maybeSwapCompleted()
			if (started.get()) {
				torrentHandler.postDelayed(this, 1000)
			}
		}
	}

	private val listener = object : AlertListener {
		override fun types(): IntArray? = null

		override fun alert(alert: Alert<*>) {
			try {
				onAlert(alert)
			} catch (e: Exception) {
				Log.w(TAG, "alert", e)
			}
		}
	}

	fun status(): EvMapTorrentStatus = snapshot

	fun isStarted(): Boolean = started.get()

	fun torrentFile(): File = File(app.getAppInternalPath(DIR), TORRENT_FILE)

	fun hasTorrentFile(): Boolean = torrentFile().isFile && torrentFile().length() > 0L

	fun importTorrent(uri: Uri): Boolean {
		return try {
			val dest = torrentFile()
			dest.parentFile?.mkdirs()
			app.contentResolver.openInputStream(uri)?.use { input ->
				dest.outputStream().use { output -> input.copyTo(output) }
			} ?: return false
			if (dest.length() < 64L) {
				dest.delete()
				return false
			}
			plugin.TORRENT_PATH.set(dest.absolutePath)
			plugin.TORRENT_NAME.set(displayName(uri) ?: dest.name)
			true
		} catch (e: Exception) {
			Log.e(TAG, "import torrent", e)
			false
		}
	}

	fun pathSummary(): String {
		if (!hasTorrentFile()) {
			return app.getString(R.string.ev_bms_torrent_path_empty)
		}
		val name = plugin.TORRENT_NAME.get().orEmpty().ifBlank { torrentFile().name }
		return name
	}

	fun startManual() {
		manualRun = true
		sync()
	}

	fun stopManual() {
		manualRun = false
		stop()
	}

	fun sync() {
		torrentHandler.post {
			val want = shouldRun()
			if (want && !started.get()) {
				startLocked()
			} else if (!want && started.get() && !manualRun) {
				if (plugin.TORRENT_ENABLED.get()) {
					pauseLocked(waitingReason())
				} else {
					stopLocked()
				}
			} else if (want && started.get()) {
				resumeLocked()
			} else if (!plugin.TORRENT_ENABLED.get() && started.get()) {
				stopLocked()
			} else {
				snapshot = snapshot.copy(
					waitingReason = if (!started.get()) waitingReason() else snapshot.waitingReason
				)
			}
			EvMapTorrentService.sync(app, want || (manualRun && plugin.TORRENT_ENABLED.get()))
		}
	}

	fun stop() {
		manualRun = false
		torrentHandler.post { stopLocked() }
		EvMapTorrentService.sync(app, false)
	}

	fun shouldRun(): Boolean {
		if (!plugin.TORRENT_ENABLED.get()) {
			return false
		}
		if (!hasTorrentFile()) {
			return false
		}
		if (plugin.TORRENT_WIFI_ONLY.get() && !app.settings.isWifiConnected) {
			return false
		}
		if (!manualRun && plugin.TORRENT_SEED_ON_CHARGE.get() && !isChargeOk()) {
			return false
		}
		return true
	}

	private fun waitingReason(): String? {
		if (!plugin.TORRENT_ENABLED.get()) {
			return null
		}
		if (!hasTorrentFile()) {
			return app.getString(R.string.ev_bms_torrent_path_empty)
		}
		if (plugin.TORRENT_WIFI_ONLY.get() && !app.settings.isWifiConnected) {
			return app.getString(R.string.ev_bms_torrent_wait_wifi)
		}
		if (plugin.TORRENT_SEED_ON_CHARGE.get() && !isChargeOk() && !manualRun) {
			return app.getString(R.string.ev_bms_torrent_wait_charge)
		}
		return null
	}

	private fun isChargeOk(): Boolean {
		if (plugin.isCharging()) {
			return true
		}
		val speed = plugin.fusedSpeedKmh() ?: 0.0
		return isPhonePlugged() && speed < plugin.STOP_SPEED_KMH.get()
	}

	private fun isPhonePlugged(): Boolean {
		val intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
		return intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
	}

	private fun startLocked() {
		if (started.get()) {
			resumeLocked()
			return
		}
		val file = torrentFile()
		if (!file.isFile) {
			snapshot = EvMapTorrentStatus(error = app.getString(R.string.ev_bms_torrent_path_empty))
			return
		}
		try {
			val ti = TorrentInfo(file)
			if (!ti.isValid) {
				snapshot = EvMapTorrentStatus(error = app.getString(R.string.ev_bms_torrent_invalid))
				return
			}
			val localByName = scanLocalMaps()
			val files = ti.files()
			val n = ti.numFiles()
			val priorities = Array(n) { Priority.IGNORE }
			val matched = ArrayList<Int>()
			pendingSwaps.clear()
			renamed.set(false)
			for (i in 0 until n) {
				if (files.padFileAt(i)) {
					continue
				}
				val name = files.fileName(i)
				val local = localByName[name.lowercase(Locale.US)] ?: continue
				matched.add(i)
				priorities[i] = Priority.DEFAULT
				val torrentSize = files.fileSize(i)
				if (local.isFile && local.length() == torrentSize) {
					pendingSwaps.remove(i)
				} else {
					pendingSwaps[i] = local
				}
			}
			matchedIndexes = matched.toIntArray()
			if (matched.isEmpty()) {
				snapshot = EvMapTorrentStatus(
					torrentName = ti.name(),
					torrentFiles = n,
					matchedFiles = 0,
					error = app.getString(R.string.ev_bms_torrent_no_match)
				)
				return
			}
			val sp = SettingsPack()
			sp.setEnableDht(true)
			sp.setEnableLsd(true)
			sp.listenInterfaces("0.0.0.0:0")
			val sm = SessionManager()
			sm.addListener(listener)
			sm.start(SessionParams(sp))
			session = sm
			sessionDown0 = sm.totalDownload()
			sessionUp0 = sm.totalUpload()
			lastPersistDown = 0L
			lastPersistUp = 0L
			baseDown = plugin.TORRENT_DOWNLOADED.get()
			baseUp = plugin.TORRENT_UPLOADED.get()
			val saveDir = app.getAppPath(IndexConstants.MAPS_PATH)
			saveDir.mkdirs()
			val resume = resumeFile().takeIf { it.isFile }
			sm.download(ti, saveDir, resume, priorities, null, TorrentFlags.PAUSED)
			started.set(true)
			snapshot = EvMapTorrentStatus(
				running = true,
				paused = true,
				state = app.getString(R.string.ev_bms_torrent_state_starting),
				torrentName = ti.name(),
				matchedFiles = matched.size,
				torrentFiles = n,
				totalDownloaded = baseDown,
				totalUploaded = baseUp
			)
			torrentHandler.removeCallbacks(poll)
			torrentHandler.post(poll)
		} catch (e: UnsatisfiedLinkError) {
			Log.e(TAG, "native", e)
			snapshot = EvMapTorrentStatus(error = e.message ?: "libtorrent")
		} catch (e: Exception) {
			Log.e(TAG, "start", e)
			snapshot = EvMapTorrentStatus(error = e.message)
		}
	}

	private fun resumeLocked() {
		val sm = session ?: return
		if (sm.isPaused) {
			sm.resume()
		}
		handle?.resume()
		snapshot = snapshot.copy(paused = false, waitingReason = null, running = true)
	}

	private fun pauseLocked(reason: String?) {
		try {
			handle?.pause()
			session?.pause()
		} catch (_: Exception) {
		}
		snapshot = snapshot.copy(paused = true, waitingReason = reason, running = true)
	}

	private fun stopLocked() {
		torrentHandler.removeCallbacks(poll)
		started.set(false)
		try {
			handle?.pause()
			handle?.saveResumeData()
		} catch (_: Exception) {
		}
		persistCounters(force = true)
		try {
			session?.stop()
		} catch (e: Exception) {
			Log.w(TAG, "stop session", e)
		}
		session = null
		handle = null
		snapshot = snapshot.copy(
			running = false,
			paused = false,
			state = app.getString(R.string.ev_bms_torrent_state_stopped),
			peers = 0,
			seeds = 0,
			downloadRate = 0L,
			uploadRate = 0L,
			waitingReason = waitingReason()
		)
	}

	private fun onAlert(alert: Alert<*>) {
		when (alert) {
			is AddTorrentAlert -> {
				val err = alert.error()
				if (err.isError) {
					snapshot = snapshot.copy(error = err.message)
					return
				}
				val th = alert.handle()
				handle = th
				renameMatched(th)
			}
			is FileRenamedAlert -> {
				if (!renamed.get()) {
					maybeResumeAfterRename()
				}
			}
			is FileCompletedAlert -> {
				val idx = alert.index()
				uiHandler.post { swapIfNeeded(idx) }
			}
			is SaveResumeDataAlert -> saveResume(alert)
			is TorrentAlert<*> -> refreshStatus()
		}
	}

	private fun renameMatched(th: TorrentHandle) {
		val ti = th.torrentFile() ?: return
		val files = ti.files()
		val localByName = scanLocalMaps()
		for (i in matchedIndexes) {
			val name = files.fileName(i)
			val local = localByName[name.lowercase(Locale.US)] ?: continue
			val torrentSize = files.fileSize(i)
			val target = if (local.isFile && local.length() == torrentSize) {
				local
			} else {
				pendingSwaps[i] = local
				File(local.absolutePath + ".new")
			}
			try {
				th.renameFile(i, target.absolutePath)
			} catch (e: Exception) {
				Log.w(TAG, "rename $name", e)
			}
		}
		torrentHandler.postDelayed({ maybeResumeAfterRename() }, 1500)
	}

	private fun maybeResumeAfterRename() {
		if (!started.get() || renamed.getAndSet(true)) {
			return
		}
		try {
			handle?.resume()
			session?.resume()
		} catch (_: Exception) {
		}
	}

	private fun refreshStatus() {
		val sm = session ?: return
		val th = handle
		val ts = try {
			th?.status()
		} catch (_: Exception) {
			null
		}
		val sessionDown = (sm.totalDownload() - sessionDown0).coerceAtLeast(0L)
		val sessionUp = (sm.totalUpload() - sessionUp0).coerceAtLeast(0L)
		val progress = ts?.progressPpm()?.div(10000) ?: snapshot.progressPercent
		val stateName = when {
			snapshot.error != null -> snapshot.state
			ts == null -> app.getString(R.string.ev_bms_torrent_state_starting)
			sm.isPaused || snapshot.paused -> app.getString(R.string.ev_bms_torrent_state_paused)
			ts.isSeeding -> app.getString(R.string.ev_bms_torrent_state_seeding)
			else -> ts.state().name.lowercase(Locale.US).replace('_', ' ')
		}
		snapshot = snapshot.copy(
			running = started.get(),
			paused = sm.isPaused || snapshot.paused,
			state = stateName,
			progressPercent = progress.coerceIn(0, 100),
			peers = ts?.numPeers() ?: 0,
			seeds = ts?.numSeeds() ?: 0,
			downloadRate = sm.downloadRate(),
			uploadRate = sm.uploadRate(),
			sessionDownloaded = sessionDown,
			sessionUploaded = sessionUp,
			totalDownloaded = baseDown + sessionDown,
			totalUploaded = baseUp + sessionUp,
			waitingReason = if (sm.isPaused) waitingReason() else null
		)
	}

	private fun persistCounters(force: Boolean = false) {
		val sm = session ?: return
		val down = (sm.totalDownload() - sessionDown0).coerceAtLeast(0L)
		val up = (sm.totalUpload() - sessionUp0).coerceAtLeast(0L)
		if (!force && down - lastPersistDown < 64 * 1024 && up - lastPersistUp < 64 * 1024) {
			return
		}
		synchronized(persistLock) {
			plugin.TORRENT_DOWNLOADED.set(baseDown + down)
			plugin.TORRENT_UPLOADED.set(baseUp + up)
			lastPersistDown = down
			lastPersistUp = up
		}
		try {
			handle?.saveResumeData()
		} catch (_: Exception) {
		}
	}

	private fun saveResume(alert: SaveResumeDataAlert) {
		try {
			val bytes = org.libtorrent4j.AddTorrentParams.writeResumeDataBuf(alert.params())
			val file = resumeFile()
			file.parentFile?.mkdirs()
			file.writeBytes(bytes)
		} catch (e: Exception) {
			Log.w(TAG, "resume save", e)
		}
	}

	private fun maybeSwapCompleted() {
		if (pendingSwaps.isEmpty()) {
			return
		}
		val th = handle ?: return
		val progress = try {
			th.fileProgress()
		} catch (_: Exception) {
			return
		}
		val ti = try {
			th.torrentFile()
		} catch (_: Exception) {
			null
		} ?: return
		val files = ti.files()
		for ((idx, dest) in pendingSwaps.toMap()) {
			if (idx < 0 || idx >= progress.size) {
				continue
			}
			if (progress[idx] >= files.fileSize(idx) && files.fileSize(idx) > 0L) {
				uiHandler.post { swapIfNeeded(idx) }
			}
		}
	}

	private fun swapIfNeeded(index: Int) {
		val dest = pendingSwaps.remove(index) ?: return
		val src = File(dest.absolutePath + ".new")
		if (!src.isFile) {
			return
		}
		try {
			app.resourceManager.closeFile(dest.name)
		} catch (_: Exception) {
		}
		val bak = File(dest.absolutePath + ".bak")
		try {
			if (dest.exists()) {
				bak.delete()
				if (!dest.renameTo(bak)) {
					dest.delete()
				}
			}
			if (!src.renameTo(dest)) {
				src.copyTo(dest, overwrite = true)
				src.delete()
			}
			bak.delete()
			app.resourceManager.reloadIndexesAsync(null, null)
		} catch (e: Exception) {
			Log.e(TAG, "swap ${dest.name}", e)
		}
	}

	private fun scanLocalMaps(): Map<String, File> {
		val out = HashMap<String, File>()
		val root = app.getAppPath(IndexConstants.MAPS_PATH)
		scanDir(root, out)
		return out
	}

	private fun scanDir(dir: File, out: HashMap<String, File>) {
		val files = dir.listFiles() ?: return
		for (file in files) {
			if (file.isDirectory) {
				if (file.name.lowercase(Locale.US) in SKIP_DIRS) {
					continue
				}
				scanDir(file, out)
				continue
			}
			val name = file.name
			val lower = name.lowercase(Locale.US)
			if (MAP_EXTS.none { lower.endsWith(it) }) {
				continue
			}
			if (lower.endsWith(IndexConstants.DOWNLOAD_EXT) || lower.endsWith(".new") || lower.endsWith(".bak")) {
				continue
			}
			out.putIfAbsent(lower, file)
		}
	}

	private fun resumeFile(): File = File(app.getAppInternalPath(DIR), RESUME_FILE)

	private fun displayName(uri: Uri): String? {
		return try {
			app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
				if (c.moveToFirst()) c.getString(0) else null
			}
		} catch (_: Exception) {
			null
		}
	}
}
