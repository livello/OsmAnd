package net.osmand.plus.plugins.loraosmand

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.content.ContextCompat
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
	val MARKER_STYLE: CommonPreference<String> =
		registerStringPreference(PREF_MARKER_STYLE, LoraMarkerStyle.CIRCLE.id).makeGlobal().makeShared()
	val MARKER_SIZE: CommonPreference<Int> =
		registerIntPreference(PREF_MARKER_SIZE, 100).makeGlobal().makeShared()
	val MARKER_FONT: CommonPreference<Int> =
		registerIntPreference(PREF_MARKER_FONT, 11).makeGlobal().makeShared()
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
	private val recordedNodesPref: CommonPreference<String> =
		registerStringPreference(PREF_RECORDED_NODES, "").makeGlobal().makeShared()
	private val shownTracksPref: CommonPreference<String> =
		registerStringPreference(PREF_SHOWN_TRACKS, "").makeGlobal().makeShared()
	private val settingsGroupsPref: CommonPreference<String> =
		registerStringPreference(PREF_SETTINGS_GROUPS, DEFAULT_GROUPS).makeGlobal().makeShared()
	private val messagesGroupSeeded: CommonPreference<Boolean> =
		registerBooleanPreference(PREF_MESSAGES_GROUP_SEEDED, false).makeGlobal()
	val SPEAK_CHAT: CommonPreference<Boolean> =
		registerBooleanPreference(PREF_SPEAK, true).makeGlobal().makeShared()
	val SPEAK_RECENT: CommonPreference<Int> =
		registerIntPreference(PREF_SPEAK_RECENT, 5).makeGlobal().makeShared()
	val MESSAGE_STYLE: CommonPreference<String> =
		registerStringPreference(PREF_MESSAGE_STYLE, LoraMessageStyle.LORA.id).makeGlobal().makeShared()
	val SPEAK_NAME: CommonPreference<Boolean> =
		registerBooleanPreference(PREF_SPEAK_NAME, true).makeGlobal().makeShared()
	val SPEAK_SHORT: CommonPreference<Boolean> =
		registerBooleanPreference(PREF_SPEAK_SHORT, false).makeGlobal().makeShared()
	val SPEAK_PLAIN: CommonPreference<Boolean> =
		registerBooleanPreference(PREF_SPEAK_PLAIN, false).makeGlobal().makeShared()
	val TRACK_INTERVAL_SEC: CommonPreference<Int> =
		registerIntPreference(PREF_TRACK_INTERVAL, 60).makeGlobal().makeShared()
	val TRACK_OFFSET_M: CommonPreference<Int> =
		registerIntPreference(PREF_TRACK_OFFSET, 50).makeGlobal().makeShared()
	private val readIncomingPref: CommonPreference<Int> =
		registerIntPreference(PREF_READ_INCOMING, 0).makeGlobal()
	private val trackFilesPref: CommonPreference<String> =
		registerStringPreference(PREF_TRACK_FILES, "").makeGlobal()

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private val session = LoraSession(app, this)
	private val tak = LoraTakClient(app, this)
	private val speaker = LoraSpeaker(app)
	private val tracks = LoraNodeTracks(app, this)
	private val bridgeNodes = LinkedHashMap<Int, NodeInfo>()
	private val nodeRssi = HashMap<Int, Int>()
	private val heardChat = LinkedHashSet<String>()
	private var echoText: String = ""
	private var echoAt: Long = 0L
	private var readIncoming: Int = 0
	private val uiListeners = CopyOnWriteArrayList<Runnable>()

	@Volatile var statusText: String = ""
	@Volatile var connected: Boolean = false
	@Volatile var nodes: List<NodeInfo> = emptyList()
	@Volatile var channels: List<Channel> = emptyList()
	@Volatile var foundRadios: List<FoundRadio> = emptyList()
	@Volatile var lastMeshNoticeMs: Long = 0L
	val chat = ArrayList<LoraChatLine>()

	private var mapActivity: MapActivity? = null
	private var layer: LoraMapLayer? = null
	private var anchorLat: Double? = null
	private var anchorLon: Double? = null
	private var pendingSend: Boolean = false
	private var presentRegistered: Boolean = false
	private var appliedSchedule: String? = null
	private val presentReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context?, intent: Intent?) {
			wakeMeshLink()
		}
	}
	private val positionTick = Runnable { tickPosition() }

	init {
		WidgetsAvailabilityHelper.regWidgetVisibility(WidgetType.LORA_OSMAND_BATTERY, null)
		WidgetsAvailabilityHelper.regWidgetVisibility(WidgetType.LORA_OSMAND_NODES, null)
		WidgetsAvailabilityHelper.regWidgetVisibility(WidgetType.LORA_OSMAND_MESSAGE, null)
		WidgetsAvailabilityHelper.regWidgetVisibility(WidgetType.LORA_OSMAND_SPEAK, null)
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
		loadChat()
		app.locationProvider.addLocationListener(this)
		schedulePositionTick()
		if (usesMeshApp()) {
			registerUnlock()
			wakeMeshLink()
		}
		return true
	}

	override fun disable(app: OsmandApplication) {
		super.disable(app)
		app.locationProvider.removeLocationListener(this)
		app.uiHandler.removeCallbacks(positionTick)
		unregisterUnlock()
		cancelWake()
		LoraBridgeService.stop(app)
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
		if (mapActivity != null) {
			existing.setMapActivity(mapActivity)
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
			wakeMeshLink()
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
			WidgetType.LORA_OSMAND_SPEAK -> LoraTextWidget.Field.SPEAK
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
		val stamp = System.currentTimeMillis()
		echoText = body
		echoAt = stamp
		chat.add(LoraChatLine(stamp, app.getString(R.string.lora_osmand_me), body, true, channelIndex, LoraDelivery.SENDING))
		trimChat()
		notifyUi()
		if (usesMeshApp()) {
			scope.launch(Dispatchers.IO) {
				val viaReply = LoraMeshInbox.reply(app, channelIndex, body)
				val viaTak = !viaReply && channelIndex == PRIMARY_CHANNEL && tak.sendGeoChat(body)
				val sent = viaReply || viaTak
				Log.i(TAG, "send ch=$channelIndex reply=$viaReply tak=$viaTak text=${body.take(48)}")
				app.uiHandler.post {
					markDelivery(stamp, if (sent) LoraDelivery.SENT else LoraDelivery.FAILED)
					if (!sent) {
						app.showToastMessage(R.string.lora_osmand_send_failed)
					}
				}
			}
			return
		}
		scope.launch {
			val result = session.sendText(body, channelIndex)
			markDelivery(stamp, if (result.isSuccess) LoraDelivery.SENT else LoraDelivery.FAILED)
			if (result.isFailure) {
				app.showToastMessage(result.exceptionOrNull()?.message ?: app.getString(R.string.lora_osmand_send_failed))
			}
			notifyUi()
		}
	}

	fun speakLine(sender: String, text: String) {
		speaker.speakPhrase(spokenPhrase(sender, text))
	}

	fun speakRecent() {
		if (chat.isEmpty()) {
			return
		}
		val count = SPEAK_RECENT.get().coerceIn(1, 20)
		for (line in chat.takeLast(count)) {
			speakLine(line.fromName, line.text)
		}
	}

	fun messageStyle(): LoraMessageStyle = LoraMessageStyle.from(MESSAGE_STYLE.get())

	private fun spokenPhrase(sender: String, text: String): String {
		val body = speechText(text)
		if (!SPEAK_NAME.get()) {
			return body
		}
		val node = nodeMatching(sender)
		val rawName = if (SPEAK_SHORT.get() && node != null) node.shortLabel() else sender
		val name = speechText(rawName)
		if (name.isBlank()) {
			return body
		}
		return "$name. $body"
	}

	private fun speechText(raw: String): String {
		val trimmed = raw.trim()
		if (!SPEAK_PLAIN.get()) {
			return trimmed
		}
		return trimmed.replace(Regex("[_\\-/*]+"), " ").replace(Regex("\\s+"), " ").trim()
	}

	fun nodeMatching(sender: String): NodeInfo? {
		return nodes.firstOrNull { LoraMarkerHit.namesMatch(sender, it.shortLabel(), it.longLabel()) }
	}

	/** Centers the map on a node that already has coordinates. Returns false when it does not. */
	fun focusNode(node: NodeInfo): Boolean {
		val latLng = node.meshLatLon() ?: return false
		app.mapViewTrackingUtilities.setMapLinkedToLocation(false)
		app.osmandMap.mapView.setLatLon(latLng.latitude, latLng.longitude)
		return true
	}

	private fun markDelivery(stamp: Long, delivery: LoraDelivery) {
		val index = chat.indexOfLast { it.timeMs == stamp && it.outgoing }
		if (index >= 0) {
			chat[index] = chat[index].copy(delivery = delivery)
			saveChat()
			notifyUi()
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

	fun isTrackRecording(num: Int): Boolean = num in nodeSet(recordedNodesPref)

	fun setTrackRecording(num: Int, record: Boolean) {
		val nums = nodeSet(recordedNodesPref)
		val was = num in nums
		if (record) nums.add(num) else nums.remove(num)
		recordedNodesPref.set(nums.joinToString(","))
		if (record && !was) {
			nodes.firstOrNull { it.num == num }?.let {
				tracks.startSession(it)
				tracks.consider(it)
			}
		}
		notifyUi()
	}

	fun isTrackShown(num: Int): Boolean = num in nodeSet(shownTracksPref)

	fun setTrackShown(num: Int, shown: Boolean) {
		val nums = nodeSet(shownTracksPref)
		if (shown) nums.add(num) else nums.remove(num)
		shownTracksPref.set(nums.joinToString(","))
		tracks.refreshVisibility(num)
		if (shown && findTrackFile(num) == null) {
			app.showToastMessage(R.string.lora_osmand_track_empty)
		}
		notifyUi()
	}

	fun settingsGroups(): MutableSet<String> {
		val raw = settingsGroupsPref.get()
		if (raw == "-") {
			messagesGroupSeeded.set(true)
			return mutableSetOf()
		}
		val keys = if (raw.isBlank()) {
			DEFAULT_GROUPS.split(',').toMutableSet()
		} else {
			raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
		}
		if (!messagesGroupSeeded.get()) {
			keys.add("lora_cat_messages")
			messagesGroupSeeded.set(true)
			settingsGroupsPref.set(keys.sorted().joinToString(","))
		}
		return keys
	}

	fun saveSettingsGroups(keys: Set<String>) {
		settingsGroupsPref.set(if (keys.isEmpty()) "-" else keys.sorted().joinToString(","))
	}

	private fun nodeSet(pref: CommonPreference<String>): MutableSet<Int> {
		return pref.get().split(',').mapNotNull { it.toIntOrNull() }.toMutableSet()
	}

	fun trackFileName(num: Int): String? = trackFiles()[num]

	fun trackFiles(): Map<Int, String> {
		val raw = trackFilesPref.get()
		if (raw.isBlank()) {
			return emptyMap()
		}
		val map = LinkedHashMap<Int, String>()
		for (part in raw.split('|')) {
			val split = part.indexOf('=')
			if (split <= 0) {
				continue
			}
			val num = part.substring(0, split).toIntOrNull() ?: continue
			val name = part.substring(split + 1)
			if (name.isNotBlank()) {
				map[num] = name
			}
		}
		return map
	}

	fun rememberTrackFile(num: Int, name: String) {
		val map = trackFiles().toMutableMap()
		map[num] = name
		trackFilesPref.set(map.entries.joinToString("|") { "${it.key}=${it.value}" })
	}

	private fun findTrackFile(num: Int): java.io.File? {
		val name = trackFileName(num) ?: return null
		val folder = java.io.File(app.getAppPath(net.osmand.IndexConstants.GPX_RECORDED_INDEX_DIR), LoraNodeTracks.DIR)
		val file = java.io.File(folder, name)
		return file.takeIf { it.isFile }
	}

	fun rssiOf(num: Int): Int? = nodeRssi[num]

	fun phoneLocation(): net.osmand.Location? = app.locationProvider.lastKnownLocation

	fun markerStyle(): LoraMarkerStyle = LoraMarkerStyle.from(MARKER_STYLE.get())

	fun setMarkerStyle(style: LoraMarkerStyle) {
		MARKER_STYLE.set(style.id)
		tracks.recolor(nodes, style, ownNodeNum())
		refreshMap()
		notifyUi()
	}

	fun setMarkerSize(percent: Int) {
		MARKER_SIZE.set(percent.coerceIn(50, 200))
		refreshMap()
		notifyUi()
	}

	fun setMarkerFont(sp: Int) {
		MARKER_FONT.set(sp.coerceIn(8, 32))
		refreshMap()
		notifyUi()
	}

	fun chatTotal(): Int = chat.size

	fun chatUnread(): Int = (chat.count { !it.outgoing } - readIncoming).coerceAtLeast(0)

	fun markChatRead() {
		val incoming = chat.count { !it.outgoing }
		if (incoming == readIncoming) {
			return
		}
		readIncoming = incoming
		readIncomingPref.set(incoming)
		notifyUi()
	}

	fun pullMeshMessages() {
		LoraMessageListener.pullActive()
	}

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
		nodes.forEach { tracks.consider(it) }
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
			tracks.consider(node)
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
		if (Looper.myLooper() != Looper.getMainLooper()) {
			app.uiHandler.post { noteChannel(index, name) }
			return
		}
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

	fun noteMeshNotice() {
		lastMeshNoticeMs = System.currentTimeMillis()
	}

	fun wakeMeshLink() {
		if (!isActive || !usesMeshApp()) {
			return
		}
		rebindListener()
		scheduleWake()
		LoraBridgeService.start(app)
		pullMeshMessages()
	}

	fun onBridgeStarted() {
		if (!isActive || !usesMeshApp()) {
			return
		}
		scheduleWake()
		ensureMeshLink()
		pullMeshMessages()
	}

	fun scheduleRebind() {
		app.uiHandler.postDelayed({
			if (!LoraMessageListener.isConnected()) {
				requestRebind()
			}
		}, 3_000L)
	}

	fun onListenerReady() {
		if (!isActive || !usesMeshApp()) {
			return
		}
		scheduleWake()
		ensureMeshLink()
		LoraBridgeService.start(app)
	}

	fun ensureMeshLink() {
		startMeshBridge()
	}

	private fun rebindListener() {
		if (!notificationAccess() || LoraMessageListener.isConnected()) {
			return
		}
		val component = ComponentName(app, LoraMessageListener::class.java)
		try {
			app.packageManager.setComponentEnabledSetting(
				component,
				PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
				PackageManager.DONT_KILL_APP,
			)
		} catch (error: Exception) {
			Log.w(TAG, "listener toggle failed", error)
		} finally {
			try {
				app.packageManager.setComponentEnabledSetting(
					component,
					PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
					PackageManager.DONT_KILL_APP,
				)
			} catch (error: Exception) {
				Log.w(TAG, "listener enable failed", error)
			}
		}
		requestRebind()
	}

	private fun requestRebind() {
		if (!notificationAccess()) {
			return
		}
		try {
			NotificationListenerService.requestRebind(ComponentName(app, LoraMessageListener::class.java))
			Log.i(TAG, "requestRebind")
		} catch (error: Exception) {
			Log.w(TAG, "requestRebind failed", error)
		}
	}

	private fun registerUnlock() {
		if (presentRegistered) {
			return
		}
		try {
			ContextCompat.registerReceiver(
				app,
				presentReceiver,
				IntentFilter(Intent.ACTION_USER_PRESENT),
				ContextCompat.RECEIVER_NOT_EXPORTED,
			)
			presentRegistered = true
		} catch (error: Exception) {
			Log.w(TAG, "unlock receiver failed", error)
		}
	}

	private fun unregisterUnlock() {
		if (!presentRegistered) {
			return
		}
		presentRegistered = false
		try {
			app.unregisterReceiver(presentReceiver)
		} catch (_: Exception) {
		}
	}

	private fun scheduleWake() {
		val alarm = app.getSystemService(AlarmManager::class.java) ?: return
		val trigger = System.currentTimeMillis() + WAKE_MS
		try {
			alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, wakePending())
		} catch (error: Exception) {
			Log.w(TAG, "wake alarm failed", error)
		}
	}

	private fun cancelWake() {
		val alarm = app.getSystemService(AlarmManager::class.java) ?: return
		alarm.cancel(wakePending())
	}

	private fun wakePending(): PendingIntent {
		val intent = Intent(app, LoraBridgeReceiver::class.java).setAction(LoraBridgeReceiver.ACTION_WAKE)
		return PendingIntent.getBroadcast(
			app,
			WAKE_REQUEST,
			intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
		)
	}

	fun ingestChat(sender: String, text: String, channel: Int, speak: Boolean, timeMs: Long = System.currentTimeMillis()) {
		if (Looper.myLooper() != Looper.getMainLooper()) {
			app.uiHandler.post { ingestChat(sender, text, channel, speak, timeMs) }
			return
		}
		if (text == echoText && System.currentTimeMillis() - echoAt < ECHO_MS) {
			return
		}
		val key = "$channel|$sender|$text"
		if (!heardChat.add(key)) {
			return
		}
		Log.i(TAG, "stored ch=$channel speak=$speak from=$sender ${text.take(48)}")
		while (heardChat.size > 400) {
			heardChat.remove(heardChat.first())
		}
		chat.add(LoraChatLine(timeMs, sender, text, false, channel))
		chat.sortBy { it.timeMs }
		trimChat()
		saveChat()
		if (speak && SPEAK_CHAT.get()) {
			speaker.speak(sender, text)
		}
		notifyUi()
	}

	private fun loadChat() {
		val file = chatFile()
		if (!file.exists()) {
			readIncoming = readIncomingPref.get()
			return
		}
		try {
			val array = org.json.JSONArray(file.readText())
			for (i in 0 until array.length()) {
				val item = array.getJSONObject(i)
				val line = LoraChatLine(
					timeMs = item.optLong("t"),
					fromName = item.optString("from"),
					text = item.optString("text"),
					outgoing = item.optBoolean("out"),
					channel = item.optInt("ch"),
					delivery = runCatching { LoraDelivery.valueOf(item.optString("st")) }.getOrDefault(LoraDelivery.INCOMING),
				)
				if (line.text.isBlank()) {
					continue
				}
				heardChat.add("${line.channel}|${line.fromName}|${line.text}")
				chat.add(line)
			}
			chat.sortBy { it.timeMs }
		} catch (_: Exception) {
		}
		readIncoming = readIncomingPref.get().coerceAtMost(chat.count { !it.outgoing })
	}

	private fun saveChat() {
		val array = org.json.JSONArray()
		for (line in chat) {
			array.put(org.json.JSONObject().apply {
				put("t", line.timeMs)
				put("from", line.fromName)
				put("text", line.text)
				put("out", line.outgoing)
				put("ch", line.channel)
				put("st", line.delivery.name)
			})
		}
		try {
			chatFile().writeText(array.toString())
		} catch (_: Exception) {
		}
	}

	private fun chatFile(): java.io.File = java.io.File(app.filesDir, "lora-osmand-chat.json")

	private fun trimChat() {
		while (chat.size > 200) {
			chat.removeAt(0)
		}
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
		refreshMap()
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
			WidgetType.LORA_OSMAND_SPEAK.id,
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
		const val PREF_MARKER_STYLE = "lora_osmand_marker_style"
		const val PREF_MARKER_SIZE = "lora_osmand_marker_size"
		const val PREF_MARKER_FONT = "lora_osmand_marker_font"
		const val PREF_ADDRESS = "lora_osmand_device_address"
		const val PREF_NAME = "lora_osmand_device_name"
		const val PREF_INTERVAL = "lora_osmand_pos_interval_sec"
		const val PREF_OFFSET = "lora_osmand_pos_offset_m"
		private const val PREF_LAST_MS = "lora_osmand_pos_last_ms"
		private const val PREF_LAST_LAT = "lora_osmand_pos_last_lat"
		private const val PREF_LAST_LON = "lora_osmand_pos_last_lon"
		private const val PREF_HIDDEN_NODES = "lora_osmand_hidden_nodes"
		private const val PREF_RECORDED_NODES = "lora_osmand_recorded_nodes"
		private const val PREF_SHOWN_TRACKS = "lora_osmand_shown_tracks"
		private const val PREF_SETTINGS_GROUPS = "lora_osmand_settings_groups"
		private const val PREF_MESSAGES_GROUP_SEEDED = "lora_osmand_messages_group_seeded"
		private const val PREF_SPEAK = "lora_osmand_speak_chat"
		private const val PREF_SPEAK_RECENT = "lora_osmand_speak_recent"
		private const val PREF_MESSAGE_STYLE = "lora_osmand_message_style"
		private const val PREF_SPEAK_NAME = "lora_osmand_speak_name"
		private const val PREF_SPEAK_SHORT = "lora_osmand_speak_short"
		private const val PREF_SPEAK_PLAIN = "lora_osmand_speak_plain"
		private const val PREF_TRACK_INTERVAL = "lora_osmand_track_interval_sec"
		private const val PREF_TRACK_OFFSET = "lora_osmand_track_offset_m"
		private const val PREF_READ_INCOMING = "lora_osmand_chat_read"
		private const val PREF_TRACK_FILES = "lora_osmand_track_files"
		private const val DEFAULT_GROUPS = "lora_cat_link,lora_cat_map,lora_cat_me,lora_cat_tracks,lora_cat_messages"
		private const val WAKE_MS = 15L * 60L * 1000L
		private const val WAKE_REQUEST = 8768
		private const val TAG = "LoraOsmAnd"
		private const val PRIMARY_CHANNEL = 0
		private const val ECHO_MS = 8_000L
		private const val MIN_SEND_GAP_MS = 15_000L
		private const val FIX_MAX_AGE_MS = 30_000L

		val INTERVAL_CHOICES = intArrayOf(0, 30, 60, 120, 300, 900, 1800)
		val OFFSET_CHOICES = intArrayOf(0, 50, 100, 250, 500, 1000)
		val MARKER_SIZE_CHOICES = intArrayOf(50, 75, 100, 125, 150, 200)
		val MARKER_FONT_CHOICES = intArrayOf(8, 10, 11, 12, 14, 18, 24)
		val SPEAK_RECENT_CHOICES = intArrayOf(1, 3, 5, 10, 20)
	}
}
