package net.osmand.plus.plugins.loraosmand

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import net.osmand.Location
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.graphics.Typeface
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.fragment.app.FragmentManager
import net.osmand.plus.BuildConfig
import net.osmand.plus.R
import net.osmand.plus.base.MenuBottomSheetDialogFragment
import net.osmand.plus.base.bottomsheetmenu.BaseBottomSheetItem
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.utils.AndroidUtils
import org.meshtastic.proto.NodeInfo
import java.util.Locale
import kotlin.math.roundToInt

class LoraSettingsBottomSheet : MenuBottomSheetDialogFragment() {

	companion object {
		const val TAG = "LoraSettingsBottomSheet"
		private const val ARG_TAB = "tab"
		private const val SETTINGS_TAG = "lora_osmand_settings_embedded"

		fun showInstance(fragmentManager: FragmentManager, tab: LoraSheetTab) {
			val existing = fragmentManager.findFragmentByTag(TAG) as? LoraSettingsBottomSheet
			if (existing != null && existing.isAdded) {
				existing.showTab(tab)
				return
			}
			if (!AndroidUtils.isFragmentCanBeAdded(fragmentManager, TAG)) {
				return
			}
			LoraSettingsBottomSheet().apply {
				arguments = Bundle().apply { putString(ARG_TAB, tab.name) }
			}.show(fragmentManager, TAG)
		}
	}

	private val plugin = PluginsHelper.requirePlugin(LoraOsmAndPlugin::class.java)
	private var currentTab = LoraSheetTab.SETTINGS
	private var recognizer: SpeechRecognizer? = null
	private val channelIndexes = ArrayList<Int>()
	private var onlineOnly = false
	private var visibleOnly = false
	private val refresh = Runnable { if (view != null) bindLive() }

	private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		if (granted) {
			startDictation()
		} else {
			app.showToastMessage(R.string.lora_osmand_mic_permission)
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		usedOnMap = true
		currentTab = LoraSheetTab.from(arguments?.getString(ARG_TAB))
	}

	override fun createMenuItems(savedInstanceState: Bundle?) {
		val host = inflate(R.layout.lora_osmand_settings_bottom_sheet)
		val activity = requireActivity()
		val height = AndroidUtils.getScreenHeight(activity) - AndroidUtils.getStatusBarHeight(activity) -
			AndroidUtils.dpToPx(activity, 8f)
		host.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
		items.add(BaseBottomSheetItem.Builder().setCustomView(host).create())
	}

	override fun setupHeightAndBackground(mainView: View?, sysBars: Insets) {
		super.setupHeightAndBackground(mainView, sysBars)
		val activity = activity ?: return
		if (mainView == null) {
			return
		}
		val available = AndroidUtils.getScreenHeight(activity) - sysBars.top - sysBars.bottom -
			AndroidUtils.dpToPx(activity, 8f)
		itemsContainer?.layoutParams?.height = available
		itemsContainer?.requestLayout()
		mainView.findViewById<View>(R.id.lora_osmand_settings_host)?.let {
			it.layoutParams.height = available
			it.requestLayout()
		}
	}

	override fun useScrollableItemsContainer(): Boolean = false

