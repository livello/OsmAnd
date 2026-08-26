package net.osmand.plus.plugins.torrentmaps

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Advertise / discover OsmAnd map peers via NSD (mDNS) on the current LAN / hotspot.
 */
class NearbyMapsDiscovery(
	private val context: Context,
	private val onPeersChanged: (List<NearbyPeer>) -> Unit
) {
	companion object {
		private const val TAG = "NearbyMapsNsd"
		const val SERVICE_TYPE = "_osmand-maps._tcp."
		private const val ATTR_TOKEN = "token"
		private const val ATTR_DEVICE = "device"
		private const val ATTR_HOST = "ip"
	}

	private val nsd: NsdManager? =
		context.getSystemService(Context.NSD_SERVICE) as? NsdManager

	private val peers = ConcurrentHashMap<String, NearbyPeer>()
	@Volatile
	private var registeredName: String? = null
	private var registrationListener: NsdManager.RegistrationListener? = null
	private var discoveryListener: NsdManager.DiscoveryListener? = null
	private var discovering = false

	fun advertise(
		serviceName: String,
		port: Int,
		token: String,
		deviceName: String,
		advertiseHost: String? = null
	) {
		val manager = nsd ?: return
		stopAdvertising()
		val info = NsdServiceInfo().apply {
			this.serviceName = sanitizeName(serviceName)
			this.serviceType = SERVICE_TYPE
			this.port = port
			setAttribute(ATTR_TOKEN, token.take(48))
			setAttribute(ATTR_DEVICE, deviceName.take(48))
			if (!advertiseHost.isNullOrBlank()) {
				setAttribute(ATTR_HOST, advertiseHost.take(48))
			}
		}
		val listener = object : NsdManager.RegistrationListener {
			override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
				TorrentMapsLog.append("nearby NSD register failed: $errorCode")
			}

			override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}

			override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
				registeredName = serviceInfo.serviceName
				TorrentMapsLog.append("nearby NSD registered ${serviceInfo.serviceName}:$port")
			}

			override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
				registeredName = null
			}
		}
		registrationListener = listener
		try {
			manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
		} catch (e: Exception) {
			Log.w(TAG, "register", e)
			TorrentMapsLog.append("nearby NSD register error: ${e.message}")
		}
	}

	fun stopAdvertising() {
		val manager = nsd ?: return
		val listener = registrationListener ?: return
		try {
			manager.unregisterService(listener)
		} catch (_: Exception) {
		}
		registrationListener = null
		registeredName = null
	}

	fun startDiscovery() {
		val manager = nsd ?: return
		stopDiscovery()
		peers.clear()
		notifyPeers()
		val listener = object : NsdManager.DiscoveryListener {
			override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
				discovering = false
				TorrentMapsLog.append("nearby discovery start failed: $errorCode")
			}

			override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
				discovering = false
			}

			override fun onDiscoveryStarted(serviceType: String?) {
				discovering = true
				TorrentMapsLog.append("nearby discovery started")
			}

			override fun onDiscoveryStopped(serviceType: String?) {
				discovering = false
			}

			override fun onServiceFound(serviceInfo: NsdServiceInfo) {
				if (serviceInfo.serviceType?.contains("osmand-maps") != true &&
					SERVICE_TYPE.trimEnd('.') !in (serviceInfo.serviceType ?: "")
				) {
					// Still try resolve — vendors report types inconsistently.
				}
				if (serviceInfo.serviceName == registeredName) {
					return
				}
				resolve(serviceInfo)
			}

			override fun onServiceLost(serviceInfo: NsdServiceInfo) {
				peers.remove(serviceInfo.serviceName)
				notifyPeers()
			}
		}
		discoveryListener = listener
		try {
			manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
		} catch (e: Exception) {
			Log.w(TAG, "discover", e)
			TorrentMapsLog.append("nearby discovery error: ${e.message}")
		}
	}

	fun stopDiscovery() {
		val manager = nsd ?: return
		val listener = discoveryListener ?: return
		try {
			manager.stopServiceDiscovery(listener)
		} catch (_: Exception) {
		}
		discoveryListener = null
		discovering = false
	}

	fun snapshotPeers(): List<NearbyPeer> =
		peers.values.sortedBy { it.deviceName.lowercase() }

	private fun resolve(serviceInfo: NsdServiceInfo) {
		val manager = nsd ?: return
		try {
			manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
				override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
					TorrentMapsLog.append("nearby resolve failed ${serviceInfo?.serviceName}: $errorCode")
				}

				override fun onServiceResolved(resolved: NsdServiceInfo) {
					val resolvedHost = pickIpv4(resolved.host) ?: resolved.host?.hostAddress
					val txtHost = attribute(resolved, ATTR_HOST).trim()
					// Prefer explicit IPv4 from TXT (stable across SoftAP / multi-homed devices).
					val host = when {
						txtHost.isNotBlank() && looksLikeIpv4(txtHost) -> txtHost
						!resolvedHost.isNullOrBlank() && looksLikeIpv4(resolvedHost) -> resolvedHost
						txtHost.isNotBlank() -> txtHost
						!resolvedHost.isNullOrBlank() -> resolvedHost
						else -> return
					}
					var token = attribute(resolved, ATTR_TOKEN)
					if (token.isBlank()) {
						val sn = resolved.serviceName.orEmpty()
						val prefix = "OsmAndMaps-"
						if (sn.startsWith(prefix)) {
							token = sn.removePrefix(prefix)
						}
					}
					val device = attribute(resolved, ATTR_DEVICE)
						.ifBlank { resolved.serviceName ?: host }
					val peer = NearbyPeer(
						serviceName = resolved.serviceName ?: device,
						host = host,
						port = resolved.port,
						token = token,
						deviceName = device
					)
					peers[peer.serviceName] = peer
					TorrentMapsLog.append("nearby peer ${peer.deviceName} @ ${peer.host}:${peer.port}")
					notifyPeers()
				}
			})
		} catch (e: Exception) {
			Log.w(TAG, "resolve", e)
		}
	}

	private fun attribute(info: NsdServiceInfo, key: String): String {
		return try {
			if (Build.VERSION.SDK_INT >= 21) {
				val map = info.attributes ?: return ""
				val bytes = map[key] ?: return ""
				String(bytes, Charsets.UTF_8)
			} else {
				""
			}
		} catch (_: Exception) {
			""
		}
	}

	private fun notifyPeers() {
		onPeersChanged(snapshotPeers())
	}

	private fun sanitizeName(name: String): String =
		name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48).ifBlank { "OsmAndMaps" }

	private fun looksLikeIpv4(host: String): Boolean =
		host.count { it == '.' } == 3 && host.all { it.isDigit() || it == '.' }

	private fun pickIpv4(address: java.net.InetAddress?): String? {
		if (address == null) return null
		if (address is java.net.Inet4Address) {
			return address.hostAddress
		}
		val mapped = address.hostAddress ?: return null
		return if (mapped.startsWith("::ffff:")) {
			mapped.removePrefix("::ffff:")
		} else if (looksLikeIpv4(mapped)) {
			mapped
		} else {
			null
		}
	}
}
