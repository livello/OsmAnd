package net.osmand.plus.plugins.voicegps

import net.osmand.data.FavouritePoint
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.myplaces.favorites.FavouritesHelper.AddFavoriteOptions
import net.osmand.plus.utils.AndroidUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VoiceGpsNoteWriter(private val app: OsmandApplication) {

	fun saveTextNote(lat: Double, lon: Double, body: String, timeMs: Long) {
		val text = body.trim()
		if (text.isEmpty()) {
			return
		}
		val title = makeTitle(text, timeMs)
		val category = app.getString(R.string.voice_gps_favorites_group)
		if (!app.favoritesHelper.groupExists(category)) {
			app.favoritesHelper.addFavoriteGroup(category, 0xFF5E35B1.toInt(), "note", "circle")
		}
		val point = FavouritePoint(lat, lon, title, category)
		point.description = text
		point.setIconName("note")
		point.setColor(0xFF5E35B1.toInt())
		app.favoritesHelper.addFavourite(point, AddFavoriteOptions().enableAll())

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
		app.osmandMap.mapView.refreshMap(true, false, false)
		AndroidUtils.getMapActivity(app)?.showShortToastMessage(
			app.getString(R.string.voice_gps_note_saved, title)
		)
	}

	private fun makeTitle(text: String, timeMs: Long): String {
		val oneLine = text.replace('\n', ' ').trim()
		val stamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMs))
		if (oneLine.isEmpty()) {
			return app.getString(R.string.voice_gps_note_default_title, stamp)
		}
		if (oneLine.length <= 42) {
			return oneLine
		}
		return oneLine.take(40).trimEnd() + "…"
	}
}
