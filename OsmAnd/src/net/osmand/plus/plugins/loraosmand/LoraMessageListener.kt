package net.osmand.plus.plugins.loraosmand

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import net.osmand.plus.plugins.PluginsHelper

/**
 * Meshtastic keeps channel text in its own database. The only copy another app
 * can see is the conversation notification: new lines in MessagingStyle, and
 * already-read lines in the historic-message extra. There is no exported API
 * for the older database.
 */
class LoraMessageListener : NotificationListenerService() {
	override fun onListenerConnected() {
		connected = this
		Log.i(TAG, "listener connected")
		try {
			pullFromService(this)
		} catch (error: Throwable) {
			Log.w(TAG, "pull failed", error)
		}
		PluginsHelper.getPlugin(LoraOsmAndPlugin::class.java)?.onListenerReady()
	}

	override fun onListenerDisconnected() {
		if (connected === this) {
			connected = null
		}
		Log.i(TAG, "listener disconnected")
		PluginsHelper.getPlugin(LoraOsmAndPlugin::class.java)?.scheduleRebind()
	}

	override fun onNotificationPosted(sbn: StatusBarNotification) {
		try {
			ingest(sbn)
		} catch (error: Throwable) {
			Log.w(TAG, "ingest failed", error)
		}
	}

	private fun ingest(sbn: StatusBarNotification) {
		if (sbn.packageName != MESH_PACKAGE) {
			return
		}
		val notification = sbn.notification ?: return
		if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
			return
		}
		if (!isChannelText(notification)) {
			return
		}
		val plugin = PluginsHelper.getPlugin(LoraOsmAndPlugin::class.java) ?: return
		plugin.noteMeshNotice()
		val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
		val channel = resolveChannel(sbn, notification, style)
		rememberReply(channel, notification)
		val conversation = style?.conversationTitle?.toString()?.takeIf { it.isNotBlank() }
		plugin.noteChannel(channel, conversation)
		val userName = style?.user?.name?.toString()?.trim().orEmpty()
		val userKey = style?.user?.key?.trim().orEmpty()
		if (userName.isNotEmpty()) {
			plugin.noteSelfName(userName)
		}
		val now = System.currentTimeMillis()
		val live = lines(notification, Notification.EXTRA_MESSAGES, userName, userKey)
		val backlog = if (Build.VERSION.SDK_INT >= 26) {
			lines(notification, Notification.EXTRA_HISTORIC_MESSAGES, userName, userKey)
		} else {
			emptyList()
		}
		if (live.isEmpty() && backlog.isEmpty()) {
			val text = notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
			val sender = notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
			if (text.isNotEmpty()) {
				plugin.ingestChat(
					sender.ifBlank { "mesh" },
					text,
					channel,
					true,
					now,
					fromSelf = localSender(userName, userKey, sender, null),
				)
			}
			return
		}
		for (line in backlog) {
			plugin.ingestChat(line.sender.ifBlank { "mesh" }, line.text, channel, false, line.timeMs, fromSelf = line.fromSelf)
		}
		for (line in live) {
			val fresh = now - line.timeMs < FRESH_MS
			plugin.ingestChat(
				line.sender.ifBlank { "mesh" },
				line.text,
				channel,
				fresh,
				line.timeMs,
				fromSelf = line.fromSelf,
			)
		}
		val newest = (live.lastOrNull() ?: backlog.lastOrNull())?.text?.take(40)
		Log.i(TAG, "mesh ch=$channel live=${live.size} backlog=${backlog.size} newest=$newest")
	}

	private fun isChannelText(notification: Notification): Boolean {
		val extras = notification.extras
		val template = extras?.getString(Notification.EXTRA_TEMPLATE).orEmpty()
		val compat = extras?.getString("androidx.core.app.extra.COMPAT_TEMPLATE").orEmpty()
		if (template.contains("MessagingStyle") || compat.contains("MessagingStyle")) {
			return true
		}
		val channelId = if (Build.VERSION.SDK_INT >= 26) notification.channelId.orEmpty() else ""
		return channelId == "my_broadcasts" || channelId == "my_messages"
	}

	private fun lines(notification: Notification, key: String, userName: String, userKey: String): List<MeshLine> {
		val raw = messageBundles(notification, key) ?: return emptyList()
		if (Build.VERSION.SDK_INT >= 30) {
			try {
				val parsed = Notification.MessagingStyle.Message.getMessagesFromBundleArray(raw)
				val mapped = parsed.mapNotNull { message ->
					val text = message?.text?.toString()?.trim().orEmpty()
					if (text.isEmpty()) {
						return@mapNotNull null
					}
					val person = if (Build.VERSION.SDK_INT >= 28) message.senderPerson else null
					val sender = person?.name?.toString()?.trim().orEmpty().ifBlank {
						@Suppress("DEPRECATION")
						message.sender?.toString()?.trim().orEmpty()
					}
					MeshLine(sender, text, message.timestamp, localSender(userName, userKey, sender, person?.key))
				}
				if (mapped.isNotEmpty()) {
					return mapped
				}
			} catch (error: Throwable) {
				Log.w(TAG, "message parse failed", error)
			}
		}
		val lines = ArrayList<MeshLine>()
		for (item in raw) {
			val bundle = item as? Bundle ?: continue
			val text = bundle.getCharSequence("text")?.toString()?.trim().orEmpty()
			if (text.isEmpty()) {
				continue
			}
			val person = messagePerson(bundle)
			val sender = bundle.getCharSequence("sender")?.toString()?.trim().orEmpty().ifBlank {
				person?.name?.toString()?.trim().orEmpty()
			}
			lines.add(MeshLine(sender, text, bundle.getLong("time"), localSender(userName, userKey, sender, person?.key)))
		}
		return lines
	}

	private fun localSender(userName: String, userKey: String, sender: String, senderKey: String?): Boolean {
		if (userKey.isNotEmpty() && userKey == senderKey) {
			return true
		}
		if (userName.isEmpty()) {
			return false
		}
		if (sender.isBlank()) {
			return true
		}
		return sender.equals(userName, ignoreCase = true)
	}

	private fun messagePerson(bundle: Bundle): android.app.Person? {
		if (Build.VERSION.SDK_INT < 28) {
			return null
		}
		return if (Build.VERSION.SDK_INT >= 33) {
			bundle.getParcelable("sender_person", android.app.Person::class.java)
		} else {
			@Suppress("DEPRECATION")
			bundle.getParcelable("sender_person")
		}
	}

	private fun messageBundles(notification: Notification, key: String): Array<out android.os.Parcelable>? {
		val extras = notification.extras ?: return null
		if (Build.VERSION.SDK_INT >= 33) {
			try {
				val typed = extras.getParcelableArray(key, Bundle::class.java)
				if (typed != null && typed.isNotEmpty()) {
					return typed
				}
			} catch (_: Throwable) {
			}
		}
		@Suppress("DEPRECATION")
		return extras.getParcelableArray(key)
	}

	private fun pullHiddenHistory() {
		try {
			val manager = getSystemService(NotificationManager::class.java) ?: return
			val method = manager.javaClass.methods.firstOrNull { candidate ->
				candidate.name == "getHistoricalNotifications" && candidate.parameterTypes.isNotEmpty()
			} ?: return
			val result = when (method.parameterTypes.size) {
				1 -> method.invoke(manager, 40)
				2 -> method.invoke(manager, packageName, 40)
				else -> method.invoke(manager, packageName, 40, false)
			}
			val records = result as? Array<*> ?: return
			for (record in records) {
				ingest(record as? StatusBarNotification ?: continue)
			}
		} catch (_: Throwable) {
		}
	}

	private fun resolveChannel(
		sbn: StatusBarNotification,
		notification: Notification,
		style: NotificationCompat.MessagingStyle?,
	): Int {
		val candidates = ArrayList<String?>()
		if (Build.VERSION.SDK_INT >= 29) {
			candidates.add(notification.shortcutId)
		}
		candidates.add(notification.extras?.getString("android.shortcutId"))
		candidates.add(sbn.tag)
		candidates.add(style?.conversationTitle?.toString())
		candidates.add(notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString())
		for (raw in candidates) {
			parseChannel(raw)?.let { return it }
		}
		val blob = candidates.filterNotNull().joinToString(" ")
		val channelId = if (Build.VERSION.SDK_INT >= 26) notification.channelId.orEmpty() else ""
		if (blob.contains("Long Fast", true) || blob.contains("^all") || channelId.contains("broadcast")) {
			return 0
		}
		return 0
	}

	private fun parseChannel(raw: String?): Int? {
		val key = raw?.trim().orEmpty()
		if (key.isEmpty()) {
			return null
		}
		if (key.contains("^all")) {
			return key.substringBefore("^all").toIntOrNull() ?: 0
		}
		if (key.contains("!")) {
			return key.substringBefore("!").toIntOrNull() ?: 0
		}
		if (key.equals("Long Fast", true)) {
			return 0
		}
		return key.toIntOrNull()
	}

	private fun rememberReply(channel: Int, notification: Notification) {
		val action = notification.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true } ?: return
		val remote = action.remoteInputs?.firstOrNull() ?: return
		val pending = action.actionIntent ?: return
		LoraMeshInbox.remember(channel, pending, remote.resultKey)
		Log.i(TAG, "reply cached ch=$channel")
	}

	companion object {
		const val MESH_PACKAGE = "com.geeksville.mesh"
		private const val TAG = "LoraOsmAnd"
		private const val FRESH_MS = 20_000L

		fun isConnected(): Boolean = connected != null

		@Volatile
		private var connected: LoraMessageListener? = null

		fun pullActive() {
			val service = connected ?: return
			pullFromService(service)
		}

		private fun pullFromService(service: LoraMessageListener) {
			val active = service.activeNotifications?.size ?: 0
			val snoozed = if (Build.VERSION.SDK_INT >= 26) service.snoozedNotifications?.size ?: 0 else 0
			Log.i(TAG, "pull active=$active snoozed=$snoozed")
			service.activeNotifications?.forEach { service.ingest(it) }
			if (Build.VERSION.SDK_INT >= 26) {
				service.snoozedNotifications?.forEach { service.ingest(it) }
			}
			service.pullHiddenHistory()
		}
	}
}

private data class MeshLine(val sender: String, val text: String, val timeMs: Long, val fromSelf: Boolean)

internal object LoraMeshInbox {
	private val replies = HashMap<Int, Reply>()

	fun remember(channel: Int, pending: PendingIntent, resultKey: String) {
		synchronized(replies) {
			replies[channel] = Reply(pending, resultKey)
		}
	}

	fun reply(context: android.content.Context, channel: Int, text: String): Boolean {
		val reply = synchronized(replies) { replies[channel] } ?: return false
		return try {
			val fillIn = Intent()
			val results = Bundle()
			results.putCharSequence(reply.resultKey, text)
			RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(reply.resultKey).build()), fillIn, results)
			reply.pending.send(context, 0, fillIn)
			true
		} catch (error: Exception) {
			Log.w("LoraOsmAnd", "reply failed", error)
			synchronized(replies) { replies.remove(channel) }
			false
		}
	}

	private data class Reply(val pending: PendingIntent, val resultKey: String)
}
