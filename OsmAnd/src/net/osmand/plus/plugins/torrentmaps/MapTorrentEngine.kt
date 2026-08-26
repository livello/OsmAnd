package net.osmand.plus.plugins.torrentmaps

import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import net.osmand.IndexConstants
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import org.libtorrent4j.AlertListener
import org.libtorrent4j.FileStorage
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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MapTorrentEngine(
	private val app: OsmandApplication,
	private val plugin: TorrentMapsPlugin
) {

	companion object {
		private const val TAG = "MapTorrent"
		private const val DIR = "torrent_maps"
		private const val LEGACY_DIR = "ev_torrent"
		private const val TORRENT_FILE = "maps.torrent"
		private const val RESUME_FILE = "maps.resume"
		private const val LISTING_FILE = "torrent_maps_files.txt"
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
			"telemetry", DIR
		)
		// OsmAnd download names use _2 before the map extension (Foo_2.obf, Foo_2.wiki.obf).
		private val VERSION_SUFFIX = Regex(
			"_\\d+(?=\\.(?:(?:wiki|road|srtm|srtmf|depth|travel)\\.)?obf(?:\\.zip)?$)",
			RegexOption.IGNORE_CASE
		)

		fun mapKey(rawName: String): String {
			var n = rawName.substringAfterLast('/').substringAfterLast('\\').lowercase(Locale.US)
			if (n.endsWith(".obf.zip")) {
				n = n.removeSuffix(".zip")
			}
			n = VERSION_SUFFIX.replace(n, "")
			return n
		}

		fun hasVersionSuffix(rawName: String): Boolean {
			val n = rawName.substringAfterLast('/').substringAfterLast('\\')
			return VERSION_SUFFIX.containsMatchIn(n)
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
		val replaceExisting: Boolean,
		val seedOnly: Boolean
	)

	private enum class SelectKind {
		SEED,
		UPDATE,
		DOWNLOAD_NEW
	}

	private val torrentThread = HandlerThread("map-torrent").apply { start() }
	private val torrentHandler = Handler(torrentThread.looper)
	private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
	private val persistLock = Any()

	@Volatile
	private var snapshot = MapTorrentStatus()
	@Volatile
	private var fileRows: List<TorrentFileRow> = emptyList()
	private var session: SessionManager? = null
	/** Never keep a handle from an Alert — those SWIG wrappers do not own memory and dangle. */
	private var infoHash: Sha1Hash? = null
	/**
	 * Pin TorrentInfo for the whole session. SessionManager.download → set_ti() shares the
	 * native torrent_info; SWIG finalize/delete on GC causes Scudo "invalid chunk state"
	 * (SIGABRT on ev-map-torrent) if this is collected early.
	 */
	private var pinnedTorrentInfo: TorrentInfo? = null
	private var sessionDown0 = 0L
	private var sessionUp0 = 0L
	private var lastPersistDown = 0L
	private var lastPersistUp = 0L
	private var baseDown = 0L
	private var baseUp = 0L
	private var selected = emptyList<SelectedFile>()
	@Volatile
	private var catalog = emptyList<TorrentCatalogEntry>()
	private val forceDownloadKeys = HashSet<String>()
	private val pendingSwaps = HashMap<Int, File>()
	private val pendingReload = AtomicBoolean(false)
	private val renamed = AtomicBoolean(false)
	private val prepared = AtomicBoolean(false)
	private var manualRun = false
	private val started = AtomicBoolean(false)
	private var attachAttempts = 0
	private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

	private val poll = object : Runnable {
		override fun run() {
			try {
				if (!prepared.get()) {
					attachAndPrepare()
					// Avoid status/fileProgress until rename/resume finished — concurrent
					// handle use during renameFile has aborted libtorrent (Scudo).
				} else {
					refreshStatus()
					persistCounters()
					maybeSwapCompleted()
				}
			} catch (e: Exception) {
				Log.w(TAG, "poll", e)
				TorrentMapsLog.append("poll error: ${e.message}")
			} catch (e: Error) {
				Log.e(TAG, "poll native", e)
				TorrentMapsLog.append("poll native: ${e.message}")
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
					// Copy buffer on the alert thread only; never retain alert/params.
					val bytes = try {
						org.libtorrent4j.AddTorrentParams.writeResumeDataBuf(alert.params())
					} catch (e: Exception) {
						Log.w(TAG, "resume encode", e)
						null
					} catch (e: Error) {
						Log.e(TAG, "resume encode native", e)
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

	/** Fresh handle from session.find — must be isValid; never keep alert-owned handles. */
	private fun currentHandle(): TorrentHandle? {
		val sm = session ?: return null
		val hash = infoHash ?: return null
		return try {
			val th = sm.find(hash) ?: return null
			if (!th.isValid) null else th
		} catch (e: Exception) {
			Log.w(TAG, "find", e)
			null
		} catch (e: Error) {
			Log.e(TAG, "find native", e)
			null
		}
	}

	fun status(): MapTorrentStatus = snapshot

	fun fileRows(): List<TorrentFileRow> = fileRows

	fun isStarted(): Boolean = started.get()

	fun torrentDir(): File = app.getAppInternalPath(DIR).also { it.mkdirs() }

	fun torrentFile(): File {
		val preferred = File(torrentDir(), TORRENT_FILE)
		if (preferred.isFile && preferred.length() > 0L) {
			return preferred
		}
		val legacy = File(app.getAppInternalPath(LEGACY_DIR), TORRENT_FILE)
		if (legacy.isFile && legacy.length() > 0L) {
			try {
				legacy.copyTo(preferred, overwrite = true)
				val legacyResume = File(app.getAppInternalPath(LEGACY_DIR), RESUME_FILE)
				if (legacyResume.isFile) {
					legacyResume.copyTo(File(torrentDir(), RESUME_FILE), overwrite = true)
				}
			} catch (e: Exception) {
				Log.w(TAG, "migrate torrent", e)
				return legacy
			}
		}
		return preferred
	}

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
			val ti = TorrentInfo(dest)
			writeListing(ti)
			catalog = buildCatalog(ti)
			TorrentMapsLog.append("import ok: ${plugin.TORRENT_NAME.get()} (${ti.numFiles()} files)")
			true
		} catch (e: Exception) {
			Log.e(TAG, "import torrent", e)
			TorrentMapsLog.append("import failed: ${e.message}")
			false
		}
	}

	fun pathSummary(): String {
		if (!hasTorrentFile()) {
			return app.getString(R.string.torrent_maps_path_empty)
		}
		val name = plugin.TORRENT_NAME.get().orEmpty().ifBlank { torrentFile().name }
		return name
	}

	fun catalogEntries(): List<TorrentCatalogEntry> {
		val cached = catalog
		if (cached.isNotEmpty()) {
			return cached
		}
		return try {
			val file = torrentFile()
			if (!file.isFile) {
				emptyList()
			} else {
				buildCatalog(TorrentInfo(file)).also { catalog = it }
			}
		} catch (e: Exception) {
			Log.w(TAG, "catalog", e)
			emptyList()
		}
	}

	fun reloadCatalogFromDisk() {
		catalog = emptyList()
		catalogEntries()
	}

	fun findCatalogEntry(rawName: String): TorrentCatalogEntry? {
		val key = mapKey(rawName)
		return catalogEntries().firstOrNull { it.mapKey == key }
	}

	fun hasCatalogEntry(rawName: String): Boolean = findCatalogEntry(rawName) != null

	/**
	 * Force-download specific maps from the torrent (Maps & Resources bridge).
	 * Ignores freshness for these keys; starts a manual session.
	 */
	fun downloadMapKeys(rawNames: Collection<String>) {
		val keys = rawNames.map { mapKey(it) }.filter { it.isNotBlank() }.toSet()
		if (keys.isEmpty()) {
			return
		}
		TorrentMapsLog.append("force download ${keys.size} map(s): ${keys.take(5).joinToString()}")
		torrentHandler.post {
			forceDownloadKeys.clear()
			forceDownloadKeys.addAll(keys)
			if (started.get()) {
				stopLocked()
			}
			manualRun = true
			startLocked()
			MapTorrentService.sync(app, started.get())
		}
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
			// Only keep the FGS while a session is actually running.
			MapTorrentService.sync(app, started.get())
		}
	}

	fun stop() {
		manualRun = false
		torrentHandler.post { stopLocked() }
		MapTorrentService.sync(app, false)
	}

	/** Drop alias copies (Foo.obf next to Foo_2.obf). Keep the newest file under one name. */
	fun cleanupDuplicateMaps() {
		torrentHandler.post {
			val n = dedupeLocalMaps(app.getAppPath(IndexConstants.MAPS_PATH))
			if (n > 0) {
				pendingReload.set(true)
				reloadMapsSoon()
			}
		}
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
			return app.getString(R.string.torrent_maps_path_empty)
		}
		if (plugin.TORRENT_WIFI_ONLY.get() && !app.settings.isWifiConnected) {
			return app.getString(R.string.torrent_maps_wait_wifi)
		}
		if (plugin.TORRENT_SEED_ON_CHARGE.get() && !isChargeOk() && !manualRun) {
			return app.getString(R.string.torrent_maps_wait_charge)
		}
		return null
	}

	private fun isChargeOk(): Boolean = plugin.isSeedChargeConditionMet()

	private fun startLocked() {
		if (started.get()) {
			resumeLocked()
			return
		}
		val file = torrentFile()
		if (!file.isFile) {
			snapshot = MapTorrentStatus(error = app.getString(R.string.torrent_maps_path_empty))
			TorrentMapsLog.append("start aborted: no torrent file")
			return
		}
		try {
			val ti = TorrentInfo(file)
			if (!ti.isValid) {
				snapshot = MapTorrentStatus(error = app.getString(R.string.torrent_maps_invalid))
				TorrentMapsLog.append("start aborted: invalid torrent")
				return
			}
			writeListing(ti)
			catalog = buildCatalog(ti)
			val mapsDir = app.getAppPath(IndexConstants.MAPS_PATH)
			mapsDir.mkdirs()
			val dropped = dedupeLocalMaps(mapsDir)
			if (dropped > 0) {
				pendingReload.set(true)
				reloadMapsSoon()
			}
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
			val forced = HashSet(forceDownloadKeys)
			forceDownloadKeys.clear()
			var mapFilesInTorrent = 0
			var skippedCurrent = 0
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
				val destName = torrentName.substringAfterLast('/').substringAfterLast('\\')
				val dest = File(mapsDir, destName)
				val torrentSize = files.fileSize(i)
				// Prefer the torrent's expected filename when present; otherwise mapKey match.
				val local = resolveLocalFile(dest, localByKey[key], torrentSize)
				val kind = decideSelectKind(
					key = key,
					local = local,
					destName = destName,
					torrentSize = torrentSize,
					torrentMtimeMs = torrentMtimeMs(files, i),
					downloadNew = downloadNew,
					force = key in forced
				)
				if (kind == null) {
					skippedCurrent++
					continue
				}
				val replaceExisting = kind == SelectKind.UPDATE
				val seedOnly = kind == SelectKind.SEED
				chosen.add(
					SelectedFile(
						index = i,
						torrentName = torrentName,
						key = key,
						size = torrentSize,
						local = local,
						dest = dest,
						replaceExisting = replaceExisting,
						seedOnly = seedOnly
					)
				)
				priorities[i] = Priority.DEFAULT
				if (replaceExisting || kind == SelectKind.DOWNLOAD_NEW) {
					if (replaceExisting && local != null && local.name.equals(destName, ignoreCase = true)) {
						pendingSwaps[i] = local
					} else {
						pendingSwaps[i] = dest
					}
				}
			}
			selected = chosen
			if (chosen.isEmpty()) {
				val samples = sampleTorrentNames(ti, 8)
				val hint = if (skippedCurrent > 0) {
					app.getString(R.string.torrent_maps_all_current_hint, skippedCurrent)
				} else if (samples.isNotEmpty()) {
					app.getString(R.string.torrent_maps_no_match_hint, samples.joinToString(", "))
				} else {
					app.getString(R.string.torrent_maps_no_match)
				}
				Log.w(TAG, "no selectable maps local=${localByKey.size} torrentMaps=$mapFilesInTorrent skipped=$skippedCurrent samples=$samples")
				android.util.Log.w("TorrentMaps", "torrent skip-all skipped=$skippedCurrent maps=$mapFilesInTorrent")
				TorrentMapsLog.append("start: nothing to do ($hint)")
				snapshot = MapTorrentStatus(
					torrentName = ti.name(),
					torrentFiles = n,
					matchedFiles = 0,
					skippedCurrentFiles = skippedCurrent,
					error = hint
				)
				infoHash = null
				return
			}
			// Seed/update the existing OsmAnd filename. Never create a second name.
			prepareStorage(chosen)
			val sp = SettingsPack()
			sp.setEnableDht(true)
			sp.setEnableLsd(true)
			sp.listenInterfaces("0.0.0.0:0")
			val sm = SessionManager()
			sm.addListener(listener)
			sm.start(SessionParams(sp))
			session = sm
			// Keep native torrent_info alive for the session (see pinnedTorrentInfo).
			pinnedTorrentInfo = ti
			sessionDown0 = sm.totalDownload()
			sessionUp0 = sm.totalUpload()
			lastPersistDown = 0L
			lastPersistUp = 0L
			baseDown = plugin.TORRENT_DOWNLOADED.get()
			baseUp = plugin.TORRENT_UPLOADED.get()
			val resume = resumeFile().takeIf { it.isFile }
			sm.download(ti, mapsDir, resume, priorities, null, TorrentFlags.PAUSED)
			started.set(true)
			val seeding = chosen.count { it.seedOnly }
			val updating = chosen.count { it.replaceExisting }
			val downloading = chosen.count { it.local == null }
			snapshot = MapTorrentStatus(
				running = true,
				paused = true,
				state = app.getString(R.string.torrent_maps_state_starting),
				torrentName = ti.name(),
				matchedFiles = chosen.size,
				torrentFiles = n,
				totalDownloaded = baseDown,
				totalUploaded = baseUp,
				error = null,
				seedingFiles = seeding,
				updatingFiles = updating,
				downloadingFiles = downloading,
				skippedCurrentFiles = skippedCurrent,
				waitingReason = app.getString(
					R.string.torrent_maps_selected_hint,
					updating,
					downloading,
					seeding,
					skippedCurrent
				)
			)
			Log.i(
				TAG,
				"start selected=${chosen.size} update=$updating download=$downloading seed=$seeding skip=$skippedCurrent of maps=$mapFilesInTorrent"
			)
			TorrentMapsLog.append(
				"start selected=${chosen.size} update=$updating download=$downloading seed=$seeding skip=$skippedCurrent"
			)
			android.util.Log.i(
				"EvBms",
				"torrent start selected=${chosen.size} update=$updating download=$downloading seed=$seeding skip=$skippedCurrent"
			)
			torrentHandler.removeCallbacks(poll)
			torrentHandler.postDelayed(poll, 300)
		} catch (e: UnsatisfiedLinkError) {
			Log.e(TAG, "native", e)
			TorrentMapsLog.append("start native link error: ${e.message}")
			pinnedTorrentInfo = null
			session = null
			infoHash = null
			snapshot = MapTorrentStatus(error = e.message ?: "libtorrent")
		} catch (e: Exception) {
			Log.e(TAG, "start", e)
			TorrentMapsLog.append("start failed: ${e.message}")
			try {
				session?.stop()
			} catch (_: Exception) {
			} catch (_: Error) {
			}
			pinnedTorrentInfo = null
			session = null
			infoHash = null
			started.set(false)
			snapshot = MapTorrentStatus(error = e.message)
		} catch (e: Error) {
			Log.e(TAG, "start native", e)
			TorrentMapsLog.append("start native error: ${e.message}")
			try {
				session?.stop()
			} catch (_: Exception) {
			} catch (_: Error) {
			}
			pinnedTorrentInfo = null
			session = null
			infoHash = null
			started.set(false)
			snapshot = MapTorrentStatus(error = e.message ?: "libtorrent")
		}
	}

	/**
	 * Decide whether to seed, update, download, or skip.
	 * Freshness / seeding:
	 * 1) Local file size == torrent file size (exact name or mapKey alias) → seed (complete match).
	 * 2) Torrent file mtime > local OBF dateCreated → update.
	 * 3) Otherwise do not re-download unless forced / download-new for missing.
	 */
	private fun decideSelectKind(
		key: String,
		local: File?,
		destName: String,
		torrentSize: Long,
		torrentMtimeMs: Long,
		downloadNew: Boolean,
		force: Boolean
	): SelectKind? {
		if (local == null) {
			return if (downloadNew || force) SelectKind.DOWNLOAD_NEW else null
		}
		val sizeMatch = local.isFile && local.length() == torrentSize
		if (sizeMatch && !force) {
			return SelectKind.SEED
		}
		if (force) {
			return if (sizeMatch) SelectKind.SEED else SelectKind.UPDATE
		}
		val localDate = localMapDateMs(local)
		if (torrentMtimeMs > 0L && localDate > 0L) {
			return if (torrentMtimeMs > localDate) {
				SelectKind.UPDATE
			} else {
				Log.i(TAG, "skip $key (local current/newer local=$localDate torrent=$torrentMtimeMs)")
				null
			}
		}
		Log.i(TAG, "skip $key (no newer proof sizeLocal=${local.length()} sizeTorrent=$torrentSize mtime=$torrentMtimeMs localDate=$localDate)")
		return null
	}

	/** Prefer torrent dest name when present; else mapKey hit, preferring same size. */
	private fun resolveLocalFile(dest: File, byKey: File?, torrentSize: Long): File? {
		if (dest.isFile) {
			return dest
		}
		if (byKey != null && byKey.isFile) {
			return byKey
		}
		// Scan siblings with same mapKey (e.g. both Russia_x.obf and Russia_x_2.obf).
		val parent = dest.parentFile ?: return null
		val key = mapKey(dest.name)
		val files = parent.listFiles() ?: return null
		var sizeHit: File? = null
		var anyHit: File? = null
		for (f in files) {
			if (!f.isFile || !isMapFile(f.name)) {
				continue
			}
			if (mapKey(f.name) != key) {
				continue
			}
			if (anyHit == null) {
				anyHit = f
			}
			if (f.length() == torrentSize) {
				sizeHit = f
				break
			}
		}
		return sizeHit ?: anyHit
	}

	private fun torrentMtimeMs(files: FileStorage, index: Int): Long {
		return try {
			val sec = files.swig().mtime(index)
			if (sec > 0L) sec * 1000L else 0L
		} catch (_: Exception) {
			0L
		} catch (_: Error) {
			0L
		}
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
		// Avoid opening every OBF here (slow / conflicts with active readers).
		return file.lastModified().coerceAtLeast(0L)
	}

	private fun buildCatalog(ti: TorrentInfo): List<TorrentCatalogEntry> {
		val out = ArrayList<TorrentCatalogEntry>()
		val files = ti.files()
		for (i in 0 until ti.numFiles()) {
			if (files.padFileAt(i)) {
				continue
			}
			val name = files.fileName(i)
			if (!isMapFile(name)) {
				continue
			}
			out.add(
				TorrentCatalogEntry(
					index = i,
					torrentName = name,
					mapKey = mapKey(name),
					sizeBytes = files.fileSize(i),
					torrentMtimeMs = torrentMtimeMs(files, i)
				)
			)
		}
		return out
	}

	private fun onTorrentAdded(error: String?) {
		if (!error.isNullOrBlank()) {
			snapshot = snapshot.copy(error = error)
			return
		}
		attachAndPrepare()
	}

	private fun attachAndPrepare() {
		if (!started.get() || prepared.get() || renamed.get()) {
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
		val wasStarted = started.get()
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
		// Release only after session.stop() so native torrent_info is unused.
		pinnedTorrentInfo = null
		selected = emptyList()
		attachAttempts = 0
		if (wasStarted) {
			TorrentMapsLog.append("stop")
		}
		snapshot = snapshot.copy(
			running = false,
			paused = false,
			state = app.getString(R.string.torrent_maps_state_stopped),
			peers = 0,
			seeds = 0,
			downloadRate = 0L,
			uploadRate = 0L,
			waitingReason = waitingReason()
		)
	}

	/**
	 * Point libtorrent at the existing local filename. Delete leftover torrent-dest
	 * aliases so OsmAnd never sees Foo.obf and Foo_2.obf at the same time.
	 */
	private fun prepareStorage(chosen: List<SelectedFile>) {
		for (item in chosen) {
			val canonical = item.local ?: item.dest
			deleteSidecar(canonical)
			if (item.dest.absolutePath != canonical.absolutePath && item.dest.exists()) {
				closeAndDelete(item.dest)
			}
		}
	}

	private fun renameSelected(th: TorrentHandle) {
		if (!renamed.compareAndSet(false, true)) {
			return
		}
		if (!th.isValid) {
			renamed.set(false)
			return
		}
		for (item in selected) {
			val canonical = item.local ?: item.dest
			val target = if (item.seedOnly) {
				canonical
			} else if (canonical.exists()) {
				File(canonical.absolutePath + ".new")
			} else {
				canonical
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
		if (!started.get() || prepared.get()) {
			return
		}
		try {
			val sm = session
			if (sm != null && sm.isRunning && sm.isPaused) {
				sm.resume()
			}
			val th = currentHandle()
			if (th == null) {
				// Handle not ready yet — retry shortly without clearing rename guard.
				torrentHandler.postDelayed(resumeAfterRename, 500)
				return
			}
			th.resume()
			prepared.set(true)
			snapshot = snapshot.copy(paused = false, waitingReason = null, running = true, error = null)
			Log.i(TAG, "torrent prepared/resumed selected=${selected.size}")
			TorrentMapsLog.append("prepared/resumed selected=${selected.size}")
		} catch (e: Exception) {
			Log.e(TAG, "resume", e)
			TorrentMapsLog.append("resume failed: ${e.message}")
			snapshot = snapshot.copy(error = e.message)
		} catch (e: Error) {
			Log.e(TAG, "resume native", e)
			TorrentMapsLog.append("resume native: ${e.message}")
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
			ts == null -> app.getString(R.string.torrent_maps_state_starting)
			sm.isPaused || snapshot.paused -> app.getString(R.string.torrent_maps_state_paused)
			ts.isSeeding -> app.getString(R.string.torrent_maps_state_seeding)
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
		refreshFileRows()
	}

	private fun refreshFileRows() {
		val catalog = catalogEntries()
		if (catalog.isEmpty()) {
			fileRows = emptyList()
			return
		}
		val progress = try {
			currentHandle()?.fileProgress()
		} catch (_: Exception) {
			null
		} catch (_: Error) {
			null
		}
		val byIndex = selected.associateBy { it.index }
		fileRows = catalog.map { entry ->
			val item = byIndex[entry.index]
			val done = when {
				progress != null && entry.index >= 0 && entry.index < progress.size ->
					progress[entry.index].coerceAtLeast(0L)
				item?.seedOnly == true -> entry.sizeBytes
				else -> 0L
			}
			val pct = if (entry.sizeBytes > 0L) {
				((done * 100L) / entry.sizeBytes).toInt().coerceIn(0, 100)
			} else {
				0
			}
			val state = when {
				item == null -> TorrentFileState.SKIPPED
				item.seedOnly || (done >= entry.sizeBytes && entry.sizeBytes > 0L) -> TorrentFileState.SEEDING
				item.replaceExisting -> TorrentFileState.UPDATING
				item.local == null -> TorrentFileState.DOWNLOADING
				else -> TorrentFileState.QUEUED
			}
			val path = TorrentBrowser.normalizePath(entry.torrentName)
			TorrentFileRow(
				index = entry.index,
				displayName = path.substringAfterLast('/'),
				torrentPath = path,
				mapKey = entry.mapKey,
				sizeBytes = entry.sizeBytes,
				doneBytes = done.coerceAtMost(entry.sizeBytes),
				progressPercent = pct,
				state = state
			)
		}
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
		if (item.seedOnly) {
			return
		}
		val canonical = item.local ?: item.dest
		val staged = File(canonical.absolutePath + ".new")
		val src = when {
			staged.isFile && staged.length() == item.size -> staged
			canonical.isFile && canonical.length() == item.size -> canonical
			item.dest.isFile && item.dest.length() == item.size -> item.dest
			else -> return
		}
		try {
			replaceInPlace(src, canonical)
			deleteMapKeyAliases(canonical)
			pendingSwaps.remove(index)
			pendingReload.set(true)
			reloadMapsSoon()
			Log.i(TAG, "ready ${canonical.name}")
			TorrentMapsLog.append("file ready: ${canonical.name}")
		} catch (e: Exception) {
			Log.e(TAG, "finalize ${item.torrentName}", e)
			TorrentMapsLog.append("finalize failed ${item.torrentName}: ${e.message}")
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
			out.merge(mapKey(file.name), file) { a, b -> betterLocal(a, b) }
		}
	}

	/** Keep the newest map; on a size tie prefer OsmAnd's `_2` name. */
	private fun betterLocal(a: File, b: File): File {
		return when {
			b.length() != a.length() -> if (b.length() > a.length()) b else a
			hasVersionSuffix(b.name) != hasVersionSuffix(a.name) ->
				if (hasVersionSuffix(b.name)) b else a
			else -> if (b.lastModified() >= a.lastModified()) b else a
		}
	}

	private fun pickCanonical(files: List<File>): File {
		var best = files[0]
		for (i in 1 until files.size) {
			best = betterLocal(best, files[i])
		}
		return best
	}

	private fun dedupeLocalMaps(mapsDir: File): Int {
		val groups = LinkedHashMap<String, ArrayList<File>>()
		collectMapFiles(mapsDir, groups)
		var deleted = 0
		for (group in groups.values) {
			if (group.size < 2) {
				continue
			}
			val keep = pickCanonical(group)
			for (file in group) {
				if (file.absolutePath == keep.absolutePath) {
					continue
				}
				if (closeAndDelete(file)) {
					deleted++
					TorrentMapsLog.append("dedupe drop ${file.name} keep ${keep.name}")
				}
			}
			deleteSidecar(keep)
		}
		if (deleted > 0) {
			Log.i(TAG, "dedupe removed $deleted alias map(s)")
			TorrentMapsLog.append("dedupe removed $deleted alias map(s)")
		}
		return deleted
	}

	private fun collectMapFiles(dir: File, groups: LinkedHashMap<String, ArrayList<File>>) {
		val files = dir.listFiles() ?: return
		for (file in files) {
			if (file.isDirectory) {
				if (file.name.lowercase(Locale.US) in SKIP_DIRS) {
					continue
				}
				collectMapFiles(file, groups)
				continue
			}
			val lower = file.name.lowercase(Locale.US)
			if (lower.endsWith(".new") || lower.endsWith(".bak")) {
				closeAndDelete(file)
				continue
			}
			if (!isMapFile(file.name)) {
				continue
			}
			groups.getOrPut(mapKey(file.name)) { ArrayList() }.add(file)
		}
	}

	private fun deleteMapKeyAliases(canonical: File) {
		val parent = canonical.parentFile ?: return
		val key = mapKey(canonical.name)
		val files = parent.listFiles() ?: return
		for (file in files) {
			if (!file.isFile || file.absolutePath == canonical.absolutePath) {
				continue
			}
			if (isMapFile(file.name) && mapKey(file.name) == key) {
				closeAndDelete(file)
			}
		}
		deleteSidecar(canonical)
	}

	private fun replaceInPlace(src: File, dest: File) {
		if (src.absolutePath == dest.absolutePath) {
			return
		}
		closeMapFile(dest)
		closeMapFile(src)
		if (dest.exists() && !dest.delete()) {
			Log.w(TAG, "could not delete old ${dest.name} before replace")
		}
		if (!src.renameTo(dest)) {
			src.copyTo(dest, overwrite = true)
			src.delete()
		}
	}

	private fun deleteSidecar(file: File) {
		File(file.absolutePath + ".new").takeIf { it.exists() }?.let { closeAndDelete(it) }
		File(file.absolutePath + ".bak").takeIf { it.exists() }?.let { closeAndDelete(it) }
	}

	private fun closeMapFile(file: File) {
		try {
			app.resourceManager.closeFile(file.name)
		} catch (_: Exception) {
		}
	}

	private fun closeAndDelete(file: File): Boolean {
		closeMapFile(file)
		return try {
			!file.exists() || file.delete()
		} catch (e: Exception) {
			Log.w(TAG, "delete ${file.name}", e)
			false
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
					.append(torrentMtimeMs(files, i)).append('\t')
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

	private fun resumeFile(): File = File(torrentDir(), RESUME_FILE)

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
