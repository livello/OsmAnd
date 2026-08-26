package net.osmand.plus.plugins.torrentmaps

data class MapTorrentStatus(
	val running: Boolean = false,
	val paused: Boolean = false,
	val state: String = "",
	val error: String? = null,
	val torrentName: String = "",
	val matchedFiles: Int = 0,
	val torrentFiles: Int = 0,
	val progressPercent: Int = 0,
	val peers: Int = 0,
	val seeds: Int = 0,
	val downloadRate: Long = 0L,
	val uploadRate: Long = 0L,
	val sessionDownloaded: Long = 0L,
	val sessionUploaded: Long = 0L,
	val totalDownloaded: Long = 0L,
	val totalUploaded: Long = 0L,
	val waitingReason: String? = null,
	val seedingFiles: Int = 0,
	val updatingFiles: Int = 0,
	val downloadingFiles: Int = 0,
	val skippedCurrentFiles: Int = 0
)

enum class TorrentFileState {
	IDLE,
	QUEUED,
	DOWNLOADING,
	UPDATING,
	SEEDING,
	COMPLETE,
	SKIPPED
}

data class TorrentFileRow(
	val index: Int,
	val displayName: String,
	/** Full torrent-relative path (`dir/file.obf`), `/`-normalized. */
	val torrentPath: String,
	val mapKey: String,
	val sizeBytes: Long,
	val doneBytes: Long,
	val progressPercent: Int,
	val state: TorrentFileState,
	/**
	 * Virtual OsmAnd region path for the file browser (World/continent/country…).
	 * When blank, [TorrentBrowser] falls back to [torrentPath].
	 */
	val browsePath: String = ""
)
