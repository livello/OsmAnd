package net.osmand.plus.plugins.loraosmand

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
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
		pullFromService(this)
		PluginsHelper.getPlugin(LoraOsmAndPlugin::class.java)?.ensureBackground()
	}

	override fun onListenerDisconnected() {
		if (connected === this) {
			connected = null
		}
	}

	override fun onNotificationPosted(sbn: StatusBarNotification) {
		ingest(sbn)
		PluginsHelper.getPlugin(LoraOsmAndPlugin::class.java)?.ensureBackground()
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
		val now = System.currentTimeMillis()
		val live = lines(notification, Notification.EXTRA_MESSAGES)
		val backlog = if (Build.VERSION.SDK_INT >= 26) {
			lines(notification, Notification.EXTRA_HISTORIC_MESSAGES)
		} else {
			emptyList()
		}
		if (live.isEmpty() && backlog.isEmpty()) {
			val text = notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
			val sender = notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
			if (text.isNotEmpty()) {
				plugin.ingestChat(sender.ifBlank { "mesh" }, text, channel, true, now)
			}
			return
		}
		for (line in backlog) {
			plugin.ingestChat(line.sender.ifBlank { "mesh" }, line.text, channel, false, line.timeMs)
		}
		for (line in live) {
			val fresh = now - line.timeMs < FRESH_MS
			plugin.ingestChat(line.sender.ifBlank { "mesh" }, line.text, channel, fresh, line.timeMs)
		}
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

	private fun lines(notification: Notification, key: String): List<MeshLine> {
		val raw = notification.extras?.getParcelableArray(key) ?: return emptyList()
		if (Build.VERSION.SDK_INT >= 30) {
			val parsed = Notification.MessagingStyle.Message.getMessagesFromBundleArray(raw)
			if (parsed.isNotEmpty()) {
				return parsed.mapNotNull { message ->
					val text = message.text?.toString()?.trim().orEmpty()
					if (text.isEmpty()) {
						return@mapNotNull null
					}
					val sender = if (Build.VERSION.SDK_INT >= 28) {
						message.senderPerson?.name?.toString()?.trim().orEmpty()
					} else {
						""
					}.ifBlank {
						@Suppress("DEPRECATION")
						message.sender?.toString()?.trim().orEmpty()
					}
					MeshLine(sender, text, message.timestamp)
				}
			}
		}
		val lines = ArrayList<MeshLine>()
		for (item in raw) {
			val bundle = item as? Bundle ?: continue
			val text = bundle.getCharSequence("text")?.toString()?.trim().orEmpty()
			if (text.isEmpty()) {
				continue
			}
			val sender = bundle.getCharSequence("sender")?.toString()?.trim().orEmpty().ifBlank {
				personName(bundle)
			}
			lines.add(MeshLine(sender, text, bundle.getLong("time")))
		}
		return lines
	}

	private fun personName(bundle: Bundle): String {
		if (Build.VERSION.SDK_INT < 28) {
			return ""
		}
		val person = if (Build.VERSION.SDK_INT >= 33) {
			bundle.getParcelable("sender_person", android.app.Person::class.java)
		} else {
			@Suppress("DEPRECATION")
			bundle.getParcelable<android.app.Person>("sender_person")
		}
		return person?.name?.toString()?.trim().orEmpty()
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
	}

	companion object {
		const val MESH_PACKAGE = "com.geeksville.mesh"
		private const val FRESH_MS = 20_000L

		@Volatile
		private var connected: LoraMessageListener? = null

		fun pullActive() {
			val service = connected ?: return
			pullFromService(service)
		}

		private fun pullFromService(service: LoraMessageListener) {
			service.activeNotifications?.forEach { service.ingest(it) }
			if (Build.VERSION.SDK_INT >= 26) {
				service.snoozedNotifications?.forEach { service.ingest(it) }
			}
			service.pullHiddenHistory()
		}
	}
}

private data class MeshLine(val sender: String, val text: String, val timeMs: Long)

internal object LoraMeshInbox {
	private val replies = HashMap<Int, Reply>()

	fun remember(channel: Int, pending: PendingIntent, resultKey: String) {
		synchronized(replies) {
			replies[channel] = Reply(pending, resultKey)
		}
	}

	fun reply(channel: Int, text: String): Boolean {
		val reply = synchronized(replies) { replies[channel] } ?: return false
		return try {
			val fillIn = Intent()
			val results = Bundle()
			results.putCharSequence(reply.resultKey, text)
			RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(reply.resultKey).build()), fillIn, results)
			reply.pending.send(null, 0, fillIn)
			true
		} catch (_: PendingIntent.CanceledException) {
			synchronized(replies) { replies.remove(channel) }
			false
		}
	}

	private data class Reply(val pending: PendingIntent, val resultKey: String)
}
