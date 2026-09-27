package net.osmand.plus.plugins.voicegps

import android.view.View
import androidx.appcompat.widget.TooltipCompat
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.helpers.AndroidUiHelper
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.views.layers.base.OsmandMapLayer.DrawSettings
import net.osmand.plus.views.mapwidgets.WidgetType
import net.osmand.plus.views.mapwidgets.WidgetsPanel
import net.osmand.plus.views.mapwidgets.widgets.SimpleWidget

/**
 * Map button: emoji only. Tap opens EV Voice GPX settings, long press starts a voice note.
 * The widget list title stays on [WidgetType.VOICE_GPS_NOTE]; that text is the tooltip.
 */
class VoiceGpsNoteWidget(
	mapActivity: MapActivity,
	customId: String?,
	widgetsPanel: WidgetsPanel?,
) : SimpleWidget(mapActivity, WidgetType.VOICE_GPS_NOTE, customId, widgetsPanel) {

	init {
		applyEmojiOnly()
	}

	override fun shouldShowIcon(): Boolean = false

	override fun getWidgetName(): String? = null

	override fun getOnClickListener(): View.OnClickListener {
		return View.OnClickListener {
			VoiceGpsSettingsBottomSheet.showInstance(mapActivity.supportFragmentManager)
		}
	}

	override fun handleLongClick(view: View): Boolean {
		val plugin = PluginsHelper.getPlugin(VoiceGpsPlugin::class.java) ?: return false
		plugin.beginManualSession(mapActivity)
		return true
	}

	override fun updateSimpleWidgetInfo(drawSettings: DrawSettings?) {
		applyEmojiOnly()
	}

	private fun applyEmojiOnly() {
		setText(NOTE_EMOJI, null)
		AndroidUiHelper.updateVisibility(imageView, false)
		widgetName?.visibility = View.GONE
		val hint = mapActivity.getString(R.string.voice_gps_widget_name)
		val root = view
		root.contentDescription = hint
		TooltipCompat.setTooltipText(root, hint)
		textView?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
	}

	companion object {
		private const val NOTE_EMOJI = "🎤"
	}
}
