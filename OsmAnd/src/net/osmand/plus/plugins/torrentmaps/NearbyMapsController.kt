package net.osmand.plus.plugins.torrentmaps

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import net.osmand.plus.OsmandApplication
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Coordinates local HTTP sharing + NSD discovery for offline map exchange.
 */
class NearbyMapsController(
	private val app: OsmandApplication,
	private val plugin: TorrentMapsPlugin
) {
	companion object {
		private val HEX = "0123456789abcdef".toCharArray()
	}

	interface Listener {
		fun onPeersChanged(peers: List<NearbyPeer>)
		fun onSharingChanged(sharing: Boolean, endpoint: String?)
	}

	val catalog = NearbyMapsCatalog(app)
	val downloader = NearbyMapsDownloader(app, catalog)

	private val ui = Handler(Looper.getMainLooper())
	private val io = Executors.newSingleThreadExecutor { r ->
		Thread(r, "nearby-maps-ctl").apply { isDaemon = true }
	}
	private val listeners = CopyOnWriteArrayList<Listener>()
	private var discovery: NearbyMapsDiscovery? = null
	private var server: NearbyMapsHttpServer? = null
	private var multicastLock: WifiManager.MulticastLock? = null
	@Volatile
	private var token: String = newToken()
	@Volatile
	var sharing: Boolean = false
		private set
	@Volatile
	var endpoint: String? = null
		private set
	@Volatile
	private var peers: List<NearbyPeer> = emptyList()

	fun addListener(listener: Listener) {
		listeners.add(listener)
		listener.onPeersChanged(peers)
		listener.onSharingChanged(sharing, endpoint)
	}

	fun removeListener(listener: Listener) {
		listeners.remove(listener)
	}

	fun currentPeers(): List<NearbyPeer> = peers

	fun deviceName(): String {
		val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
			try {
				Settings.Global.getString(app.contentResolver, "device_name")
			} catch (_: Exception) {
				null
			}
		} else {
			null
		}
		return name?.takeIf { it.isNotBlank() }
			?: Build.MODEL
			?: "OsmAnd"
	}

	fun startSharing() {
		io.execute {
			stopHttpOnlyLocked()
			val advertise = pickAdvertiseAddress()
			if (advertise == null) {
				TorrentMapsLog.append("nearby: no Wi‑Fi/hotspot IPv4 — connect to same network or start hotspot")
				ui.post {
					app.showToastMessage(net.osmand.plus.R.string.torrent_maps_nearby_no_wifi)
				}
				return@execute
			}
			acquireMulticastLock()
			token = newToken()
			val http = NearbyMapsHttpServer(
				app,
				catalog,
				token,
				deviceName(),
				advertise,
				torrentOffer = { plugin.nearbyTorrentOffer() },
				torrentFile = { plugin.torrentFile().takeIf { it.isFile && it.length() > 64L } }
			)
			val port = try {
				http.start()
			} catch (e: Exception) {
				TorrentMapsLog.append("nearby HTTP start failed: ${e.message}")
				http.stop()
				ui.post {
					app.showToastMessage(net.osmand.plus.R.string.torrent_maps_nearby_share_failed)
				}
				return@execute
			}
			server = http
			val nsd = discovery ?: NearbyMapsDiscovery(app) { list ->
				peers = list
				ui.post { listeners.forEach { it.onPeersChanged(list) } }
			}.also { discovery = it }
			val host = advertise.hostAddress
			nsd.advertise("OsmAndMaps-$token", port, token, deviceName(), host)
			nsd.startDiscovery()
			sharing = true
			plugin.NEARBY_SHARE.set(true)
			endpoint = "$host:$port"
			NearbyMapsService.sync(app, true)
			TorrentMapsLog.append("nearby sharing on $endpoint (HTTP 0.0.0.0:$port)")
			ui.post {
				listeners.forEach { it.onSharingChanged(true, endpoint) }
				app.showToastMessage(net.osmand.plus.R.string.torrent_maps_nearby_sharing_on)
			}
		}
	}

	fun stopSharing() {
		io.execute {
			stopHttpOnlyLocked()
			plugin.NEARBY_SHARE.set(false)
			ui.post {
				listeners.forEach { it.onSharingChanged(false, null) }
			}
		}
	}

	/**
	 * Force a fresh Scan pass (clears current peer list and rediscovers).
	 */
	fun rescan() {
		io.execute {
			acquireMulticastLock()
			var nsd = discovery
			if (nsd == null) {
				nsd = NearbyMapsDiscovery(app) { list ->
					peers = list
					ui.post { listeners.forEach { it.onPeersChanged(list) } }
				}
				discovery = nsd
			} else {
				nsd.stopDiscovery()
			}
			nsd.startDiscovery()
		}
	}

	fun ensureDiscovery() {
		io.execute {
			acquireMulticastLock()
			var nsd = discovery
			if (nsd == null) {
				nsd = NearbyMapsDiscovery(app) { list ->
					peers = list
					ui.post { listeners.forEach { it.onPeersChanged(list) } }
				}
				discovery = nsd
			}
			nsd.startDiscovery()
		}
	}

	/**
	 * Stop Scan/discovery without touching the share HTTP server.
	 * Used when leaving Nearby UI while not sharing.
	 */
	fun stopDiscoveryOnly() {
		io.execute {
			if (!sharing) {
				discovery?.stopDiscovery()
				discovery = null
				releaseMulticastLock()
				TorrentMapsLog.append("nearby discovery stopped (UI closed)")
			}
		}
	}

	fun fetchPeerTorrent(peer: NearbyPeer, onDone: (ByteArray?, String?) -> Unit) {
		io.execute {
			try {
				val tokenEnc = java.net.URLEncoder.encode(peer.token, "UTF-8")
				val url = URL("${peer.baseUrl}/torrent?token=$tokenEnc")
				val conn = (url.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
					connectTimeout = 15_000
					readTimeout = 60_000
					requestMethod = "GET"
					useCaches = false
				}
				try {
					if (conn.responseCode !in 200..299) {
						ui.post { onDone(null, "HTTP ${conn.responseCode}") }
						return@execute
					}
					val bytes = conn.inputStream.use { it.readBytes() }
					ui.post { onDone(bytes, null) }
				} finally {
					conn.disconnect()
				}
			} catch (e: Exception) {
				ui.post { onDone(null, e.message) }
			}
		}
	}

	fun fetchCatalog(peer: NearbyPeer, onDone: (NearbyCatalogResponse?, String?) -> Unit) {
		io.execute {
			try {
				val reachable = probeHealth(peer)
				if (!reachable) {
					val msg = app.getString(
						net.osmand.plus.R.string.torrent_maps_nearby_unreachable,
						peer.deviceName,
						"${peer.host}:${peer.port}"
					)
					TorrentMapsLog.append("nearby catalog unreachable ${peer.host}:${peer.port}")
					ui.post { onDone(null, msg) }
					return@execute
				}
				val url = URL(
					"${peer.baseUrl}/catalog?token=${java.net.URLEncoder.encode(peer.token, "UTF-8")}"
				)
				val conn = (url.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
					connectTimeout = 10_000
					readTimeout = 30_000
					requestMethod = "GET"
					useCaches = false
					instanceFollowRedirects = false
				}
				try {
					val code = conn.responseCode
					if (code !in 200..299) {
						TorrentMapsLog.append("nearby catalog HTTP $code")
						ui.post {
							onDone(null, "HTTP $code")
						}
						return@execute
					}
					conn.inputStream.use { input ->
						val text = input.bufferedReader().readText()
						val parsed = catalog.parseCatalog(text)
						ui.post { onDone(parsed, null) }
					}
				} finally {
					conn.disconnect()
				}
			} catch (e: Exception) {
				TorrentMapsLog.append("nearby catalog failed: ${e.message}")
				val msg = if (isConnectFailure(e)) {
					app.getString(
						net.osmand.plus.R.string.torrent_maps_nearby_unreachable,
						peer.deviceName,
						"${peer.host}:${peer.port}"
					)
				} else {
					e.message
				}
				ui.post { onDone(null, msg) }
			}
		}
	}

	@Volatile
	private var localByKey: Map<String, NearbyMapEntry> = emptyMap()

	fun refreshLocalIndex() {
		localByKey = catalog.buildEntries(computeMissingHashes = false).associateBy { it.mapKey }
	}

	fun compareStatus(peerEntry: NearbyMapEntry): NearbyMapStatus {
		if (localByKey.isEmpty()) {
			refreshLocalIndex()
		}
		val local = localByKey[peerEntry.mapKey]
		return NearbyMapCompare.compare(local, peerEntry)
	}

	fun localWifiHint(): String {
		val bind = pickAdvertiseAddress()
		return bind?.hostAddress ?: "—"
	}

	fun probeHealth(peer: NearbyPeer): Boolean {
		return try {
			val url = URL("${peer.baseUrl}/health")
			val conn = (url.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
				connectTimeout = 4_000
				readTimeout = 4_000
				requestMethod = "GET"
				instanceFollowRedirects = false
				useCaches = false
			}
			try {
				val code = conn.responseCode
				code in 200..299
			} finally {
				conn.disconnect()
			}
		} catch (e: Exception) {
			TorrentMapsLog.append("nearby health failed ${peer.host}:${peer.port}: ${e.message}")
			false
		}
	}

	private fun isConnectFailure(e: Exception): Boolean {
		val msg = (e.message ?: "").lowercase()
		return e is java.net.ConnectException ||
			e is java.net.SocketTimeoutException ||
			e is java.net.NoRouteToHostException ||
			msg.contains("failed to connect") ||
			msg.contains("econnrefused") ||
			msg.contains("enetunreach") ||
			msg.contains("no route to host")
	}

	/** Tear down share HTTP + NSD advertise; leave peer Scan running. */
	private fun stopHttpOnlyLocked() {
		val wasSharing = sharing
		sharing = false
		endpoint = null
		endpoint = null
		server?.stop()
		server = null
		discovery?.stopAdvertising()
		NearbyMapsService.sync(app, false)
		if (wasSharing) {
			TorrentMapsLog.append("nearby sharing stopped (discovery kept)")
		}
	}

	/** Full teardown (plugin disable / process cleanup). */
	fun shutdownAll() {
		io.execute {
			sharing = false
			plugin.NEARBY_SHARE.set(false)
			endpoint = null
			server?.stop()
			server = null
			discovery?.stopAdvertising()
			discovery?.stopDiscovery()
			discovery = null
			peers = emptyList()
			NearbyMapsService.sync(app, false)
			releaseMulticastLock()
			TorrentMapsLog.append("nearby fully stopped")
			ui.post {
				listeners.forEach {
					it.onSharingChanged(false, null)
					it.onPeersChanged(emptyList())
				}
			}
		}
	}

	/**
	 * Prefer SoftAP / hotspot IPv4 when hosting; otherwise Wi‑Fi / Ethernet client IPv4.
	 * This address is advertised to peers (HTTP itself listens on 0.0.0.0).
	 */
	private fun pickAdvertiseAddress(): InetAddress? {
		val softAp = findIpv4OnInterfaces { name ->
			name.startsWith("ap") || name.startsWith("swlan") ||
				name.startsWith("softap") || name.contains("ap0")
		}
		if (softAp != null) {
			return softAp
		}
		try {
			val cm = app.getSystemService(ConnectivityManager::class.java)
			if (cm != null) {
				for (network in cm.allNetworks) {
					val caps = cm.getNetworkCapabilities(network) ?: continue
					val hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
					val hasWifiAware = if (Build.VERSION.SDK_INT >= 26) {
						caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
					} else {
						false
					}
					if (!hasWifi && !hasWifiAware &&
						!caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
					) {
						continue
					}
					val lp: LinkProperties = cm.getLinkProperties(network) ?: continue
					for (link in lp.linkAddresses) {
						val addr = link.address
						if (addr is Inet4Address && !addr.isLoopbackAddress) {
							return addr
						}
					}
				}
			}
		} catch (_: Exception) {
		}
		return findIpv4OnInterfaces { name ->
			name.startsWith("wlan") || name.startsWith("ap") ||
				name.startsWith("swlan") || name.startsWith("eth") ||
				name.contains("wlan")
		}
	}

	private fun findIpv4OnInterfaces(predicate: (String) -> Boolean): InetAddress? {
		try {
			val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
			for (nif in interfaces) {
				if (!nif.isUp || nif.isLoopback) continue
				val name = nif.name.lowercase()
				if (!predicate(name)) continue
				for (addr in nif.inetAddresses) {
					if (addr is Inet4Address && !addr.isLoopbackAddress) {
						return addr
					}
				}
			}
		} catch (_: Exception) {
		}
		return null
	}

	private fun acquireMulticastLock() {
		if (multicastLock?.isHeld == true) return
		try {
			val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
			val lock = wifi.createMulticastLock("osmand-nearby-maps")
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

	private fun newToken(): String {
		val bytes = ByteArray(12)
		SecureRandom().nextBytes(bytes)
		val out = CharArray(bytes.size * 2)
		var i = 0
		for (b in bytes) {
			val v = b.toInt() and 0xff
			out[i++] = HEX[v ushr 4]
			out[i++] = HEX[v and 0x0f]
		}
		return String(out)
	}
}
