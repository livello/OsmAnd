package net.osmand.plus.plugins.evbms

import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.plugins.aistracker.AisTrackerPlugin
import net.osmand.plus.plugins.audionotes.AudioVideoNotesPlugin
import net.osmand.plus.plugins.externalsensors.ExternalSensorsPlugin
import net.osmand.plus.plugins.mapillary.MapillaryPlugin
import net.osmand.plus.plugins.odb.VehicleMetricsPlugin
import net.osmand.plus.plugins.srtm.SRTMPlugin
import net.osmand.plus.plugins.weather.WeatherPlugin
import net.osmand.plus.settings.enums.CompassMode
import net.osmand.plus.transport.TransportLinesMenu
import net.osmand.plus.views.OsmandMapTileView
import net.osmand.plus.wikipedia.WikipediaPlugin
import org.json.JSONArray
import org.json.JSONObject

class HikeModeController(
	private val app: OsmandApplication,
	private val plugin: EvBmsPlugin
) {

	companion object {
		const val HIKE_POLL_MS = 5000
		const val HIKE_TRACK_INTERVAL_MS = 10000
	}

	private val settings = app.settings

	fun isEnabled(): Boolean = plugin.HIKE_MODE.get()

	fun toggle(mapActivity: MapActivity) {
		if (isEnabled()) {
			restore(mapActivity)
			plugin.HIKE_MODE.set(false)
			plugin.HIKE_SNAPSHOT.set("")
			app.showToastMessage(app.getString(R.string.ev_bms_hike_off))
		} else {
			plugin.HIKE_SNAPSHOT.set(capture().toString())
			apply(mapActivity)
			plugin.HIKE_MODE.set(true)
			app.showToastMessage(app.getString(R.string.ev_bms_hike_on))
		}
		plugin.applyHikeTelemetryState()
	}

	private fun capture(): JSONObject {
		val json = JSONObject()
		json.put("batterySaving", settings.BATTERY_SAVING_MODE.get())
		json.put("enable3d", settings.ENABLE_3D_MAPS.get())
		json.put("animateLocation", settings.ANIMATE_MY_LOCATION.get())
		json.put("autoZoom", settings.AUTO_ZOOM_MAP.get())
		json.put("rotateMap", settings.ROTATE_MAP.get())
		json.put("mapOnline", settings.MAP_ONLINE_DATA.get())
		json.put("overlay", settings.MAP_OVERLAY.get() ?: JSONObject.NULL)
		json.put("underlay", settings.MAP_UNDERLAY.get() ?: JSONObject.NULL)
		json.put("liveMonitoring", settings.LIVE_MONITORING.get())
		json.put("liveUpdates", settings.IS_LIVE_UPDATES_ON.get())
		json.put("turnScreenOnTime", settings.TURN_SCREEN_ON_TIME_INT.get())
		json.put("turnScreenOnSensor", settings.TURN_SCREEN_ON_SENSOR.get())
		json.put("useSystemTimeout", settings.USE_SYSTEM_SCREEN_TIMEOUT.get())
		json.put("debugRendering", settings.DEBUG_RENDERING_INFO.get())
		json.put("recordTelemetry", plugin.RECORD_TELEMETRY.get())
		json.put("pollInterval", plugin.POLL_INTERVAL_MS.get())
		json.put("saveTrackInterval", settings.SAVE_TRACK_INTERVAL.get())
		json.put("magneticCompass", settings.USE_MAGNETIC_FIELD_SENSOR_COMPASS.get())
		json.put("coordinatesGrid", settings.SHOW_COORDINATES_GRID.get())
		json.put("elevation", app.osmandMap.mapView.elevationAngle.toDouble())
		json.put("transport", TransportLinesMenu(app).isShowAnyTransport)
		val poiIds = JSONArray()
		for (filter in app.poiFilters.selectedPoiFilters) {
			poiIds.put(filter.getFilterId())
		}
		json.put("poi", poiIds)
		val srtm = PluginsHelper.getPlugin(SRTMPlugin::class.java)
		if (srtm != null) {
			json.put("terrain", srtm.isTerrainLayerEnabled)
			json.put("buildings3d", srtm.ENABLE_3D_MAP_OBJECTS.get())
		}
		val weather = PluginsHelper.getPlugin(WeatherPlugin::class.java)
		if (weather != null) {
			json.put("weather", weather.isWeatherEnabled)
		}
		val mapillary = PluginsHelper.getPlugin(MapillaryPlugin::class.java)
		if (mapillary != null) {
			json.put("mapillary", mapillary.SHOW_MAPILLARY.get())
		}
		val plugins = JSONObject()
		for (p in listOf(
			PluginsHelper.getPlugin(VehicleMetricsPlugin::class.java),
			PluginsHelper.getPlugin(ExternalSensorsPlugin::class.java),
			PluginsHelper.getPlugin(AudioVideoNotesPlugin::class.java),
			PluginsHelper.getPlugin(AisTrackerPlugin::class.java)
		)) {
			if (p != null) {
				plugins.put(p.id, p.isEnabled)
			}
		}
		json.put("plugins", plugins)
		return json
	}

	private fun apply(mapActivity: MapActivity) {
		settings.BATTERY_SAVING_MODE.set(true)
		settings.ENABLE_3D_MAPS.set(false)
		settings.ANIMATE_MY_LOCATION.set(false)
		settings.AUTO_ZOOM_MAP.set(false)
		settings.MAP_ONLINE_DATA.set(false)
		settings.MAP_OVERLAY.set(null)
		settings.MAP_UNDERLAY.set(null)
		settings.LIVE_MONITORING.set(false)
		settings.IS_LIVE_UPDATES_ON.set(false)
		settings.TURN_SCREEN_ON_TIME_INT.set(0)
		settings.TURN_SCREEN_ON_SENSOR.set(false)
		settings.USE_SYSTEM_SCREEN_TIMEOUT.set(true)
		settings.DEBUG_RENDERING_INFO.set(false)
		settings.USE_MAGNETIC_FIELD_SENSOR_COMPASS.set(false)
		settings.SHOW_COORDINATES_GRID.set(false)
		if (settings.SAVE_TRACK_INTERVAL.get() < HIKE_TRACK_INTERVAL_MS) {
			settings.SAVE_TRACK_INTERVAL.set(HIKE_TRACK_INTERVAL_MS)
		}
		if (plugin.POLL_INTERVAL_MS.get() < HIKE_POLL_MS) {
			plugin.POLL_INTERVAL_MS.set(HIKE_POLL_MS)
		}
		app.mapViewTrackingUtilities.switchCompassModeTo(CompassMode.NORTH_IS_UP)
		app.osmandMap.mapView.elevationAngle = OsmandMapTileView.DEFAULT_ELEVATION_ANGLE
		TransportLinesMenu(app).toggleTransportLines(mapActivity, false)
		app.poiFilters.clearAllSelectedPoiFilters()
		PluginsHelper.getPlugin(WikipediaPlugin::class.java)?.toggleWikipediaPoi(false, null)
		PluginsHelper.getPlugin(SRTMPlugin::class.java)?.let {
			it.setTerrainLayerEnabled(false)
			it.ENABLE_3D_MAP_OBJECTS.set(false)
		}
		PluginsHelper.getPlugin(WeatherPlugin::class.java)?.setWeatherEnabled(false)
		PluginsHelper.getPlugin(MapillaryPlugin::class.java)?.SHOW_MAPILLARY?.set(false)
		disablePlugins(mapActivity)
		applyFrameRate()
		mapActivity.updateLayers()
		mapActivity.refreshMapComplete()
	}

	private fun restore(mapActivity: MapActivity) {
		val raw = plugin.HIKE_SNAPSHOT.get()
		if (raw.isNullOrEmpty()) {
			return
		}
		val json = JSONObject(raw)
		settings.BATTERY_SAVING_MODE.set(json.optBoolean("batterySaving", false))
		settings.ENABLE_3D_MAPS.set(json.optBoolean("enable3d", true))
		settings.ANIMATE_MY_LOCATION.set(json.optBoolean("animateLocation", true))
		settings.AUTO_ZOOM_MAP.set(json.optBoolean("autoZoom", false))
		settings.MAP_ONLINE_DATA.set(json.optBoolean("mapOnline", false))
		settings.MAP_OVERLAY.set(json.optNullableString("overlay"))
		settings.MAP_UNDERLAY.set(json.optNullableString("underlay"))
		settings.LIVE_MONITORING.set(json.optBoolean("liveMonitoring", false))
		settings.IS_LIVE_UPDATES_ON.set(json.optBoolean("liveUpdates", false))
		settings.TURN_SCREEN_ON_TIME_INT.set(json.optInt("turnScreenOnTime", 0))
		settings.TURN_SCREEN_ON_SENSOR.set(json.optBoolean("turnScreenOnSensor", false))
		settings.USE_SYSTEM_SCREEN_TIMEOUT.set(json.optBoolean("useSystemTimeout", false))
		settings.DEBUG_RENDERING_INFO.set(json.optBoolean("debugRendering", false))
		settings.USE_MAGNETIC_FIELD_SENSOR_COMPASS.set(json.optBoolean("magneticCompass", false))
		settings.SHOW_COORDINATES_GRID.set(json.optBoolean("coordinatesGrid", false))
		settings.SAVE_TRACK_INTERVAL.set(json.optInt("saveTrackInterval", 5000))
		plugin.RECORD_TELEMETRY.set(json.optBoolean("recordTelemetry", true))
		plugin.POLL_INTERVAL_MS.set(json.optInt("pollInterval", EvBmsPlugin.DEFAULT_POLL_MS))
		val rotate = json.optInt("rotateMap", settings.ROTATE_MAP.get())
		app.mapViewTrackingUtilities.switchCompassModeTo(CompassMode.getByValue(rotate))
		if (json.has("elevation")) {
			app.osmandMap.mapView.elevationAngle = json.optDouble("elevation", 90.0).toFloat()
		}
		if (json.optBoolean("transport", false) && settings.DISPLAYED_TRANSPORT_SETTINGS.getStringsList() != null) {
			TransportLinesMenu(app).toggleTransportLines(mapActivity, true)
		}
		val poi = json.optJSONArray("poi")
		if (poi != null) {
			app.poiFilters.clearAllSelectedPoiFilters()
			for (i in 0 until poi.length()) {
				val filter = app.poiFilters.getFilterById(poi.optString(i))
				if (filter != null) {
					app.poiFilters.addSelectedPoiFilter(filter)
				}
			}
		}
		PluginsHelper.getPlugin(SRTMPlugin::class.java)?.let {
			it.setTerrainLayerEnabled(json.optBoolean("terrain", false))
			it.ENABLE_3D_MAP_OBJECTS.set(json.optBoolean("buildings3d", false))
		}
		PluginsHelper.getPlugin(WeatherPlugin::class.java)?.setWeatherEnabled(json.optBoolean("weather", false))
		PluginsHelper.getPlugin(MapillaryPlugin::class.java)?.SHOW_MAPILLARY?.set(json.optBoolean("mapillary", false))
		restorePlugins(mapActivity, json.optJSONObject("plugins"))
		applyFrameRate()
		mapActivity.updateLayers()
		mapActivity.refreshMapComplete()
	}

	private fun disablePlugins(mapActivity: MapActivity) {
		for (p in listOf(
			PluginsHelper.getPlugin(VehicleMetricsPlugin::class.java),
			PluginsHelper.getPlugin(ExternalSensorsPlugin::class.java),
			PluginsHelper.getPlugin(AudioVideoNotesPlugin::class.java),
			PluginsHelper.getPlugin(AisTrackerPlugin::class.java)
		)) {
			if (p != null && p.isEnabled) {
				PluginsHelper.enablePlugin(mapActivity, app, p, false)
			}
		}
	}

	private fun restorePlugins(mapActivity: MapActivity, plugins: JSONObject?) {
		if (plugins == null) {
			return
		}
		val byId = listOf(
			PluginsHelper.getPlugin(VehicleMetricsPlugin::class.java),
			PluginsHelper.getPlugin(ExternalSensorsPlugin::class.java),
			PluginsHelper.getPlugin(AudioVideoNotesPlugin::class.java),
			PluginsHelper.getPlugin(AisTrackerPlugin::class.java)
		).filterNotNull().associateBy { it.id }
		val keys = plugins.keys()
		while (keys.hasNext()) {
			val id = keys.next()
			val p = byId[id] ?: continue
			PluginsHelper.enablePlugin(mapActivity, app, p, plugins.optBoolean(id, false))
		}
	}

	private fun applyFrameRate() {
		val mapView = app.osmandMap.mapView
		val renderer = mapView.mapRenderer ?: return
		mapView.applyMaximumFrameRate(renderer)
	}

	private fun JSONObject.optNullableString(key: String): String? {
		if (!has(key) || isNull(key)) {
			return null
		}
		val value = optString(key)
		return value.ifEmpty { null }
	}
}
