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
		val sizeBytes: Long
	) : NearbyBrowserRow()
	data class File(val row: NearbyFileRow) : NearbyBrowserRow()
	data class Peer(val peer: NearbyPeer) : NearbyBrowserRow()
}

object NearbyBrowser {

	fun buildRows(files: List<NearbyFileRow>, currentPath: String): List<NearbyBrowserRow> {
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
		if (cwd.isNotEmpty()) {
			out.add(NearbyBrowserRow.Up(TorrentBrowser.parentPath(cwd)))
		}
		val folderRows = folders.map { (name, children) ->
			NearbyBrowserRow.Folder(
				name = name,
				path = if (cwd.isEmpty()) name else "$cwd/$name",
				childCount = children.size,
				sizeBytes = children.sumOf { it.entry.sizeBytes }
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
