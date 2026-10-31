package net.osmand.plus.plugins.loraosmand

import net.osmand.plus.R

internal enum class LoraMessageStyle(val id: String, val titleRes: Int) {
	TELEGRAM("telegram", R.string.lora_osmand_style_telegram),
	FACEBOOK("facebook", R.string.lora_osmand_style_facebook),
	ICQ("icq", R.string.lora_osmand_style_icq),
	TWITTER("twitter", R.string.lora_osmand_style_twitter),
	ZSH("zsh", R.string.lora_osmand_style_zsh),
	LORA("lora", R.string.lora_osmand_style_lora),
	;

	companion object {
		fun from(id: String?): LoraMessageStyle {
			return entries.firstOrNull { it.id == id } ?: LORA
		}
	}
}
