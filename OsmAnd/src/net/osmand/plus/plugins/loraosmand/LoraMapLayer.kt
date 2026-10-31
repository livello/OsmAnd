package net.osmand.plus.plugins.loraosmand

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import net.osmand.core.jni.MapMarker
import net.osmand.core.jni.MapMarkerBuilder
import net.osmand.core.jni.MapMarkersCollection
import net.osmand.core.jni.PointI
import net.osmand.data.LatLon
import net.osmand.data.PointDescription
import net.osmand.data.RotatedTileBox
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.utils.NativeUtilities
import net.osmand.plus.views.layers.ContextMenuLayer.IContextMenuProvider
import net.osmand.plus.views.layers.MapSelectionResult
import net.osmand.plus.views.layers.MapSelectionRules
import net.osmand.plus.views.layers.base.OsmandMapLayer
import net.osmand.plus.widgets.ctxmenu.ContextMenuAdapter
import net.osmand.plus.widgets.ctxmenu.data.ContextMenuItem
import net.osmand.util.MapUtils
import org.meshtastic.proto.NodeInfo

internal class LoraMapLayer(
	context: Context,
	private val plugin: LoraOsmAndPlugin,
) : OsmandMapLayer(context), IContextMenuProvider {

	private var markerKey: String = ""
	private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
	private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
	private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.WHITE
		textAlign = Paint.Align.CENTER
		isFakeBoldText = true
	}
	private val pin = Path()

	override fun onDraw(canvas: Canvas, tileBox: RotatedTileBox, settings: DrawSettings) {}

	override fun onPrepareBufferImage(canvas: Canvas, tileBox: RotatedTileBox, settings: DrawSettings) {
		super.onPrepareBufferImage(canvas, tileBox, settings)
		val nodes = plugin.positionedNodes()
		val night = settings.isNightMode()
		val renderer = mapRenderer
		if (renderer != null) {
			updateGlMarkers(renderer, nodes, night)
			return
		}
		for (node in nodes) {
			val latLng = node.meshLatLon() ?: continue
			val x = tileBox.getPixXFromLatLon(latLng.latitude, latLng.longitude)
			val y = tileBox.getPixYFromLatLon(latLng.latitude, latLng.longitude)
			val bitmap = badgeBitmap(node, night)
			canvas.drawBitmap(bitmap, x - bitmap.width / 2f, y - bitmap.height / 2f, null)
		}
	}

	override fun drawInScreenPixels(): Boolean = true

	override fun collectObjectsFromPoint(result: MapSelectionResult, rules: MapSelectionRules) {
		val point = result.point
		val tileBox = result.tileBox
		val renderer = mapRenderer
		val radius = touchRadius(tileBox.density)
		for (node in plugin.positionedNodes()) {
			val latLng = node.meshLatLon() ?: continue
			val pixel = NativeUtilities.getPixelFromLatLon(renderer, tileBox, latLng.latitude, latLng.longitude)
			val dx = pixel.x - point.x
			val dy = pixel.y - point.y
			if (dx * dx + dy * dy <= radius * radius) {
				result.collect(node, this)
			}
		}
	}

	override fun collectMapSymbolByExtraId(extraId: Int, result: MapSelectionResult): Boolean {
		val node = plugin.positionedNodes().firstOrNull { it.num == extraId } ?: return false
		val already = result.allObjects.any { (it.`object` as? NodeInfo)?.num == node.num }
		if (!already) {
			result.collect(node, this)
		}
		return true
	}

	override fun getObjectLocation(o: Any?): LatLon? {
		val node = o as? NodeInfo ?: return null
		val latLng = node.meshLatLon() ?: return null
		return LatLon(latLng.latitude, latLng.longitude)
	}

	override fun getObjectName(o: Any?): PointDescription? {
		val node = o as? NodeInfo ?: return null
		return PointDescription(PointDescription.POINT_TYPE_POI, node.longLabel())
	}

	override fun showMenuAction(o: Any?): Boolean {
		val node = o as? NodeInfo ?: return false
		val activity = mapView?.context as? MapActivity ?: return false
		val online = if (node.isMeshOnline()) "🟢" else "⚫"
		val metrics = formatNodeMetrics(activity, plugin, node)
		val body = if (metrics.isEmpty()) online else "$online\n$metrics"
		val density = activity.resources.displayMetrics.density
		val pad = (12 * density).toInt()
		val column = LinearLayout(activity).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(pad, pad, pad, 0)
		}
		column.addView(TextView(activity).apply {
			text = body
			textSize = 15f
		})
		val record = TextView(activity).apply {
			textSize = 16f
			setPadding(0, pad, 0, pad / 2)
			layoutParams = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
			)
		}
		fun paintRecord() {
			val on = plugin.isTrackRecording(node.num)
			record.text = (if (on) "🔴 " else "⚪ ") + activity.getString(R.string.lora_osmand_track_record)
		}
		paintRecord()
		record.setOnClickListener {
			plugin.setTrackRecording(node.num, !plugin.isTrackRecording(node.num))
			paintRecord()
		}
		column.addView(record)
		AlertDialog.Builder(activity)
			.setTitle(node.longLabel())
			.setView(column)
			.setPositiveButton(R.string.lora_osmand_open_node) { _, _ ->
				plugin.openNodeInMeshtastic(node.num)
			}
			.setNegativeButton(R.string.shared_string_close, null)
			.show()
		return true
	}

	override fun populateObjectContextMenu(latLon: LatLon, o: Any?, adapter: ContextMenuAdapter) {
		if (o !is NodeInfo) {
			return
		}
		val mapActivity = mapView?.context as? MapActivity ?: return
		adapter.addItem(
			ContextMenuItem("lora_osmand_message")
				.setTitle(context.getString(R.string.lora_osmand_widget_message))
				.setIcon(R.drawable.ic_action_micro_dark)
				.setOrder(2100)
				.setListener { _, _, _, _ ->
					plugin.showSettings(mapActivity, LoraSheetTab.MESSAGES)
					true
				},
		)
	}

	private fun updateGlMarkers(
		renderer: net.osmand.core.android.MapRendererView,
		nodes: List<NodeInfo>,
		night: Boolean,
	) {
		val style = plugin.markerStyle()
		val key = buildString {
			append(style.id)
			append(':')
			append(plugin.MARKER_SIZE.get())
			append(':')
			append(plugin.MARKER_FONT.get())
			append(':')
			append(night)
			for (node in nodes) {
				val latLng = node.meshLatLon()
				append('|')
				append(node.num)
				append(':')
				append(latLng?.latitude)
				append(':')
				append(latLng?.longitude)
				append(':')
				append(node.shortLabel())
				append(':')
				append(iconColor(style, node, plugin.ownNodeNum()))
				append(':')
				append(node.isMeshOnline())
			}
		}
		if (key == markerKey && mapMarkersCollection != null) {
			return
		}
		markerKey = key
		clearMapMarkersCollections()
		if (nodes.isEmpty()) {
			return
		}
		mapMarkersCollection = MapMarkersCollection()
		for (node in nodes) {
			val latLng = node.meshLatLon() ?: continue
			val bitmap = badgeBitmap(node, night)
			val marker = MapMarkerBuilder()
				.setIsAccuracyCircleSupported(false)
				.setBaseOrder(getPointsOrder())
				.setPinIcon(NativeUtilities.createSkImageFromBitmap(bitmap))
				.setPinIconVerticalAlignment(MapMarker.PinIconVerticalAlignment.CenterVertical)
				.setPinIconHorisontalAlignment(MapMarker.PinIconHorisontalAlignment.CenterHorizontal)
				.setPosition(PointI(MapUtils.get31TileNumberX(latLng.longitude), MapUtils.get31TileNumberY(latLng.latitude)))
				.buildAndAddToCollection(mapMarkersCollection)
			marker.setExtraId(node.num)
		}
		renderer.addSymbolsProvider(mapMarkersCollection)
	}

	private fun badgeBitmap(node: NodeInfo, night: Boolean): Bitmap {
		val density = textScale.coerceAtLeast(1f)
		val sizeScale = plugin.MARKER_SIZE.get() / 100f
		val font = plugin.MARKER_FONT.get() * density
		text.textSize = font
		val label = nameFor(node)
		val textWidth = text.measureText(label).coerceAtLeast(font)
		val pad = 4f * density
		val radius = (16f * density * sizeScale).coerceAtLeast(textWidth / 2f + pad)
		val style = plugin.markerStyle()
		val width: Int
		val height: Int
		when (style) {
			LoraMarkerStyle.PIN -> {
				val tail = radius * 1.15f
				width = (radius * 2f + pad).toInt().coerceAtLeast(1)
				height = (radius * 2f + tail * 2f).toInt().coerceAtLeast(1)
			}
			LoraMarkerStyle.PLATE -> {
				width = (textWidth + pad * 4f).toInt().coerceAtLeast(1)
				height = (font + pad * 3f).toInt().coerceAtLeast(1)
			}
			LoraMarkerStyle.DOT -> {
				val dot = 7f * density * sizeScale
				val side = textWidth + pad
				width = (side + dot * 2f + side).toInt().coerceAtLeast(1)
				height = (font.coerceAtLeast(dot * 2f) + pad).toInt().coerceAtLeast(1)
			}
			else -> {
				val box = (radius * 2f + pad).toInt().coerceAtLeast(1)
				width = box
				height = box
			}
		}
		val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
		drawBadge(Canvas(bitmap), node, label, radius, font, night)
		return bitmap
	}

	private fun drawBadge(canvas: Canvas, node: NodeInfo, label: String, radius: Float, font: Float, night: Boolean) {
		val style = plugin.markerStyle()
		val color = iconColor(style, node, plugin.ownNodeNum())
		text.textSize = font
		text.color = Color.WHITE
		text.textAlign = Paint.Align.CENTER
		when (style) {
			LoraMarkerStyle.MESHTASTIC -> drawMeshtastic(canvas, node, label, radius, color)
			LoraMarkerStyle.PIN -> drawPin(canvas, label, radius, color)
			LoraMarkerStyle.PLATE -> drawPlate(canvas, label, color, night)
			LoraMarkerStyle.DOT -> drawDot(canvas, label, radius, color, night)
			LoraMarkerStyle.CIRCLE -> drawCircle(canvas, label, radius, color)
		}
	}

	private fun drawCircle(canvas: Canvas, label: String, radius: Float, color: Int) {
		val cx = canvas.width / 2f
		val cy = canvas.height / 2f
		fill.color = color
		canvas.drawCircle(cx, cy, radius, fill)
		canvas.drawText(label, cx, cy + text.textSize * 0.35f, text)
	}

	private fun drawMeshtastic(canvas: Canvas, node: NodeInfo, label: String, radius: Float, color: Int) {
		val cx = canvas.width / 2f
		val cy = canvas.height / 2f
		fill.color = color
		fill.alpha = if (node.isMeshOnline()) 255 else 150
		canvas.drawCircle(cx, cy, radius, fill)
		fill.alpha = 255
		stroke.strokeWidth = (radius * 0.14f).coerceAtLeast(2f)
		stroke.color = if (node.isMeshOnline()) 0xFF43A047.toInt() else 0xFF9E9E9E.toInt()
		canvas.drawCircle(cx, cy, radius - stroke.strokeWidth / 2f, stroke)
		canvas.drawText(label, cx, cy + text.textSize * 0.35f, text)
		val dot = radius * 0.22f
		fill.color = stroke.color
		canvas.drawCircle(cx + radius * 0.62f, cy + radius * 0.62f, dot, fill)
	}

	private fun drawPin(canvas: Canvas, label: String, radius: Float, color: Int) {
		val cx = canvas.width / 2f
		val cy = canvas.height / 2f
		fill.color = color
		canvas.drawCircle(cx, cy, radius, fill)
		pin.reset()
		pin.moveTo(cx - radius * 0.55f, cy + radius * 0.2f)
		pin.lineTo(cx + radius * 0.55f, cy + radius * 0.2f)
		pin.lineTo(cx, canvas.height - 1f)
		pin.close()
		canvas.drawPath(pin, fill)
		canvas.drawText(label, cx, cy + text.textSize * 0.35f, text)
	}

	private fun drawPlate(canvas: Canvas, label: String, color: Int, night: Boolean) {
		val rect = RectF(1f, 1f, canvas.width - 1f, canvas.height - 1f)
		fill.color = if (night) 0xF0212121.toInt() else 0xF2FFFFFF.toInt()
		canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fill)
		fill.color = color
		canvas.drawRoundRect(RectF(1f, 1f, 8f, canvas.height - 1f), 4f, 4f, fill)
		text.color = if (night) Color.WHITE else Color.BLACK
		text.textAlign = Paint.Align.LEFT
		canvas.drawText(label, 12f, canvas.height / 2f + text.textSize * 0.35f, text)
	}

	private fun drawDot(canvas: Canvas, label: String, radius: Float, color: Int, night: Boolean) {
		val dot = (7f * (plugin.MARKER_SIZE.get() / 100f)).coerceAtMost(radius).coerceAtLeast(4f)
		val cy = canvas.height / 2f
		val cx = canvas.width / 2f
		fill.color = color
		canvas.drawCircle(cx, cy, dot, fill)
		val nameX = cx + dot + 4f
		val nameY = cy + text.textSize * 0.35f
		stroke.color = if (night) 0xCC000000.toInt() else 0xCCFFFFFF.toInt()
		stroke.strokeWidth = 4f
		stroke.style = Paint.Style.STROKE
		stroke.textSize = text.textSize
		stroke.textAlign = Paint.Align.LEFT
		stroke.isFakeBoldText = true
		canvas.drawText(label, nameX, nameY, stroke)
		stroke.style = Paint.Style.STROKE
		text.color = if (night) Color.WHITE else Color.BLACK
		text.textAlign = Paint.Align.LEFT
		canvas.drawText(label, nameX, nameY, text)
	}

	private fun touchRadius(density: Float): Float {
		val scale = plugin.MARKER_SIZE.get() / 100f
		val font = plugin.MARKER_FONT.get() * density
		return when (plugin.markerStyle()) {
			LoraMarkerStyle.PLATE, LoraMarkerStyle.DOT -> font * 6f + 28f * density
			else -> 26f * density * scale + font
		}
	}

	private fun nameFor(node: NodeInfo): String {
		val short = node.shortLabel()
		return when (plugin.markerStyle()) {
			LoraMarkerStyle.PLATE, LoraMarkerStyle.DOT -> short.take(8)
			else -> short.take(4)
		}
	}

	companion object {
		fun iconColor(style: LoraMarkerStyle, node: NodeInfo, ownNum: Int?): Int {
			if (style == LoraMarkerStyle.MESHTASTIC) {
				val index = (node.num.toUInt() % MESH_COLORS.size.toUInt()).toInt()
				return MESH_COLORS[index]
			}
			if (ownNum != null && ownNum == node.num) {
				return 0xFF1565C0.toInt()
			}
			return if (node.isMeshOnline()) 0xFF2E7D32.toInt() else 0xFF757575.toInt()
		}

		private val MESH_COLORS = intArrayOf(
			0xFF1E88E5.toInt(),
			0xFF43A047.toInt(),
			0xFFFB8C00.toInt(),
			0xFF8E24AA.toInt(),
			0xFF00ACC1.toInt(),
			0xFFE53935.toInt(),
			0xFF3949AB.toInt(),
			0xFF6D4C41.toInt(),
		)
	}
}
