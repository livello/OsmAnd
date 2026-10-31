package net.osmand.plus.plugins.loraosmand

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import net.osmand.plus.R
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.settings.enums.ScreenLayoutMode
import net.osmand.plus.views.mapwidgets.OutlinedTextContainer
import net.osmand.plus.views.mapwidgets.WidgetType
import net.osmand.plus.views.mapwidgets.WidgetsPanel
import net.osmand.plus.views.mapwidgets.widgets.MapWidget

internal object LoraWidgetChrome {

	fun attach(widget: LoraTextWidget, container: ViewGroup, panel: WidgetsPanel) {
		val view = widget.view
		applySideLayout(view, panel)
		if (!panel.isPanelVertical && container is LinearLayout) {
			val params = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
			)
			params.gravity = sideGravity(panel)
			container.addView(view, params)
		} else {
			container.addView(view)
		}
		bindTap(widget)
	}

	fun bindTap(widget: LoraTextWidget) {
		val root = widget.view
		val plugin = PluginsHelper.getPlugin(LoraOsmAndPlugin::class.java)
		val message = widget.widgetType == WidgetType.LORA_OSMAND_MESSAGE
		val speak = widget.widgetType == WidgetType.LORA_OSMAND_SPEAK
		if (speak && plugin != null) {
			root.setTag(R.id.ev_widget_map_passthrough, null)
			root.setOnClickListener { plugin.speakRecent() }
			root.isClickable = true
			root.isLongClickable = false
			return
		}
		val opensSheet = message || isTopLoraWidget(widget)
		if (opensSheet && plugin != null) {
			root.setTag(R.id.ev_widget_map_passthrough, null)
			root.setOnClickListener {
				val tab = if (message) LoraSheetTab.MESSAGES else LoraSheetTab.SETTINGS
				plugin.showSettings(widget.mapActivity, tab)
			}
			root.isClickable = true
			root.isLongClickable = false
		} else {
			root.setOnClickListener(null)
			root.isClickable = false
			root.isLongClickable = false
			root.setTag(R.id.ev_widget_map_passthrough, true)
		}
	}

	fun isTopLoraWidget(widget: MapWidget): Boolean {
		if (!isLoraWidget(widget.widgetType)) {
			return false
		}
		val mapActivity = widget.mapActivity
		val app = mapActivity.app
		val registry = app.osmandMap.mapLayers.mapWidgetRegistry
		val appMode = app.settings.applicationMode
		val layoutMode = ScreenLayoutMode.getDefault(mapActivity)
		var panel: WidgetsPanel? = null
		var myPage = 0
		for (candidate in WidgetsPanel.entries) {
			for (info in registry.getWidgetsForPanel(candidate)) {
				if (info.widget === widget) {
					panel = candidate
					myPage = info.pageIndex
					break
				}
			}
			if (panel != null) {
				break
			}
		}
		if (panel == null) {
			return opensSettings(widget.widgetType)
		}
		for (info in registry.getWidgetsForPanel(panel)) {
			if (info.pageIndex != myPage) {
				continue
			}
			if (!info.isEnabledForAppMode(appMode, layoutMode) || !info.widget.isViewVisible) {
				continue
			}
			if (!opensSettings(info.widget.widgetType)) {
				continue
			}
			return info.widget === widget
		}
		return opensSettings(widget.widgetType)
	}

	private fun opensSettings(type: WidgetType?): Boolean {
		return isLoraWidget(type) &&
			type != WidgetType.LORA_OSMAND_MESSAGE &&
			type != WidgetType.LORA_OSMAND_SPEAK
	}

	fun applySideLayout(root: View, panel: WidgetsPanel) {
		if (panel.isPanelVertical) {
			return
		}
		val gravity = sideGravity(panel)
		root.minimumWidth = 0
		val rootParams = LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.WRAP_CONTENT,
			ViewGroup.LayoutParams.WRAP_CONTENT,
		)
		rootParams.gravity = gravity
		root.layoutParams = rootParams
		wrapToContent(root.findViewById(R.id.widget_bg), gravity)
		wrapToContent(root.findViewById(R.id.container), gravity)
		val textGravity = gravity or Gravity.CENTER_VERTICAL
		root.findViewById<OutlinedTextContainer>(R.id.widget_text)?.setGravity(textGravity)
		root.findViewById<OutlinedTextContainer>(R.id.widget_text_small)?.setGravity(textGravity)
	}

	fun sideGravity(panel: WidgetsPanel): Int {
		return if (panel == WidgetsPanel.RIGHT) Gravity.END else Gravity.START
	}

	private fun isLoraWidget(type: WidgetType?): Boolean {
		return type != null && type.group == net.osmand.plus.views.mapwidgets.WidgetGroup.LORA_OSMAND
	}

	private fun wrapToContent(view: View?, gravity: Int) {
		if (view == null) {
			return
		}
		view.minimumWidth = 0
		when (val params = view.layoutParams) {
			is LinearLayout.LayoutParams -> {
				params.width = ViewGroup.LayoutParams.WRAP_CONTENT
				params.gravity = gravity
				view.layoutParams = params
			}
			is FrameLayout.LayoutParams -> {
				params.width = ViewGroup.LayoutParams.WRAP_CONTENT
				params.gravity = gravity
				view.layoutParams = params
			}
			else -> Unit
		}
	}
}
