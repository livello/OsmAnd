package net.osmand.plus.plugins.loraosmand

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import net.osmand.plus.OsmandApplication
import net.osmand.plus.plugins.PluginsHelper

/**
 * Starts the mesh link after boot, after an update, and from the inexact
 * alarm that brings the process back when Android has dropped the dataSync
 * service. Also accepts an explicit send used to check Long Fast from adb.
 */
class LoraBridgeReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent?) {
		val app = context.applicationContext as? OsmandApplication ?: return
		val plugin = PluginsHelper.getPlugin(LoraOsmAndPlugin::class.java) ?: return
		if (!plugin.isActive || !plugin.usesMeshApp()) {
			return
		}
		if (intent?.action == ACTION_SEND) {
			val from = if (Build.VERSION.SDK_INT >= 34) sentFromUid else Process.myUid()
			if (from != Process.SHELL_UID && from != Process.myUid()) {
				Log.w(TAG, "send ignored from uid=$from")
				return
			}
			val text = intent.getStringExtra(EXTRA_TEXT)?.trim().orEmpty()
			if (text.isEmpty()) {
				return
			}
			Log.i(TAG, "send requested ch=${intent.getIntExtra(EXTRA_CHANNEL, 0)}")
			plugin.wakeMeshLink()
			plugin.sendChat(text, intent.getIntExtra(EXTRA_CHANNEL, 0))
			return
		}
		plugin.wakeMeshLink()
	}

	companion object {
		private const val TAG = "LoraOsmAnd"
		const val ACTION_WAKE = "net.osmand.plus.plugins.loraosmand.MESH_WAKE"
		const val ACTION_SEND = "net.osmand.plus.plugins.loraosmand.SEND_CHAT"
		const val EXTRA_TEXT = "text"
		const val EXTRA_CHANNEL = "channel"
	}
}
