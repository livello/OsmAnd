package net.osmand.plus.plugins.loraosmand

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
	private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.WHITE
		textAlign = Paint.Align.CENTER
		isFakeBoldText = true
	}

	override fun onDraw(canvas: Canvas, tileBox: RotatedTileBox, settings: DrawSettings) {}

	override fun onPrepareBufferImage(canvas: Canvas, tileBox: RotatedTileBox, settings: DrawSettings) {
		super.onPrepareBufferImage(canvas, tileBox, settings)
		val nodes = plugin.positionedNodes()
		val renderer = mapRenderer
		if (renderer != null) {
			updateGlMarkers(renderer, nodes)
			return
		}
		val density = tileBox.density
		for (node in nodes) {
			val latLng = node.meshLatLon() ?: continue
			val x = tileBox.getPixXFromLatLon(latLng.latitude, latLng.longitude)
			val y = tileBox.getPixYFromLatLon(latLng.latitude, latLng.longitude)
			drawBadge(canvas, x, y, node.shortLabel(), colorFor(node), density)
		}
	}

	override fun drawInScreenPixels(): Boolean = true

	override fun collectObjectsFromPoint(result: MapSelectionResult, rules: MapSelectionRules) {
		val point = result.point
		val tileBox = result.tileBox
		val radius = 28f * tileBox.density
		for (node in plugin.positionedNodes()) {
			val latLng = node.meshLatLon() ?: continue
			if (tileBox.isLatLonNearPixel(latLng.latitude, latLng.longitude, point.x, point.y, radius)) {
				result.collect(node, this)
			}
		}
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

	private fun updateGlMarkers(renderer: net.osmand.core.android.MapRendererView, nodes: List<NodeInfo>) {
		val key = nodes.joinToString("|") { node ->
			val latLng = node.meshLatLon()
			"${node.num}:${latLng?.latitude}:${latLng?.longitude}:${node.shortLabel()}:${colorFor(node)}"
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
		val density = textScale
		for (node in nodes) {
			val latLng = node.meshLatLon() ?: continue
			val bitmap = badgeBitmap(node.shortLabel(), colorFor(node), density)
			MapMarkerBuilder()
				.setIsAccuracyCircleSupported(false)
				.setBaseOrder(getPointsOrder())
				.setPinIcon(NativeUtilities.createSkImageFromBitmap(bitmap))
				.setPinIconVerticalAlignment(net.osmand.core.jni.MapMarker.PinIconVerticalAlignment.CenterVertical)
				.setPinIconHorisontalAlignment(net.osmand.core.jni.MapMarker.PinIconHorisontalAlignment.CenterHorizontal)
				.setPosition(PointI(MapUtils.get31TileNumberX(latLng.longitude), MapUtils.get31TileNumberY(latLng.latitude)))
				.buildAndAddToCollection(mapMarkersCollection)
		}
		renderer.addSymbolsProvider(mapMarkersCollection)
	}

	private fun colorFor(node: NodeInfo): Int {
		if (plugin.ownNodeNum() == node.num) {
			return 0xFF1565C0.toInt()
		}
		return if (node.isMeshOnline()) 0xFF2E7D32.toInt() else 0xFF757575.toInt()
	}

	private fun drawBadge(canvas: Canvas, x: Float, y: Float, label: String, color: Int, density: Float) {
		val radius = 16f * density
		fill.color = color
		canvas.drawCircle(x, y, radius, fill)
		text.textSize = 11f * density
		canvas.drawText(label.take(4), x, y + text.textSize * 0.35f, text)
	}

	private fun badgeBitmap(label: String, color: Int, density: Float): Bitmap {
		val scale = density.coerceAtLeast(1f)
		val size = (36f * scale).toInt().coerceAtLeast(36)
		val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
		drawBadge(Canvas(bitmap), size / 2f, size / 2f, label, color, scale)
		return bitmap
	}
}
