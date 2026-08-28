package net.osmand.plus.plugins.torrentmaps

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import net.osmand.aidlapi.OsmAndCustomizationConstants
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.plugins.OsmandPlugin
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.plugins.evbms.EvBmsPlugin
import net.osmand.plus.settings.backend.ApplicationMode
import net.osmand.plus.settings.backend.WidgetsAvailabilityHelper
import net.osmand.plus.settings.backend.preferences.CommonPreference
import net.osmand.plus.settings.enums.ScreenLayoutMode
import net.osmand.plus.settings.fragments.SettingsScreenType
import net.osmand.plus.views.mapwidgets.MapWidgetInfo
import net.osmand.plus.views.mapwidgets.WidgetInfoCreator
import net.osmand.plus.views.mapwidgets.WidgetType
import net.osmand.plus.views.mapwidgets.WidgetsPanel
import net.osmand.plus.views.mapwidgets.widgets.MapWidget

class TorrentMapsPlugin(app: OsmandApplication) : OsmandPlugin(app) {

	companion object {
		const val STOP_SPEED_KMH_DEFAULT = 3
		/** Keep legacy preference ids so existing EvBms torrent settings survive the split. */
		private const val PREF_ENABLED = "ev_bms_torrent_enabled"
		private const val PREF_PATH = "ev_bms_torrent_path"
		private const val PREF_NAME = "ev_bms_torrent_name"
		private const val PREF_SEED_CHARGE = "ev_bms_torrent_seed_charge"
		private const val PREF_WIFI_ONLY = "ev_bms_torrent_wifi_only"
		private const val PREF_DOWNLOAD_NEW = "ev_bms_torrent_download_new"
		private const val PREF_TOPIC_URL = "ev_bms_torrent_topic_url"
		private const val PREF_COOKIE = "ev_bms_torrent_rutracker_cookie"
		private const val PREF_MAGNET = "torrent_maps_magnet_uri"
		private const val PREF_DOWNLOADED = "ev_bms_torrent_downloaded"
		private const val PREF_UPLOADED = "ev_bms_torrent_uploaded"
		private const val PREF_SORT_KEY = "torrent_maps_sort_key"
		private const val PREF_SORT_ASC = "torrent_maps_sort_asc"
		private const val PREF_NEARBY_SHARE = "torrent_maps_nearby_share"
	}

	val TORRENT_ENABLED: CommonPreference<Boolean> =
		registerBooleanPreference(PREF_ENABLED, false).makeGlobal().makeShared()
	val TORRENT_PATH: CommonPreference<String> =
		registerStringPreference(PREF_PATH, "").makeGlobal().makeShared()
	val TORRENT_NAME: CommonPreference<String> =
		registerStringPreference(PREF_NAME, "").makeGlobal().makeShared()
	val TORRENT_SEED_ON_CHARGE: CommonPreference<Boolean> =
		registerBooleanPreference(PREF_SEED_CHARGE, true).makeGlobal().makeShared()
	val TORRENT_WIFI_ONLY: CommonPreference<Boolean> =
		registerBooleanPreference(PREF_WIFI_ONLY, true).makeGlobal().makeShared()
	val TORRENT_DOWNLOAD_NEW: CommonPreference<Boolean> =
		registerBooleanPreference(PREF_DOWNLOAD_NEW, false).makeGlobal().makeShared()
	val TORRENT_TOPIC_URL: CommonPreference<String> =
		registerStringPreference(
			PREF_TOPIC_URL,
			"https://rutracker.org/forum/viewtopic.php?t=5233935"
		).makeGlobal().makeShared()
	val TORRENT_RUTRACKER_COOKIE: CommonPreference<String> =
		registerStringPreference(PREF_COOKIE, "").makeGlobal().makeShared()
	val TORRENT_MAGNET: CommonPreference<String> =
		registerStringPreference(PREF_MAGNET, "").makeGlobal().makeShared()
	val TORRENT_DOWNLOADED: CommonPreference<Long> =
		registerLongPreference(PREF_DOWNLOADED, 0L).makeGlobal()
	val TORRENT_UPLOADED: CommonPreference<Long> =
		registerLongPreference(PREF_UPLOADED, 0L).makeGlobal()
	val TORRENT_SORT_KEY: CommonPreference<String> =
		registerStringPreference(PREF_SORT_KEY, TorrentSortKey.NAME.name).makeGlobal().makeShared()
	val TORRENT_SORT_ASC: CommonPreference<Boolean> =
		registerBooleanPreference(PREF_SORT_ASC, true).makeGlobal().makeShared()
	val NEARBY_SHARE: CommonPreference<Boolean> =
		registerBooleanPreference(PREF_NEARBY_SHARE, false).makeGlobal().makeShared()
	val STOP_SPEED_KMH: CommonPreference<Int> =
		registerIntPreference("torrent_maps_stop_speed_kmh", STOP_SPEED_KMH_DEFAULT).makeGlobal().makeShared()

