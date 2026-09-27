package net.osmand.plus.plugins.voicegps

import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.shared.SharedUtil
import net.osmand.plus.track.GpxSelectionParams
import net.osmand.shared.gpx.GpxFile
import net.osmand.shared.gpx.primitives.WptPt
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Daily GPX journal for voice notes (`ev-voice-notes-yyyy-MM-dd.gpx` under the active tracks folder).
 */
object EvVoiceGpxStore {

	private const val FILE_PREFIX = "ev-voice-notes-"
	private const val LEGACY_PLUGIN_ID = "osmand.voice.gps"

	fun migrateLegacyPluginId(app: OsmandApplication, currentId: String) {
		val enabled = app.settings.getPlugins()
		if (enabled.contains(LEGACY_PLUGIN_ID) && currentId != LEGACY_PLUGIN_ID) {
			app.settings.enablePlugin(currentId, true)
			app.settings.enablePlugin(LEGACY_PLUGIN_ID, false)
		}
	}

	fun appendWaypoint(
		app: OsmandApplication,
		lat: Double,
		lon: Double,
		timeMs: Long,
		title: String,
		description: String,
		showOnMap: Boolean,
	) {
		val file = notesFile(app, timeMs)
		file.parentFile?.mkdirs()
		val gpx = loadOrCreate(app, file)
		if (gpx.error != null) {
			return
		}
		val pt = WptPt(lat, lon, timeMs, Double.NaN, 0f, Float.NaN)
		pt.name = title
		pt.desc = description
		pt.category = app.getString(R.string.voice_gps_favorites_group)
		pt.setColor(0xFF5E35B1.toInt())
		pt.setIconName("note")
		pt.setBackgroundType("circle")
		gpx.addPoint(pt)
		gpx.modifiedTime = timeMs
		if (SharedUtil.writeGpxFile(file, gpx) != null) {
			return
		}
		if (showOnMap) {
			val reloaded = SharedUtil.loadGpxFile(file)
			reloaded.path = file.absolutePath
			val params = GpxSelectionParams.newInstance()
				.showOnMap()
				.selectedAutomatically()
				.saveSelection()
			app.getSelectedGpxHelper().selectGpxFile(reloaded, params)
		}
		app.osmandMap.refreshMap()
	}

	private fun notesFile(app: OsmandApplication, timeMs: Long): File {
		val date = Date(timeMs)
		val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
		val month = SimpleDateFormat("yyyy-MM", Locale.US).format(date)
		return File(File(app.appCustomization.tracksDir, month), "$FILE_PREFIX$day.gpx")
	}

	private fun loadOrCreate(app: OsmandApplication, file: File): GpxFile {
		if (file.exists()) {
			return SharedUtil.loadGpxFile(file)
		}
		val gpx = GpxFile(app.getString(R.string.voice_gps_favorites_group))
		gpx.path = file.absolutePath
		return gpx
	}
}
