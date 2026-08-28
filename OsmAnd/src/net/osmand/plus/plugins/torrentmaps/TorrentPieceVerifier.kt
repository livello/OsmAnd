package net.osmand.plus.plugins.torrentmaps

import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Verifies a local map file against v1 torrent piece SHA-1 hashes.
 *
 * Pieces that span a file boundary cannot be hashed from one file alone and are skipped.
 * OsmAnd maps are hundreds of MB with typical 2–16 MB pieces, so interior pieces are enough
 * to catch sparse/partial writes that wipe large regions off the map.
 */
object TorrentPieceVerifier {

	private const val IO_BUFFER = 256 * 1024

	enum class Verdict {
		OK,
		FAIL,
		SIZE_MISMATCH,
		INCONCLUSIVE,
		ERROR,
		CANCELLED
	}

	data class Result(
		val verdict: Verdict,
		val piecesChecked: Int = 0,
		val piecesFailed: Int = 0,
		val piecesSkipped: Int = 0,
		val error: String? = null
	) {
		val ok: Boolean get() = verdict == Verdict.OK
		val definitelyBad: Boolean
			get() = verdict == Verdict.FAIL || verdict == Verdict.SIZE_MISMATCH
	}

	fun sha256Hex(file: File): String {
		val md = MessageDigest.getInstance("SHA-256")
		FileInputStream(file).use { input ->
			val buf = ByteArray(IO_BUFFER)
			while (true) {
				val n = input.read(buf)
				if (n <= 0) break
				md.update(buf, 0, n)
			}
		}
		return toHex(md.digest())
	}

	fun verify(
		ti: TorrentInfo,
		fileIndex: Int,
		file: File,
		cancelled: () -> Boolean = { false }
	): Result {
		return try {
			verifyLocked(ti, fileIndex, file, cancelled)
		} catch (e: Exception) {
			Result(Verdict.ERROR, error = e.message)
		}
	}

	private fun verifyLocked(
		ti: TorrentInfo,
		fileIndex: Int,
		file: File,
		cancelled: () -> Boolean
	): Result {
		if (fileIndex < 0 || fileIndex >= ti.numFiles()) {
			return Result(Verdict.ERROR, error = "file index")
		}
		val files = ti.files()
		if (files.padFileAt(fileIndex)) {
			return Result(Verdict.INCONCLUSIVE)
		}
		val expectedSize = files.fileSize(fileIndex)
		if (!file.isFile) {
			return Result(Verdict.SIZE_MISMATCH)
		}
		if (file.length() != expectedSize) {
			return Result(Verdict.SIZE_MISMATCH, error = "size ${file.length()} != $expectedSize")
		}
		if (expectedSize <= 0L) {
			return Result(Verdict.INCONCLUSIVE)
		}
		val pieceLen = ti.pieceLength()
		if (pieceLen <= 0) {
			return Result(Verdict.ERROR, error = "piece length")
		}
		val fileOffset = files.fileOffset(fileIndex)
		val firstPiece = files.pieceIndexAtFile(fileIndex).coerceAtLeast(0)
		val lastPiece = files.lastPieceIndexAtFile(fileIndex).coerceAtMost(ti.numPieces() - 1)
		if (lastPiece < firstPiece) {
			return Result(Verdict.INCONCLUSIVE)
		}
		val sha1 = MessageDigest.getInstance("SHA-1")
		val buf = ByteArray(IO_BUFFER)
		var checked = 0
		var failed = 0
		var skipped = 0
		FileInputStream(file).use { input ->
			var filePos = 0L
			for (piece in firstPiece..lastPiece) {
				if (cancelled()) {
					return Result(Verdict.CANCELLED, checked, failed, skipped)
				}
				val pieceSize = ti.pieceSize(piece)
				if (pieceSize <= 0) {
					skipped++
					continue
				}
				val pieceTorrentOff = piece.toLong() * pieceLen.toLong()
				val readStart = pieceTorrentOff - fileOffset
				val coversWholePiece = readStart >= 0L &&
					readStart + pieceSize.toLong() <= expectedSize
				if (!coversWholePiece) {
					skipped++
					continue
				}
				val skip = readStart - filePos
				if (skip > 0L) {
					skipFully(input, skip)
					filePos += skip
				} else if (skip < 0L) {
					return Result(Verdict.ERROR, checked, failed, skipped, "piece order")
				}
				sha1.reset()
				var remaining = pieceSize
				while (remaining > 0) {
					if (cancelled()) {
						return Result(Verdict.CANCELLED, checked, failed, skipped)
					}
					val n = input.read(buf, 0, minOf(buf.size, remaining))
					if (n <= 0) {
						return Result(
							Verdict.FAIL,
							checked,
							failed + 1,
							skipped,
							"truncated at piece $piece"
						)
					}
					sha1.update(buf, 0, n)
					remaining -= n
					filePos += n.toLong()
				}
				val expected = ti.hashForPiece(piece)
				val actual = Sha1Hash.fromBytes(sha1.digest())
				checked++
				if (actual != expected) {
					failed++
				}
			}
		}
		val verdict = when {
			failed > 0 -> Verdict.FAIL
			checked > 0 -> Verdict.OK
			else -> Verdict.INCONCLUSIVE
		}
		return Result(verdict, checked, failed, skipped)
	}

	private fun skipFully(input: InputStream, bytes: Long) {
		var left = bytes
		while (left > 0L) {
			val skipped = input.skip(left)
			if (skipped > 0L) {
				left -= skipped
				continue
			}
			if (input.read() < 0) {
				throw java.io.EOFException("skip")
			}
			left--
		}
	}

	private fun toHex(bytes: ByteArray): String {
		val hex = "0123456789abcdef"
		val out = CharArray(bytes.size * 2)
		var i = 0
		for (b in bytes) {
			val v = b.toInt() and 0xff
			out[i++] = hex[v ushr 4]
			out[i++] = hex[v and 0x0f]
		}
		return String(out)
	}
}

data class TorrentVerifyStatus(
	val running: Boolean = false,
	val done: Int = 0,
	val total: Int = 0,
	val currentName: String = "",
	val okCount: Int = 0,
	val badCount: Int = 0,
	val badNames: List<String> = emptyList(),
	val finished: Boolean = false,
	val cancelled: Boolean = false
)
