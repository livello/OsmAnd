package net.osmand.plus.plugins.evbms

data class EvMapTorrentStatus(
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
	val waitingReason: String? = null
)