	private val handler = Handler(Looper.getMainLooper())
	private val mapTorrent by lazy { MapTorrentEngine(app, this) }
	val nearby by lazy { NearbyMapsController(app, this) }
	private var torrentNetworkCallback: ConnectivityManager.NetworkCallback? = null
	private var torrentPowerReceiver: BroadcastReceiver? = null

	init {
		WidgetsAvailabilityHelper.regWidgetVisibility(WidgetType.TORRENTS_MAPS, null)
	}

	override fun getId(): String = OsmAndCustomizationConstants.PLUGIN_TORRENT_MAPS

	override fun getName(): String = app.getString(R.string.torrent_maps_plugin_name)

	override fun getDescription(linksEnabled: Boolean): CharSequence {
		return HtmlCompat.fromHtml(
			app.getString(R.string.torrent_maps_plugin_description),
			HtmlCompat.FROM_HTML_MODE_LEGACY
		)
	}

	override fun getLogoResourceId(): Int = R.drawable.ic_action_gsave_dark

	override fun getAssetResourceImage(): Drawable? =
		app.uiUtilities.getIcon(R.drawable.ic_action_gsave_dark)

	override fun getSettingsScreenType(): SettingsScreenType =
		SettingsScreenType.TORRENT_MAPS_SETTINGS

	override fun init(app: OsmandApplication, activity: Activity?): Boolean {
		TorrentMapsLog.init(app)
		TorrentMapsLog.append("plugin init")
		registerTorrentWatchers()
		handler.postDelayed({
			mapTorrent.cleanupDuplicateMaps()
			syncMapTorrent()
			if (NEARBY_SHARE.get()) {
				nearby.startSharing()
			}
		}, 2000)
		handler.postDelayed({
			mapTorrent.scheduleBackgroundHashCheck()
		}, 12_000)
		return true
	}

	override fun disable(app: OsmandApplication) {
		super.disable(app)
		TorrentMapsLog.append("plugin disable")
		unregisterTorrentWatchers()
		nearby.shutdownAll()
		mapTorrent.stop()
	}

	fun openNearbyMapsUi(context: Context) {
		val intent = Intent(context, NearbyMapsActivity::class.java)
		if (context !is Activity) {
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		}
		context.startActivity(intent)
	}

	override fun mapActivityResume(activity: MapActivity) {
		syncMapTorrent()
	}

	override fun createWidgets(
		mapActivity: MapActivity,
		widgetsInfos: MutableList<MapWidgetInfo>,
		appMode: ApplicationMode,
		layoutMode: ScreenLayoutMode?
	) {
		val creator = WidgetInfoCreator(app, appMode, layoutMode)
		val widget = createMapWidgetForParams(mapActivity, WidgetType.TORRENTS_MAPS)
		val info = creator.createWidgetInfo(widget)
		if (info != null) {
			widgetsInfos.add(info)
		}
	}

	override fun createMapWidgetForParams(
		mapActivity: MapActivity,
		widgetType: WidgetType,
		customId: String?,
		widgetsPanel: WidgetsPanel?
	): MapWidget? {
		if (widgetType == WidgetType.TORRENTS_MAPS) {
			return TorrentsMapsWidget(mapActivity, customId, widgetsPanel)
		}
		return null
	}

	fun mapTorrentStatus(): MapTorrentStatus = mapTorrent.status()

	fun torrentFileRows(): List<TorrentFileRow> = mapTorrent.fileRows()

	fun torrentPathSummary(): String = mapTorrent.pathSummary()

	fun torrentCatalogEntries(): List<TorrentCatalogEntry> = mapTorrent.catalogEntries()

	fun hasTorrentMapOffer(rawName: String): Boolean =
		TORRENT_ENABLED.get() && mapTorrent.hasTorrentFile() && mapTorrent.hasCatalogEntry(rawName)

	fun findTorrentMapOffer(rawName: String): TorrentCatalogEntry? =
		if (hasTorrentMapOffer(rawName)) mapTorrent.findCatalogEntry(rawName) else null

