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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Coordinates local HTTP sharing + NSD discovery for offline map exchange.
 */
class NearbyMapsController(
	private val app: OsmandApplication
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
			stopSharingLocked()
			val bind = pickBindAddress()
			if (bind == null) {
				TorrentMapsLog.append("nearby: no Wi‑Fi/hotspot IPv4 — connect to same network or start hotspot")
				ui.post {
					app.showToastMessage(net.osmand.plus.R.string.torrent_maps_nearby_no_wifi)
				}
				return@execute
			}
			acquireMulticastLock()
			token = newToken()
			val http = NearbyMapsHttpServer(app, catalog, token, deviceName(), bind)
			val port = try {
				http.start()
			} catch (e: Exception) {
				TorrentMapsLog.append("nearby HTTP start failed: ${e.message}")
				http.stop()
				releaseMulticastLock()
				ui.post {
					app.showToastMessage(net.osmand.plus.R.string.torrent_maps_nearby_share_failed)
				}
				return@execute
			}
			server = http
			val nsd = NearbyMapsDiscovery(app) { list ->
				peers = list
				ui.post { listeners.forEach { it.onPeersChanged(list) } }
			}
			discovery = nsd
			nsd.advertise("OsmAndMaps-$token", port, token, deviceName())
			nsd.startDiscovery()
			sharing = true
			endpoint = "${bind.hostAddress}:$port"
			NearbyMapsService.sync(app, true)
			TorrentMapsLog.append("nearby sharing on $endpoint")
			ui.post {
				listeners.forEach { it.onSharingChanged(true, endpoint) }
				app.showToastMessage(net.osmand.plus.R.string.torrent_maps_nearby_sharing_on)
			}
		}
	}

	fun stopSharing() {
		io.execute {
			stopSharingLocked()
			ui.post {
				listeners.forEach { it.onSharingChanged(false, null) }
			}
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

	fun stopDiscoveryOnly() {
		io.execute {
			if (!sharing) {
				discovery?.stopDiscovery()
				discovery = null
				releaseMulticastLock()
			}
		}
	}

	fun fetchCatalog(peer: NearbyPeer, onDone: (NearbyCatalogResponse?, String?) -> Unit) {
		io.execute {
			try {
				val url = java.net.URL(
					"${peer.baseUrl}/catalog?token=${java.net.URLEncoder.encode(peer.token, "UTF-8")}"
				)
				val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
					connectTimeout = 10_000
					readTimeout = 30_000
					requestMethod = "GET"
				}
				conn.inputStream.use { input ->
					val text = input.bufferedReader().readText()
					val parsed = catalog.parseCatalog(text)
					ui.post { onDone(parsed, null) }
				}
				conn.disconnect()
			} catch (e: Exception) {
				TorrentMapsLog.append("nearby catalog failed: ${e.message}")
				ui.post { onDone(null, e.message) }
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
		val bind = pickBindAddress()
		return bind?.hostAddress ?: "—"
	}

	private fun stopSharingLocked() {
		sharing = false
		endpoint = null
		server?.stop()
		server = null
		discovery?.stopAdvertising()
		discovery?.stopDiscovery()
		discovery = null
		peers = emptyList()
		NearbyMapsService.sync(app, false)
		releaseMulticastLock()
		TorrentMapsLog.append("nearby sharing stopped")
	}

	private fun pickBindAddress(): InetAddress? {
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
					// SoftAP / hotspot clients often appear as WIFI; host AP may be local-only.
					if (!hasWifi && !hasWifiAware && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
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
		try {
			val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
			for (nif in interfaces) {
				if (!nif.isUp || nif.isLoopback) continue
				val name = nif.name.lowercase()
				if (!(name.startsWith("wlan") || name.startsWith("ap") ||
						name.startsWith("swlan") || name.startsWith("eth") ||
						name.contains("wlan"))
				) {
					continue
				}
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
