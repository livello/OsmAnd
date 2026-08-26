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
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.FileCompletedAlert
import org.libtorrent4j.alerts.SaveResumeDataAlert
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
		private const val LISTING_FILE = "ev_torrent_files.txt"
		private val MAP_EXTS = arrayOf(
			IndexConstants.BINARY_MAP_INDEX_EXT_ZIP,
			IndexConstants.BINARY_MAP_INDEX_EXT,
			IndexConstants.BINARY_WIKI_MAP_INDEX_EXT,
			IndexConstants.BINARY_ROAD_MAP_INDEX_EXT,
			IndexConstants.BINARY_SRTM_MAP_INDEX_EXT,
			IndexConstants.BINARY_SRTM_FEET_MAP_INDEX_EXT,
			IndexConstants.BINARY_DEPTH_MAP_INDEX_EXT,
			IndexConstants.BINARY_TRAVEL_GUIDE_MAP_INDEX_EXT,
			".wiki.obf.zip",
			".road.obf.zip",
			".srtm.obf.zip",
			".srtmf.obf.zip",
			".depth.obf.zip",
			".travel.obf.zip",
			IndexConstants.SQLITE_EXT,
			IndexConstants.TIF_EXT,
			IndexConstants.TIFF_DB_EXT
		)
		private val SKIP_DIRS = setOf(
			"tracks", "favorites", "avnotes", "voice", "fonts", "tiles",
			"hidden", "backup", "rec", "import", "media", "help",
			TelemetryRecorder.DIR_NAME, DIR
		)
		private val VERSION_SUFFIX = Regex("_\\d+(?=\\.obf(?:\\.zip)?$)", RegexOption.IGNORE_CASE)

		fun mapKey(rawName: String): String {
			var n = rawName.substringAfterLast('/').substringAfterLast('\\').lowercase(Locale.US)
			if (n.endsWith(".obf.zip")) {
				n = n.removeSuffix(".zip")
			}
			n = VERSION_SUFFIX.replace(n, "")
			return n
		}

		fun isMapFile(rawName: String): Boolean {
			val lower = rawName.substringAfterLast('/').substringAfterLast('\\').lowercase(Locale.US)
			if (lower.endsWith(IndexConstants.DOWNLOAD_EXT) || lower.endsWith(".new") || lower.endsWith(".bak")) {
				return false
			}
			return MAP_EXTS.any { lower.endsWith(it) }
		}
	}

	private data class SelectedFile(
		val index: Int,
		val torrentName: String,
		val key: String,
		val size: Long,
		val local: File?,
		val dest: File,
		val replaceExisting: Boolean
	)

	private val torrentThread = HandlerThread("ev-map-torrent").apply { start() }
	private val torrentHandler = Handler(torrentThread.looper)
	private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
	private val persistLock = Any()

	@Volatile
	private var snapshot = EvMapTorrentStatus()
	private var session: SessionManager? = null
	/** Never keep a handle from an Alert — those SWIG wrappers do not own memory and dangle. */
	private var infoHash: Sha1Hash? = null
	private var sessionDown0 = 0L
	private var sessionUp0 = 0L
	private var lastPersistDown = 0L
	private var lastPersistUp = 0L
	private var baseDown = 0L
	private var baseUp = 0L
	private var selected = emptyList<SelectedFile>()
	private val pendingSwaps = HashMap<Int, File>()
	private val pendingReload = AtomicBoolean(false)
	private val renamed = AtomicBoolean(false)
	private val prepared = AtomicBoolean(false)
	private var manualRun = false
	private val started = AtomicBoolean(false)
	private var attachAttempts = 0

	private val poll = object : Runnable {
		override fun run() {
			try {
				if (!prepared.get()) {
					attachAndPrepare()
				}
				refreshStatus()
				persistCounters()
				maybeSwapCompleted()
			} catch (e: Exception) {
				Log.w(TAG, "poll", e)
			} catch (e: Error) {
				Log.e(TAG, "poll native", e)
				snapshot = snapshot.copy(error = e.message ?: "libtorrent")
				stopLocked()
				return
			}
			if (started.get()) {
				torrentHandler.postDelayed(this, 1000)
			}
		}
	}

	private val listener = object : AlertListener {
		override fun types(): IntArray? = null

		override fun alert(alert: Alert<*>) {
			// Capture only plain data on the alert thread. Never keep Alert/TorrentHandle
			// across threads — alert-owned torrent_handle has cMemOwn=false and is freed
			// when the alert queue advances (SIGABRT in is_valid/status/resume).
			when (alert) {
				is AddTorrentAlert -> {
					val errMsg = if (alert.error().isError) alert.error().message else null
					torrentHandler.post {
						try {
							onTorrentAdded(errMsg)
						} catch (e: Exception) {
							Log.w(TAG, "add alert", e)
						} catch (e: Error) {
							Log.e(TAG, "add alert native", e)
							snapshot = snapshot.copy(error = e.message ?: "libtorrent")
							stopLocked()
						}
					}
				}
				is FileCompletedAlert -> {
					val index = alert.index()
					uiHandler.post { onFileDone(index) }
				}
				is SaveResumeDataAlert -> {
					val bytes = try {
						org.libtorrent4j.AddTorrentParams.writeResumeDataBuf(alert.params())
					} catch (e: Exception) {
						Log.w(TAG, "resume encode", e)
						null
					}
					if (bytes != null) {
						torrentHandler.post { writeResumeBytes(bytes) }
					}
				}
				else -> {}
			}
		}
	}

	/** Fresh handle from session.find — safe to use until the next stop. */
	private fun currentHandle(): TorrentHandle? {
		val sm = session ?: return null
		val hash = infoHash ?: return null
		return try {
			sm.find(hash)
		} catch (e: Exception) {
			Log.w(TAG, "find", e)
			null
		} catch (e: Error) {
			Log.e(TAG, "find native", e)
			null
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
			writeListing(TorrentInfo(dest))
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
			writeListing(ti)
			val mapsDir = app.getAppPath(IndexConstants.MAPS_PATH)
			mapsDir.mkdirs()
			val localByKey = scanLocalMaps()
			val files = ti.files()
			val n = ti.numFiles()
			val priorities = Array(n) { Priority.IGNORE }
			val chosen = ArrayList<SelectedFile>()
			pendingSwaps.clear()
			renamed.set(false)
			prepared.set(false)
			pendingReload.set(false)
			attachAttempts = 0
			infoHash = ti.infoHash()
			val downloadNew = plugin.TORRENT_DOWNLOAD_NEW.get() && manualRun
			var mapFilesInTorrent = 0
			for (i in 0 until n) {
				if (files.padFileAt(i)) {
					continue
				}
				val torrentName = files.fileName(i)
				if (!isMapFile(torrentName)) {
					continue
				}
				mapFilesInTorrent++
				val key = mapKey(torrentName)
				val local = localByKey[key]
				val missing = local == null
				if (missing && !downloadNew) {
					continue
				}
				val destName = torrentName.substringAfterLast('/').substringAfterLast('\\')
				val dest = File(mapsDir, destName)
				val sameFile = local != null &&
						local.isFile &&
						local.length() == files.fileSize(i) &&
						local.name.equals(destName, ignoreCase = true)
				val replaceExisting = local != null && !sameFile
				chosen.add(
					SelectedFile(
						index = i,
						torrentName = torrentName,
						key = key,
						size = files.fileSize(i),
						local = local,
						dest = dest,
						replaceExisting = replaceExisting
					)
				)
				priorities[i] = Priority.DEFAULT
				if (replaceExisting || (local == null && downloadNew)) {
					if (replaceExisting && local != null && local.name.equals(destName, ignoreCase = true)) {
						pendingSwaps[i] = local
					} else if (replaceExisting && local != null) {
						pendingSwaps[i] = dest
					} else {
						pendingSwaps[i] = dest
					}
				}
			}
			selected = chosen
			if (chosen.isEmpty()) {
				val samples = sampleTorrentNames(ti, 8)
				val hint = if (samples.isNotEmpty()) {
					app.getString(R.string.ev_bms_torrent_no_match_hint, samples.joinToString(", "))
				} else {
					app.getString(R.string.ev_bms_torrent_no_match)
				}
				Log.w(TAG, "no selectable maps local=${localByKey.size} torrentMaps=$mapFilesInTorrent samples=$samples")
				snapshot = EvMapTorrentStatus(
					torrentName = ti.name(),
					torrentFiles = n,
					matchedFiles = 0,
					error = hint
				)
				infoHash = null
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
			val resume = resumeFile().takeIf { it.isFile }
			sm.download(ti, mapsDir, resume, priorities, null, TorrentFlags.PAUSED)
			started.set(true)
			val updating = chosen.count { it.local != null }
			val downloading = chosen.size - updating
			snapshot = EvMapTorrentStatus(
				running = true,
				paused = true,
				state = app.getString(R.string.ev_bms_torrent_state_starting),
				torrentName = ti.name(),
				matchedFiles = chosen.size,
				torrentFiles = n,
				totalDownloaded = baseDown,
				totalUploaded = baseUp,
				error = null,
				waitingReason = app.getString(R.string.ev_bms_torrent_selected_hint, updating, downloading)
			)
			Log.i(TAG, "start selected=${chosen.size} update=$updating download=$downloading of maps=$mapFilesInTorrent")
			torrentHandler.removeCallbacks(poll)
			torrentHandler.postDelayed(poll, 300)
		} catch (e: UnsatisfiedLinkError) {
			Log.e(TAG, "native", e)
			snapshot = EvMapTorrentStatus(error = e.message ?: "libtorrent")
		} catch (e: Exception) {
			Log.e(TAG, "start", e)
			snapshot = EvMapTorrentStatus(error = e.message)
		}
	}

	private fun onTorrentAdded(error: String?) {
		if (!error.isNullOrBlank()) {
			snapshot = snapshot.copy(error = error)
			return
		}
		attachAndPrepare()
	}

	private fun attachAndPrepare() {
		if (!started.get() || prepared.get()) {
			return
		}
		val th = currentHandle()
		if (th == null) {
			attachAttempts++
			if (attachAttempts > 40) {
				snapshot = snapshot.copy(error = "torrent handle not ready")
				stopLocked()
			}
			return
		}
		renameSelected(th)
	}

	private fun resumeLocked() {
		if (!prepared.get()) {
			attachAndPrepare()
			return
		}
		val sm = session ?: return
		try {
			if (sm.isRunning && sm.isPaused) {
				sm.resume()
			}
			currentHandle()?.resume()
			snapshot = snapshot.copy(paused = false, waitingReason = null, running = true, error = null)
		} catch (e: Exception) {
			Log.w(TAG, "resumeLocked", e)
		} catch (e: Error) {
			Log.e(TAG, "resumeLocked native", e)
			snapshot = snapshot.copy(error = e.message ?: "libtorrent")
			stopLocked()
		}
	}

	private fun pauseLocked(reason: String?) {
		try {
			currentHandle()?.pause()
			session?.pause()
		} catch (_: Exception) {
		} catch (_: Error) {
		}
		snapshot = snapshot.copy(paused = true, waitingReason = reason, running = true)
	}

	private fun stopLocked() {
		torrentHandler.removeCallbacks(poll)
		torrentHandler.removeCallbacks(resumeAfterRename)
		started.set(false)
		prepared.set(false)
		renamed.set(false)
		try {
			currentHandle()?.let {
				it.pause()
				it.saveResumeData()
			}
		} catch (_: Exception) {
		} catch (_: Error) {
		}
		persistCounters(force = true)
		try {
			session?.removeListener(listener)
			session?.stop()
		} catch (e: Exception) {
			Log.w(TAG, "stop session", e)
		} catch (e: Error) {
			Log.e(TAG, "stop session native", e)
		}
		session = null
		infoHash = null
		selected = emptyList()
		attachAttempts = 0
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

	private fun renameSelected(th: TorrentHandle) {
		for (item in selected) {
			val target = if (item.replaceExisting &&
				item.local != null &&
				item.local.name.equals(item.dest.name, ignoreCase = true)
			) {
				File(item.local.absolutePath + ".new")
			} else if (item.replaceExisting || item.local == null) {
				if (item.dest.exists() && item.dest.length() != item.size) {
					File(item.dest.absolutePath + ".new")
				} else {
					item.dest
				}
			} else {
				item.local
			}
			try {
				th.renameFile(item.index, target.absolutePath)
			} catch (e: Exception) {
				Log.w(TAG, "rename ${item.torrentName}", e)
			} catch (e: Error) {
				Log.e(TAG, "rename native ${item.torrentName}", e)
				snapshot = snapshot.copy(error = e.message ?: "libtorrent rename")
				stopLocked()
				return
			}
		}
		torrentHandler.removeCallbacks(resumeAfterRename)
		torrentHandler.postDelayed(resumeAfterRename, 1500)
	}

	private val resumeAfterRename = Runnable { maybeResumeAfterRename() }

	private fun maybeResumeAfterRename() {
		if (!started.get() || renamed.getAndSet(true)) {
			return
		}
		try {
			val sm = session
			if (sm != null && sm.isRunning && sm.isPaused) {
				sm.resume()
			}
			currentHandle()?.resume()
			prepared.set(true)
			snapshot = snapshot.copy(paused = false, waitingReason = null, running = true, error = null)
		} catch (e: Exception) {
			Log.e(TAG, "resume", e)
			snapshot = snapshot.copy(error = e.message)
		} catch (e: Error) {
			Log.e(TAG, "resume native", e)
			snapshot = snapshot.copy(error = e.message ?: "libtorrent resume")
			stopLocked()
		}
	}

	private fun refreshStatus() {
		val sm = session ?: return
		if (!sm.isRunning) {
			return
		}
		val ts = try {
			currentHandle()?.status()
		} catch (_: Exception) {
			null
		} catch (_: Error) {
			null
		}
		val sessionDown = (sm.totalDownload() - sessionDown0).coerceAtLeast(0L)
		val sessionUp = (sm.totalUpload() - sessionUp0).coerceAtLeast(0L)
		val progress = ts?.progressPpm()?.div(10000) ?: snapshot.progressPercent
		val stateName = when {
			!snapshot.error.isNullOrBlank() && !started.get() -> snapshot.state
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
			matchedFiles = selected.size,
			waitingReason = if (sm.isPaused) waitingReason() else snapshot.waitingReason
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
			currentHandle()?.saveResumeData()
		} catch (_: Exception) {
		} catch (_: Error) {
		}
	}

	private fun writeResumeBytes(bytes: ByteArray) {
		try {
			val file = resumeFile()
			file.parentFile?.mkdirs()
			file.writeBytes(bytes)
		} catch (e: Exception) {
			Log.w(TAG, "resume save", e)
		}
	}

	private fun maybeSwapCompleted() {
		val th = currentHandle() ?: return
		val progress = try {
			th.fileProgress()
		} catch (_: Exception) {
			return
		} catch (_: Error) {
			return
		}
		for (item in selected) {
			val idx = item.index
			if (idx < 0 || idx >= progress.size) {
				continue
			}
			if (progress[idx] >= item.size && item.size > 0L) {
				uiHandler.post { onFileDone(idx) }
			}
		}
	}

	private fun onFileDone(index: Int) {
		val item = selected.firstOrNull { it.index == index } ?: return
		val staged = File(item.dest.absolutePath + ".new")
		val localStaged = item.local?.let { File(it.absolutePath + ".new") }
		val src = when {
			staged.isFile -> staged
			localStaged?.isFile == true -> localStaged
			item.dest.isFile && item.dest.length() == item.size -> item.dest
			else -> return
		}
		try {
			if (src.absolutePath == item.dest.absolutePath && !item.replaceExisting) {
				pendingReload.set(true)
				reloadMapsSoon()
				return
			}
			val finalDest = when {
				item.local != null && item.local.name.equals(item.dest.name, ignoreCase = true) -> item.local
				else -> item.dest
			}
			try {
				app.resourceManager.closeFile(finalDest.name)
			} catch (_: Exception) {
			}
			if (item.local != null && item.local != finalDest && item.local.exists()) {
				try {
					app.resourceManager.closeFile(item.local.name)
				} catch (_: Exception) {
				}
			}
			val bak = File(finalDest.absolutePath + ".bak")
			if (finalDest.exists() && src != finalDest) {
				bak.delete()
				if (!finalDest.renameTo(bak)) {
					finalDest.delete()
				}
			}
			if (src != finalDest) {
				if (!src.renameTo(finalDest)) {
					src.copyTo(finalDest, overwrite = true)
					src.delete()
				}
			}
			bak.delete()
			if (item.local != null && item.local != finalDest && item.local.exists()) {
				item.local.delete()
			}
			pendingSwaps.remove(index)
			pendingReload.set(true)
			reloadMapsSoon()
			Log.i(TAG, "ready ${finalDest.name}")
		} catch (e: Exception) {
			Log.e(TAG, "finalize ${item.torrentName}", e)
		}
	}

	private fun reloadMapsSoon() {
		uiHandler.removeCallbacks(reloadRunnable)
		uiHandler.postDelayed(reloadRunnable, 1500)
	}

	private val reloadRunnable = Runnable {
		if (!pendingReload.getAndSet(false)) {
			return@Runnable
		}
		try {
			app.resourceManager.reloadIndexesAsync(null, null)
		} catch (e: Exception) {
			Log.w(TAG, "reload indexes", e)
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
			if (!isMapFile(file.name)) {
				continue
			}
			out.putIfAbsent(mapKey(file.name), file)
		}
	}

	private fun writeListing(ti: TorrentInfo) {
		try {
			val out = app.getAppPath(LISTING_FILE)
			val sb = StringBuilder()
			sb.append("torrent=").append(ti.name()).append('\n')
			sb.append("files=").append(ti.numFiles()).append('\n')
			val files = ti.files()
			for (i in 0 until ti.numFiles()) {
				if (files.padFileAt(i)) {
					continue
				}
				val name = files.fileName(i)
				sb.append(i).append('\t')
					.append(files.fileSize(i)).append('\t')
					.append(mapKey(name)).append('\t')
					.append(name).append('\n')
			}
			out.writeText(sb.toString())
		} catch (e: Exception) {
			Log.w(TAG, "listing", e)
		}
	}

	private fun sampleTorrentNames(ti: TorrentInfo, limit: Int): List<String> {
		val out = ArrayList<String>()
		val files = ti.files()
		for (i in 0 until ti.numFiles()) {
			if (files.padFileAt(i)) {
				continue
			}
			val name = files.fileName(i)
			if (!isMapFile(name)) {
				continue
			}
			out.add(name.substringAfterLast('/').substringAfterLast('\\'))
			if (out.size >= limit) {
				break
			}
		}
		return out
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
