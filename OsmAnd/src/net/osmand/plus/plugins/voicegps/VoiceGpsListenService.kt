package net.osmand.plus.plugins.voicegps

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

class VoiceGpsListenService : Service() {

	companion object {
		const val NOTIFICATION_ID = 8755

		fun sync(context: Context, start: Boolean) {
			val intent = Intent(context, VoiceGpsListenService::class.java)
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

	private val plugin: VoiceGpsPlugin?
		get() = PluginsHelper.getPlugin(VoiceGpsPlugin::class.java)

	private var controller: VoiceGpsSpeechController? = null

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onCreate() {
		super.onCreate()
		val p = plugin ?: return
		controller = VoiceGpsSpeechController(app, p)
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		app.notificationHelper.createNotificationChannel()
		val p = plugin
		if (p == null || !p.isActive || !p.shouldListenNow()) {
			controller?.stop()
			stopSelf()
			return START_NOT_STICKY
		}
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
				startForeground(
					NOTIFICATION_ID,
					buildNotification(),
					ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
				)
			} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				startForeground(NOTIFICATION_ID, buildNotification())
			} else {
				startForeground(NOTIFICATION_ID, buildNotification())
			}
		} catch (_: Exception) {
			stopSelf()
			return START_NOT_STICKY
		}
		controller?.start()
		return START_STICKY
	}

	override fun onDestroy() {
		controller?.stop()
		controller = null
		super.onDestroy()
	}

	private fun buildNotification(): Notification {
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
			.setSmallIcon(R.drawable.ic_action_micro_dark)
			.setContentTitle(getString(R.string.voice_gps_notification_title))
			.setContentText(getString(R.string.voice_gps_notification_text))
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setContentIntent(pending)
			.build()
	}
}
