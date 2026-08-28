package net.osmand.plus.plugins.torrentmaps

import android.util.Log
import net.osmand.plus.OsmandApplication
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory ring buffer (+ optional append-only file) for torrent-maps plugin diagnostics.
 */
object TorrentMapsLog {

	private const val TAG = "TorrentMapsLog"
	private const val MAX_LINES = 400
	private const val FILE_NAME = "torrent_maps.log"
	private const val MAX_FILE_BYTES = 256 * 1024L

	private val lock = Any()
	private val lines = ArrayDeque<String>(MAX_LINES)
	private val listeners = CopyOnWriteArrayList<() -> Unit>()
	private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
	@Volatile
	private var logFile: File? = null

	fun init(app: OsmandApplication) {
		synchronized(lock) {
			if (logFile != null) {
				return
			}
			val dir = app.getAppInternalPath("torrent_maps")
			dir.mkdirs()
			logFile = File(dir, FILE_NAME)
		}
	}

	fun append(message: String) {
		val stamp = synchronized(timeFormat) { timeFormat.format(Date()) }
		val line = "$stamp  $message"
		synchronized(lock) {
			lines.addLast(line)
			while (lines.size > MAX_LINES) {
				lines.removeFirst()
			}
			writeFile(line)
		}
		Log.i(TAG, message)
		for (listener in listeners) {
			try {
				listener()
			} catch (_: Exception) {
			}
		}
	}

	fun snapshot(): List<String> {
		synchronized(lock) {
			return lines.toList()
		}
	}

	fun clear() {
		synchronized(lock) {
			lines.clear()
			try {
				logFile?.writeText("")
			} catch (e: Exception) {
				Log.w(TAG, "clear file", e)
			}
		}
		for (listener in listeners) {
			try {
				listener()
			} catch (_: Exception) {
			}
		}
	}

	fun addListener(listener: () -> Unit) {
		listeners.add(listener)
	}

	fun removeListener(listener: () -> Unit) {
		listeners.remove(listener)
	}

	private fun writeFile(line: String) {
		val file = logFile ?: return
		try {
			if (file.exists() && file.length() > MAX_FILE_BYTES) {
				val keep = file.readLines().takeLast(MAX_LINES / 2)
				file.writeText(keep.joinToString("\n") + "\n")
			}
			file.appendText(line + "\n")
		} catch (e: Exception) {
			Log.w(TAG, "write", e)
		}
	}
}

enum class TorrentSortKey {
	NAME,
	SIZE,
	PROGRESS
}

sealed class TorrentBrowserRow {
	data class Up(val parentPath: String) : TorrentBrowserRow()

	data class Folder(
		val name: String,
		val path: String,
		val childCount: Int,
		val sizeBytes: Long,
		val doneBytes: Long,
		val progressPercent: Int,
		val state: TorrentFileState,
		val downloadableCount: Int = 0
	) : TorrentBrowserRow()

	data class File(val row: TorrentFileRow) : TorrentBrowserRow()
}

object TorrentBrowser {

	fun normalizePath(raw: String): String =
		raw.replace('\\', '/').trim('/').removePrefix("./")

	fun parentPath(path: String): String {
		val n = normalizePath(path)
		val slash = n.lastIndexOf('/')
		return if (slash <= 0) "" else n.substring(0, slash)
	}

