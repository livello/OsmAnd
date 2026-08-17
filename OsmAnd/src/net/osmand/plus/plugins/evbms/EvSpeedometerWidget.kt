package net.osmand.plus.plugins.evbms

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

	companion object {
		private const val HIDE_HYSTERESIS_KMH = 5.0
	}

	private val plugin = PluginsHelper.requirePlugin(EvBmsPlugin::class.java)
	private var hudView: EvSpeedometerHudView? = null
	private var hudVisible = false

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
		attachHud()
		val hud = hudView ?: return
		val speed = plugin.speedometerReading()?.kmh ?: 0.0
		if (plugin.isFastSpeedProfile()) {
			hudVisible = true
		} else {
			val showAt = plugin.HUD_SHOW_KMH.get().toDouble()
			val hideAt = (showAt - HIDE_HYSTERESIS_KMH).coerceAtLeast(0.0)
			hudVisible = if (hudVisible) speed >= hideAt else speed >= showAt
		}
		if (!hudVisible) {
			hud.visibility = View.GONE
			return
		}
		hud.visibility = View.VISIBLE
		hud.updateHud(
			speed.toFloat(),
			plugin.speedometerHudZone(speed),
			plugin.HUD_LIMIT2_KMH.get(),
			plugin.HUD_BUFFER2_KMH.get()
		)
	}

	private fun attachHud() {
		if (hudView?.parent != null) {
			return
		}
		val host = mapActivity.findViewById<ViewGroup>(R.id.map_hud_layout) ?: return
		val hud = EvSpeedometerHudView(mapActivity)
		hud.layoutParams = FrameLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.MATCH_PARENT
		)
		hud.visibility = View.GONE
		hud.elevation = 24f
		host.addView(hud)
		hudView = hud
	}

	private fun detachHud() {
		val hud = hudView ?: return
		(hud.parent as? ViewGroup)?.removeView(hud)
		hudView = null
		hudVisible = false
	}
}
