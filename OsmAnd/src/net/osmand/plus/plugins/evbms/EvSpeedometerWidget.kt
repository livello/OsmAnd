package net.osmand.plus.plugins.evbms

import android.animation.ValueAnimator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.settings.backend.ApplicationMode
import net.osmand.plus.views.layers.base.OsmandMapLayer.DrawSettings
import net.osmand.plus.views.mapwidgets.MapWidgetInfo
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
	private var demoAnim: ValueAnimator? = null

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
		widgets: MutableList<MapWidgetInfo>,
		mode: ApplicationMode
	) {
		setDemoRunning(false)
		detachHud()
		super.detachView(widgetsPanel, widgets, mode)
	}

	override fun supportsPanelRowDivider(): Boolean = false

	override fun updateInfo(view: View, drawSettings: DrawSettings?) {
		val demo = plugin.HUD_DEMO.get()
		setDemoRunning(demo)
		pushHud()
	}

	private fun setDemoRunning(on: Boolean) {
		if (on) {
			if (demoAnim?.isRunning == true) {
				return
			}
			demoAnim = ValueAnimator.ofFloat(0f, 1f).apply {
				duration = 1000L
				repeatCount = ValueAnimator.INFINITE
				interpolator = LinearInterpolator()
				addUpdateListener { pushHud() }
				start()
			}
		} else {
			demoAnim?.cancel()
			demoAnim = null
		}
	}

	private fun pushHud() {
		val hud = attachHud() ?: return
		val host = hud.parent as? ViewGroup
		if (host != null) {
			val params = hudLayoutParams(host)
			val current = hud.layoutParams
			if (current == null || current.width != params.width || current.height != params.height) {
				hud.layoutParams = params
			}
		}
		val speed = plugin.hudDisplaySpeedKmh()
		plugin.noteHudSpeed(speed)
		hud.visibility = View.VISIBLE
		hud.bringToFront()
		hud.updateHud(
			EvSpeedometerHudView.Frame(
				speedKmh = speed.toFloat(),
				zone = plugin.speedometerHudZone(speed),
				limit2Kmh = plugin.HUD_LIMIT2_KMH.get(),
				buffer2Kmh = plugin.HUD_BUFFER2_KMH.get(),
				strokeFraction = plugin.HUD_STROKE_PERCENT.get() / 100f,
				fontFraction = plugin.HUD_FONT_PERCENT.get() / 100f,
				showUnits = plugin.HUD_SHOW_UNITS.get(),
				maxKmh = plugin.hudWindowMaxKmh(),
				avgKmh = plugin.hudWindowAvgKmh()
			)
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
		removeStaleHudViews(host)
		host.clipChildren = false
		host.clipToPadding = false
		val hud = EvSpeedometerHudView(mapActivity)
		hud.layoutParams = hudLayoutParams(host)
		hud.visibility = View.VISIBLE
		hud.elevation = 48f
		hud.translationZ = 48f
		hud.isClickable = false
		hud.isFocusable = false
		host.addView(hud)
		hud.bringToFront()
		hudView = hud
		return hud
	}

	private fun hudLayoutParams(host: ViewGroup): FrameLayout.LayoutParams {
		val hostH = host.height
		val height = if (hostH > 0) {
			(hostH * (plugin.HUD_HEIGHT_PERCENT.get() / 100f)).toInt().coerceAtLeast(1)
		} else {
			ViewGroup.LayoutParams.MATCH_PARENT
		}
		return FrameLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			height,
			Gravity.CENTER
		)
	}

	private fun findHudHost(): ViewGroup? {
		mapActivity.findViewById<ViewGroup>(R.id.map_hud_layout)?.let { return it }
		(mapActivity.findViewById<View>(R.id.map_hud_container)?.parent as? ViewGroup)?.let { return it }
		return mapActivity.findViewById(android.R.id.content)
	}

	private fun removeStaleHudViews(host: ViewGroup) {
		for (i in host.childCount - 1 downTo 0) {
			val child = host.getChildAt(i)
			if (child === hudView) {
				continue
			}
			if (child is EvSpeedometerHudView || child.tag == EvSpeedometerHudView.HUD_TAG) {
				host.removeViewAt(i)
			}
		}
	}

	private fun detachHud() {
		val hud = hudView ?: return
		(hud.parent as? ViewGroup)?.removeView(hud)
		hudView = null
	}
}
