package net.osmand.plus.plugins.voicegps

import net.osmand.data.FavouritePoint
import net.osmand.data.BackgroundType
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.myplaces.favorites.add.AddFavoriteOptions
import net.osmand.plus.myplaces.favorites.add.AddFavoriteResult
import net.osmand.plus.plugins.PluginsHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VoiceGpsNoteWriter(private val app: OsmandApplication) {

	private var lastSavedKey = ""
	private var lastSavedAt = 0L

	fun saveTextNote(lat: Double, lon: Double, body: String, timeMs: Long) {
		val text = body.trim()
		if (text.isEmpty()) {
			return
		}
		val key = "$lat:$lon:$text"
		val now = System.currentTimeMillis()
		if (key == lastSavedKey && now - lastSavedAt < 20_000L) {
			return
		}
		lastSavedKey = key
		lastSavedAt = now
		val title = makeTitle(text, timeMs)
		val category = app.getString(R.string.voice_gps_favorites_group)
		if (!app.favoritesHelper.groupExists(category)) {
			app.favoritesHelper.addFavoriteGroup(category, 0xFF5E35B1.toInt(), "note", BackgroundType.CIRCLE)
		}
		val point = FavouritePoint(lat, lon, title, category)
		point.description = text
		point.setTimestamp(timeMs)
		point.setIconIdFromName("note")
		point.setColor(0xFF5E35B1.toInt())
		val added = app.favoritesHelper.addFavourite(point, AddFavoriteOptions().enableAll())
		if (added == AddFavoriteResult.DUPLICATE) {
			point.name = "$title #${timeMs % 100000}"
			app.favoritesHelper.addFavourite(point, AddFavoriteOptions().enableAll())
		}

		EvVoiceGpxStore.appendWaypoint(
			app,
			lat,
			lon,
			timeMs,
			title,
			text,
			PluginsHelper.getPlugin(VoiceGpsPlugin::class.java)?.showVoiceGpxOnMap() != false
		)

		val track = app.savingTrackHelper
		if (track.isRecording || track.hasDataToSave()) {
			track.insertPointData(
				lat,
				lon,
				timeMs,
				text,
				title,
				category,
				0xFF5E35B1.toInt(),
				"note",
				"circle"
			)
		}
		app.osmandMap.refreshMap()
		android.util.Log.i("VoiceGps", "saved note \"$title\" fav=$added")
		app.showShortToastMessage(app.getString(R.string.voice_gps_note_saved, title))
		PluginsHelper.getPlugin(VoiceGpsPlugin::class.java)?.speakNoteRecorded()
	}

	private fun makeTitle(text: String, timeMs: Long): String {
		val oneLine = text.replace('\n', ' ').trim()
		val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timeMs))
		if (oneLine.isEmpty()) {
			return app.getString(R.string.voice_gps_note_default_title, stamp)
		}
		val prefix = "$stamp · "
		val maxBody = 48 - prefix.length
		val body = if (oneLine.length <= maxBody) oneLine else oneLine.take(maxBody - 1).trimEnd() + "…"
		return prefix + body
	}
}
