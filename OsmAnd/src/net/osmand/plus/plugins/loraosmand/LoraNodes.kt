package net.osmand.plus.plugins.loraosmand

import android.location.Location
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.Position

internal fun NodeInfo.shortLabel(): String {
	val short = user?.short_name?.takeIf { it.isNotBlank() }
	if (short != null) {
		return short
	}
	return "!" + num.toUInt().toString(16).padStart(8, '0')
}

internal fun NodeInfo.longLabel(): String {
	return user?.long_name?.takeIf { it.isNotBlank() } ?: shortLabel()
}

internal fun NodeInfo.batteryPercent(): Int? {
	val level = device_metrics?.battery_level ?: return null
	return when {
		level in 0..100 -> level
		level > 100 -> 100
		else -> null
	}
}

internal data class MeshLatLon(val latitude: Double, val longitude: Double)

internal fun Position.latLon(): MeshLatLon? {
	val lat = latitude_i ?: return null
	val lon = longitude_i ?: return null
	if (lat == 0 && lon == 0) {
		return null
	}
	return MeshLatLon(lat / 1e7, lon / 1e7)
}

internal fun NodeInfo.meshLatLon(): MeshLatLon? = position?.latLon()

internal const val MESH_ONLINE_SEC = 2 * 60 * 60

internal fun NodeInfo.isMeshOnline(nowSec: Int = (System.currentTimeMillis() / 1000L).toInt()): Boolean {
	return last_heard > 0 && nowSec - last_heard in 0..MESH_ONLINE_SEC
}

internal fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
	val out = FloatArray(1)
	Location.distanceBetween(lat1, lon1, lat2, lon2, out)
	return out[0].toDouble()
}
