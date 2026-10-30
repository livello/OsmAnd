package net.osmand.plus.plugins.loraosmand

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.notifications.NotificationHelper
import net.osmand.plus.plugins.PluginsHelper

/** Keeps the Meshtastic TAK socket alive after the map leaves the screen. */
class LoraBridgeService : Service() {

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		val app = application as OsmandApplication
		val plugin = PluginsHelper.getPlugin(LoraOsmAndPlugin::class.java)
		if (plugin == null || !plugin.isActive || !plugin.usesMeshApp()) {
			stopSelf()
			return START_NOT_STICKY
		}
		app.notificationHelper.createNotificationChannel()
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				startForeground(
					NOTIFICATION_ID,
					buildNotification(),
					ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
				)
			} else {
				startForeground(NOTIFICATION_ID, buildNotification())
			}
		} catch (_: Exception) {
			stopSelf()
			return START_NOT_STICKY
		}
		plugin.ensureMeshLink()
		return START_STICKY
	}

	private fun buildNotification(): Notification {
		val launch = Intent(this, MapActivity::class.java).apply {
			flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
		}
		val pending = PendingIntent.getActivity(
			this,
			NOTIFICATION_ID,
			launch,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
		)
		return NotificationCompat.Builder(this, NotificationHelper.NOTIFICATION_CHANEL_ID)
			.setSmallIcon(R.drawable.ic_action_signal)
			.setContentTitle(getString(R.string.lora_osmand_bridge_notification))
			.setContentText(getString(R.string.lora_osmand_bridge_notification_text))
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setSilent(true)
			.setContentIntent(pending)
			.build()
	}

	companion object {
		const val NOTIFICATION_ID = 8767

		fun start(context: Context) {
			try {
				ContextCompat.startForegroundService(context, Intent(context, LoraBridgeService::class.java))
			} catch (_: Exception) {
			}
		}

		fun stop(context: Context) {
			context.stopService(Intent(context, LoraBridgeService::class.java))
		}
	}
}
