package net.osmand.plus.plugins.evbms

import android.view.View
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.views.layers.base.OsmandMapLayer.DrawSettings
import net.osmand.plus.views.mapwidgets.WidgetType
import net.osmand.plus.views.mapwidgets.WidgetsPanel
import net.osmand.plus.views.mapwidgets.widgets.SimpleWidget

class EvHikeWidget(
	mapActivity: MapActivity,
	customId: String?,
	widgetsPanel: WidgetsPanel?
) : SimpleWidget(mapActivity, WidgetType.EV_HIKE, customId, widgetsPanel) {

	private val plugin = PluginsHelper.requirePlugin(EvBmsPlugin::class.java)
	private var cacheOn: Boolean? = null

	init {
		updateWidgetView()
		setIcons(widgetType)
	}

	override fun updateSimpleWidgetInfo(drawSettings: DrawSettings?) {
		val on = plugin.isHikeMode()
		if (on != cacheOn) {
			cacheOn = on
			setText(
				app.getString(if (on) R.string.shared_string_on else R.string.shared_string_off),
				null
			)
			setIcons(widgetType)
			updateWidgetView()
		}
		EvWidgetLinkFrame.apply(view, plugin.isLinkHealthy(), app)
	}

	override fun getOnClickListener(): View.OnClickListener {
		return View.OnClickListener {
			plugin.toggleHikeMode(mapActivity)
			cacheOn = null
			updateSimpleWidgetInfo(null)
		}
	}
}
