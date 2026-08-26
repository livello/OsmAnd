package net.osmand.plus.plugins.torrentmaps

import android.view.View
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.utils.AndroidUtils
import net.osmand.plus.views.layers.base.OsmandMapLayer.DrawSettings
import net.osmand.plus.views.mapwidgets.WidgetType
import net.osmand.plus.views.mapwidgets.WidgetsPanel
import net.osmand.plus.views.mapwidgets.widgets.SimpleWidget

class TorrentsMapsWidget(
	mapActivity: MapActivity,
	customId: String?,
	widgetsPanel: WidgetsPanel?
) : SimpleWidget(mapActivity, WidgetType.TORRENTS_MAPS, customId, widgetsPanel) {

	private val plugin = PluginsHelper.requirePlugin(TorrentMapsPlugin::class.java)
	private var cacheText: String? = null

	init {
		setIcons(WidgetType.TORRENTS_MAPS)
		setText(NO_VALUE, null)
	}

	override fun getOnClickListener(): View.OnClickListener {
		return View.OnClickListener { plugin.openClientUi(mapActivity) }
	}

	override fun updateSimpleWidgetInfo(drawSettings: DrawSettings?) {
		val st = plugin.mapTorrentStatus()
		val text = when {
			!st.error.isNullOrBlank() && !st.running -> mapActivity.getString(R.string.torrent_maps_widget_error)
			!st.running -> {
				when {
					!st.waitingReason.isNullOrBlank() -> mapActivity.getString(R.string.torrent_maps_widget_waiting)
					else -> mapActivity.getString(R.string.torrent_maps_widget_idle)
				}
			}
			st.paused -> mapActivity.getString(R.string.torrent_maps_widget_paused)
			st.downloadRate > 0L || st.uploadRate > 0L -> {
				val down = AndroidUtils.formatSize(app, st.downloadRate) + "/s"
				val up = AndroidUtils.formatSize(app, st.uploadRate) + "/s"
				"↓$down ↑$up"
			}
			st.seedingFiles > 0 || st.state.contains("seed", ignoreCase = true) -> {
				mapActivity.getString(R.string.torrent_maps_widget_seeding, st.seedingFiles.coerceAtLeast(st.matchedFiles))
			}
			else -> st.state.ifBlank { mapActivity.getString(R.string.torrent_maps_widget_running) }
		}
		if (text != cacheText) {
			cacheText = text
			setText(text, null)
		}
	}
}
