package net.osmand.plus.plugins.loraosmand

import android.app.Notification
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
 * Meshtastic keeps plain channel text (Long Fast, weather) in its own database
 * and posts it as a conversation notification. The reply action on that
 * notification is the supported way for another app to send into the same channel.
 */
class LoraMessageListener : NotificationListenerService() {
	override fun onListenerConnected() {
		activeNotifications?.forEach { ingest(it, speakFresh = false) }
	}

	override fun onNotificationPosted(sbn: StatusBarNotification) {
		ingest(sbn, speakFresh = true)
	}

	private fun ingest(sbn: StatusBarNotification, speakFresh: Boolean) {
		if (Build.VERSION.SDK_INT < 29 || sbn.packageName != MESH_PACKAGE) {
			return
		}
		val notification = sbn.notification ?: return
		if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
			return
		}
		val contactKey = notification.shortcutId ?: return
		val channel = channelIndex(contactKey) ?: return
		rememberReply(channel, notification)
		val plugin = PluginsHelper.getPlugin(LoraOsmAndPlugin::class.java) ?: return
		val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
		val channelName = style?.conversationTitle?.toString()?.takeIf { it.isNotBlank() }
		if (contactKey.contains(BROADCAST)) {
			plugin.noteChannel(channel, channelName)
		}
		val now = System.currentTimeMillis()
		val messages = style?.messages
		if (messages.isNullOrEmpty()) {
			val text = notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
			val sender = notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
			if (text.isNotEmpty()) {
				plugin.ingestChat(sender.ifBlank { channelName ?: "mesh" }, text, channel, speakFresh)
			}
			return
		}
		for (message in messages) {
			val text = message.text?.toString()?.trim().orEmpty()
			if (text.isEmpty()) {
				continue
			}
			val sender = message.person?.name?.toString()?.trim().orEmpty().ifBlank { channelName ?: "mesh" }
			val fresh = speakFresh && now - message.timestamp < FRESH_MS
			plugin.ingestChat(sender, text, channel, fresh)
		}
	}

	private fun rememberReply(channel: Int, notification: Notification) {
		val action = notification.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true } ?: return
		val remote = action.remoteInputs?.firstOrNull() ?: return
		val pending = action.actionIntent ?: return
		LoraMeshInbox.remember(channel, pending, remote.resultKey)
	}

	private fun channelIndex(contactKey: String): Int? {
		if (contactKey.contains(BROADCAST)) {
			return contactKey.substringBefore(BROADCAST).toIntOrNull()
		}
		if (contactKey.contains("!")) {
			return contactKey.substringBefore("!").toIntOrNull()
		}
		return contactKey.toIntOrNull()
	}

	companion object {
		const val MESH_PACKAGE = "com.geeksville.mesh"
		private const val BROADCAST = "^all"
		private const val FRESH_MS = 20_000L
	}
}

internal object LoraMeshInbox {
	private val replies = HashMap<Int, Reply>()

	fun remember(channel: Int, pending: PendingIntent, resultKey: String) {
		replies[channel] = Reply(pending, resultKey)
	}

	fun reply(channel: Int, text: String): Boolean {
		val reply = replies[channel] ?: return false
		return try {
			val fillIn = Intent()
			val results = Bundle()
			results.putCharSequence(reply.resultKey, text)
			RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(reply.resultKey).build()), fillIn, results)
			reply.pending.send(null, 0, fillIn)
			true
		} catch (_: PendingIntent.CanceledException) {
			replies.remove(channel)
			false
		}
	}

	private data class Reply(val pending: PendingIntent, val resultKey: String)
}
