package net.osmand.plus.plugins.evbms

object EvBmsRevision {
	const val GIT_HASH = "d86fd86ac3"

	data class Commit(val hash: String, val date: String, val subject: String)

	val RECENT_COMMITS = listOf(
		Commit("d86fd86ac3", "2026-10-29", "Record every cell voltage and pack temperature in telemetry."),
		Commit("827234189e", "2026-10-18", "Restore telemetry after a crash and rebuild the ride journal."),
		Commit("5ed988170e", "2026-09-29", "Let EV-Telemetry numeric settings accept an exact value."),
		Commit("70199d4d32", "2026-09-27", "Fix inflated charge Ah in the journal and repair it from telemetry."),
		Commit("371d3d4cbc", "2026-09-27", "Rebuild charge and trip history only when asked on the History tab."),
		Commit("bb2d0a1c04", "2026-09-27", "Repair history off the UI thread so settings stay responsive."),
		Commit("793e0625f2", "2026-09-27", "Let users hide consumption kilometre circles and resize them while keeping Wh/km squares on the track."),
		Commit("7cd5101f4e", "2026-09-26", "Stop doubled trip-journal rows, compare range with the route to the destination, and keep consumption squares on the moving track."),
		Commit("fdd12e2989", "2026-09-16", "Pin split labels to the OpenGL track, restore their clicks, and list the last 10 commits in About."),
		Commit("9db62c9ccf", "2026-09-14", "Color track consumption from a windowed ΔE/Δs and add gradient interval settings."),
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
