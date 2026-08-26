package net.osmand.plus.plugins.torrentmaps

import net.osmand.IndexConstants
import net.osmand.map.OsmandRegions
import net.osmand.map.WorldRegion
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.helpers.FileNameTranslationHelper
import java.util.Locale

/**
 * Maps torrent / nearby map keys onto OsmAnd's official WorldRegion hierarchy
 * (same tree as Maps & Resources), producing virtual browse paths like
 * `Europe/Denmark/Denmark_europe.obf`.
 */
object TorrentRegionPaths {

	private val EXT_SUFFIXES = listOf(
		IndexConstants.BINARY_WIKI_MAP_INDEX_EXT_ZIP,
		IndexConstants.BINARY_WIKI_MAP_INDEX_EXT,
		IndexConstants.BINARY_TRAVEL_GUIDE_MAP_INDEX_EXT_ZIP,
		IndexConstants.BINARY_TRAVEL_GUIDE_MAP_INDEX_EXT,
		IndexConstants.BINARY_MAP_INDEX_EXT_ZIP,
		IndexConstants.BINARY_MAP_INDEX_EXT,
		IndexConstants.BINARY_WIKIVOYAGE_MAP_INDEX_EXT,
		IndexConstants.SQLITE_EXT,
		IndexConstants.TIF_EXT,
		".tiff",
		IndexConstants.ZIP_EXT
	)

	private val TYPE_PREFIXES = listOf(
		(FileNameTranslationHelper.HILL_SHADE + "_").lowercase(Locale.US),
		(FileNameTranslationHelper.SLOPE + "_").lowercase(Locale.US),
		(FileNameTranslationHelper.HEIGHTMAP + "_").lowercase(Locale.US),
		(FileNameTranslationHelper.HILL_SHADE + " ").lowercase(Locale.US),
		(FileNameTranslationHelper.SLOPE + " ").lowercase(Locale.US),
		(FileNameTranslationHelper.HEIGHTMAP + " ").lowercase(Locale.US)
	)

	fun worldFolderName(app: OsmandApplication): String =
		app.getString(R.string.world_maps)

	fun otherFolderName(app: OsmandApplication): String =
		app.getString(R.string.shared_string_other)

	/**
	 * Virtual path used only for folder browsing (not for HTTP / torrent I/O).
	 * Example: `Europe/Denmark/Denmark_europe.obf`.
	 */
	fun browsePath(app: OsmandApplication, mapKey: String, displayName: String): String {
		val folder = regionFolder(app, mapKey, displayName)
		val leaf = displayName.ifBlank {
			mapKey.substringAfterLast('/').ifBlank { mapKey }
		}
		return if (folder.isEmpty()) leaf else "$folder/$leaf"
	}

	/**
	 * Continent → country → … folder path without the file leaf.
	 * Unmatched maps go under localized "Other"; world-scoped under "World maps".
	 */
	fun regionFolder(app: OsmandApplication, mapKey: String, hintName: String = ""): String {
		val regions = app.regions ?: return otherFolderName(app)
		val region = resolveRegion(regions, mapKey)
		if (region != null) {
			return folderPathForRegion(region)
		}
		val probe = listOf(mapKey, hintName).joinToString(" ").lowercase(Locale.US)
		if (probe.contains("world")) {
			return worldFolderName(app)
		}
		return otherFolderName(app)
	}

	fun resolveRegion(regions: OsmandRegions, mapKey: String): WorldRegion? {
		for (candidate in downloadNameCandidates(mapKey)) {
			val region = regions.getRegionDataByDownloadName(candidate)
			if (region != null) {
				return region
			}
		}
		return null
	}

	fun folderPathForRegion(region: WorldRegion): String {
		val parts = ArrayList<String>()
		parts.add(region.localeName)
		for (parent in region.superRegions) {
			if (WorldRegion.WORLD == parent.regionId) {
				continue
			}
			parts.add(0, parent.localeName)
		}
		return parts.joinToString("/")
	}

	fun downloadNameCandidates(mapKey: String): List<String> {
		var base = mapKey.substringAfterLast('/').substringAfterLast('\\')
			.lowercase(Locale.US)
		base = WorldRegion.getRegionDownloadName(base)
		for (ext in EXT_SUFFIXES) {
			if (base.endsWith(ext)) {
				base = base.removeSuffix(ext)
				break
			}
		}
		val out = LinkedHashSet<String>()
		fun add(name: String) {
			val n = name.trim('_').trim('.')
			if (n.isNotBlank()) out.add(n)
		}
		add(base)
		for (prefix in TYPE_PREFIXES) {
			if (base.startsWith(prefix)) {
				add(base.removePrefix(prefix).replace(' ', '_'))
			}
		}
		if (base.endsWith(".road")) {
			add(base.removeSuffix(".road"))
		}
		if (base.endsWith("_roads")) {
			add(base.removeSuffix("_roads"))
		}
		if (base.endsWith(".wiki")) {
			add(base.removeSuffix(".wiki"))
		}
		if (base.endsWith("_wiki")) {
			add(base.removeSuffix("_wiki"))
		}
		if (base.endsWith("_travel")) {
			add(base.removeSuffix("_travel"))
		}
		if (base.contains('_')) {
			// e.g. weather / composite names — try last two segments flipped
			val parts = base.split('_')
			if (parts.size >= 2) {
				add(parts[parts.size - 1] + "_" + parts[parts.size - 2])
			}
		}
		return out.toList()
	}
}
