package net.osmand.plus.plugins.loraosmand

enum class LoraSheetTab {
	SETTINGS,
	NODES,
	MESSAGES,
	ABOUT;

	companion object {
		fun from(name: String?): LoraSheetTab {
			return entries.firstOrNull { it.name == name } ?: SETTINGS
		}
	}
}
