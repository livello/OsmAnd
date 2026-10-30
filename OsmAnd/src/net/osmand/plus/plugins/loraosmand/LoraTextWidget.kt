package net.osmand.plus.plugins.loraosmand

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.helpers.AndroidUiHelper
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.views.layers.base.OsmandMapLayer.DrawSettings
import net.osmand.plus.views.mapwidgets.OutlinedTextContainer
import net.osmand.plus.views.mapwidgets.WidgetType
import net.osmand.plus.views.mapwidgets.WidgetsPanel
import net.osmand.plus.views.mapwidgets.widgets.MapWidget
import net.osmand.plus.views.mapwidgets.widgets.SimpleWidget

class LoraTextWidget(
	mapActivity: MapActivity,
	widgetType: WidgetType,
	private val field: Field,
	customId: String?,
	widgetsPanel: WidgetsPanel?,
) : SimpleWidget(mapActivity, widgetType, customId, widgetsPanel) {

	enum class Field { BATTERY, NODES, MESSAGE }

	private val plugin = PluginsHelper.requirePlugin(LoraOsmAndPlugin::class.java)
	private var cache: String? = null

	init {
		setText(NO_VALUE, null)
		setIcons(widgetType)
		updateWidgetView()
		LoraWidgetChrome.applySideLayout(view, panel)
		LoraWidgetChrome.bindTap(this)
	}

	fun widgetsPanel(): WidgetsPanel = panel

	override fun attachView(container: ViewGroup, panel: WidgetsPanel, followingWidgets: MutableList<MapWidget>) {
		LoraWidgetChrome.attach(this, container, panel)
	}

	override fun recreateViewInternal() {
		super.recreateViewInternal()
		LoraWidgetChrome.applySideLayout(view, panel)
		LoraWidgetChrome.bindTap(this)
	}

	override fun updateValueAlign(fullRow: Boolean) {
		if (isVerticalWidget()) {
			super.updateValueAlign(fullRow)
			return
		}
		val gravity = LoraWidgetChrome.sideGravity(panel) or Gravity.CENTER_VERTICAL
		textView.setGravity(gravity)
		smallTextView?.setGravity(gravity)
		LoraWidgetChrome.applySideLayout(view, panel)
	}

	override fun updateSimpleWidgetInfo(drawSettings: DrawSettings?) {
		val text = when (field) {
			Field.BATTERY -> plugin.batteryPercent()?.toString() ?: NO_VALUE
			Field.NODES -> if (plugin.connected) plugin.onlineCount().toString() else NO_VALUE
			Field.MESSAGE -> "✉"
		}
		if (text != cache) {
			setText(text, null)
			AndroidUiHelper.updateVisibility(smallTextView, false)
			AndroidUiHelper.updateVisibility(smallTextViewShadow, false)
			cache = text
			updateWidgetView()
		}
		val color = if (plugin.connected) Color.BLACK else 0xFFC62828.toInt()
		textView?.setTextColor(color)
		(widgetName as? OutlinedTextContainer)?.setTextColor(color)
		LoraWidgetChrome.applySideLayout(view, panel)
		LoraWidgetChrome.bindTap(this)
	}

	override fun getOnClickListener(): View.OnClickListener {
		return View.OnClickListener {
			val tab = if (field == Field.MESSAGE) LoraSheetTab.MESSAGES else LoraSheetTab.SETTINGS
			if (field == Field.MESSAGE || LoraWidgetChrome.isTopLoraWidget(this)) {
				plugin.showSettings(mapActivity, tab)
			}
		}
	}
}
