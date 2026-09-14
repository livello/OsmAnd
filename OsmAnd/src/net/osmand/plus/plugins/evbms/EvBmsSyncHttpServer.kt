package net.osmand.plus.plugins.evbms

import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unauthenticated local HTTP server for EV telemetry files. Binds 0.0.0.0.
 * Recording continues while this serves.
 */
class EvBmsSyncHttpServer(
	private val files: EvBmsSyncFiles,
	private val listenPort: Int,
	private val deviceName: String,
	private val pluginId: String,
	private val peerId: String,
	private val onClient: (delta: Int) -> Unit,
	private val onServed: (name: String, bytes: Long) -> Unit
) {
	companion object {
		private const val TAG = "EvBmsSyncHttp"
		private const val MAX_HEADER = 32 * 1024
		private const val IO_BUFFER = 64 * 1024
	}

	private val running = AtomicBoolean(false)
	private val clients = AtomicInteger(0)
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
		val ss = ServerSocket()
		ss.reuseAddress = true
		ss.bind(InetSocketAddress("0.0.0.0", listenPort), 32)
		serverSocket = ss
		port = ss.localPort
		pool = Executors.newCachedThreadPool { r ->
			Thread(r, "ev-sync-http").apply { isDaemon = true }
		}
		acceptThread = Thread({
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
		}, "ev-sync-accept").apply {
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
		clients.set(0)
	}

	fun clientCount(): Int = clients.get()

	private fun handleClient(socket: Socket) {
		clients.incrementAndGet()
		onClient(1)
		try {
			socket.soTimeout = 120_000
			socket.use { s ->
				val input = BufferedInputStream(s.getInputStream(), IO_BUFFER)
				val output = BufferedOutputStream(s.getOutputStream(), IO_BUFFER)
				val request = readRequest(input) ?: run {
					writeText(output, 400, "text/plain", "bad request")
					output.flush()
					return
				}
				dispatch(request, output)
				output.flush()
			}
		} catch (e: Exception) {
			if (running.get()) {
				Log.w(TAG, "client", e)
			}
		} finally {
			clients.decrementAndGet()
			onClient(-1)
		}
	}

	private data class HttpRequest(val method: String, val path: String, val query: Map<String, String>)

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
			out[urlDecode(part.substring(0, eq))] = urlDecode(part.substring(eq + 1))
		}
		return out
	}

	private fun urlDecode(s: String): String =
		java.net.URLDecoder.decode(s.replace('+', ' '), StandardCharsets.UTF_8.name())

	private fun dispatch(req: HttpRequest, output: OutputStream) {
		if (req.method != "GET" && req.method != "HEAD") {
			writeText(output, 405, "text/plain", "method not allowed")
			return
		}
		val headOnly = req.method == "HEAD"
		when {
			req.path == "/health" -> writeText(
				output,
				200,
				"application/json; charset=utf-8",
				JSONObject()
					.put("ok", true)
					.put("plugin", pluginId)
					.put("id", peerId)
					.put("name", deviceName)
					.put("port", port)
					.put("role", "sync-peer")
					.toString(),
				headOnly
			)
			req.path == "/files" -> writeText(
				output,
				200,
				"application/json; charset=utf-8",
				files.catalogJson(deviceName, port, pluginId, peerId),
				headOnly
			)
			req.path.startsWith("/file/") || req.path == "/file" -> {
				val relative = when {
					req.path.startsWith("/file/") -> urlDecode(req.path.removePrefix("/file/"))
					else -> req.query["path"].orEmpty()
				}
				val clean = EvBmsSyncFiles.sanitizePath(relative)
				if (clean == null) {
					writeText(output, 404, "text/plain", "not found")
					return
				}
				val stream = files.open(clean)
				if (stream == null) {
					writeText(output, 404, "text/plain", "not found")
					return
				}
				streamFile(clean, stream, output, headOnly)
			}
			else -> writeText(output, 404, "text/plain", "not found")
		}
	}

	private fun streamFile(path: String, input: InputStream, output: OutputStream, headOnly: Boolean) {
		val length = files.localSize(path)
		val name = path.substringAfterLast('/').replace("\"", "")
		val header = buildString {
			append("HTTP/1.1 200 OK\r\n")
			append("Content-Type: application/octet-stream\r\n")
			append("Content-Length: ").append(length).append("\r\n")
			append("Content-Disposition: attachment; filename=\"").append(name).append("\"\r\n")
			append("Connection: close\r\n")
			append("\r\n")
		}.toByteArray(StandardCharsets.US_ASCII)
		output.write(header)
		if (headOnly) {
			input.close()
			return
		}
		var sent = 0L
		input.use { src ->
			val buf = ByteArray(IO_BUFFER)
			while (true) {
				val n = src.read(buf)
				if (n <= 0) break
				output.write(buf, 0, n)
				sent += n
			}
		}
		onServed(name, sent)
	}

	private fun writeText(
		output: OutputStream,
		code: Int,
		contentType: String,
		body: String,
		headOnly: Boolean = false
	) {
		val payload = body.toByteArray(StandardCharsets.UTF_8)
		val reason = when (code) {
			200 -> "OK"
			400 -> "Bad Request"
			404 -> "Not Found"
			405 -> "Method Not Allowed"
			else -> "Error"
		}
		val header = buildString {
			append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
			append("Content-Type: ").append(contentType).append("\r\n")
			append("Content-Length: ").append(payload.size).append("\r\n")
			append("Connection: close\r\n")
			append("\r\n")
		}.toByteArray(StandardCharsets.US_ASCII)
		output.write(header)
		if (!headOnly) {
			output.write(payload)
		}
	}
}
