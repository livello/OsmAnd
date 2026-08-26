package net.osmand.plus.plugins.evbms

/**
 * One map file entry from the active EV torrent catalog (for Maps & Resources bridging).
 */
data class EvTorrentCatalogEntry(
	val index: Int,
	val torrentName: String,
	val mapKey: String,
	val sizeBytes: Long,
	/** File mtime from the torrent (ms since epoch), or 0 if unknown. */
	val torrentMtimeMs: Long
)
