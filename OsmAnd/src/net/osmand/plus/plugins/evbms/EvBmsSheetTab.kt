package net.osmand.plus.plugins.evbms

enum class EvBmsSheetTab(val index: Int, val emoji: String) {
	SETTINGS(0, "⚙️"),
	FIELDS(1, "☑️"),
	CHARTS(2, "📊"),
	ABOUT(3, "ℹ️");

	companion object {
		fun from(index: Int): EvBmsSheetTab {
			return entries.firstOrNull { it.index == index } ?: SETTINGS
		}
	}
}
