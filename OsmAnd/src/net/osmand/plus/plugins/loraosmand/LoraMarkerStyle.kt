package net.osmand.plus.plugins.loraosmand

enum class LoraMarkerStyle(val id: String) {
	CIRCLE("circle"),
	MESHTASTIC("meshtastic"),
	PIN("pin"),
	PLATE("plate"),
	DOT("dot");

	companion object {
		fun from(id: String?): LoraMarkerStyle {
			return entries.firstOrNull { it.id == id } ?: CIRCLE
		}
	}
}
