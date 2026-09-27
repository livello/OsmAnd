package net.osmand.plus.plugins.voicegps

import android.view.View
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.settings.fragments.BaseSettingsFragment
import net.osmand.plus.settings.fragments.SettingsScreenType
import net.osmand.plus.views.layers.base.OsmandMapLayer.DrawSettings
import net.osmand.plus.views.mapwidgets.WidgetType
import net.osmand.plus.views.mapwidgets.WidgetsPanel
import net.osmand.plus.views.mapwidgets.widgets.SimpleWidget

/**
 * Map panel button: short tap starts a voice note session (same as the wake key),
 * long press opens EV Voice GPX settings.
 */
class VoiceGpsNoteWidget(
	mapActivity: MapActivity,
	customId: String?,
	widgetsPanel: WidgetsPanel?,
) : SimpleWidget(mapActivity, WidgetType.VOICE_GPS_NOTE, customId, widgetsPanel) {

	init {
		setIcons(WidgetType.VOICE_GPS_NOTE)
		setText(mapActivity.getString(R.string.voice_gps_widget_label), null)
	}

	override fun getOnClickListener(): View.OnClickListener {
		return View.OnClickListener {
			val plugin = PluginsHelper.getPlugin(VoiceGpsPlugin::class.java) ?: return@OnClickListener
			plugin.beginManualSession(mapActivity)
		}
	}

	override fun handleLongClick(view: View): Boolean {
		BaseSettingsFragment.showInstance(mapActivity, SettingsScreenType.VOICE_GPS_SETTINGS)
		return true
	}

	override fun updateSimpleWidgetInfo(drawSettings: DrawSettings?) {
		val label = mapActivity.getString(R.string.voice_gps_widget_label)
		setText(label, null)
		setIcons(WidgetType.VOICE_GPS_NOTE)
	}
}
