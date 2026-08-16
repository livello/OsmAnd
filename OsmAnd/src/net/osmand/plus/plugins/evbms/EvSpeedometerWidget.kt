package net.osmand.plus.plugins.evbms

import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.settings.enums.ScreenLayoutMode
import net.osmand.plus.utils.AndroidUtils
import net.osmand.plus.utils.FontCache
import net.osmand.plus.views.layers.base.OsmandMapLayer.DrawSettings
import net.osmand.plus.views.mapwidgets.WidgetType
import net.osmand.plus.views.mapwidgets.WidgetsContextMenu
import net.osmand.plus.views.mapwidgets.WidgetsPanel
import net.osmand.plus.views.mapwidgets.appearance.ResolvedPanelAppearance
import net.osmand.plus.views.mapwidgets.widgets.MapWidget
import kotlin.math.roundToInt

class EvSpeedometerWidget(
	mapActivity: MapActivity,
	customId: String?,
	widgetsPanel: WidgetsPanel?
) : MapWidget(mapActivity, WidgetType.EV_SPEEDOMETER, customId, widgetsPanel) {

	companion object {
		private const val HEIGHT_FRACTION = 0.80f
		private const val DIGIT_COLOR = 0xFFF7F7F7.toInt()
		private const val STALE_COLOR = 0xFFEF9A9A.toInt()
		private const val UNIT_COLOR = 0xFF8A8A8A.toInt()
		private const val CLUSTER_BG = 0xFF0B0B0B.toInt()
	}

	private val plugin = PluginsHelper.requirePlugin(EvBmsPlugin::class.java)
	private lateinit var valueView: TextView
	private lateinit var unitView: TextView
	private lateinit var sourceView: TextView
	private var cacheText: String? = null
	private var cacheHasValue: Boolean? = null
	private var cacheGps: Boolean? = null

	override fun getLayoutId(): Int = R.layout.ev_speedometer_widget

	override fun setupView(view: View) {
		super.setupView(view)
		view.setBackgroundColor(CLUSTER_BG)
		valueView = view.findViewById(R.id.ev_speedometer_value)
		unitView = view.findViewById(R.id.ev_speedometer_unit)
		sourceView = view.findViewById(R.id.ev_speedometer_source)
		valueView.typeface = leafDigits()
		valueView.letterSpacing = -0.04f
		unitView.setTextColor(UNIT_COLOR)
		view.setOnClickListener { plugin.askShowSettingsDialog(mapActivity) }
		view.setOnLongClickListener { v ->
			val layoutMode = ScreenLayoutMode.getDefault(v.context)
			WidgetsContextMenu.showMenu(
				v, mapActivity, widgetType, customId, null, layoutMode, panel, nightMode, true
			)
			true
		}
		applyHudSize()
	}

	override fun attachView(
		container: ViewGroup,
		panel: WidgetsPanel,
		followingWidgets: MutableList<MapWidget>
	) {
		container.addView(getView())
		applyHudSize()
	}

	override fun supportsPanelRowDivider(): Boolean = false

	override fun onPanelAppearanceChanged(appearance: ResolvedPanelAppearance) {
		getView().setBackgroundColor(CLUSTER_BG)
		applyLeafColors(cacheHasValue == true)
	}

	override fun updateInfo(view: View, drawSettings: DrawSettings?) {
		applyHudSize()
		val reading = plugin.speedometerReading()
		val gpsFallback = reading != null && !reading.fromController
		val text = if (reading != null) {
			reading.kmh.coerceAtLeast(0.0).roundToInt().toString()
		} else {
			"—"
		}
		if (text != cacheText) {
			valueView.text = text
			cacheText = text
		}
		val hasValue = reading != null
		if (hasValue != cacheHasValue) {
			applyLeafColors(hasValue)
			cacheHasValue = hasValue
		}
		if (gpsFallback != cacheGps) {
			sourceView.text = if (gpsFallback) app.getString(R.string.ev_bms_speedometer_gps) else ""
			sourceView.visibility = if (gpsFallback) View.VISIBLE else View.GONE
			cacheGps = gpsFallback
		}
		updateVisibility(true)
	}

	private fun applyLeafColors(linked: Boolean) {
		valueView.setTextColor(if (linked) DIGIT_COLOR else STALE_COLOR)
		unitView.setTextColor(if (linked) UNIT_COLOR else STALE_COLOR)
	}

	private fun applyHudSize() {
		val host = getView()
		val targetH = (AndroidUtils.getScreenHeight(mapActivity) * HEIGHT_FRACTION).toInt()
			.coerceAtLeast(AndroidUtils.dpToPx(app, 220f))
		val params = host.layoutParams
		if (params != null) {
			params.width = ViewGroup.LayoutParams.MATCH_PARENT
			params.height = targetH
			host.layoutParams = params
		}
		if (host.height > 0) {
			val digitPx = (host.height * 0.58f).coerceIn(
				TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 72f, host.resources.displayMetrics),
				TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 360f, host.resources.displayMetrics)
			)
			if (valueView.textSize != digitPx) {
				valueView.setTextSize(TypedValue.COMPLEX_UNIT_PX, digitPx)
			}
		}
	}

	private fun leafDigits(): Typeface {
		val thin = Typeface.create("sans-serif-thin", Typeface.NORMAL)
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			FontCache.getFont(thin, 200)
		} else {
			thin
		}
	}
}
