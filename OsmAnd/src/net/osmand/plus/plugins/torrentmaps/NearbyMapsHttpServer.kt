package net.osmand.plus.plugins.torrentmaps

import android.util.Log
import net.osmand.plus.OsmandApplication
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal local HTTP server for map catalog + OBF streaming (LAN / hotspot only).
 */
class NearbyMapsHttpServer(
	private val app: OsmandApplication,
	private val catalog: NearbyMapsCatalog,
	private val token: String,
	private val deviceName: String,
	private val advertiseAddress: InetAddress
) {
	companion object {
		private const val TAG = "NearbyMapsHttp"
		private const val MAX_HEADER = 64 * 1024
	}

	private val running = AtomicBoolean(false)
	private var serverSocket: ServerSocket? = null
	private var acceptThread: Thread? = null
	private var pool: ExecutorService? = null
	@Volatile
	var port: Int = 0
		private set

	fun start(): Int {
		if (!running.compareAndSet(false, true)) {
			return port
		}
		// Bind all interfaces so SoftAP / Wi‑Fi / Ethernet clients can connect.
		// Advertise a specific LAN IPv4 separately via NSD TXT.
		val ss = ServerSocket(0, 32, null)
		serverSocket = ss
		port = ss.localPort
		pool = Executors.newCachedThreadPool { r ->
			Thread(r, "nearby-http").apply { isDaemon = true }
		}
		acceptThread = Thread({
			TorrentMapsLog.append(
				"nearby HTTP listen 0.0.0.0:$port (advertise ${advertiseAddress.hostAddress})"
			)
			while (running.get()) {
				try {
					val socket = ss.accept()
					pool?.execute { handleClient(socket) }
				} catch (_: SocketException) {
					break
				} catch (e: Exception) {
					if (running.get()) {
						Log.w(TAG, "accept", e)
					}
					break
				}
			}
		}, "nearby-http-accept").apply {
			isDaemon = true
			start()
		}
		return port
	}

	fun stop() {
		if (!running.compareAndSet(true, false)) {
			return
		}
		try {
			serverSocket?.close()
		} catch (_: Exception) {
		}
		serverSocket = null
		pool?.shutdownNow()
		pool = null
		acceptThread = null
		port = 0
		TorrentMapsLog.append("nearby HTTP stopped")
	}

	private fun handleClient(socket: Socket) {
		socket.soTimeout = 60_000
		try {
			socket.use { s ->
				val input = BufferedInputStream(s.getInputStream())
				val output = BufferedOutputStream(s.getOutputStream())
				val request = readRequest(input) ?: run {
					writeResponse(output, 400, "text/plain", "bad request")
					return
				}
				dispatch(request, output)
				output.flush()
			}
		} catch (e: Exception) {
			Log.w(TAG, "client", e)
		}
	}

	private data class HttpRequest(
		val method: String,
		val path: String,
		val query: Map<String, String>
	)

	private fun readRequest(input: InputStream): HttpRequest? {
		val headerBytes = ByteArrayOutputStream()
		var state = 0
		while (headerBytes.size() < MAX_HEADER) {
			val b = input.read()
			if (b < 0) return null
			headerBytes.write(b)
			when (state) {
				0 -> state = if (b == '\r'.code) 1 else 0
				1 -> state = if (b == '\n'.code) 2 else 0
				2 -> state = if (b == '\r'.code) 3 else 0
				3 -> if (b == '\n'.code) {
					val text = headerBytes.toString(StandardCharsets.US_ASCII.name())
					val lines = text.split("\r\n")
					if (lines.isEmpty()) return null
					val parts = lines[0].split(' ')
					if (parts.size < 2) return null
					val rawTarget = parts[1]
					val qIdx = rawTarget.indexOf('?')
					val path = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget
					val query = if (qIdx >= 0) parseQuery(rawTarget.substring(qIdx + 1)) else emptyMap()
					return HttpRequest(parts[0].uppercase(Locale.US), path, query)
				} else {
					state = 0
				}
			}
		}
		return null
	}

	private fun parseQuery(raw: String): Map<String, String> {
		if (raw.isBlank()) return emptyMap()
		val out = LinkedHashMap<String, String>()
		for (part in raw.split('&')) {
			val eq = part.indexOf('=')
			if (eq <= 0) continue
			val key = urlDecode(part.substring(0, eq))
			val value = urlDecode(part.substring(eq + 1))
			out[key] = value
		}
		return out
	}

	private fun urlDecode(s: String): String =
		URLDecoder.decode(s.replace('+', ' '), StandardCharsets.UTF_8.name())

	private fun dispatch(req: HttpRequest, output: OutputStream) {
		if (req.method != "GET" && req.method != "HEAD") {
			writeResponse(output, 405, "text/plain", "method not allowed")
			return
		}
		when {
			req.path == "/health" -> writeResponse(output, 200, "text/plain", "ok")
			req.path == "/catalog" -> {
				if (!checkToken(req)) {
					writeResponse(output, 401, "text/plain", "unauthorized")
					return
				}
				val json = catalog.toJson(catalog.buildEntries(), deviceName)
				writeResponse(output, 200, "application/json; charset=utf-8", json, headOnly = req.method == "HEAD")
			}
			req.path.startsWith("/file/") || req.path == "/file" -> {
				if (!checkToken(req)) {
					writeResponse(output, 401, "text/plain", "unauthorized")
					return
				}
				val relative = when {
					req.path.startsWith("/file/") ->
						urlDecode(req.path.removePrefix("/file/"))
					else -> req.query["path"].orEmpty()
				}
				val found = catalog.findByPath(relative)
				if (found == null) {
					writeResponse(output, 404, "text/plain", "not found")
					return
				}
				streamFile(found.second, output, headOnly = req.method == "HEAD")
			}
			else -> writeResponse(output, 404, "text/plain", "not found")
		}
	}

	private fun checkToken(req: HttpRequest): Boolean {
		val provided = req.query["token"].orEmpty()
		return provided.isNotBlank() && provided == token
	}

	private fun streamFile(file: File, output: OutputStream, headOnly: Boolean) {
		val length = file.length()
		val header = buildString {
			append("HTTP/1.1 200 OK\r\n")
			append("Content-Type: application/octet-stream\r\n")
			append("Content-Length: ").append(length).append("\r\n")
			append("Content-Disposition: attachment; filename=\"")
				.append(file.name.replace("\"", "")).append("\"\r\n")
			append("Connection: close\r\n")
			append("\r\n")
		}.toByteArray(StandardCharsets.US_ASCII)
		output.write(header)
		if (headOnly) {
			return
		}
		FileInputStream(file).use { input ->
			val buf = ByteArray(64 * 1024)
			while (true) {
				val n = input.read(buf)
				if (n <= 0) break
				output.write(buf, 0, n)
			}
		}
		TorrentMapsLog.append("nearby served ${file.name} (${length} B)")
	}

	private fun writeResponse(
		output: OutputStream,
		code: Int,
		contentType: String,
		body: String,
		headOnly: Boolean = false
	) {
		val bytes = body.toByteArray(StandardCharsets.UTF_8)
		val reason = when (code) {
			200 -> "OK"
			400 -> "Bad Request"
			401 -> "Unauthorized"
			404 -> "Not Found"
			405 -> "Method Not Allowed"
			else -> "Error"
		}
		val header = buildString {
			append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
			append("Content-Type: ").append(contentType).append("\r\n")
			append("Content-Length: ").append(bytes.size).append("\r\n")
			append("Connection: close\r\n")
			append("\r\n")
		}.toByteArray(StandardCharsets.US_ASCII)
		output.write(header)
		if (!headOnly) {
			output.write(bytes)
		}
	}
}
