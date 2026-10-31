package net.osmand.plus.plugins.loraosmand

/**
 * Screen-space hit test for a node marker and the name match used by the
 * message map button. Kept free of the map renderer so it can be checked
 * without a device.
 */
internal object LoraMarkerHit {
	/**
	 * More negative than ordinary map layers, so a tapped node wins over the
	 * road or place drawn underneath it.
	 */
	const val SELECTION_ORDER = -2_000_000_000L

	fun contains(touchX: Float, touchY: Float, markerX: Float, markerY: Float, radius: Float): Boolean {
		if (radius < 0f) {
			return false
		}
		val dx = markerX - touchX
		val dy = markerY - touchY
		return dx * dx + dy * dy <= radius * radius
	}

	fun namesMatch(sender: String, shortName: String, longName: String): Boolean {
		val name = sender.trim()
		if (name.isEmpty()) {
			return false
		}
		if (name.equals(shortName.trim(), ignoreCase = true)) {
			return true
		}
		return longName.isNotBlank() && name.equals(longName.trim(), ignoreCase = true)
	}

	fun selfCheck(): List<String> {
		val errors = ArrayList<String>()
		if (!contains(0f, 0f, 10f, 0f, 10f)) {
			errors.add("edge of the radius must hit")
		}
		if (contains(0f, 0f, 11f, 0f, 10f)) {
			errors.add("outside the radius must miss")
		}
		if (!contains(5f, 5f, 5f, 5f, 0f)) {
			errors.add("the marker center must hit")
		}
		if (contains(0f, 0f, 3f, 4f, -1f)) {
			errors.add("a negative radius must miss")
		}
		if (!namesMatch("Mrixs", "Mrixs", "Mrixs_base-east")) {
			errors.add("short name must match")
		}
		if (!namesMatch("mrixs_base-east", "Mx", "Mrixs_base-east")) {
			errors.add("long name must match ignoring case")
		}
		if (namesMatch("Mrixs", "Mx", "Mrixs_base-east")) {
			errors.add("a prefix of the long name is not a match")
		}
		if (namesMatch("  ", "Mrixs", "Mrixs")) {
			errors.add("a blank sender must not match")
		}
		if (namesMatch("Я", "live110", "live110.sk")) {
			errors.add("the local name must not match another node")
		}
		return errors
	}
}
