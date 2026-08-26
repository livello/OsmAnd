package net.osmand.plus.plugins.torrentmaps

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
import net.osmand.plus.notifications.NotificationHelper
import net.osmand.plus.plugins.PluginsHelper

class NearbyMapsService : Service() {

	companion object {
		const val NOTIFICATION_ID = 13

		fun sync(context: Context, start: Boolean) {
			val intent = Intent(context, NearbyMapsService::class.java)
			if (start) {
				ContextCompat.startForegroundService(context, intent)
			} else {
				context.stopService(intent)
			}
		}
	}

	private val app: OsmandApplication
		get() = application as OsmandApplication

	private val plugin: TorrentMapsPlugin?
		get() = PluginsHelper.getPlugin(TorrentMapsPlugin::class.java)

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		app.notificationHelper.createNotificationChannel()
		val ctl = plugin?.nearby
		if (ctl == null || !ctl.sharing) {
			stopSelf()
			return START_NOT_STICKY
		}
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				startForeground(
					NOTIFICATION_ID,
					buildNotification(ctl.endpoint),
					ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
				)
			} else {
				startForeground(NOTIFICATION_ID, buildNotification(ctl.endpoint))
			}
			TorrentMapsLog.append("nearby service foreground")
		} catch (_: Exception) {
			TorrentMapsLog.append("nearby service foreground failed")
			stopSelf()
			return START_NOT_STICKY
		}
		return START_STICKY
	}

	private fun buildNotification(endpoint: String?): Notification {
		val title = getString(R.string.torrent_maps_nearby_title)
		val text = getString(
			R.string.torrent_maps_nearby_notification,
			endpoint ?: "…"
		)
		val launch = Intent(this, NearbyMapsActivity::class.java).apply {
			flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
		}
		val pending = PendingIntent.getActivity(
			this,
			NOTIFICATION_ID,
			launch,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)
		return NotificationCompat.Builder(this, NotificationHelper.NOTIFICATION_CHANEL_ID)
			.setSmallIcon(android.R.drawable.stat_sys_upload)
			.setContentTitle(title)
			.setContentText(text)
			.setStyle(NotificationCompat.BigTextStyle().bigText(text))
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setContentIntent(pending)
			.build()
	}
}
