package net.osmand.plus.plugins.torrentmaps

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps Wi‑Fi / CPU awake during Nearby map transfers so SoftAP / hotspot
 * power-save does not throttle throughput to Bluetooth-like speeds.
 */
object NearbyTransferLocks {
	private val holdCount = AtomicInteger(0)
	private var wifiLock: WifiManager.WifiLock? = null
	private var wakeLock: PowerManager.WakeLock? = null

	@Synchronized
	fun acquire(context: Context, reason: String) {
		val count = holdCount.incrementAndGet()
		if (count == 1) {
			try {
				val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
				@Suppress("DEPRECATION")
				val mode = if (android.os.Build.VERSION.SDK_INT >= 29) {
					WifiManager.WIFI_MODE_FULL_LOW_LATENCY
				} else {
					WifiManager.WIFI_MODE_FULL_HIGH_PERF
				}
				val lock = wifi.createWifiLock(mode, "osmand-nearby-xfer")
				lock.setReferenceCounted(false)
				lock.acquire()
				wifiLock = lock
			} catch (e: Exception) {
				TorrentMapsLog.append("nearby WifiLock failed: ${e.message}")
				try {
					val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
					@Suppress("DEPRECATION")
					val lock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL, "osmand-nearby-xfer")
					lock.setReferenceCounted(false)
					lock.acquire()
					wifiLock = lock
				} catch (_: Exception) {
				}
			}
			try {
				val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
				val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "osmand:nearby-xfer")
				wl.setReferenceCounted(false)
				wl.acquire(60 * 60 * 1000L) // 1h max; release() clears earlier
				wakeLock = wl
			} catch (e: Exception) {
				TorrentMapsLog.append("nearby WakeLock failed: ${e.message}")
			}
			TorrentMapsLog.append("nearby transfer locks on ($reason)")
		}
	}

	@Synchronized
	fun release(reason: String) {
		val count = holdCount.decrementAndGet()
		if (count > 0) return
		holdCount.set(0)
		try {
			wifiLock?.let { if (it.isHeld) it.release() }
		} catch (_: Exception) {
		}
		wifiLock = null
		try {
			wakeLock?.let { if (it.isHeld) it.release() }
		} catch (_: Exception) {
		}
		wakeLock = null
		TorrentMapsLog.append("nearby transfer locks off ($reason)")
	}
}
