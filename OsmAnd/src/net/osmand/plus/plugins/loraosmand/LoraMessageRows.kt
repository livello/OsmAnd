package net.osmand.plus.plugins.loraosmand

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import net.osmand.plus.R
import net.osmand.plus.utils.AndroidUtils
import net.osmand.plus.utils.ColorUtilities
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Draws one chat line. The messages tab and the style picker share this. */
internal object LoraMessageRows {

	fun build(
		context: Context,
		night: Boolean,
		style: LoraMessageStyle,
		entry: LoraChatLine,
		shortNameOf: (String) -> String?,
	): View {
		return when (style) {
			LoraMessageStyle.TELEGRAM -> telegramRow(context, night, entry)
			LoraMessageStyle.FACEBOOK -> facebookRow(context, night, entry)
			LoraMessageStyle.ICQ -> icqRow(context, night, entry)
			LoraMessageStyle.TWITTER -> twitterRow(context, night, entry, shortNameOf)
			LoraMessageStyle.ZSH -> zshRow(context, night, entry)
			LoraMessageStyle.LORA -> loraRow(context, night, entry)
		}
	}

	private fun loraRow(context: Context, night: Boolean, entry: LoraChatLine): View {
		val body = framedColumn(context, night)
		val head = LinearLayout(context).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
		}
		head.addView(TextView(context).apply {
			text = "${clock(entry)}  ${messageAge(context, entry.timeMs)}"
			textSize = 12f
			setTextColor(ColorUtilities.getSecondaryTextColor(context, night))
			layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
		})
		head.addView(TextView(context).apply {
			text = listOf(entry.fromName, deliveryMark(entry)).filter { it.isNotEmpty() }.joinToString("  ")
			textSize = 13f
			setTypeface(typeface, Typeface.BOLD)
			gravity = Gravity.END
			setTextColor(ColorUtilities.getPrimaryTextColor(context, night))
		})
		body.addView(head)
		body.addView(messageText(context, night, entry.text, 15f))
		body.layoutParams = LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.WRAP_CONTENT,
		).apply {
			topMargin = dp(context, 4)
			bottomMargin = dp(context, 4)
		}
		return body
	}

	private fun telegramRow(context: Context, night: Boolean, entry: LoraChatLine): View {
		val column = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			layoutParams = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
			)
			gravity = if (entry.outgoing) Gravity.END else Gravity.START
			setPadding(0, dp(context, 4), 0, dp(context, 4))
		}
		if (!entry.outgoing) {
			column.addView(TextView(context).apply {
				text = entry.fromName
				textSize = 12f
				setTypeface(typeface, Typeface.BOLD)
				setTextColor(ColorUtilities.getSecondaryTextColor(context, night))
				setPadding(dp(context, 6), 0, dp(context, 6), dp(context, 2))
			})
		}
		val bubble = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
			background = GradientDrawable().apply {
				cornerRadius = dp(context, 16).toFloat()
				setColor(if (entry.outgoing) 0x3327AE60 else if (night) 0xFF2C2C2C.toInt() else 0xFFE7F3FF.toInt())
			}
		}
		val body = messageText(context, night, entry.text, 15f)
		body.maxWidth = (context.resources.displayMetrics.widthPixels * 0.72f).toInt()
		bubble.addView(body)
		column.addView(bubble)
		column.addView(TextView(context).apply {
			val mark = deliveryMark(entry)
			text = "${clock(entry)}  ${messageAge(context, entry.timeMs)}" + if (mark.isEmpty()) "" else "  $mark"
			textSize = 11f
			setTextColor(ColorUtilities.getSecondaryTextColor(context, night))
			setPadding(dp(context, 6), dp(context, 2), dp(context, 6), 0)
		})
		return column
	}

	private fun facebookRow(context: Context, night: Boolean, entry: LoraChatLine): View {
		val row = LinearLayout(context).apply {
			orientation = LinearLayout.HORIZONTAL
			setPadding(0, dp(context, 6), 0, dp(context, 6))
		}
		val letter = entry.fromName.firstOrNull()?.uppercaseChar()?.toString() ?: "•"
		row.addView(TextView(context).apply {
			text = letter
			gravity = Gravity.CENTER
			textSize = 16f
			setTextColor(Color.WHITE)
			val size = dp(context, 36)
			layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(context, 8) }
			background = GradientDrawable().apply {
				shape = GradientDrawable.OVAL
				setColor(0xFF5C6BC0.toInt())
			}
		})
		val column = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
		}
		column.addView(TextView(context).apply {
			text = entry.fromName
			textSize = 14f
			setTypeface(typeface, Typeface.BOLD)
			setTextColor(ColorUtilities.getPrimaryTextColor(context, night))
		})
		column.addView(messageText(context, night, entry.text, 15f))
		column.addView(TextView(context).apply {
			val mark = deliveryMark(entry)
			text = "${clock(entry)} · ${messageAge(context, entry.timeMs)}" + if (mark.isEmpty()) "" else "  $mark"
			textSize = 12f
			setTextColor(ColorUtilities.getSecondaryTextColor(context, night))
		})
		row.addView(column)
		return row
	}

	private fun icqRow(context: Context, night: Boolean, entry: LoraChatLine): View {
		return TextView(context).apply {
			typeface = Typeface.MONOSPACE
			textSize = 14f
			setTextColor(0xFF39FF14.toInt())
			val mark = deliveryMark(entry)
			text = "[${clock(entry)}] ${entry.fromName}> ${entry.text}" + if (mark.isEmpty()) "" else " $mark"
			setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6))
			background = GradientDrawable().apply {
				cornerRadius = dp(context, 4).toFloat()
				setColor(if (night) 0xFF031006.toInt() else 0xFF0B0B0B.toInt())
			}
		}
	}

	private fun twitterRow(
		context: Context,
		night: Boolean,
		entry: LoraChatLine,
		shortNameOf: (String) -> String?,
	): View {
		val column = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(0, dp(context, 6), 0, dp(context, 8))
		}
		val short = shortNameOf(entry.fromName) ?: entry.fromName
		column.addView(TextView(context).apply {
			text = "${entry.fromName}  @$short"
			textSize = 14f
			setTypeface(typeface, Typeface.BOLD)
			setTextColor(ColorUtilities.getPrimaryTextColor(context, night))
		})
		column.addView(messageText(context, night, entry.text, 15f))
		column.addView(TextView(context).apply {
			val mark = deliveryMark(entry)
			text = "${clock(entry)} · ${messageAge(context, entry.timeMs)}" + if (mark.isEmpty()) "" else "  $mark"
			textSize = 12f
			setTextColor(ColorUtilities.getSecondaryTextColor(context, night))
		})
		return column
	}

	private fun zshRow(context: Context, night: Boolean, entry: LoraChatLine): View {
		return TextView(context).apply {
			typeface = Typeface.MONOSPACE
			textSize = 14f
			setTextColor(ColorUtilities.getPrimaryTextColor(context, night))
			val mark = deliveryMark(entry)
			text = "${clock(entry)} ${entry.fromName}% ${entry.text}" + if (mark.isEmpty()) "" else " $mark"
			setPadding(0, dp(context, 3), 0, dp(context, 3))
		}
	}

	private fun framedColumn(context: Context, night: Boolean): LinearLayout {
		return LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6))
			background = GradientDrawable().apply {
				setColor(ColorUtilities.getActivityBgColor(context, night))
				setStroke(dp(context, 1).coerceAtLeast(1), ColorUtilities.getSecondaryTextColor(context, night))
				cornerRadius = dp(context, 8).toFloat()
			}
		}
	}

	private fun messageText(context: Context, night: Boolean, text: String, size: Float): TextView {
		return TextView(context).apply {
			this.text = text
			textSize = size
			setTextColor(ColorUtilities.getPrimaryTextColor(context, night))
		}
	}

	private fun clock(entry: LoraChatLine): String {
		return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(entry.timeMs))
	}

	private fun deliveryMark(entry: LoraChatLine): String {
		return when (entry.delivery) {
			LoraDelivery.SENDING -> "⏳"
			LoraDelivery.SENT -> "✅"
			LoraDelivery.FAILED -> "⚠️"
			LoraDelivery.INCOMING -> ""
		}
	}

	private fun messageAge(context: Context, timeMs: Long): String {
		val minutes = ((System.currentTimeMillis() - timeMs).coerceAtLeast(0L) / 60_000L).toInt()
		return when {
			minutes < 60 -> context.getString(R.string.lora_osmand_age_min, minutes)
			minutes < 60 * 24 -> context.getString(R.string.lora_osmand_age_hour, minutes / 60)
			else -> context.getString(R.string.lora_osmand_age_day, minutes / (60 * 24))
		}
	}

	private fun dp(context: Context, value: Int): Int = AndroidUtils.dpToPx(context, value.toFloat())
}
