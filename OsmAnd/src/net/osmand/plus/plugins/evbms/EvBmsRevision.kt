package net.osmand.plus.plugins.evbms

object EvBmsRevision {
	const val GIT_HASH = "252a10498d"

	data class Commit(val hash: String, val date: String, val subject: String)

	val RECENT_COMMITS = listOf(
		Commit("252a10498d", "2026-09-16", "Write charge waypoints into saved GPX after recording ends."),
		Commit("07d5a911e2", "2026-09-16", "Thin split labels with zoom, apply background opacity, and add a history emoji legend."),
		Commit("114ce99fb5", "2026-09-15", "Sync every GPX under tracks/ from the selected phone into My Places."),
		Commit("7a28762f6c", "2026-09-15", "Show synced telemetry GPX in My Places and remember collapsed EV settings."),
		Commit("24b66024ec", "2026-09-15", "Show integer Wh/km on consumption splits and highlight the chosen speed band."),
		Commit("9db62c9ccf", "2026-09-14", "Color track consumption from a windowed ΔE/Δs and add gradient interval settings."),
		Commit("30d8949493", "2026-09-14", "Show specific-consumption color and 3D in track appearance."),
		Commit("ec3b1e36ff", "2026-09-14", "Add GPX specific-consumption color, 3D and split labels at 40–300 Wh/km."),
		Commit("659fa570dd", "2026-09-14", "Add autodiscover and live two-way telemetry/track sync between phones."),
		Commit("0bf0779da6", "2026-09-14", "Add LAN telemetry sync from motorcycle Z1 to analysis phone S200X."),
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
