package net.osmand.plus.plugins.evbms

import android.app.Activity
import android.content.Intent
import net.osmand.plus.OsmandApplication
import net.osmand.plus.utils.AndroidUtils
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

class EvDebugJournal(private val app: OsmandApplication) {

	companion object {
		const val FILE_NAME = "ev_debug.log"
		private const val MAX_BYTES = 2L * 1024L * 1024L
		private const val TAIL_BYTES = 24 * 1024
		private const val HEX_MAX = 64

		fun hex(bytes: ByteArray, max: Int = HEX_MAX): String {
			val n = minOf(bytes.size, max)
			val sb = StringBuilder(n * 3 + 8)
			for (i in 0 until n) {
				if (i > 0) {
					sb.append(' ')
				}
				sb.append(String.format(Locale.US, "%02X", bytes[i].toInt() and 0xFF))
			}
			if (bytes.size > max) {
				sb.append(" …+").append(bytes.size - max)
			}
			return sb.toString()
		}
	}

	@Volatile
	var enabled: Boolean = false

	private val lock = Any()
	private val io = Executors.newSingleThreadExecutor { r ->
		Thread(r, "ev-debug-journal").apply { isDaemon = true }
	}
	private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply {
		timeZone = TimeZone.getDefault()
	}

	fun file(): File {
		val dir = app.getAppPath(TelemetryRecorder.DIR_NAME)
		if (!dir.exists()) {
			dir.mkdirs()
		}
		return File(dir, FILE_NAME)
	}

	fun sizeBytes(): Long = file().takeIf { it.isFile }?.length() ?: 0L

	fun d(src: String, msg: String) = log("D", src, msg)

	fun i(src: String, msg: String) = log("I", src, msg)

	fun w(src: String, msg: String) = log("W", src, msg)

	fun e(src: String, msg: String) = log("E", src, msg)

	fun log(level: String, src: String, msg: String, force: Boolean = false) {
		if (!enabled && !force) {
			return
		}
		val at = Date()
		io.execute {
			synchronized(lock) {
				try {
					val line = "${timeFmt.format(at)} $level [$src] $msg\n"
					val f = file()
					if (f.exists() && f.length() > MAX_BYTES) {
						rotate(f)
					}
					FileOutputStream(f, true).use { out ->
						out.write(line.toByteArray(StandardCharsets.UTF_8))
					}
				} catch (_: Exception) {
				}
			}
		}
	}

	fun clear() {
		synchronized(lock) {
			try {
				file().delete()
				File(file().parentFile, "$FILE_NAME.1").delete()
			} catch (_: Exception) {
			}
		}
		if (enabled) {
			i("journal", "cleared")
		}
	}

	fun tail(maxBytes: Int = TAIL_BYTES): String {
		val f = file()
		if (!f.isFile || f.length() <= 0L) {
			return ""
		}
		synchronized(lock) {
			try {
				RandomAccessFile(f, "r").use { raf ->
					val len = raf.length()
					val take = minOf(len, maxBytes.toLong())
					val start = len - take
					raf.seek(start)
					val buf = ByteArray(take.toInt())
					raf.readFully(buf)
					var text = String(buf, StandardCharsets.UTF_8)
					if (start > 0L) {
						val nl = text.indexOf('\n')
						if (nl >= 0 && nl + 1 < text.length) {
							text = text.substring(nl + 1)
						}
						text = "…\n$text"
					}
					return text
				}
			} catch (_: Exception) {
				return ""
			}
		}
	}

	fun share(activity: Activity): Boolean {
		val src = file()
		if (!src.isFile || src.length() <= 0L) {
			return false
		}
		val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
		val copy = File(app.cacheDir, "ev_debug_$stamp.log")
		return try {
			src.copyTo(copy, overwrite = true)
			val uri = AndroidUtils.getUriForFile(activity, copy)
			val intent = Intent(Intent.ACTION_SEND).apply {
				type = "text/plain"
				putExtra(Intent.EXTRA_STREAM, uri)
				putExtra(Intent.EXTRA_SUBJECT, copy.name)
				addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			}
			AndroidUtils.startActivityIfSafe(activity, Intent.createChooser(intent, null))
			true
		} catch (_: Exception) {
			false
		}
	}

	private fun rotate(current: File) {
		val bak = File(current.parentFile, "$FILE_NAME.1")
		if (bak.exists()) {
			bak.delete()
		}
		current.renameTo(bak)
	}
}