	fun downloadMapsFromTorrent(rawNames: Collection<String>) {
		if (!TORRENT_ENABLED.get()) {
			app.showToastMessage(R.string.torrent_maps_enable_first)
			return
		}
		if (!mapTorrent.hasTorrentFile()) {
			app.showToastMessage(R.string.torrent_maps_path_empty)
			return
		}
		TorrentMapsLog.append("Maps & Resources → torrent download (${rawNames.size})")
		val added = mapTorrent.downloadMapKeys(rawNames)
		if (added > 0) {
			app.showToastMessage(app.getString(R.string.torrent_maps_nearby_queued, added))
		} else {
			app.showToastMessage(R.string.torrent_maps_download_started)
		}
	}

	fun torrentQueuedCount(): Int = mapTorrent.queuedCount()

	fun torrentQueuedKeys(): Set<String> = mapTorrent.queuedMapKeys()

	fun pauseTorrentQueue() = mapTorrent.pauseDownloadQueue()

	fun resumeTorrentQueue() = mapTorrent.resumeDownloadQueue()

	fun removeTorrentQueueKey(mapKey: String) = mapTorrent.removeQueuedKey(mapKey)

	fun clearTorrentQueue() = mapTorrent.clearDownloadQueue()

	fun isTorrentPaused(): Boolean = mapTorrent.status().paused

	fun getSortKey(): TorrentSortKey {
		return try {
			TorrentSortKey.valueOf(TORRENT_SORT_KEY.get().orEmpty())
		} catch (_: Exception) {
			TorrentSortKey.NAME
		}
	}

	fun setSortKey(key: TorrentSortKey) {
		TORRENT_SORT_KEY.set(key.name)
	}

	fun isSortAscending(): Boolean = TORRENT_SORT_ASC.get()

	fun setSortAscending(asc: Boolean) {
		TORRENT_SORT_ASC.set(asc)
	}

	fun refreshTorrentFromRutracker(onDone: ((Boolean, String) -> Unit)? = null) {
		val url = TORRENT_TOPIC_URL.get().orEmpty().ifBlank {
			"https://rutracker.org/forum/viewtopic.php?t=5233935"
		}
		val cookie = TORRENT_RUTRACKER_COOKIE.get()
		TorrentMapsLog.append("RuTracker refresh started")
		Thread({
			val result = RutrackerTorrentFetcher.fetchTorrent(url, cookie)
			var message: String
			var ok: Boolean
			if (result.ok && result.bytes != null) {
				val dest = mapTorrent.torrentFile()
				try {
					if (mapTorrent.isStarted()) {
						mapTorrent.stop()
					}
					RutrackerTorrentFetcher.writeTo(dest, result.bytes)
					TORRENT_PATH.set(dest.absolutePath)
					TORRENT_NAME.set(result.fileName ?: dest.name)
					if (!result.magnet.isNullOrBlank()) {
						TORRENT_MAGNET.set(result.magnet)
					}
					mapTorrent.reloadCatalogFromDisk()
					ok = true
					message = app.getString(R.string.torrent_maps_refresh_ok, result.fileName ?: dest.name)
					handler.post { syncMapTorrent() }
				} catch (e: Exception) {
					ok = false
					message = e.message ?: app.getString(R.string.torrent_maps_refresh_failed)
				}
			} else {
				ok = false
				message = when (result.message) {
					"cloudflare" -> app.getString(R.string.torrent_maps_refresh_cloudflare)
					"no_magnet" -> app.getString(R.string.torrent_maps_refresh_no_magnet)
					"magnet_timeout" -> app.getString(R.string.torrent_maps_refresh_magnet_timeout)
					"bad_torrent" -> app.getString(R.string.torrent_maps_refresh_bad_file)
					"empty_url" -> app.getString(R.string.torrent_maps_topic_url_empty)
					else -> app.getString(R.string.torrent_maps_refresh_failed_detail, result.message)
				}
			}
			TorrentMapsLog.append(if (ok) "RuTracker refresh ok: $message" else "RuTracker refresh failed: $message")
			handler.post {
				app.showToastMessage(message)
				onDone?.invoke(ok, message)
			}
		}, "rutracker-torrent").start()
	}

	fun importTorrentFile(uri: Uri): Boolean {
		val ok = mapTorrent.importTorrent(uri)
		if (ok) {
			syncMapTorrent()
		}
		return ok
	}

	fun torrentFile(): java.io.File = mapTorrent.torrentFile()

