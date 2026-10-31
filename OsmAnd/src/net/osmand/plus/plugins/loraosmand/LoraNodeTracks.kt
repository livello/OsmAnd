package net.osmand.plus.plugins.loraosmand

import net.osmand.IndexConstants
import net.osmand.plus.OsmandApplication
import net.osmand.plus.shared.SharedUtil
import net.osmand.plus.track.GpxSelectionParams
import net.osmand.shared.gpx.GpxFile
import net.osmand.shared.gpx.GpxParameter
import net.osmand.shared.io.KFile
import net.osmand.shared.gpx.primitives.WptPt
import org.meshtastic.proto.NodeInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * One GPX per node under tracks/rec/lora-osmand. Shown tracks are selected
 * through the normal OsmAnd track list, in the color of the node icon.
 */
internal class LoraNodeTracks(
	private val app: OsmandApplication,
	private val plugin: LoraOsmAndPlugin,
) {
	private val queue = Executors.newSingleThreadExecutor { runnable ->
		Thread(runnable, "lora-tracks").apply { isDaemon = true }
	}
	private val last = HashMap<Int, Sample>()

	fun startSession(node: NodeInfo) {
		rememberTrackFile(node, System.currentTimeMillis())
	}

	fun consider(node: NodeInfo) {
		if (!plugin.isTrackRecording(node.num)) {
			return
		}
		val loc = node.meshLatLon() ?: return
		val altitude = node.position?.altitude?.toDouble()
		val style = plugin.markerStyle()
		val own = plugin.ownNodeNum()
		val shown = plugin.isTrackShown(node.num)
		queue.execute {
			if (!due(node.num, loc.latitude, loc.longitude)) {
				return@execute
			}
			val color = LoraMapLayer.iconColor(style, node, own)
			val file = writePoint(node, loc.latitude, loc.longitude, altitude, color) ?: return@execute
			if (shown) {
				show(file, color)
			}
		}
	}

	fun refreshVisibility(num: Int) {
		val node = plugin.nodes.firstOrNull { it.num == num }
		val style = plugin.markerStyle()
		val own = plugin.ownNodeNum()
		val shown = plugin.isTrackShown(num)
		queue.execute {
			val file = findFile(num) ?: return@execute
			val color = if (node != null) LoraMapLayer.iconColor(style, node, own) else null
			if (color != null) {
				paint(file, color, node?.longLabel())
			}
			if (shown) {
				show(file, color)
			} else {
				hide(file)
			}
		}
	}

	fun recolor(nodes: List<NodeInfo>, style: LoraMarkerStyle, own: Int?) {
		val colors = nodes.associate { it.num to LoraMapLayer.iconColor(style, it, own) }
		val names = nodes.associate { it.num to it.longLabel() }
		val shown = nodes.map { it.num }.filter { plugin.isTrackShown(it) }.toSet()
		val files = plugin.trackFiles()
		queue.execute {
			val folder = dir()
			for ((num, name) in files) {
				val file = File(folder, name)
				if (!file.isFile) {
					continue
				}
				val color = colors[num] ?: continue
				paint(file, color, names[num])
				if (num in shown) {
					show(file, color)
				}
			}
		}
	}

	private fun due(num: Int, lat: Double, lon: Double): Boolean {
		val prev = last[num]
		val now = System.currentTimeMillis()
		if (prev == null) {
			last[num] = Sample(lat, lon, now)
			return true
		}
		val interval = plugin.TRACK_INTERVAL_SEC.get()
		val offset = plugin.TRACK_OFFSET_M.get()
		val moved = metersBetween(prev.lat, prev.lon, lat, lon)
		val elapsed = now - prev.timeMs
		val timerDue = interval > 0 && elapsed >= interval * 1000L
		val moveDue = offset > 0 && moved >= offset
		val accept = when {
			interval > 0 && offset > 0 -> timerDue || moveDue
			interval > 0 -> timerDue
			offset > 0 -> moveDue
			else -> moved >= IDLE_MOVE_M
		}
		if (!accept) {
			return false
		}
		last[num] = Sample(lat, lon, now)
		return true
	}

	private fun writePoint(node: NodeInfo, lat: Double, lon: Double, altitude: Double?, color: Int): File? {
		val file = fileFor(node)
		val gpx = loadOrCreate(file, node.longLabel())
		if (gpx.error != null) {
			return null
		}
		val point = WptPt(lat, lon, System.currentTimeMillis(), altitude ?: Double.NaN, 0f, Float.NaN)
		val segment = gpx.tracks.lastOrNull()?.segments?.lastOrNull { !it.generalSegment }
		if (segment == null) {
			gpx.addTrkSegment(listOf(point))
		} else {
			segment.points.add(point)
			gpx.modifiedTime = point.time
		}
		gpx.tracks.lastOrNull()?.name = node.longLabel()
		gpx.metadata.name = node.longLabel()
		gpx.setColor(color)
		gpx.path = file.absolutePath
		if (SharedUtil.writeGpxFile(file, gpx) != null) {
			return null
		}
		return file
	}

	private fun paint(file: File, color: Int, name: String?) {
		val gpx = SharedUtil.loadGpxFile(file)
		if (gpx.error != null) {
			return
		}
		gpx.setColor(color)
		if (!name.isNullOrBlank()) {
			gpx.metadata.name = name
			gpx.tracks.lastOrNull()?.let { it.name = name }
		}
		gpx.path = file.absolutePath
		SharedUtil.writeGpxFile(file, gpx)
	}

	private fun show(file: File, color: Int?) {
		val gpx = SharedUtil.loadGpxFile(file)
		if (gpx.error != null || !gpx.hasTrkPt()) {
			return
		}
		gpx.path = file.absolutePath
		if (color != null) {
			gpx.setColor(color)
		}
		val params = GpxSelectionParams.newInstance()
			.showOnMap()
			.selectedByUser()
			.notShowNavigationDialog()
			.saveSelection()
		app.uiHandler.post {
			app.selectedGpxHelper.selectGpxFile(gpx, params)
			val item = app.gpxDbHelper.getItem(KFile(file.absolutePath))
			if (item != null && color != null) {
				app.gpxDbHelper.updateDataItemParameter(item, GpxParameter.COLOR, color)
			}
			app.osmandMap.refreshMap()
		}
	}

	private fun hide(file: File) {
		if (!file.exists()) {
			return
		}
		val gpx = SharedUtil.loadGpxFile(file)
		if (gpx.error != null) {
			return
		}
		gpx.path = file.absolutePath
		val params = GpxSelectionParams.newInstance().hideFromMap().saveSelection()
		app.uiHandler.post {
			app.selectedGpxHelper.selectGpxFile(gpx, params)
			app.osmandMap.refreshMap()
		}
	}

	private fun loadOrCreate(file: File, name: String): GpxFile {
		if (file.exists()) {
			val loaded = SharedUtil.loadGpxFile(file)
			loaded.path = file.absolutePath
			return loaded
		}
		file.parentFile?.mkdirs()
		val created = GpxFile("lora-osmand")
		created.path = file.absolutePath
		created.metadata.name = name
		return created
	}

	private fun fileFor(node: NodeInfo): File {
		val stored = plugin.trackFileName(node.num)
		if (!stored.isNullOrBlank()) {
			return File(dir(), stored)
		}
		return File(dir(), rememberTrackFile(node, System.currentTimeMillis()))
	}

	private fun rememberTrackFile(node: NodeInfo, startMs: Long): String {
		val folder = dir()
		val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date(startMs))
		val safe = node.longLabel()
			.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
			.trim('_', '.')
			.take(40)
			.ifBlank { "node" }
		var name = "${safe}_$stamp.gpx"
		val ownedByOther = plugin.trackFiles().any { it.key != node.num && it.value == name }
		if (ownedByOther || (File(folder, name).exists() && plugin.trackFileName(node.num) != name)) {
			val hex = node.num.toUInt().toString(16).takeLast(4)
			name = "${safe}_${stamp}_$hex.gpx"
		}
		plugin.rememberTrackFile(node.num, name)
		return name
	}

	private fun findFile(num: Int): File? {
		val name = plugin.trackFileName(num) ?: return null
		val file = File(dir(), name)
		return file.takeIf { it.isFile }
	}

	private fun dir(): File {
		val rec = app.getAppPath(IndexConstants.GPX_RECORDED_INDEX_DIR)
		return File(rec, DIR).also { it.mkdirs() }
	}

	private data class Sample(val lat: Double, val lon: Double, val timeMs: Long)

	companion object {
		const val DIR = "lora-osmand"
		private const val IDLE_MOVE_M = 8.0
	}
}
