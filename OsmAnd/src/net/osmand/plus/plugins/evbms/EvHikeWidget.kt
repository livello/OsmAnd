package net.osmand.plus.plugins.evbms

import android.view.View
import android.view.ViewGroup
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.views.layers.base.OsmandMapLayer.DrawSettings
import net.osmand.plus.views.mapwidgets.WidgetType
import net.osmand.plus.views.mapwidgets.WidgetsPanel
import net.osmand.plus.views.mapwidgets.appearance.ResolvedPanelAppearance
import net.osmand.plus.views.mapwidgets.widgets.MapWidget
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
		EvWidgetChrome.applySideLayout(view, panel)
		EvWidgetChrome.bindPanelTap(this)
	}

	override fun attachView(
		container: ViewGroup,
		panel: WidgetsPanel,
		followingWidgets: MutableList<MapWidget>
	) {
		EvWidgetChrome.attach(this, container, panel)
	}

	override fun recreateViewInternal() {
		super.recreateViewInternal()
		EvWidgetChrome.applySideLayout(view, panel)
		EvWidgetChrome.bindPanelTap(this)
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
		EvWidgetChrome.applySideLayout(view, panel)
		EvWidgetLinkFrame.clearOuterFrame(view)
		EvWidgetChrome.bindPanelTap(this)
	}

	override fun applySimpleWidgetAppearance(appearance: ResolvedPanelAppearance) {
		super.applySimpleWidgetAppearance(appearance)
		EvWidgetLinkFrame.clearOuterFrame(view)
	}

	override fun getOnClickListener(): View.OnClickListener {
		return View.OnClickListener { plugin.askShowSettingsDialog(mapActivity) }
	}
}
