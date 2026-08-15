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
		root?.findViewById<View>(R.id.widget_bg)?.foreground = null
		val color = if (connected) CONNECTED else DISCONNECTED
		value?.setTextColor(color)
		small?.setTextColor(color)
		name?.setTextColor(color)
	}
}
