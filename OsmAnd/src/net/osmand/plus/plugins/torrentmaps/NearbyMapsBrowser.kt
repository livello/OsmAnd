package net.osmand.plus.plugins.torrentmaps

import java.util.Locale

data class NearbyFileRow(
	val entry: NearbyMapEntry,
	val status: NearbyMapStatus,
	val progressPercent: Int = 0,
	/** Virtual OsmAnd region path for browsing; HTTP still uses [NearbyMapEntry.path]. */
	val browsePath: String = ""
)

sealed class NearbyBrowserRow {
	data class Up(val parentPath: String) : NearbyBrowserRow()
	data class Folder(
		val name: String,
		val path: String,
		val childCount: Int,
		val sizeBytes: Long,
		val progressPercent: Int = 0,
		val downloadableCount: Int = 0
	) : NearbyBrowserRow()
	data class File(val row: NearbyFileRow) : NearbyBrowserRow()
	data class Peer(val peer: NearbyPeer) : NearbyBrowserRow()
	data class TorrentOffer(val offer: NearbyTorrentOffer) : NearbyBrowserRow()
}

object NearbyBrowser {

	fun buildRows(
		files: List<NearbyFileRow>,
		currentPath: String,
		torrentOffer: NearbyTorrentOffer? = null
	): List<NearbyBrowserRow> {
		val cwd = TorrentBrowser.normalizePath(currentPath)
		val prefix = if (cwd.isEmpty()) "" else "$cwd/"
		val folders = LinkedHashMap<String, MutableList<NearbyFileRow>>()
		val direct = ArrayList<NearbyFileRow>()
		for (file in files) {
			val path = TorrentBrowser.normalizePath(
				file.browsePath.ifBlank { file.entry.path }
			)
			val relative = when {
				prefix.isEmpty() -> path
				path.startsWith(prefix) -> path.removePrefix(prefix)
				else -> continue
			}
			if (relative.isEmpty()) continue
			val slash = relative.indexOf('/')
			if (slash < 0) {
				direct.add(file)
			} else {
				val folderName = relative.substring(0, slash)
				folders.getOrPut(folderName) { ArrayList() }.add(file)
			}
		}
		val out = ArrayList<NearbyBrowserRow>()
		if (cwd.isEmpty() && torrentOffer != null &&
			(torrentOffer.magnet.isNotBlank() || torrentOffer.torrentAvailable)
		) {
			out.add(NearbyBrowserRow.TorrentOffer(torrentOffer))
		}
		if (cwd.isNotEmpty()) {
			out.add(NearbyBrowserRow.Up(TorrentBrowser.parentPath(cwd)))
		}
		val folderRows = folders.map { (name, children) ->
			val total = children.sumOf { it.entry.sizeBytes }.coerceAtLeast(1L)
			val filled = children.sumOf { it.entry.sizeBytes * it.progressPercent / 100L }
			NearbyBrowserRow.Folder(
				name = name,
				path = if (cwd.isEmpty()) name else "$cwd/$name",
				childCount = children.size,
				sizeBytes = children.sumOf { it.entry.sizeBytes },
				progressPercent = ((filled * 100L) / total).toInt().coerceIn(0, 100),
				downloadableCount = children.count { NearbyMapCompare.isDownloadable(it.status) }
			)
		}.sortedBy { it.name.lowercase(Locale.US) }
		val fileRows = direct
			.sortedBy { it.entry.displayName.lowercase(Locale.US) }
			.map { NearbyBrowserRow.File(it) }
		out.addAll(folderRows)
		out.addAll(fileRows)
		return out
	}
}
