package net.osmand.plus.plugins.evbms

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

class EvBmsSyncService : Service() {

	companion object {
		const val NOTIFICATION_ID = 8742

		fun sync(context: Context, start: Boolean) {
			val intent = Intent(context, EvBmsSyncService::class.java)
			if (start) {
				try {
					ContextCompat.startForegroundService(context, intent)
				} catch (_: Exception) {
				}
			} else {
				context.stopService(intent)
			}
		}
	}

	private val app: OsmandApplication
		get() = application as OsmandApplication

	private val plugin: EvBmsPlugin?
		get() = PluginsHelper.getPlugin(EvBmsPlugin::class.java)

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		app.notificationHelper.createNotificationChannel()
		val ctl = plugin?.sync
		if (ctl == null || !ctl.serving) {
			stopSelf()
			return START_NOT_STICKY
		}
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				startForeground(
					NOTIFICATION_ID,
					buildNotification(ctl),
					ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
				)
			} else {
				startForeground(NOTIFICATION_ID, buildNotification(ctl))
			}
		} catch (_: Exception) {
			stopSelf()
			return START_NOT_STICKY
		}
		return START_STICKY
	}

	private fun buildNotification(ctl: EvBmsSyncController): Notification {
		val ips = ctl.localIpv4Addresses().joinToString(", ").ifBlank { "0.0.0.0" }
		val text = getString(R.string.ev_bms_sync_notification_text, ips, plugin?.syncPort() ?: EvBmsSyncController.DEFAULT_PORT)
		val launch = Intent(this, MapActivity::class.java).apply {
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
			.setContentTitle(getString(R.string.ev_bms_sync_notification))
			.setContentText(text)
			.setStyle(NotificationCompat.BigTextStyle().bigText(text))
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setContentIntent(pending)
			.build()
	}
}
