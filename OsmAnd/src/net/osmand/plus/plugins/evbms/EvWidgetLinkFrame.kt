package net.osmand.plus.plugins.evbms

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.utils.AndroidUtils

object EvWidgetLinkFrame {

	private const val GREEN = 0xFF22C55E.toInt()
	private const val RED = 0xFFEF4444.toInt()

	fun apply(root: View?, connected: Boolean, app: OsmandApplication) {
		val target = root?.findViewById<View>(R.id.widget_bg) ?: root ?: return
		val stroke = GradientDrawable()
		stroke.setColor(Color.TRANSPARENT)
		stroke.setStroke(AndroidUtils.dpToPx(app, 2f), if (connected) GREEN else RED)
		stroke.cornerRadius = AndroidUtils.dpToPx(app, 5f).toFloat()
		target.foreground = stroke
	}
}