	fun nearbyTorrentOffer(): NearbyTorrentOffer {
		val file = mapTorrent.torrentFile()
		val available = file.isFile && file.length() > 64L
		return NearbyTorrentOffer(
			magnet = TORRENT_MAGNET.get().orEmpty(),
			torrentName = TORRENT_NAME.get().orEmpty().ifBlank { if (available) file.name else "" },
			torrentDateMs = if (available) file.lastModified() else 0L,
			torrentAvailable = available
		)
	}

	fun applyTorrentBytes(bytes: ByteArray, displayName: String, magnet: String?): Boolean {
		val ok = mapTorrent.applyTorrentBytes(bytes, displayName)
		if (ok) {
			if (!magnet.isNullOrBlank()) {
				TORRENT_MAGNET.set(magnet)
			}
			syncMapTorrent()
		}
		return ok
	}

	fun startMapTorrentManual() {
		TorrentMapsLog.append("manual start")
		mapTorrent.startManual()
	}

	fun stopMapTorrent() {
		TorrentMapsLog.append("manual stop")
		mapTorrent.stop()
	}

	fun syncMapTorrent() {
		mapTorrent.sync()
	}

	fun verifyDownloadedMaps() = mapTorrent.startVerifyAll()

	fun cancelMapVerify() = mapTorrent.cancelVerify()

	fun mapVerifyStatus(): TorrentVerifyStatus = mapTorrent.verifyStatus()

	fun redownloadCorruptMaps() = mapTorrent.redownloadCorruptMaps()

	fun isTorrentRunning(): Boolean = mapTorrent.isStarted()

	fun openClientUi(context: Context) {
		val intent = Intent(context, TorrentMapsActivity::class.java)
		if (context !is Activity) {
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		}
		context.startActivity(intent)
	}

	/**
	 * Seed-on-charge: EV charging via EvBms if available, else phone plugged while stopped.
	 */
	fun isSeedChargeConditionMet(): Boolean {
		val ev = PluginsHelper.getPlugin(EvBmsPlugin::class.java)
		if (ev != null && ev.isActive && ev.isCharging()) {
			return true
		}
		val speed = currentSpeedKmh() ?: 0.0
		return isPhonePlugged() && speed < STOP_SPEED_KMH.get()
	}

	private fun currentSpeedKmh(): Double? {
		val ev = PluginsHelper.getPlugin(EvBmsPlugin::class.java)
		if (ev != null && ev.isActive) {
			val fused = ev.fusedSpeedKmh()
			if (fused != null) {
				return fused
			}
		}
		val loc = app.locationProvider.lastKnownLocation ?: return null
		if (!loc.hasSpeed()) {
			return null
		}
		return loc.speed * 3.6
	}

	private fun isPhonePlugged(): Boolean {
		val intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
		return intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
	}

	private fun registerTorrentWatchers() {
		unregisterTorrentWatchers()
		val cm = app.getSystemService(ConnectivityManager::class.java)
		if (cm != null) {
			val cb = object : ConnectivityManager.NetworkCallback() {
				override fun onAvailable(network: Network) {
					syncMapTorrent()
				}

				override fun onLost(network: Network) {
					syncMapTorrent()
				}
			}
			try {
				cm.registerDefaultNetworkCallback(cb)
				torrentNetworkCallback = cb
			} catch (_: Exception) {
			}
		}
		val receiver = object : BroadcastReceiver() {
			override fun onReceive(context: Context?, intent: Intent?) {
				syncMapTorrent()
			}
		}
		val filter = IntentFilter().apply {
			addAction(Intent.ACTION_POWER_CONNECTED)
			addAction(Intent.ACTION_POWER_DISCONNECTED)
		}
		try {
			ContextCompat.registerReceiver(
				app,
				receiver,
				filter,
				ContextCompat.RECEIVER_NOT_EXPORTED
			)
			torrentPowerReceiver = receiver
		} catch (_: Exception) {
		}
	}

	private fun unregisterTorrentWatchers() {
		val cb = torrentNetworkCallback
		if (cb != null) {
			try {
				app.getSystemService(ConnectivityManager::class.java)
					?.unregisterNetworkCallback(cb)
			} catch (_: Exception) {
			}
			torrentNetworkCallback = null
		}
		val receiver = torrentPowerReceiver
		if (receiver != null) {
			try {
				app.unregisterReceiver(receiver)
			} catch (_: Exception) {
			}
			torrentPowerReceiver = null
		}
	}
}
