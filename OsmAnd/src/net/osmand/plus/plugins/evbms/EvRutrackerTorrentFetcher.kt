package net.osmand.plus.plugins.evbms

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.Locale
import java.util.regex.Pattern

/**
 * Best-effort download of a .torrent from a RuTracker topic page.
 * Cloudflare often blocks automated fetches; optional user Cookie helps when the user
 * pastes their own browser cookie for their account.
 */
object EvRutrackerTorrentFetcher {

	private const val TAG = "EvRutrackerTorrent"
	private const val UA =
		"Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
	private val DL_HREF = Pattern.compile(
		"href\\s*=\\s*[\"']([^\"']*dl\\.php\\?t=\\d+[^\"']*)[\"']",
		Pattern.CASE_INSENSITIVE
	)
	private val TOPIC_ID = Pattern.compile("[?&]t=(\\d+)")

	data class Result(
		val ok: Boolean,
		val message: String,
		val bytes: ByteArray? = null,
		val fileName: String? = null
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
			val dl = resolveDownloadUrl(url, html)
				?: return Result(false, "no_dl_link")
			val (bytes, name) = httpGetBytes(dl, cookie, referer = url)
			if (bytes == null || bytes.size < 64 || !looksLikeTorrent(bytes)) {
				return Result(false, "bad_torrent")
			}
			Result(true, "ok", bytes, name ?: "maps.torrent")
		} catch (e: Exception) {
			Log.w(TAG, "fetch", e)
			Result(false, e.message ?: "error")
		}
	}

	private fun resolveDownloadUrl(topicUrl: String, html: String): String? {
		val matcher = DL_HREF.matcher(html)
		if (matcher.find()) {
			return absolutize(topicUrl, matcher.group(1)!!)
		}
		val idMatcher = TOPIC_ID.matcher(topicUrl)
		if (idMatcher.find()) {
			val id = idMatcher.group(1)
			val base = topicUrl.substringBefore("/forum/")
			if (base.startsWith("http")) {
				return "$base/forum/dl.php?t=$id"
			}
			return "https://rutracker.org/forum/dl.php?t=$id"
		}
		return null
	}

	private fun absolutize(pageUrl: String, href: String): String {
		if (href.startsWith("http://") || href.startsWith("https://")) {
			return href
		}
		val base = URL(pageUrl)
		return URL(base, href).toString()
	}

	private fun isCloudflareChallenge(body: String): Boolean {
		val lower = body.lowercase(Locale.US)
		return lower.contains("cf-browser-verification") ||
				lower.contains("just a moment") ||
				lower.contains("performing security verification") ||
				lower.contains("cdn-cgi/challenge") ||
				(lower.contains("cloudflare") && lower.contains("ray id"))
	}

	private fun looksLikeTorrent(bytes: ByteArray): Boolean {
		// bencoded torrent usually starts with "d" and contains "4:infod" / "6:pieces"
		if (bytes.isEmpty() || bytes[0].toInt().toChar() != 'd') {
			return false
		}
		val head = bytes.copyOfRange(0, minOf(bytes.size, 512)).toString(Charsets.ISO_8859_1)
		return head.contains("4:info") || head.contains("6:pieces") || head.contains("announce")
	}

	private fun httpGetText(url: String, cookie: String?): String {
		val conn = open(url, cookie, null)
		conn.instanceFollowRedirects = true
		conn.connect()
		val code = conn.responseCode
		val stream = if (code in 200..299) conn.inputStream else conn.errorStream
		val charset = charsetFromContentType(conn.contentType) ?: Charsets.UTF_8
		return stream.use { it.readBytes().toString(charset) }
	}

	private fun httpGetBytes(
		url: String,
		cookie: String?,
		referer: String?
	): Pair<ByteArray?, String?> {
		val conn = open(url, cookie, referer)
		conn.instanceFollowRedirects = true
		conn.connect()
		val code = conn.responseCode
		if (code !in 200..299) {
			val err = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
			if (isCloudflareChallenge(err)) {
				throw IllegalStateException("cloudflare")
			}
			throw IllegalStateException("http_$code")
		}
		val name = fileNameFromDisposition(conn.getHeaderField("Content-Disposition"))
		val bytes = conn.inputStream.use { it.readBytes() }
		val asText = bytes.copyOfRange(0, minOf(bytes.size, 800)).toString(Charsets.UTF_8)
		if (isCloudflareChallenge(asText)) {
			throw IllegalStateException("cloudflare")
		}
		return bytes to name
	}

	private fun open(url: String, cookie: String?, referer: String?): HttpURLConnection {
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
		if (!referer.isNullOrBlank()) {
			conn.setRequestProperty("Referer", referer)
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

	private fun fileNameFromDisposition(header: String?): String? {
		if (header.isNullOrBlank()) {
			return null
		}
		val star = Regex("filename\\*=(?:UTF-8''|utf-8'')([^;]+)").find(header)
		if (star != null) {
			return star.groupValues[1].trim().removeSurrounding("\"")
		}
		val plain = Regex("filename=\"?([^\";]+)\"?").find(header)
		return plain?.groupValues?.get(1)?.trim()
	}

	fun writeTo(file: File, bytes: ByteArray) {
		file.parentFile?.mkdirs()
		file.writeBytes(bytes)
	}
}