	fun buildRows(
		files: List<TorrentFileRow>,
		currentPath: String,
		sortKey: TorrentSortKey,
		ascending: Boolean
	): List<TorrentBrowserRow> {
		val cwd = normalizePath(currentPath)
		val prefix = if (cwd.isEmpty()) "" else "$cwd/"
		val folders = LinkedHashMap<String, MutableList<TorrentFileRow>>()
		val direct = ArrayList<TorrentFileRow>()
		for (file in files) {
			val path = normalizePath(
				file.browsePath.ifBlank { file.torrentPath }
			)
			val relative = when {
				prefix.isEmpty() -> path
				path.startsWith(prefix) -> path.removePrefix(prefix)
				else -> continue
			}
			if (relative.isEmpty()) {
				continue
			}
			val slash = relative.indexOf('/')
			if (slash < 0) {
				direct.add(file)
			} else {
				val folderName = relative.substring(0, slash)
				folders.getOrPut(folderName) { ArrayList() }.add(file)
			}
		}
		val out = ArrayList<TorrentBrowserRow>()
		if (cwd.isNotEmpty()) {
			out.add(TorrentBrowserRow.Up(parentPath(cwd)))
		}
		val folderRows = folders.map { (name, children) ->
			val size = children.sumOf { it.sizeBytes }
			val done = children.sumOf { it.doneBytes }
			val pct = if (size > 0L) {
				((done * 100L) / size).toInt().coerceIn(0, 100)
			} else {
				0
			}
			TorrentBrowserRow.Folder(
				name = name,
				path = if (cwd.isEmpty()) name else "$cwd/$name",
				childCount = children.size,
				sizeBytes = size,
				doneBytes = done,
				progressPercent = pct,
				state = aggregateState(children),
				downloadableCount = children.count { isDownloadable(it.state) }
			)
		}
		val fileRows = direct.map { TorrentBrowserRow.File(it) }
		out.addAll(sortFolderRows(folderRows, sortKey, ascending))
		out.addAll(sortFileRows(fileRows, sortKey, ascending))
		return out
	}

	fun isDownloadable(state: TorrentFileState): Boolean =
		state == TorrentFileState.IDLE ||
			state == TorrentFileState.SKIPPED ||
			state == TorrentFileState.QUEUED ||
			state == TorrentFileState.CORRUPT

	private fun aggregateState(children: List<TorrentFileRow>): TorrentFileState {
		if (children.isEmpty()) {
			return TorrentFileState.IDLE
		}
		val states = children.map { it.state }.toSet()
		return when {
			TorrentFileState.DOWNLOADING in states -> TorrentFileState.DOWNLOADING
			TorrentFileState.UPDATING in states -> TorrentFileState.UPDATING
			TorrentFileState.VERIFYING in states -> TorrentFileState.VERIFYING
			TorrentFileState.CORRUPT in states -> TorrentFileState.CORRUPT
			TorrentFileState.QUEUED in states -> TorrentFileState.QUEUED
			states.all { it == TorrentFileState.SEEDING || it == TorrentFileState.COMPLETE } ->
				TorrentFileState.SEEDING
			TorrentFileState.SKIPPED in states && states.size == 1 -> TorrentFileState.SKIPPED
			else -> TorrentFileState.IDLE
		}
	}

	private fun sortFolderRows(
		rows: List<TorrentBrowserRow.Folder>,
		sortKey: TorrentSortKey,
		ascending: Boolean
	): List<TorrentBrowserRow.Folder> {
		val comparator = when (sortKey) {
			TorrentSortKey.NAME -> compareBy<TorrentBrowserRow.Folder> { it.name.lowercase(Locale.US) }
			TorrentSortKey.SIZE -> compareBy { it.sizeBytes }
			TorrentSortKey.PROGRESS -> compareBy { it.progressPercent }
		}
		return if (ascending) rows.sortedWith(comparator) else rows.sortedWith(comparator.reversed())
	}

	private fun sortFileRows(
		rows: List<TorrentBrowserRow.File>,
		sortKey: TorrentSortKey,
		ascending: Boolean
	): List<TorrentBrowserRow.File> {
		val comparator = when (sortKey) {
			TorrentSortKey.NAME -> compareBy<TorrentBrowserRow.File> {
				it.row.displayName.lowercase(Locale.US)
			}
			TorrentSortKey.SIZE -> compareBy { it.row.sizeBytes }
			TorrentSortKey.PROGRESS -> compareBy { it.row.progressPercent }
		}
		return if (ascending) rows.sortedWith(comparator) else rows.sortedWith(comparator.reversed())
	}
}
