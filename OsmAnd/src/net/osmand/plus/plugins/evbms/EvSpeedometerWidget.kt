package net.osmand.plus.plugins.evbms

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.views.layers.base.OsmandMapLayer.DrawSettings
import net.osmand.plus.views.mapwidgets.WidgetType
import net.osmand.plus.views.mapwidgets.WidgetsPanel
import net.osmand.plus.views.mapwidgets.widgets.MapWidget

class EvSpeedometerWidget(
	mapActivity: MapActivity,
	customId: String?,
	widgetsPanel: WidgetsPanel?
) : MapWidget(mapActivity, WidgetType.EV_SPEEDOMETER, customId, widgetsPanel) {

	private val plugin = PluginsHelper.requirePlugin(EvBmsPlugin::class.java)
	private var hudView: EvSpeedometerHudView? = null

	override fun getLayoutId(): Int = R.layout.ev_speedometer_widget

	override fun setupView(view: View) {
		super.setupView(view)
		view.visibility = View.GONE
	}

	override fun attachView(
		container: ViewGroup,
		panel: WidgetsPanel,
		followingWidgets: MutableList<MapWidget>
	) {
		val placeholder = getView()
		placeholder.layoutParams = ViewGroup.LayoutParams(0, 0)
		placeholder.visibility = View.GONE
		container.addView(placeholder)
		attachHud()
	}

	override fun detachView(
		widgetsPanel: WidgetsPanel,
		widgets: MutableList<net.osmand.plus.views.mapwidgets.MapWidgetInfo>,
		mode: net.osmand.plus.settings.backend.ApplicationMode
	) {
		detachHud()
		super.detachView(widgetsPanel, widgets, mode)
	}

	override fun supportsPanelRowDivider(): Boolean = false

	override fun updateInfo(view: View, drawSettings: DrawSettings?) {
		val hud = attachHud() ?: return
		val speed = plugin.speedometerReading()?.kmh ?: 0.0
		hud.visibility = View.VISIBLE
		hud.bringToFront()
		hud.updateHud(
			speed.toFloat(),
			plugin.speedometerHudZone(speed),
			plugin.HUD_LIMIT2_KMH.get(),
			plugin.HUD_BUFFER2_KMH.get()
		)
	}

	private fun attachHud(): EvSpeedometerHudView? {
		val existing = hudView
		if (existing != null) {
			if (existing.isAttachedToWindow) {
				return existing
			}
			(existing.parent as? ViewGroup)?.removeView(existing)
			hudView = null
		}
		val host = findHudHost() ?: return null
		host.clipChildren = false
		host.clipToPadding = false
		val hud = EvSpeedometerHudView(mapActivity)
		hud.layoutParams = FrameLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.MATCH_PARENT,
			Gravity.FILL
		)
		hud.visibility = View.VISIBLE
		hud.elevation = 32f
		hud.isClickable = false
		hud.isFocusable = false
		host.addView(hud)
		hud.bringToFront()
		hudView = hud
		return hud
	}

	private fun findHudHost(): ViewGroup? {
		mapActivity.findViewById<ViewGroup>(R.id.map_hud_layout)?.let { return it }
		(mapActivity.findViewById<View>(R.id.map_hud_container)?.parent as? ViewGroup)?.let { return it }
		return mapActivity.findViewById(android.R.id.content)
	}

	private fun detachHud() {
		val hud = hudView ?: return
		(hud.parent as? ViewGroup)?.removeView(hud)
		hudView = null
	}
}
