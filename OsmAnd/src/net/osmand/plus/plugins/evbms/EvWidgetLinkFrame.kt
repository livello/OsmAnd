package net.osmand.plus.plugins.evbms

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.utils.AndroidUtils

object EvWidgetLinkFrame {

	private const val STROKE_COLOR = 0xFF22C55E.toInt()

	fun apply(root: View?, linked: Boolean, app: OsmandApplication) {
		val target = root?.findViewById<View>(R.id.widget_bg) ?: root ?: return
		if (linked) {
			val stroke = GradientDrawable()
			stroke.setColor(Color.TRANSPARENT)
			stroke.setStroke(AndroidUtils.dpToPx(app, 2f), STROKE_COLOR)
			stroke.cornerRadius = AndroidUtils.dpToPx(app, 5f).toFloat()
			target.foreground = stroke
		} else {
			target.foreground = null
		}
	}
}
