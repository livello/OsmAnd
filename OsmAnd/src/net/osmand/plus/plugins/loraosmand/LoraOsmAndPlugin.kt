package net.osmand.plus.plugins.loraosmand

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.provider.Settings
import androidx.core.text.HtmlCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.osmand.aidlapi.OsmAndCustomizationConstants
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.plugins.OsmandPlugin
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
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.Position
import org.meshtastic.proto.User
import java.util.concurrent.CopyOnWriteArrayList

class LoraOsmAndPlugin(app: OsmandApplication) : OsmandPlugin(app),
	LoraSession.Listener,
	LoraTakClient.Events,
	net.osmand.plus.OsmAndLocationProvider.OsmAndLocationListener {

	val AUTO_CONNECT: CommonPreference<Boolean> =
		registerBooleanPreference(PREF_AUTO, true).makeGlobal().makeShared()
	val SHOW_ON_MAP: CommonPreference<Boolean> =
		registerBooleanPreference(PREF_SHOW_MAP, true).makeGlobal().makeShared()
	val DEVICE_ADDRESS: CommonPreference<String> =
		registerStringPreference(PREF_ADDRESS, "").makeGlobal().makeShared()
	val DEVICE_NAME: CommonPreference<String> =
		registerStringPreference(PREF_NAME, "l110").makeGlobal().makeShared()
	val POS_INTERVAL_SEC: CommonPreference<Int> =
		registerIntPreference(PREF_INTERVAL, 0).makeGlobal().makeShared()
	val POS_OFFSET_M: CommonPreference<Int> =
		registerIntPreference(PREF_OFFSET, 0).makeGlobal().makeShared()
	private val lastSentMsPref: CommonPreference<Long> =
		registerLongPreference(PREF_LAST_MS, 0L).makeGlobal()
	private val lastSentLatPref: CommonPreference<String> =
		registerStringPreference(PREF_LAST_LAT, "").makeGlobal()
	private val lastSentLonPref: CommonPreference<String> =
		registerStringPreference(PREF_LAST_LON, "").makeGlobal()
	private val hiddenNodesPref: CommonPreference<String> =
		registerStringPreference(PREF_HIDDEN_NODES, "").makeGlobal().makeShared()

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private val session = LoraSession(app, this)
	private val tak = LoraTakClient(app, this)
	private val speaker = LoraSpeaker(app)
	private val bridgeNodes = LinkedHashMap<Int, NodeInfo>()
	private val nodeRssi = HashMap<Int, Int>()
	private val heardChat = LinkedHashSet<String>()
	private var echoText: String = ""
	private var echoAt: Long = 0L
	private val uiListeners = CopyOnWriteArrayList<Runnable>()

	@Volatile var statusText: String = ""
	@Volatile var connected: Boolean = false
	@Volatile var nodes: List<NodeInfo> = emptyList()
	@Volatile var channels: List<Channel> = emptyList()
	@Volatile var foundRadios: List<FoundRadio> = emptyList()
	val chat = ArrayList<LoraChatLine>()

	private var mapActivity: MapActivity? = null
	private var layer: LoraMapLayer? = null
	private var anchorLat: Double? = null
	private var anchorLon: Double? = null
	private var pendingSend: Boolean = false
	private var appliedSchedule: String? = null
	private val positionTick = Runnable { tickPosition() }

	init {
		WidgetsAvailabilityHelper.regWidgetVisibility(WidgetType.LORA_OSMAND_BATTERY, null)
		WidgetsAvailabilityHelper.regWidgetVisibility(WidgetType.LORA_OSMAND_NODES, null)
		WidgetsAvailabilityHelper.regWidgetVisibility(WidgetType.LORA_OSMAND_MESSAGE, null)
		seedRightPanel()
	}

	override fun getId(): String = OsmAndCustomizationConstants.PLUGIN_LORA_OSMAND

	override fun getName(): String = app.getString(R.string.lora_osmand_plugin_name)

	override fun getDescription(linksEnabled: Boolean): CharSequence {
		return HtmlCompat.fromHtml(
			app.getString(R.string.lora_osmand_plugin_description),
			HtmlCompat.FROM_HTML_MODE_LEGACY,
		)
	}

	override fun getLogoResourceId(): Int = R.drawable.ic_action_signal

	override fun getAssetResourceImage(): Drawable? = app.uiUtilities.getIcon(R.drawable.osmand_development)

	override fun getSettingsScreenType(): SettingsScreenType = SettingsScreenType.LORA_OSMAND_SETTINGS

	override fun init(app: OsmandApplication, activity: Activity?): Boolean {
		restoreAnchor()
		app.locationProvider.addLocationListener(this)
		schedulePositionTick()
		if (usesMeshApp()) {
			startMeshBridge()
		}
		return true
	}

	override fun disable(app: OsmandApplication) {
		super.disable(app)
		app.locationProvider.removeLocationListener(this)
		app.uiHandler.removeCallbacks(positionTick)
		tak.stop()
		speaker.shutdown()
		session.disconnect()
		val mapView = app.osmandMap.mapView
		layer?.let { mapView.removeLayer(it) }
		layer = null
	}

	override fun registerLayers(context: Context, mapActivity: MapActivity?) {
		if (!isActive) {
			return
		}
		val mapView = app.osmandMap.mapView
		val existing = layer ?: LoraMapLayer(app, this).also { layer = it }
		if (!mapView.layers.contains(existing)) {
			mapView.addLayer(existing, 5.5f)
		}
	}

	override fun updateLayers(context: Context, mapActivity: MapActivity?) {
		if (isActive) {
			registerLayers(context, mapActivity)
		} else {
			layer?.let { app.osmandMap.mapView.removeLayer(it) }
			layer = null
		}
	}

	override fun mapActivityResume(activity: MapActivity) {
		mapActivity = activity
		registerLayers(activity, activity)
		if (usesMeshApp()) {
			if (session.isConnected()) {
				session.disconnect()
			}
			startMeshBridge()
		} else if (AUTO_CONNECT.get() && DEVICE_ADDRESS.get().isNotBlank() && !session.isConnected()) {
			session.connect(DEVICE_ADDRESS.get())
		}
	}

	override fun mapActivityPause(activity: MapActivity) {
		if (mapActivity === activity) {
			mapActivity = null
		}
	}

	override fun createWidgets(
		mapActivity: MapActivity,
		widgetsInfos: MutableList<MapWidgetInfo>,
		appMode: ApplicationMode,
		layoutMode: ScreenLayoutMode?,
	) {
		val creator = WidgetInfoCreator(app, appMode, layoutMode)
		for (type in WidgetType.values()) {
			if (type.group != net.osmand.plus.views.mapwidgets.WidgetGroup.LORA_OSMAND) {
				continue
			}
			val widget = createMapWidgetForParams(mapActivity, type) ?: continue
			val info = creator.createWidgetInfo(widget)
			if (info != null) {
				widgetsInfos.add(info)
			}
		}
	}

	override fun createMapWidgetForParams(
		mapActivity: MapActivity,
		widgetType: WidgetType,
		customId: String?,
		widgetsPanel: WidgetsPanel?,
	): MapWidget? {
		val field = when (widgetType) {
			WidgetType.LORA_OSMAND_BATTERY -> LoraTextWidget.Field.BATTERY
			WidgetType.LORA_OSMAND_NODES -> LoraTextWidget.Field.NODES
			WidgetType.LORA_OSMAND_MESSAGE -> LoraTextWidget.Field.MESSAGE
			else -> return null
		}
		return LoraTextWidget(mapActivity, widgetType, field, customId, widgetsPanel)
	}

	fun addUiListener(listener: Runnable) {
		uiListeners.add(listener)
	}

	fun removeUiListener(listener: Runnable) {
		uiListeners.remove(listener)
	}

	fun showSettings(activity: FragmentActivity, tab: LoraSheetTab) {
		LoraSettingsBottomSheet.showInstance(activity.supportFragmentManager, tab)
	}

	fun startScan(activity: Activity) {
		if (usesMeshApp()) {
			app.showToastMessage(R.string.lora_osmand_radio_kept)
			return
		}
		if (!net.osmand.plus.utils.AndroidUtils.hasBLEPermission(activity)) {
			net.osmand.plus.utils.AndroidUtils.requestBLEPermissions(activity)
			return
		}
		session.startScan(DEVICE_NAME.get())
	}

	fun connectFound(radio: FoundRadio) {
		if (usesMeshApp()) {
			app.showToastMessage(R.string.lora_osmand_radio_kept)
			return
		}
		DEVICE_ADDRESS.set(radio.address)
		DEVICE_NAME.set(radio.name)
		session.connect(radio.address)
		notifyUi()
	}

	fun disconnectRadio() {
		session.disconnect()
		appliedSchedule = null
	}

	fun usesMeshApp(): Boolean {
		return try {
			app.packageManager.getPackageInfo(LoraMessageListener.MESH_PACKAGE, 0)
			true
		} catch (_: PackageManager.NameNotFoundException) {
			false
		}
	}

	fun notificationAccess(): Boolean {
		val enabled = Settings.Secure.getString(app.contentResolver, "enabled_notification_listeners") ?: return false
		val name = ComponentName(app, LoraMessageListener::class.java).flattenToString()
		return enabled.contains(name)
	}

	fun sendChat(text: String, channelIndex: Int) {
		val body = text.trim()
		if (body.isEmpty()) {
			return
		}
		if (usesMeshApp()) {
			scope.launch(Dispatchers.IO) {
				val viaReply = LoraMeshInbox.reply(channelIndex, body)
				val sent = viaReply || (channelIndex == PRIMARY_CHANNEL && tak.sendGeoChat(body))
				app.uiHandler.post {
					if (sent) {
						echoText = body
						echoAt = System.currentTimeMillis()
						chat.add(LoraChatLine(echoAt, app.getString(R.string.lora_osmand_me), body, true, channelIndex))
						notifyUi()
					} else {
						app.showToastMessage(R.string.lora_osmand_send_failed)
					}
				}
			}
			return
		}
		scope.launch {
			val result = session.sendText(body, channelIndex)
			if (result.isSuccess) {
				chat.add(LoraChatLine(System.currentTimeMillis(), app.getString(R.string.lora_osmand_me), body, true, channelIndex))
				notifyUi()
			} else {
				app.showToastMessage(result.exceptionOrNull()?.message ?: app.getString(R.string.lora_osmand_send_failed))
			}
		}
	}

	fun createChannel(name: String) {
		scope.launch {
			val result = session.createChannel(name.trim())
			app.showToastMessage(result.fold(
				onSuccess = { app.getString(R.string.lora_osmand_channel_created, it) },
				onFailure = { it.message ?: app.getString(R.string.lora_osmand_send_failed) },
			))
			notifyUi()
		}
	}

	fun ownNodeNum(): Int? = session.ownNodeNum()

	fun batteryPercent(): Int? {
		val ownNum = session.ownNodeNum()
		val node = if (ownNum != null) nodes.firstOrNull { it.num == ownNum } else nodes.firstOrNull()
		return node?.batteryPercent()
	}

	fun onlineCount(): Int = nodes.count { it.isMeshOnline() }

	fun nodeShownOnMap(num: Int): Boolean = num !in hiddenNodes()

	private fun hiddenNodes(): MutableSet<Int> {
		return hiddenNodesPref.get().split(',').mapNotNull { it.toIntOrNull() }.toMutableSet()
	}

	fun setNodeShownOnMap(num: Int, shown: Boolean) {
		val hidden = hiddenNodes()
		if (shown) {
			hidden.remove(num)
		} else {
			hidden.add(num)
		}
		hiddenNodesPref.set(hidden.joinToString(","))
		refreshMap()
		notifyUi()
	}

	fun rssiOf(num: Int): Int? = nodeRssi[num]

	fun openNodeInMeshtastic(num: Int) {
		val intent = Intent(Intent.ACTION_VIEW, Uri.parse("meshtastic://meshtastic/nodes/$num")).apply {
			setPackage(LoraMessageListener.MESH_PACKAGE)
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		}
		try {
			app.startActivity(intent)
		} catch (_: ActivityNotFoundException) {
			app.showToastMessage(R.string.lora_osmand_node_open_failed)
		}
	}

	fun positionedNodes(): List<NodeInfo> {
		if (!SHOW_ON_MAP.get()) {
			return emptyList()
		}
		return nodes.filter { it.meshLatLon() != null && nodeShownOnMap(it.num) }
	}

	fun metersSinceSend(): Double? {
		val lat = lastSentLatPref.get().toDoubleOrNull() ?: return null
		val lon = lastSentLonPref.get().toDoubleOrNull() ?: return null
		val loc = app.locationProvider.lastKnownLocation ?: return null
		return metersBetween(lat, lon, loc.latitude, loc.longitude)
	}

	fun lastSentMs(): Long = lastSentMsPref.get()

	fun setPositionInterval(seconds: Int) {
		POS_INTERVAL_SEC.set(seconds.coerceAtLeast(0))
		if (lastSentMsPref.get() == 0L) {
			lastSentMsPref.set(System.currentTimeMillis())
		}
		appliedSchedule = null
		applySchedule()
		notifyUi()
	}

	fun setPositionOffset(meters: Int) {
		POS_OFFSET_M.set(meters.coerceAtLeast(0))
		captureAnchorIfNeeded()
		appliedSchedule = null
		applySchedule()
		notifyUi()
	}

	override fun updateLocation(location: net.osmand.Location?) {
		tickPosition()
	}

	override fun onStatus(text: String, connected: Boolean) {
		if (usesMeshApp()) {
			return
		}
		statusText = text
		this.connected = connected
		if (connected) {
			applySchedule()
		} else {
			appliedSchedule = null
		}
		refreshMap()
		notifyUi()
	}

	override fun onNodes(nodes: List<NodeInfo>) {
		if (usesMeshApp()) {
			return
		}
		this.nodes = nodes
		refreshMap()
		notifyUi()
	}

	override fun onChannels(channels: List<Channel>) {
		if (usesMeshApp()) {
			return
		}
		this.channels = channels
		notifyUi()
	}

	override fun onChat(line: LoraChatLine) {
		if (usesMeshApp()) {
			return
		}
		chat.add(line)
		notifyUi()
	}

	override fun onScan(devices: List<FoundRadio>) {
		foundRadios = devices
		notifyUi()
	}

	override fun onTakStatus(up: Boolean, detail: String) {
		app.uiHandler.post {
			connected = up
			statusText = if (up) {
				app.getString(R.string.lora_osmand_tak_up, bridgeNodes.size)
			} else {
				app.getString(R.string.lora_osmand_tak_waiting)
			}
			refreshMap()
			notifyUi()
		}
	}

	override fun onTakNode(update: TakNodeUpdate) {
		val previous = bridgeNodes[update.num]
		val previousMetrics = previous?.device_metrics
		val telemetry = update.telemetry
		val node = NodeInfo.Builder().apply {
			num = update.num
			user = User.Builder().apply {
				short_name = update.shortName
				long_name = update.longName
			}.build()
			position = Position.Builder().apply {
				latitude_i = (update.latitude * 1e7).toInt()
				longitude_i = (update.longitude * 1e7).toInt()
				val altitudeMeters = update.altitudeMeters ?: previous?.position?.altitude
				if (altitudeMeters != null) {
					altitude = altitudeMeters
				}
			}.build()
			val battery = telemetry.batteryPercent ?: previousMetrics?.battery_level
			val voltage = telemetry.voltage ?: previousMetrics?.voltage
			val channelUtil = telemetry.channelUtil ?: previousMetrics?.channel_utilization
			val airUtil = telemetry.airUtil ?: previousMetrics?.air_util_tx
			if (battery != null || voltage != null || channelUtil != null || airUtil != null) {
				device_metrics = DeviceMetrics.Builder().apply {
					if (battery != null) {
						battery_level = battery
					}
					if (voltage != null) {
						this.voltage = voltage
					}
					if (channelUtil != null) {
						channel_utilization = channelUtil
					}
					if (airUtil != null) {
						air_util_tx = airUtil
					}
				}.build()
			}
			telemetry.snr?.let { snr = it } ?: previous?.snr?.let { snr = it }
			last_heard = (System.currentTimeMillis() / 1000L).toInt()
		}.build()
		app.uiHandler.post {
			telemetry.rssi?.let { nodeRssi[update.num] = it }
			bridgeNodes[update.num] = node
			nodes = bridgeNodes.values.toList()
			if (connected) {
				statusText = app.getString(R.string.lora_osmand_tak_up, bridgeNodes.size)
			}
			refreshMap()
			notifyUi()
		}
	}

	override fun onTakChat(sender: String, text: String) {
		app.uiHandler.post { ingestChat(sender, text, PRIMARY_CHANNEL, true) }
	}

	fun noteChannel(index: Int, name: String?) {
		val label = name?.trim().orEmpty().ifBlank {
			if (index == PRIMARY_CHANNEL) app.getString(R.string.lora_osmand_long_fast) else "($index)"
		}
		val existing = channels.indexOfFirst { it.index == index }
		val channel = Channel.Builder().apply {
			this.index = index
			role = if (index == PRIMARY_CHANNEL) Channel.Role.PRIMARY else Channel.Role.SECONDARY
			settings = ChannelSettings.Builder().apply { this.name = label }.build()
		}.build()
		val next = channels.toMutableList()
		if (existing >= 0) {
			next[existing] = channel
		} else {
			next.add(channel)
		}
		channels = next.sortedBy { it.index }
		notifyUi()
	}

	fun ingestChat(sender: String, text: String, channel: Int, speak: Boolean) {
		if (text == echoText && System.currentTimeMillis() - echoAt < ECHO_MS) {
			return
		}
		val key = "$channel|$sender|$text"
		if (!heardChat.add(key)) {
			return
		}
		while (heardChat.size > 400) {
			heardChat.remove(heardChat.first())
		}
		chat.add(LoraChatLine(System.currentTimeMillis(), sender, text, false, channel))
		if (speak) {
			speaker.speak(sender, text)
		}
		notifyUi()
	}

	private fun schedulePositionTick() {
		app.uiHandler.removeCallbacks(positionTick)
		app.uiHandler.postDelayed(positionTick, 1000L)
	}

	private fun tickPosition() {
		schedulePositionTick()
		val interval = POS_INTERVAL_SEC.get()
		val offset = POS_OFFSET_M.get()
		if (interval <= 0 && offset <= 0) {
			return
		}
		captureAnchorIfNeeded()
		val loc = freshFix() ?: return
		val now = System.currentTimeMillis()
		val sinceSend = now - lastSentMsPref.get()
		val timerDue = interval > 0 && lastSentMsPref.get() > 0L && sinceSend >= interval * 1000L
		val moved = offset > 0 && distanceFromAnchor(loc) >= offset
		if (!timerDue && !moved && !pendingSend) {
			return
		}
		if (lastSentMsPref.get() > 0L && sinceSend < MIN_SEND_GAP_MS) {
			pendingSend = true
			return
		}
		pendingSend = false
		sendPosition(loc)
	}

	private fun sendPosition(loc: net.osmand.Location) {
		val alt = if (loc.hasAltitude()) loc.altitude.toInt() else null
		val timeSec = (loc.time / 1000L).toInt()
		scope.launch {
			val result = session.sendPhonePosition(loc.latitude, loc.longitude, alt, timeSec)
			if (result.isSuccess) {
				lastSentMsPref.set(System.currentTimeMillis())
				lastSentLatPref.set(loc.latitude.toString())
				lastSentLonPref.set(loc.longitude.toString())
				anchorLat = loc.latitude
				anchorLon = loc.longitude
				notifyUi()
			}
		}
	}

	private fun applySchedule() {
		val key = "${POS_INTERVAL_SEC.get()}:${POS_OFFSET_M.get()}"
		if (appliedSchedule == key || !session.isConnected()) {
			return
		}
		appliedSchedule = key
		scope.launch {
			val result = session.applyPositionSchedule(POS_INTERVAL_SEC.get(), POS_OFFSET_M.get())
			if (result.isFailure) {
				appliedSchedule = null
			}
		}
	}

	private fun captureAnchorIfNeeded() {
		if (anchorLat != null) {
			return
		}
		val loc = app.locationProvider.lastKnownLocation ?: return
		anchorLat = loc.latitude
		anchorLon = loc.longitude
	}

	private fun restoreAnchor() {
		anchorLat = lastSentLatPref.get().toDoubleOrNull()
		anchorLon = lastSentLonPref.get().toDoubleOrNull()
	}

	private fun distanceFromAnchor(loc: net.osmand.Location): Double {
		val lat = anchorLat ?: return 0.0
		val lon = anchorLon ?: return 0.0
		return metersBetween(lat, lon, loc.latitude, loc.longitude)
	}

	private fun freshFix(): net.osmand.Location? {
		val loc = app.locationProvider.lastKnownLocation ?: return null
		if (System.currentTimeMillis() - loc.time > FIX_MAX_AGE_MS) {
			return null
		}
		return loc
	}

	private fun refreshMap() {
		app.osmandMap.mapView.refreshMap()
	}

	private fun notifyUi() {
		for (listener in uiListeners) {
			listener.run()
		}
	}

	private fun startMeshBridge() {
		if (channels.none { it.index == PRIMARY_CHANNEL }) {
			noteChannel(PRIMARY_CHANNEL, app.getString(R.string.lora_osmand_long_fast))
		}
		if (!connected) {
			statusText = app.getString(R.string.lora_osmand_tak_waiting)
			notifyUi()
		}
		tak.start()
	}

	private fun seedRightPanel() {
		val ids = listOf(
			WidgetType.LORA_OSMAND_BATTERY.id,
			WidgetType.LORA_OSMAND_NODES.id,
			WidgetType.LORA_OSMAND_MESSAGE.id,
		)
		for (mode in ApplicationMode.allPossibleValues()) {
			for (layout in ScreenLayoutMode.entries) {
				val preference = WidgetsPanel.RIGHT.getOrderPreference(app.settings, layout)
				val pages = preference.getStringsListForProfile(mode)
				if (pages.isNullOrEmpty()) {
					continue
				}
				val updated = pages.map { page ->
					page.split(WidgetsPanel.WIDGET_SEPARATOR).filter { it.isNotBlank() }
				}.toMutableList()
				if (updated.isEmpty()) {
					continue
				}
				val page = updated[0].toMutableList()
				var changed = false
				for (id in ids) {
					if (updated.none { it.contains(id) }) {
						page.add(id)
						changed = true
					}
				}
				if (changed) {
					updated[0] = page
					WidgetsPanel.RIGHT.setWidgetsOrder(mode, updated, app.settings, layout)
				}
			}
		}
	}

	companion object {
		const val PREF_AUTO = "lora_osmand_autoconnect"
		const val PREF_SHOW_MAP = "lora_osmand_show_map"
		const val PREF_ADDRESS = "lora_osmand_device_address"
		const val PREF_NAME = "lora_osmand_device_name"
		const val PREF_INTERVAL = "lora_osmand_pos_interval_sec"
		const val PREF_OFFSET = "lora_osmand_pos_offset_m"
		private const val PREF_LAST_MS = "lora_osmand_pos_last_ms"
		private const val PREF_LAST_LAT = "lora_osmand_pos_last_lat"
		private const val PREF_LAST_LON = "lora_osmand_pos_last_lon"
		private const val PREF_HIDDEN_NODES = "lora_osmand_hidden_nodes"
		private const val PRIMARY_CHANNEL = 0
		private const val ECHO_MS = 8_000L
		private const val MIN_SEND_GAP_MS = 15_000L
		private const val FIX_MAX_AGE_MS = 30_000L

		val INTERVAL_CHOICES = intArrayOf(0, 30, 60, 120, 300, 900, 1800)
		val OFFSET_CHOICES = intArrayOf(0, 50, 100, 250, 500, 1000)
	}
}
