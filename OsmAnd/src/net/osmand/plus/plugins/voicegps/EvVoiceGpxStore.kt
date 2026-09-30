package net.osmand.plus.plugins.voicegps

import net.osmand.data.FavouritePoint
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

	data class VoiceNoteEntry(
		val title: String,
		val description: String,
		val timeMs: Long,
		val lat: Double,
		val lon: Double,
		val favorite: FavouritePoint?,
	) {
		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (other !is VoiceNoteEntry) return false
			return timeMs == other.timeMs && lat == other.lat && lon == other.lon && title == other.title
		}

		override fun hashCode(): Int {
			var result = timeMs.hashCode()
			result = 31 * result + lat.hashCode()
			result = 31 * result + lon.hashCode()
			result = 31 * result + title.hashCode()
			return result
		}
	}

	private const val FILE_PREFIX = "ev-voice-notes-"
	private const val LEGACY_PLUGIN_ID = "osmand.voice.gps"

	@JvmStatic
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

	fun listVoiceNotes(app: OsmandApplication): List<VoiceNoteEntry> {
		val merged = LinkedHashMap<String, VoiceNoteEntry>()
		val category = app.getString(R.string.voice_gps_favorites_group)
		val group = app.favoritesHelper.getGroup(category)
		group?.points?.forEachIndexed { index, pt ->
			val time = pt.getTimestamp().takeIf { it > 0 }
				?: pt.getVisitedDate().takeIf { it > 0 }
				?: (index + 1).toLong()
			val entry = VoiceNoteEntry(
				title = pt.name ?: pt.getName(),
				description = pt.description ?: "",
				timeMs = time,
				lat = pt.latitude,
				lon = pt.longitude,
				favorite = pt,
			)
			putNote(merged, entry)
		}
		val root = app.appCustomization.tracksDir
		if (root != null && root.exists()) {
			root.walkTopDown().maxDepth(3).filter { file ->
				file.isFile && file.name.startsWith(FILE_PREFIX) && file.name.endsWith(".gpx")
			}.forEach { file ->
				val gpx = SharedUtil.loadGpxFile(file)
				if (gpx.error != null) {
					return@forEach
				}
				for (pt in gpx.getPointsList()) {
					val time = pt.time.takeIf { it > 0 } ?: file.lastModified()
					val entry = VoiceNoteEntry(
						title = pt.name ?: "",
						description = pt.desc ?: "",
						timeMs = time,
						lat = pt.lat,
						lon = pt.lon,
						favorite = group?.points?.firstOrNull { fav ->
							kotlin.math.abs(fav.latitude - pt.lat) < 1e-5 &&
								kotlin.math.abs(fav.longitude - pt.lon) < 1e-5 &&
								(fav.name == pt.name || fav.description == pt.desc)
						},
					)
					putNote(merged, entry)
				}
			}
		}
		return merged.values.sortedByDescending { it.timeMs }
	}

	private fun putNote(merged: LinkedHashMap<String, VoiceNoteEntry>, entry: VoiceNoteEntry) {
		val existing = merged.entries.firstOrNull { sameNote(it.value, entry) }
		if (existing == null) {
			merged[noteKey(entry)] = entry
			return
		}
		// The GPX copy and the favourite are the same note. Keep the favourite when we have it.
		if (existing.value.favorite == null && entry.favorite != null) {
			merged.remove(existing.key)
			merged[noteKey(entry)] = entry
		}
	}

	private fun sameNote(left: VoiceNoteEntry, right: VoiceNoteEntry): Boolean {
		if (kotlin.math.abs(left.lat - right.lat) > 2e-4 || kotlin.math.abs(left.lon - right.lon) > 2e-4) {
			return false
		}
		if (kotlin.math.abs(left.timeMs - right.timeMs) > 180_000L) {
			return false
		}
		return noteText(left) == noteText(right)
	}

	private fun noteText(entry: VoiceNoteEntry): String {
		val description = entry.description.trim()
		if (description.isNotEmpty()) {
			return description
		}
		return entry.title.substringAfter("·", entry.title).trim()
	}

	private fun noteKey(entry: VoiceNoteEntry): String =
		"${entry.timeMs}:${entry.lat}:${entry.lon}:${entry.title}"

	fun deleteVoiceNotes(app: OsmandApplication, entries: Collection<VoiceNoteEntry>): Int {
		if (entries.isEmpty()) {
			return 0
		}
		var deleted = 0
		for (entry in entries) {
			removeFromGpx(app, entry)
			if (entry.favorite != null && app.favoritesHelper.deleteFavourite(entry.favorite, true)) {
				deleted++
			} else if (entry.favorite == null) {
				deleted++
			}
		}
		app.osmandMap.refreshMap()
		return deleted
	}

	private fun removeFromGpx(app: OsmandApplication, entry: VoiceNoteEntry) {
		val file = notesFile(app, entry.timeMs)
		if (!file.exists()) {
			return
		}
		val gpx = SharedUtil.loadGpxFile(file)
		if (gpx.error != null) {
			return
		}
		val lat = entry.lat
		val lon = entry.lon
		val title = entry.title
		val toRemove = gpx.getPointsList().filter { pt ->
			kotlin.math.abs(pt.lat - lat) < 1e-6 &&
				kotlin.math.abs(pt.lon - lon) < 1e-6 &&
				(title.isNullOrEmpty() || title == pt.name)
		}
		for (pt in toRemove) {
			gpx.deleteWptPt(pt)
		}
		if (gpx.getPointsSize() == 0 && file.exists()) {
			file.delete()
		} else {
			SharedUtil.writeGpxFile(file, gpx)
		}
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
