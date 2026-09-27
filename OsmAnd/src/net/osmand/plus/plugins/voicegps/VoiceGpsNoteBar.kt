package net.osmand.plus.plugins.voicegps

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import net.osmand.plus.R
import net.osmand.plus.utils.AndroidUtils
import net.osmand.plus.utils.ColorUtilities

/** Compact emoji bar, same density as the EV telemetry sheet footer controls. */
internal object VoiceGpsNoteBar {

	const val HEIGHT_DP = 48f

	fun create(context: android.content.Context, nightMode: Boolean, onClick: () -> Unit): View {
		val bar = FrameLayout(context)
		bar.setBackgroundColor(ColorUtilities.getListBgColor(context, nightMode))
		bar.layoutParams = ViewGroup.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			AndroidUtils.dpToPx(context, HEIGHT_DP)
		)
		val divider = View(context).apply {
			setBackgroundColor(ColorUtilities.getDividerColor(context, nightMode))
			layoutParams = FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				AndroidUtils.dpToPx(context, 1f),
				Gravity.TOP
			)
		}
		val hint = context.getString(R.string.voice_gps_create_note)
		val typed = TypedValue()
		context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typed, true)
		val button = TextView(context).apply {
			text = "🎤"
			textSize = 22f
			gravity = Gravity.CENTER
			includeFontPadding = false
			minWidth = AndroidUtils.dpToPx(context, 48f)
			minHeight = AndroidUtils.dpToPx(context, HEIGHT_DP.toInt().toFloat())
			contentDescription = hint
			setBackgroundResource(typed.resourceId)
			setOnClickListener { onClick() }
		}
		TooltipCompat.setTooltipText(button, hint)
		bar.addView(divider)
		bar.addView(
			button,
			FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.MATCH_PARENT,
				Gravity.CENTER
			)
		)
		return bar
	}

	fun setVisible(bar: View, visible: Boolean, animate: Boolean) {
		bar.animate().cancel()
		if (!animate) {
			bar.alpha = if (visible) 1f else 0f
			bar.visibility = if (visible) View.VISIBLE else View.GONE
			return
		}
		if (visible) {
			if (bar.visibility != View.VISIBLE) {
				bar.alpha = 0f
			}
			bar.visibility = View.VISIBLE
			bar.animate().alpha(1f).setDuration(160).start()
		} else {
			bar.animate().alpha(0f).setDuration(160).withEndAction {
				if (bar.alpha == 0f) {
					bar.visibility = View.GONE
				}
			}.start()
		}
	}
}
