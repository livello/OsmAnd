package net.osmand.plus.plugins.torrentmaps

/**
 * Offline LAN map exchange models (NSD + local HTTP).
 */
data class NearbyMapEntry(
	val id: String,
	val path: String,
	val fileName: String,
	val displayName: String,
	val mapKey: String,
	val sizeBytes: Long,
	val dateCreated: Long,
	val sha256: String,
	val mtimeMs: Long
)

data class NearbyPeer(
	val serviceName: String,
	val host: String,
	val port: Int,
	val token: String,
	val deviceName: String
) {
	val baseUrl: String
		get() = "http://${formatHostForUrl(host)}:$port"

	companion object {
		fun formatHostForUrl(host: String): String {
			val h = host.trim().removePrefix("/").trim()
			return if (h.contains(':') && !h.startsWith('[')) "[$h]" else h
		}
	}
}

enum class NearbyMapStatus {
	MISSING,
	SAME,
	LOCAL_OLDER,
	LOCAL_NEWER,
	SIZE_MISMATCH,
	UNKNOWN
}

data class NearbyCatalogResponse(
	val deviceName: String,
	val tokenRequired: Boolean,
	val maps: List<NearbyMapEntry>
)

object NearbyMapCompare {

	fun compare(local: NearbyMapEntry?, peer: NearbyMapEntry): NearbyMapStatus {
		if (local == null) {
			return NearbyMapStatus.MISSING
		}
		val localDate = local.dateCreated
		val peerDate = peer.dateCreated
		if (localDate > 0L && peerDate > 0L) {
			return when {
				peerDate > localDate -> NearbyMapStatus.LOCAL_OLDER
				peerDate < localDate -> NearbyMapStatus.LOCAL_NEWER
				local.sizeBytes == peer.sizeBytes &&
					(local.sha256.isBlank() || peer.sha256.isBlank() || local.sha256 == peer.sha256) ->
					NearbyMapStatus.SAME
				local.sizeBytes != peer.sizeBytes -> NearbyMapStatus.SIZE_MISMATCH
				local.sha256.isNotBlank() && peer.sha256.isNotBlank() && local.sha256 != peer.sha256 ->
					NearbyMapStatus.SIZE_MISMATCH
				else -> NearbyMapStatus.SAME
			}
		}
		if (local.sha256.isNotBlank() && peer.sha256.isNotBlank()) {
			return if (local.sha256 == peer.sha256) {
				NearbyMapStatus.SAME
			} else if (local.sizeBytes != peer.sizeBytes) {
				NearbyMapStatus.SIZE_MISMATCH
			} else {
				NearbyMapStatus.UNKNOWN
			}
		}
		if (local.sizeBytes > 0L && local.sizeBytes == peer.sizeBytes) {
			return NearbyMapStatus.SAME
		}
		if (local.sizeBytes != peer.sizeBytes) {
			return NearbyMapStatus.SIZE_MISMATCH
		}
		return NearbyMapStatus.UNKNOWN
	}

	fun isDownloadable(status: NearbyMapStatus): Boolean =
		status == NearbyMapStatus.MISSING ||
			status == NearbyMapStatus.LOCAL_OLDER ||
			status == NearbyMapStatus.SIZE_MISMATCH
}
