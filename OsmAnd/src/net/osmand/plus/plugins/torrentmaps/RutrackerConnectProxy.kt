package net.osmand.plus.plugins.torrentmaps

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback HTTP CONNECT proxy that dials the origin IPv6-first.
 *
 * On some networks (Z1 behind S200X hotspot) Cloudflare IPv4 for rutracker.org
 * is black-holed while IPv6 works. Chrome 96 WebView then times out instead of
 * falling back, which shows "Webpage not available".
 */
class RutrackerConnectProxy {

	private val running = AtomicBoolean(false)
	private var server: ServerSocket? = null
	private val workers = Executors.newCachedThreadPool { r ->
		Thread(r, "rutracker-proxy").apply { isDaemon = true }
	}

	@Volatile
	var port: Int = 0
		private set

	fun start(): Int {
		if (running.getAndSet(true)) {
			return port
		}
		val sock = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
		server = sock
		port = sock.localPort
		workers.execute {
			while (running.get()) {
				val client = try {
					sock.accept()
				} catch (_: Exception) {
					break
				} ?: break
				workers.execute { handleClient(client) }
			}
		}
		return port
	}

	fun stop() {
		running.set(false)
		try {
			server?.close()
		} catch (_: Exception) {
		}
		server = null
		workers.shutdownNow()
	}

	private fun handleClient(client: Socket) {
		var remote: Socket? = null
		try {
			client.soTimeout = 15000
			val input = BufferedInputStream(client.getInputStream())
			val output = client.getOutputStream()
			val requestLine = readLine(input) ?: return
			val parts = requestLine.split(' ', limit = 3)
			if (parts.size < 2) return
			if (parts[0].equals("CONNECT", ignoreCase = true)) {
				drainHeaders(input)
				val hostPort = parts[1]
				val host = hostPort.substringBefore(':')
				val destPort = hostPort.substringAfter(':', "443").toIntOrNull() ?: 443
				remote = openRemote(host, destPort)
				client.soTimeout = 0
				remote.soTimeout = 0
				output.write(ESTABLISHED)
				output.flush()
				pipe(input, remote.getOutputStream(), remote.getInputStream(), output)
			}
		} catch (e: Exception) {
			TorrentMapsLog.append("RuTracker proxy: ${e.message}")
		} finally {
			try {
				remote?.close()
			} catch (_: Exception) {
			}
			try {
				client.close()
			} catch (_: Exception) {
			}
		}
	}

	private fun openRemote(host: String, destPort: Int): Socket {
		val addrs = InetAddress.getAllByName(host).sortedWith(compareBy { addr ->
			when {
				addr is Inet6Address && !addr.isLinkLocalAddress -> 0
				addr is Inet4Address -> 1
				else -> 2
			}
		})
		if (addrs.isEmpty()) {
			throw java.net.UnknownHostException(host)
		}
		var last: Exception? = null
		for (addr in addrs) {
			val timeout = if (addr is Inet6Address) 5000 else 4000
			try {
				val remote = Socket()
				remote.tcpNoDelay = true
				remote.connect(InetSocketAddress(addr, destPort), timeout)
				TorrentMapsLog.append("RuTracker proxy $host -> ${addr.hostAddress}")
				return remote
			} catch (e: Exception) {
				last = e
			}
		}
		throw last ?: java.net.ConnectException("no route to $host")
	}

	private fun pipe(
		clientIn: InputStream,
		remoteOut: OutputStream,
		remoteIn: InputStream,
		clientOut: OutputStream
	) {
		val up = Thread({
			copy(clientIn, remoteOut)
		}, "rutracker-proxy-up").apply {
			isDaemon = true
			start()
		}
		try {
			copy(remoteIn, clientOut)
		} finally {
			up.interrupt()
			try {
				remoteOut.close()
			} catch (_: Exception) {
			}
		}
	}

	private fun copy(from: InputStream, to: OutputStream) {
		try {
			val buf = ByteArray(16 * 1024)
			while (true) {
				val n = from.read(buf)
				if (n < 0) break
				to.write(buf, 0, n)
				to.flush()
			}
		} catch (_: Exception) {
		} finally {
			try {
				to.close()
			} catch (_: Exception) {
			}
		}
	}

	private fun drainHeaders(input: BufferedInputStream) {
		while (true) {
			val line = readLine(input) ?: break
			if (line.isEmpty()) break
		}
	}

	private fun readLine(input: BufferedInputStream): String? {
		val sb = StringBuilder()
		while (true) {
			val c = input.read()
			if (c < 0) {
				return if (sb.isEmpty()) null else sb.toString()
			}
			if (c == '\n'.code) break
			if (c != '\r'.code) {
				sb.append(c.toChar())
			}
		}
		return sb.toString()
	}

	companion object {
		private val ESTABLISHED =
			"HTTP/1.1 200 Connection Established\r\nProxy-Agent: osmand\r\n\r\n".toByteArray()
	}
}
