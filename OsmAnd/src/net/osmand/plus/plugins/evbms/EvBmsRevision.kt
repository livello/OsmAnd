package net.osmand.plus.plugins.evbms

object EvBmsRevision {
	const val GIT_HASH = "229bcee36a"

	data class Commit(val hash: String, val date: String, val subject: String)

	val RECENT_COMMITS = listOf(
		Commit("229bcee36a", "2026-11-09", "Record the trip with OsmAnd's own track service again. Telemetry metrics stay on those points."),
		Commit("bd4cecf34f", "2026-10-31", "Stop duplicating own mesh messages when the radio or TAK echoes them back."),
		Commit("d86fd86ac3", "2026-10-29", "Record every cell voltage and pack temperature in telemetry."),
		Commit("827234189e", "2026-10-18", "Restore telemetry after a crash and rebuild the ride journal."),
		Commit("5ed988170e", "2026-09-29", "Let EV-Telemetry numeric settings accept an exact value."),
		Commit("70199d4d32", "2026-09-27", "Fix inflated charge Ah in the journal and repair it from telemetry."),
		Commit("371d3d4cbc", "2026-09-27", "Rebuild charge and trip history only when asked on the History tab."),
		Commit("bb2d0a1c04", "2026-09-27", "Repair history off the UI thread so settings stay responsive."),
		Commit("793e0625f2", "2026-09-27", "Let users hide consumption kilometre circles and resize them while keeping Wh/km squares on the track."),
		Commit("7cd5101f4e", "2026-09-26", "Stop doubled trip-journal rows, compare range with the route to the destination, and keep consumption squares on the moving track."),
	)

	fun changelogHtml(): String {
		val rows = RECENT_COMMITS.joinToString("") { commit ->
			"• <code>${commit.hash}</code> ${commit.date} — ${escapeHtml(commit.subject)}<br/>"
		}
		return "<br/><br/><b>📝 Changelog</b> · $GIT_HASH<br/>$rows"
	}

	private fun escapeHtml(text: String): String {
		return text
			.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
	}
}
