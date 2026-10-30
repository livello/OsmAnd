package net.osmand.plus.plugins.loraosmand

import android.content.Context
import android.text.format.DateUtils
import net.osmand.plus.R
import org.meshtastic.proto.NodeInfo
import java.util.Locale
import kotlin.math.roundToInt

internal fun formatNodeMetrics(context: Context, plugin: LoraOsmAndPlugin, node: NodeInfo): String {
	val parts = ArrayList<String>()
	node.batteryPercent()?.let { parts.add(if (it < 20) "🪫$it%" else "🔋$it%") }
	node.device_metrics?.voltage?.let {
		parts.add("⚡" + String.format(Locale.getDefault(), "%.2fV", it))
	}
	if (node.snr != 0f) {
		parts.add("📶" + String.format(Locale.getDefault(), "%.1f", node.snr))
	}
	plugin.rssiOf(node.num)?.let { parts.add("📡$it") }
	node.hops_away?.takeIf { it > 0 }?.let { parts.add("🐇$it") }
	val where = node.meshLatLon()
	val phone = plugin.phoneLocation()
	if (where != null && phone != null) {
		val meters = metersBetween(where.latitude, where.longitude, phone.latitude, phone.longitude)
		val distance = if (meters < 1000) {
			context.getString(R.string.lora_osmand_dist_m, meters.roundToInt())
		} else {
			context.getString(R.string.lora_osmand_dist_km, meters / 1000.0)
		}
		parts.add("📍$distance")
	}
	node.position?.altitude?.let { parts.add("⛰$it") }
	if (node.last_heard > 0) {
		val heard = DateUtils.getRelativeTimeSpanString(
			node.last_heard * 1000L,
			System.currentTimeMillis(),
			DateUtils.MINUTE_IN_MILLIS,
			DateUtils.FORMAT_ABBREV_RELATIVE,
		)
		parts.add("🕐$heard")
	}
	node.device_metrics?.channel_utilization?.let {
		parts.add("📊" + String.format(Locale.getDefault(), "%.0f%%", it))
	}
	node.device_metrics?.air_util_tx?.let {
		parts.add("💨" + String.format(Locale.getDefault(), "%.0f%%", it))
	}
	return parts.joinToString("  ")
}
