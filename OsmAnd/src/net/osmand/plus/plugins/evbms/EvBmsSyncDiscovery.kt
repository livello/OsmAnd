package net.osmand.plus.plugins.evbms

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import net.osmand.plus.OsmandApplication
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UDP LAN/hotspot beacon: advertise this OsmAnd EV-Telemetry peer and collect others.
 * No authorization. Role-agnostic "sync-peer".
 */
class EvBmsSyncDiscovery(
	private val app: OsmandApplication,
	private val pluginId: String,
	private val peerId: () -> String,
	private val deviceName: () -> String,
	private val tcpPort: () -> Int,
	private val localIps: () -> Collection<String>,
	private val catalogRev: () -> Long,
	private val onPeersChanged: () -> Unit
) {
	companion object {
		private const val TAG = "EvBmsSyncDisco"
		const val DISCOVER_PORT = 8743
		private const val PREFIX = "EVBMS1"
		private const val ROLE = "sync-peer"
		private const val BEACON_MS = 2_000L
		private const val PEER_TTL_MS = 8_000L
		private val MULTICAST = InetAddress.getByName("239.255.87.42")
		private val BROADCAST = InetAddress.getByName("255.255.255.255")
	}

	data class Peer(
		val id: String,
		val name: String,
		val host: String,
		val port: Int,
		val rev: Long,
		val lastSeenMs: Long
	)

	private val running = AtomicBoolean(false)
	private val peers = ConcurrentHashMap<String, Peer>()
	private var socket: DatagramSocket? = null
	private var recvThread: Thread? = null
	private var sendThread: Thread? = null
	private var multicastLock: WifiManager.MulticastLock? = null

	fun start() {
		if (!running.compareAndSet(false, true)) {
			return
		}
		acquireMulticastLock()
		val ds = DatagramSocket(null).apply {
			reuseAddress = true
			broadcast = true
			soTimeout = 1_000
			bind(InetSocketAddress(DISCOVER_PORT))
		}
		socket = ds
		recvThread = Thread({ recvLoop(ds) }, "ev-bms-disco-rx").apply {
			isDaemon = true
			start()
		}
		sendThread = Thread({ sendLoop(ds) }, "ev-bms-disco-tx").apply {
			isDaemon = true
			start()
		}
	}

	fun stop() {
		if (!running.compareAndSet(true, false)) {
			return
		}
		try {
			socket?.close()
		} catch (_: Exception) {
		}
		socket = null
		recvThread = null
		sendThread = null
		peers.clear()
		releaseMulticastLock()
		onPeersChanged()
	}

	fun snapshot(): List<Peer> {
		expire()
		return peers.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
	}

	private fun recvLoop(ds: DatagramSocket) {
		val buf = ByteArray(1500)
		while (running.get()) {
			try {
				val packet = DatagramPacket(buf, buf.size)
				ds.receive(packet)
				handlePacket(packet)
			} catch (_: java.net.SocketTimeoutException) {
				expire()
			} catch (_: java.net.SocketException) {
				if (!running.get()) {
					break
				}
			} catch (e: Exception) {
				if (running.get()) {
					Log.w(TAG, "recv", e)
				}
			}
		}
	}

	private fun sendLoop(ds: DatagramSocket) {
		while (running.get()) {
			try {
				advertise(ds)
			} catch (e: Exception) {
				if (running.get()) {
					Log.w(TAG, "send", e)
				}
			}
			try {
				Thread.sleep(BEACON_MS)
			} catch (_: InterruptedException) {
				break
			}
		}
	}

	private fun advertise(ds: DatagramSocket) {
		val payload = beaconBytes()
		val targets = LinkedHashSet<InetAddress>()
		targets.add(BROADCAST)
		targets.add(MULTICAST)
		for (ip in localIps()) {
			subnetBroadcast(ip)?.let { targets.add(it) }
		}
		try {
			val interfaces = NetworkInterface.getNetworkInterfaces()
			if (interfaces != null) {
				for (nif in interfaces) {
					if (!nif.isUp || nif.isLoopback) continue
					for (ifaceAddr in nif.interfaceAddresses) {
						val bcast = ifaceAddr.broadcast ?: continue
						targets.add(bcast)
					}
				}
			}
		} catch (_: Exception) {
		}
		for (addr in targets) {
			try {
				ds.send(DatagramPacket(payload, payload.size, addr, DISCOVER_PORT))
			} catch (_: Exception) {
			}
		}
	}

	private fun beaconBytes(): ByteArray {
		val json = JSONObject()
			.put("plugin", pluginId)
			.put("id", peerId())
			.put("name", deviceName())
			.put("port", tcpPort())
			.put("role", ROLE)
			.put("rev", catalogRev())
			.toString()
		return (PREFIX + json).toByteArray(StandardCharsets.UTF_8)
	}

	private fun handlePacket(packet: DatagramPacket) {
		if (packet.length < PREFIX.length + 2) {
			return
		}
		val text = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
		if (!text.startsWith(PREFIX)) {
			return
		}
		val json = try {
			JSONObject(text.substring(PREFIX.length))
		} catch (_: Exception) {
			return
		}
		if (json.optString("plugin") != pluginId) {
			return
		}
		if (json.optString("role") != ROLE) {
			return
		}
		val id = json.optString("id")
		if (id.isBlank() || id == peerId()) {
			return
		}
		val host = packet.address?.hostAddress ?: return
		if (host in localIps()) {
			return
		}
		val port = json.optInt("port", EvBmsSyncController.DEFAULT_PORT)
		if (port !in EvBmsSyncController.MIN_PORT..EvBmsSyncController.MAX_PORT) {
			return
		}
		val name = json.optString("name").ifBlank { host }
		val rev = json.optLong("rev")
		val now = System.currentTimeMillis()
		val previous = peers[id]
		val peer = Peer(id, name, host, port, rev, now)
		peers[id] = peer
		if (previous == null || previous.host != host || previous.port != port || previous.name != name) {
			onPeersChanged()
		}
	}

	private fun expire() {
		val now = System.currentTimeMillis()
		var removed = false
		val it = peers.entries.iterator()
		while (it.hasNext()) {
			val entry = it.next()
			if (now - entry.value.lastSeenMs > PEER_TTL_MS) {
				it.remove()
				removed = true
			}
		}
		if (removed) {
			onPeersChanged()
		}
	}

	private fun subnetBroadcast(ip: String): InetAddress? {
		val parts = ip.split('.')
		if (parts.size != 4) {
			return null
		}
		return try {
			InetAddress.getByName("${parts[0]}.${parts[1]}.${parts[2]}.255")
		} catch (_: Exception) {
			null
		}
	}

	private fun acquireMulticastLock() {
		if (multicastLock?.isHeld == true) {
			return
		}
		try {
			val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
			val lock = wifi.createMulticastLock("osmand-ev-bms-sync")
			lock.setReferenceCounted(false)
			lock.acquire()
			multicastLock = lock
		} catch (_: Exception) {
		}
	}

	private fun releaseMulticastLock() {
		try {
			multicastLock?.release()
		} catch (_: Exception) {
		}
		multicastLock = null
	}
}
