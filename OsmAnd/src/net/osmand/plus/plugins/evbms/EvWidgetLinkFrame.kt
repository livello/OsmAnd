package net.osmand.plus.plugins.evbms

import android.graphics.Color
import android.view.View
import net.osmand.plus.R
import net.osmand.plus.views.mapwidgets.OutlinedTextContainer

object EvWidgetLinkFrame {

	private const val CONNECTED = Color.BLACK
	private const val DISCONNECTED = 0xFFC62828.toInt()

	fun apply(
		root: View?,
		connected: Boolean,
		value: OutlinedTextContainer?,
		small: OutlinedTextContainer?,
		name: OutlinedTextContainer?
	) {
		clearOuterFrame(root)
		val color = if (connected) CONNECTED else DISCONNECTED
		value?.setTextColor(color)
		small?.setTextColor(color)
		name?.setTextColor(color)
	}

	fun clearOuterFrame(root: View?) {
		if (root == null) {
			return
		}
		val widgetBg = root.findViewById<View>(R.id.widget_bg)
		widgetBg?.foreground = null
		widgetBg?.background = null
		if (widgetBg != null && widgetBg !== root) {
			root.background = null
		} else if (widgetBg == null) {
			root.foreground = null
			root.background = null
		}
	}
}
