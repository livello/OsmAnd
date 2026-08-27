package net.osmand.plus.plugins.torrentmaps

import android.util.Log
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.Locale
import java.util.regex.Pattern

/**
 * Refresh torrent metadata from a RuTracker topic by reading the magnet link
 * on the page (not dl.php) and resolving it via DHT.
 */
object RutrackerTorrentFetcher {

	private const val TAG = "RutrackerTorrent"
	private const val MAGNET_TIMEOUT_SEC = 120
	private const val UA =
		"Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
	private val MAGNET_HREF = Pattern.compile(
		"href\\s*=\\s*[\"'](magnet:\\?[^\"']+)[\"']",
		Pattern.CASE_INSENSITIVE
	)
	private val MAGNET_ANY = Pattern.compile(
		"magnet:\\?xt=urn:btih:[a-zA-Z0-9]+[^\\s\"'<>]*",
		Pattern.CASE_INSENSITIVE
	)

	data class Result(
		val ok: Boolean,
		val message: String,
		val bytes: ByteArray? = null,
		val fileName: String? = null,
		val magnet: String? = null
	)

	fun fetchTorrent(topicUrl: String, cookie: String?): Result {
		val url = topicUrl.trim()
		if (url.isEmpty()) {
			return Result(false, "empty_url")
		}
		return try {
			val html = httpGetText(url, cookie)
			if (isCloudflareChallenge(html)) {
				return Result(false, "cloudflare")
			}
			val magnet = extractMagnet(html)
				?: return Result(false, "no_magnet")
			TorrentMapsLog.append("RuTracker magnet ${magnet.take(80)}")
			val bytes = fetchMagnetMetadata(magnet)
			if (bytes == null || bytes.size < 64 || !looksLikeTorrent(bytes)) {
				return Result(false, "magnet_timeout", magnet = magnet)
			}
			val name = try {
				TorrentInfo(bytes).name()
			} catch (_: Exception) {
				"maps.torrent"
			}
			Result(true, "ok", bytes, name, magnet)
		} catch (e: Exception) {
			Log.w(TAG, "fetch", e)
			Result(false, e.message ?: "error")
		}
	}

	fun resolveMagnet(magnet: String): Result {
		val uri = magnet.trim()
		if (!uri.startsWith("magnet:", ignoreCase = true)) {
			return Result(false, "empty_url")
		}
		return try {
			val bytes = fetchMagnetMetadata(uri)
			if (bytes == null || bytes.size < 64 || !looksLikeTorrent(bytes)) {
				Result(false, "magnet_timeout", magnet = uri)
			} else {
				val name = try {
					TorrentInfo(bytes).name()
				} catch (_: Exception) {
					"maps.torrent"
				}
				Result(true, "ok", bytes, name, uri)
			}
		} catch (e: Exception) {
			Result(false, e.message ?: "error", magnet = uri)
		}
	}

	fun extractMagnet(html: String): String? {
		val href = MAGNET_HREF.matcher(html)
		if (href.find()) {
			return unescapeHtml(href.group(1)!!).takeIf { it.startsWith("magnet:", ignoreCase = true) }
		}
		val any = MAGNET_ANY.matcher(html)
		if (any.find()) {
			return unescapeHtml(any.group())
		}
		return null
	}

	private fun fetchMagnetMetadata(magnet: String): ByteArray? {
		val tmp = File.createTempFile("osmand-magnet", ".dir").apply {
			delete()
			mkdirs()
		}
		val sm = SessionManager()
		return try {
			val sp = SettingsPack()
			sp.setEnableDht(true)
			sp.setEnableLsd(true)
			sp.listenInterfaces("0.0.0.0:0")
			sm.start(SessionParams(sp))
			sm.fetchMagnet(magnet, MAGNET_TIMEOUT_SEC, tmp)
		} catch (e: Exception) {
			Log.w(TAG, "fetchMagnet", e)
			null
		} catch (e: Error) {
			Log.e(TAG, "fetchMagnet native", e)
			null
		} finally {
			try {
				sm.stop()
			} catch (_: Exception) {
			} catch (_: Error) {
			}
			tmp.deleteRecursively()
		}
	}

	private fun unescapeHtml(raw: String): String =
		raw.replace("&amp;", "&")
			.replace("&#038;", "&")
			.replace("&quot;", "\"")
			.replace("&amp;amp;", "&")

	private fun isCloudflareChallenge(body: String): Boolean {
		val lower = body.lowercase(Locale.US)
		return lower.contains("cf-browser-verification") ||
				lower.contains("just a moment") ||
				lower.contains("performing security verification") ||
				lower.contains("cdn-cgi/challenge") ||
				(lower.contains("cloudflare") && lower.contains("ray id"))
	}

	private fun looksLikeTorrent(bytes: ByteArray): Boolean {
		if (bytes.isEmpty() || bytes[0].toInt().toChar() != 'd') {
			return false
		}
		val head = bytes.copyOfRange(0, minOf(bytes.size, 512)).toString(Charsets.ISO_8859_1)
		return head.contains("4:info") || head.contains("6:pieces") || head.contains("announce")
	}

	private fun httpGetText(url: String, cookie: String?): String {
		val conn = open(url, cookie)
		conn.instanceFollowRedirects = true
		conn.connect()
		val code = conn.responseCode
		val stream = if (code in 200..299) conn.inputStream else conn.errorStream
		val charset = charsetFromContentType(conn.contentType) ?: Charsets.UTF_8
		return stream.use { it.readBytes().toString(charset) }
	}

	private fun open(url: String, cookie: String?): HttpURLConnection {
		val conn = URL(url).openConnection() as HttpURLConnection
		conn.connectTimeout = 20000
		conn.readTimeout = 60000
		conn.requestMethod = "GET"
		conn.setRequestProperty("User-Agent", UA)
		conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
		conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
		if (!cookie.isNullOrBlank()) {
			conn.setRequestProperty("Cookie", cookie.trim())
		}
		return conn
	}

	private fun charsetFromContentType(contentType: String?): Charset? {
		if (contentType.isNullOrBlank()) {
			return null
		}
		val idx = contentType.lowercase(Locale.US).indexOf("charset=")
		if (idx < 0) {
			return null
		}
		val raw = contentType.substring(idx + 8).substringBefore(';').trim().removeSurrounding("\"")
		return try {
			Charset.forName(raw)
		} catch (_: Exception) {
			null
		}
	}

	fun writeTo(file: File, bytes: ByteArray) {
		file.parentFile?.mkdirs()
		file.writeBytes(bytes)
	}
}
