package net.osmand.plus.plugins.evbms

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
	private val hudHandler = Handler(Looper.getMainLooper())
	private var hudView: EvSpeedometerHudView? = null
	private var lastPushMs = 0L
	private var demoRunning = false

	private val demoTick = object : Runnable {
		override fun run() {
			pushHud(force = true)
			if (demoRunning) {
				hudHandler.postDelayed(this, plugin.hudFrameIntervalMs())
			}
		}
	}

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
		if (!demo) {
			pushHud(force = false)
		}
	}

	private fun setDemoRunning(on: Boolean) {
		if (on) {
			if (demoRunning) {
				return
			}
			demoRunning = true
			hudHandler.removeCallbacks(demoTick)
			hudHandler.post(demoTick)
		} else if (demoRunning) {
			demoRunning = false
			hudHandler.removeCallbacks(demoTick)
		}
	}

	private fun pushHud(force: Boolean) {
		if (mapActivity.isFinishing || mapActivity.isDestroyed) {
			setDemoRunning(false)
			return
		}
		val now = SystemClock.elapsedRealtime()
		if (!force && now - lastPushMs < plugin.hudFrameIntervalMs()) {
			return
		}
		lastPushMs = now
		val hud = attachHud() ?: return
		val speed = plugin.hudDisplaySpeedKmh()
		plugin.noteHudSpeed(speed)
		plugin.tickSpeedProfileSwitch()
		if (!plugin.shouldShowSpeedometerHud(speed)) {
			if (hud.visibility != View.GONE) {
				hud.visibility = View.GONE
				hud.clearVisuals()
			}
			return
		}
		val host = hud.parent as? ViewGroup
		if (host != null) {
			val params = hudLayoutParams(host)
			val current = hud.layoutParams
			val currentFl = current as? FrameLayout.LayoutParams
			if (currentFl == null ||
				currentFl.width != params.width ||
				currentFl.height != params.height ||
				currentFl.gravity != params.gravity
			) {
				hud.layoutParams = params
			}
		}
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
		hud.visibility = View.GONE
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
		val percent = plugin.HUD_HEIGHT_PERCENT.get().coerceIn(50, 100) / 100f
		val height = if (hostH > 0) {
			(hostH * percent).toInt().coerceAtLeast(1)
		} else {
			ViewGroup.LayoutParams.MATCH_PARENT
		}
		return FrameLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			height,
			Gravity.BOTTOM
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
		hudHandler.removeCallbacks(demoTick)
		val hud = hudView ?: return
		hud.clearVisuals()
		(hud.parent as? ViewGroup)?.removeView(hud)
		hudView = null
	}
}