	override fun hideButtonsContainer(): Boolean = true

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		view.findViewById<View>(R.id.tab_settings).setOnClickListener { showTab(LoraSheetTab.SETTINGS) }
		view.findViewById<View>(R.id.tab_nodes).setOnClickListener { showTab(LoraSheetTab.NODES) }
		view.findViewById<View>(R.id.tab_messages).setOnClickListener { showTab(LoraSheetTab.MESSAGES) }
		view.findViewById<View>(R.id.tab_about).setOnClickListener { showTab(LoraSheetTab.ABOUT) }
		view.findViewById<View>(R.id.lora_osmand_filter_online).setOnClickListener {
			onlineOnly = !onlineOnly
			bindLive()
		}
		view.findViewById<View>(R.id.lora_osmand_filter_visible).setOnClickListener {
			visibleOnly = !visibleOnly
			bindLive()
		}
		view.findViewById<TextView>(R.id.lora_osmand_about_text).text =
			getString(R.string.lora_osmand_about, BuildConfig.VERSION_NAME)
		view.findViewById<View>(R.id.lora_osmand_channel_add).setOnClickListener { promptChannel() }
		view.findViewById<ImageButton>(R.id.lora_osmand_mic).setOnClickListener { onMic() }
		view.findViewById<ImageButton>(R.id.lora_osmand_send).setOnClickListener { sendComposed() }
		view.findViewById<EditText>(R.id.lora_osmand_compose).setOnEditorActionListener { _, _, _ ->
			sendComposed()
			true
		}
		if (childFragmentManager.findFragmentByTag(SETTINGS_TAG) == null) {
			val fragment = LoraSettingsFragment()
			fragment.arguments = Bundle().apply { putBoolean(LoraSettingsFragment.EMBEDDED_KEY, true) }
			childFragmentManager.beginTransaction()
				.replace(R.id.lora_osmand_settings_container, fragment, SETTINGS_TAG)
				.commitNowAllowingStateLoss()
		}
		showTab(currentTab)
	}

	override fun onResume() {
		super.onResume()
		plugin.addUiListener(refresh)
		bindLive()
	}

	override fun onPause() {
		plugin.removeUiListener(refresh)
		stopRecognizer()
		super.onPause()
	}

	fun showTab(tab: LoraSheetTab) {
		currentTab = tab
		val root = view ?: return
		root.findViewById<View>(R.id.lora_osmand_settings_container).visibility =
			if (tab == LoraSheetTab.SETTINGS) View.VISIBLE else View.GONE
		root.findViewById<View>(R.id.lora_osmand_nodes_page).visibility =
			if (tab == LoraSheetTab.NODES) View.VISIBLE else View.GONE
		root.findViewById<View>(R.id.lora_osmand_messages).visibility =
			if (tab == LoraSheetTab.MESSAGES) View.VISIBLE else View.GONE
		root.findViewById<View>(R.id.lora_osmand_about).visibility =
			if (tab == LoraSheetTab.ABOUT) View.VISIBLE else View.GONE
		bindLive()
	}

	private fun bindLive() {
		val root = view ?: return
		if (currentTab == LoraSheetTab.NODES) {
			bindNodes(root)
		}
		if (currentTab == LoraSheetTab.MESSAGES) {
			bindChannels(root.findViewById(R.id.lora_osmand_channel))
			val chat = root.findViewById<TextView>(R.id.lora_osmand_chat)
			chat.text = plugin.chat.joinToString("\n") { line ->
				val mark = if (line.outgoing) "→" else "←"
				"$mark ${line.fromName}: ${line.text}"
			}
		}
	}

	private fun bindChannels(spinner: Spinner) {
		val usable = LoraSession.usableChannels(plugin.channels)
		val labels = ArrayList<String>()
		val indexes = ArrayList<Int>()
		if (usable.isEmpty()) {
			labels.add(getString(R.string.lora_osmand_primary_channel))
			indexes.add(0)
		} else {
			for (channel in usable) {
				labels.add(LoraSession.channelLabel(channel))
				indexes.add(channel.index)
			}
		}
		val previous = channelIndexes.getOrNull(spinner.selectedItemPosition)
		if (indexes == channelIndexes && spinner.adapter != null) {
			return
		}
		channelIndexes.clear()
		channelIndexes.addAll(indexes)
		spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
		val restore = indexes.indexOf(previous)
		if (restore >= 0) {
			spinner.setSelection(restore)
		}
	}

	private fun selectedChannel(): Int {
		val spinner = view?.findViewById<Spinner>(R.id.lora_osmand_channel) ?: return 0
		return channelIndexes.getOrNull(spinner.selectedItemPosition) ?: 0
	}

	private fun sendComposed() {
		val field = view?.findViewById<EditText>(R.id.lora_osmand_compose) ?: return
		val text = field.text?.toString().orEmpty()
		if (text.isBlank()) {
			return
		}
		plugin.sendChat(text, selectedChannel())
		field.setText("")
	}

	private fun promptChannel() {
		val input = EditText(requireContext())
		input.hint = getString(R.string.lora_osmand_channel_name)
		AlertDialog.Builder(requireContext())
			.setTitle(R.string.lora_osmand_channel_create)
			.setView(input)
			.setPositiveButton(R.string.shared_string_add) { _, _ ->
				plugin.createChannel(input.text?.toString().orEmpty())
			}
			.setNegativeButton(R.string.shared_string_cancel, null)
			.show()
	}

	private fun onMic() {
		val ctx = context ?: return
		if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
			audioPermission.launch(Manifest.permission.RECORD_AUDIO)
			return
		}
		startDictation()
	}

	private fun startDictation() {
		val ctx = context ?: return
		val onDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
		if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(ctx)) {
			app.showToastMessage(R.string.lora_osmand_offline_speech_missing)
			return
		}
		beginRecognizer()
	}

	private fun beginRecognizer() {
		val ctx = context ?: return
		stopRecognizer()
		val speech = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
			SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
		} else {
			SpeechRecognizer.createSpeechRecognizer(ctx)
		}
		recognizer = speech
		speech.setRecognitionListener(object : android.speech.RecognitionListener {
			override fun onReadyForSpeech(params: Bundle?) {}
			override fun onBeginningOfSpeech() {}
			override fun onRmsChanged(rmsdB: Float) {}
			override fun onBufferReceived(buffer: ByteArray?) {}
			override fun onEndOfSpeech() {}
			override fun onError(error: Int) {
				if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
					error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
					error == SpeechRecognizer.ERROR_SERVER ||
					error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
					error == SpeechRecognizer.ERROR_NETWORK ||
					error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
				) {
					app.showToastMessage(R.string.lora_osmand_offline_speech_missing)
				}
			}

			override fun onResults(results: Bundle?) {
				applyTranscript(results)
			}

			override fun onPartialResults(partialResults: Bundle?) {
				applyTranscript(partialResults)
			}

			override fun onEvent(eventType: Int, params: Bundle?) {}
		})
		speech.startListening(recognitionIntent())
	}

	private fun applyTranscript(results: Bundle?) {
		val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
		view?.findViewById<EditText>(R.id.lora_osmand_compose)?.setText(text)
	}

	private fun recognitionIntent(): Intent {
		return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
			putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
			putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
			putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
			putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
		}
	}

	private fun stopRecognizer() {
		recognizer?.destroy()
		recognizer = null
	}

	private fun bindNodes(root: View) {
		styleFilter(root.findViewById(R.id.lora_osmand_filter_online), onlineOnly)
		styleFilter(root.findViewById(R.id.lora_osmand_filter_visible), visibleOnly)
		val scroll = root.findViewById<ScrollView>(R.id.lora_osmand_nodes_scroll)
		val saved = scroll.scrollY
		val list = root.findViewById<LinearLayout>(R.id.lora_osmand_nodes_list)
		list.removeAllViews()
		val now = (System.currentTimeMillis() / 1000L).toInt()
		val phone = app.locationProvider.lastKnownLocation
		val nodes = plugin.nodes
			.filter { !onlineOnly || it.isMeshOnline(now) }
			.filter { !visibleOnly || plugin.nodeShownOnMap(it.num) }
			.sortedWith(compareByDescending<NodeInfo> { it.isMeshOnline(now) }.thenByDescending { it.last_heard })
		if (nodes.isEmpty()) {
			list.addView(line(getString(R.string.lora_osmand_no_nodes)))
		}
		for (node in nodes) {
			list.addView(nodeRow(node, phone))
		}
		scroll.post { scroll.scrollTo(0, saved) }
	}

	private fun styleFilter(view: TextView, active: Boolean) {
		view.alpha = if (active) 1f else 0.35f
	}

	private fun nodeRow(node: NodeInfo, phone: net.osmand.Location?): View {
		val ctx = requireContext()
		val row = LinearLayout(ctx).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			setPadding(0, dp(2), 0, dp(6))
			setOnClickListener { plugin.openNodeInMeshtastic(node.num) }
		}
		row.addView(TextView(ctx).apply {
			text = if (node.isMeshOnline()) "🟢" else "⚫"
			textSize = 13f
			gravity = Gravity.CENTER
			layoutParams = LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT)
		})
		val column = LinearLayout(ctx).apply {
			orientation = LinearLayout.VERTICAL
			layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
		}
		val shortName = node.shortLabel()
		val longName = node.longLabel()
		column.addView(TextView(ctx).apply {
			text = if (longName == shortName) shortName else "$shortName  $longName"
			textSize = 14f
			maxLines = 1
			ellipsize = TextUtils.TruncateAt.END
			setTypeface(typeface, Typeface.BOLD)
		})
		val metrics = metricsLine(node, phone)
		if (metrics.isNotEmpty()) {
			column.addView(TextView(ctx).apply {
				text = metrics
				textSize = 12f
				maxLines = 2
			})
		}
		row.addView(column)
		val shown = plugin.nodeShownOnMap(node.num)
		row.addView(TextView(ctx).apply {
			text = if (shown) "👁" else "🚫"
			textSize = 16f
			gravity = Gravity.CENTER
			minWidth = dp(36)
			contentDescription = getString(R.string.lora_osmand_show_node)
			if (Build.VERSION.SDK_INT >= 26) {
				tooltipText = getString(R.string.lora_osmand_show_node)
			}
			setOnClickListener { plugin.setNodeShownOnMap(node.num, !plugin.nodeShownOnMap(node.num)) }
		})
		return row
	}

	private fun metricsLine(node: NodeInfo, phone: net.osmand.Location?): String {
		val parts = ArrayList<String>()
		node.batteryPercent()?.let { parts.add(if (it < 20) "🪫$it%" else "🔋$it%") }
		node.device_metrics?.voltage?.let {
			parts.add("⚡" + String.format(Locale.getDefault(), "%.2fV", it))
		}
		if (node.snr != 0f) {
			parts.add("📶" + String.format(Locale.getDefault(), "%.1f", node.snr))
		}
		plugin.rssiOf(node.num)?.let { parts.add("📡$it") }
		node.hops_away?.takeIf { it > 0 }?.let { parts.add("🐇$it") }
		val where = node.meshLatLon()
		if (where != null && phone != null) {
			val meters = metersBetween(where.latitude, where.longitude, phone.latitude, phone.longitude)
			parts.add("📍" + formatDistance(meters))
		}
		node.position?.altitude?.let { parts.add("⛰$it") }
		if (node.last_heard > 0) {
			val heard = DateUtils.getRelativeTimeSpanString(
				node.last_heard * 1000L,
				System.currentTimeMillis(),
				DateUtils.MINUTE_IN_MILLIS,
				DateUtils.FORMAT_ABBREV_RELATIVE,
			)
			parts.add("🕐$heard")
		}
		node.device_metrics?.channel_utilization?.let {
			parts.add("📊" + String.format(Locale.getDefault(), "%.0f%%", it))
		}
		node.device_metrics?.air_util_tx?.let {
			parts.add("💨" + String.format(Locale.getDefault(), "%.0f%%", it))
		}
		return parts.joinToString("  ")
	}

	private fun formatDistance(meters: Double): String {
		return if (meters < 1000) {
			getString(R.string.lora_osmand_dist_m, meters.roundToInt())
		} else {
			getString(R.string.lora_osmand_dist_km, meters / 1000.0)
		}
	}

	private fun dp(value: Int): Int = AndroidUtils.dpToPx(requireContext(), value.toFloat())

	private fun line(text: String): TextView {
		return TextView(requireContext()).apply {
			this.text = text
			textSize = 15f
			setPadding(0, 0, 0, AndroidUtils.dpToPx(context, 12f))
		}
	}
}
