package net.osmand.plus.plugins.loraosmand

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.graphics.drawable.GradientDrawable
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.text.HtmlCompat
import net.osmand.plus.utils.ColorUtilities
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.fragment.app.FragmentManager
import net.osmand.plus.BuildConfig
import net.osmand.plus.R
import net.osmand.plus.base.MenuBottomSheetDialogFragment
import net.osmand.plus.base.bottomsheetmenu.BaseBottomSheetItem
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.utils.AndroidUtils
import org.meshtastic.proto.NodeInfo

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
	private var chatSignature = ""
	private var parkMessages = false
	private var composeDialog: AlertDialog? = null
	private var composeField: EditText? = null
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
		val about = view.findViewById<TextView>(R.id.lora_osmand_about_text)
		about.setTextColor(ColorUtilities.getPrimaryTextColor(requireContext(), nightMode))
		about.text = HtmlCompat.fromHtml(
			getString(R.string.lora_osmand_about, BuildConfig.VERSION_NAME),
			HtmlCompat.FROM_HTML_MODE_LEGACY,
		)
		val speak = view.findViewById<SwitchCompat>(R.id.lora_osmand_speak_switch)
		speak.isChecked = plugin.SPEAK_CHAT.get()
		speak.setOnCheckedChangeListener { _, checked -> plugin.SPEAK_CHAT.set(checked) }
		view.findViewById<View>(R.id.lora_osmand_compose_open).setOnClickListener { showComposeDialog() }
		view.findViewById<View>(R.id.lora_osmand_speak_recent).setOnClickListener { plugin.speakRecent() }
		buildJumpBar(view)
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
		plugin.pullMeshMessages()
		bindLive()
	}

	override fun onPause() {
		plugin.removeUiListener(refresh)
		stopRecognizer()
		super.onPause()
	}

	override fun onDestroyView() {
		composeDialog?.dismiss()
		composeDialog = null
		composeField = null
		super.onDestroyView()
	}

	fun showTab(tab: LoraSheetTab) {
		val openingMessages = tab == LoraSheetTab.MESSAGES && currentTab != tab
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
		root.findViewById<View>(R.id.lora_osmand_jumps).visibility =
			if (tab == LoraSheetTab.SETTINGS) View.VISIBLE else View.GONE
		(childFragmentManager.findFragmentByTag(SETTINGS_TAG) as? LoraSettingsFragment)
			?.setJumpInset(tab == LoraSheetTab.SETTINGS)
		if (openingMessages) {
			parkMessages = true
			plugin.pullMeshMessages()
		}
		bindLive()
	}

	private fun bindLive() {
		val root = view ?: return
		if (currentTab == LoraSheetTab.NODES) {
			bindNodes(root)
		}
		if (currentTab == LoraSheetTab.MESSAGES) {
			plugin.markChatRead()
			bindMessages(root)
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

	private fun selectedChannel(spinner: Spinner?): Int {
		if (spinner == null) {
			return 0
		}
		return channelIndexes.getOrNull(spinner.selectedItemPosition) ?: 0
	}

	private fun showComposeDialog() {
		val ctx = requireContext()
		composeDialog?.dismiss()
		val content = layoutInflater.inflate(R.layout.lora_osmand_compose_dialog, null)
		val spinner = content.findViewById<Spinner>(R.id.lora_compose_channel)
		val field = content.findViewById<EditText>(R.id.lora_compose_text)
		composeField = field
		bindChannels(spinner)
		content.findViewById<View>(R.id.lora_compose_close).setOnClickListener {
			composeDialog?.dismiss()
		}
		content.findViewById<View>(R.id.lora_compose_mic).setOnClickListener { onMic() }
		content.findViewById<View>(R.id.lora_compose_send).setOnClickListener {
			val text = field.text?.toString().orEmpty()
			if (text.isBlank()) {
				return@setOnClickListener
			}
			plugin.sendChat(text, selectedChannel(spinner))
			field.setText("")
			composeDialog?.dismiss()
		}
		field.setOnEditorActionListener { _, _, _ ->
			content.findViewById<View>(R.id.lora_compose_send).performClick()
			true
		}
		val dialog = AlertDialog.Builder(ctx).setView(content).create()
		dialog.setOnDismissListener {
			if (composeDialog === dialog) {
				composeDialog = null
				composeField = null
			}
			stopRecognizer()
		}
		composeDialog = dialog
		dialog.show()
		val bg = ColorUtilities.getListBgColor(app, nightMode)
		dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
			setColor(bg)
			cornerRadius = dp(18).toFloat()
		})
		val width = (resources.displayMetrics.widthPixels * 0.9f).toInt()
		dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
		dialog.window?.setGravity(Gravity.CENTER)
	}

	private fun bindMessages(root: View) {
		val status = root.findViewById<TextView>(R.id.lora_osmand_message_status)
		val link = if (plugin.notificationAccess()) {
			getString(R.string.lora_osmand_link_on)
		} else {
			getString(R.string.lora_osmand_link_off)
		}
		val tak = if (plugin.connected) getString(R.string.lora_osmand_tak_on) else getString(R.string.lora_osmand_tak_off)
		val seen = plugin.lastMeshNoticeMs
		val whenText = if (seen <= 0L) {
			getString(R.string.lora_osmand_mesh_quiet)
		} else {
			DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(seen))
		}
		status.text = getString(R.string.lora_osmand_messages_status, link, tak, whenText)
		val signature = plugin.messageStyle().id + "|" +
			plugin.chat.joinToString("|") { "${it.timeMs}:${it.delivery}:${it.text}" }
		val scroll = root.findViewById<ScrollView>(R.id.lora_osmand_chat_scroll)
		val park = parkMessages
		if (signature == chatSignature && root.findViewById<LinearLayout>(R.id.lora_osmand_chat).childCount > 0) {
			if (park) {
				parkMessages = false
				scroll.post { revealMessages(scroll, 0) }
			}
			return
		}
		chatSignature = signature
		val nearBottom = scroll.getChildAt(0) == null ||
			scroll.getChildAt(0).bottom <= scroll.height + scroll.scrollY + dp(48)
		val saved = scroll.scrollY
		val list = root.findViewById<LinearLayout>(R.id.lora_osmand_chat)
		list.removeAllViews()
		if (plugin.chat.isEmpty()) {
			list.addView(line(getString(R.string.lora_osmand_no_messages)))
		}
		for (entry in plugin.chat) {
			list.addView(messageRow(entry))
		}
		scroll.post {
			if (park) {
				parkMessages = false
				revealMessages(scroll, 0)
			} else if (nearBottom) {
				scroll.fullScroll(View.FOCUS_DOWN)
			} else {
				scroll.scrollTo(0, saved)
			}
		}
	}

	private fun revealMessages(scroll: ScrollView, attempt: Int) {
		if (attempt > 4) {
			return
		}
		val column = scroll.getChildAt(0) ?: return
		val header = column.findViewById<View>(R.id.lora_osmand_messages_header) ?: return
		val chat = column.findViewById<View>(R.id.lora_osmand_chat) ?: return
		val headerBottom = header.bottom
		if (headerBottom == 0 || scroll.height == 0) {
			scroll.post { revealMessages(scroll, attempt + 1) }
			return
		}
		val needed = headerBottom + scroll.height - (column.height - column.paddingBottom)
		val pad = needed.coerceAtLeast(0)
		if (column.paddingBottom != pad) {
			column.setPadding(0, 0, 0, pad)
			scroll.post { revealMessages(scroll, attempt + 1) }
			return
		}
		val maxScroll = (column.height - scroll.height).coerceAtLeast(0)
		val target = if (chat.height > scroll.height) {
			maxScroll
		} else {
			headerBottom.coerceAtMost(maxScroll)
		}
		scroll.scrollTo(0, target)
	}

	private fun messageRow(entry: LoraChatLine): View {
		val view = when (plugin.messageStyle()) {
			LoraMessageStyle.TELEGRAM -> telegramRow(entry)
			LoraMessageStyle.FACEBOOK -> facebookRow(entry)
			LoraMessageStyle.ICQ -> icqRow(entry)
			LoraMessageStyle.TWITTER -> twitterRow(entry)
			LoraMessageStyle.ZSH -> zshRow(entry)
			LoraMessageStyle.LORA -> loraRow(entry)
		}
		armMessage(view, entry)
		return view
	}

	private fun armMessage(view: View, entry: LoraChatLine) {
		view.isClickable = true
		view.isLongClickable = true
		view.contentDescription = getString(R.string.lora_osmand_speak_line)
		view.setOnClickListener { plugin.speakLine(entry.fromName, entry.text) }
		view.setOnLongClickListener {
			showSenderOnMap(entry)
			true
		}
	}

	private fun loraRow(entry: LoraChatLine): View {
		val ctx = requireContext()
		val body = framedColumn()
		val head = LinearLayout(ctx).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
		}
		head.addView(TextView(ctx).apply {
			text = "${clock(entry)}  ${messageAge(entry.timeMs)}"
			textSize = 12f
			setTextColor(ColorUtilities.getSecondaryTextColor(ctx, nightMode))
			layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
		})
		head.addView(TextView(ctx).apply {
			text = listOf(entry.fromName, deliveryMark(entry)).filter { it.isNotEmpty() }.joinToString("  ")
			textSize = 13f
			setTypeface(typeface, Typeface.BOLD)
			gravity = Gravity.END
			setTextColor(ColorUtilities.getPrimaryTextColor(ctx, nightMode))
		})
		body.addView(head)
		body.addView(messageText(entry.text, 15f))
		body.layoutParams = LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.WRAP_CONTENT,
		).apply {
			topMargin = dp(4)
			bottomMargin = dp(4)
		}
		return body
	}

	private fun telegramRow(entry: LoraChatLine): View {
		val ctx = requireContext()
		val column = LinearLayout(ctx).apply {
			orientation = LinearLayout.VERTICAL
			layoutParams = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
			)
			gravity = if (entry.outgoing) Gravity.END else Gravity.START
			setPadding(0, dp(4), 0, dp(4))
		}
		if (!entry.outgoing) {
			column.addView(TextView(ctx).apply {
				text = entry.fromName
				textSize = 12f
				setTypeface(typeface, Typeface.BOLD)
				setTextColor(ColorUtilities.getSecondaryTextColor(ctx, nightMode))
				setPadding(dp(6), 0, dp(6), dp(2))
			})
		}
		val bubble = LinearLayout(ctx).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(10), dp(8), dp(10), dp(8))
			background = GradientDrawable().apply {
				cornerRadius = dp(16).toFloat()
				setColor(if (entry.outgoing) 0x3327AE60 else if (nightMode) 0xFF2C2C2C.toInt() else 0xFFE7F3FF.toInt())
			}
		}
		val body = messageText(entry.text, 15f)
		body.maxWidth = (resources.displayMetrics.widthPixels * 0.72f).toInt()
		bubble.addView(body)
		column.addView(bubble)
		column.addView(TextView(ctx).apply {
			val mark = deliveryMark(entry)
			text = "${clock(entry)}  ${messageAge(entry.timeMs)}" + if (mark.isEmpty()) "" else "  $mark"
			textSize = 11f
			setTextColor(ColorUtilities.getSecondaryTextColor(ctx, nightMode))
			setPadding(dp(6), dp(2), dp(6), 0)
		})
		return column
	}

	private fun facebookRow(entry: LoraChatLine): View {
		val ctx = requireContext()
		val row = LinearLayout(ctx).apply {
			orientation = LinearLayout.HORIZONTAL
			setPadding(0, dp(6), 0, dp(6))
		}
		val letter = entry.fromName.firstOrNull()?.uppercaseChar()?.toString() ?: "•"
		row.addView(TextView(ctx).apply {
			text = letter
			gravity = Gravity.CENTER
			textSize = 16f
			setTextColor(Color.WHITE)
			val size = dp(36)
			layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(8) }
			background = GradientDrawable().apply {
				shape = GradientDrawable.OVAL
				setColor(0xFF5C6BC0.toInt())
			}
		})
		val column = LinearLayout(ctx).apply {
			orientation = LinearLayout.VERTICAL
			layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
		}
		column.addView(TextView(ctx).apply {
			text = entry.fromName
			textSize = 14f
			setTypeface(typeface, Typeface.BOLD)
			setTextColor(ColorUtilities.getPrimaryTextColor(ctx, nightMode))
		})
		column.addView(messageText(entry.text, 15f))
		column.addView(TextView(ctx).apply {
			val mark = deliveryMark(entry)
			text = "${clock(entry)} · ${messageAge(entry.timeMs)}" + if (mark.isEmpty()) "" else "  $mark"
			textSize = 12f
			setTextColor(ColorUtilities.getSecondaryTextColor(ctx, nightMode))
		})
		row.addView(column)
		return row
	}

	private fun icqRow(entry: LoraChatLine): View {
		val ctx = requireContext()
		return TextView(ctx).apply {
			typeface = Typeface.MONOSPACE
			textSize = 14f
			setTextColor(0xFF39FF14.toInt())
			val mark = deliveryMark(entry)
			text = "[${clock(entry)}] ${entry.fromName}> ${entry.text}" + if (mark.isEmpty()) "" else " $mark"
			setPadding(dp(8), dp(6), dp(8), dp(6))
			background = GradientDrawable().apply {
				cornerRadius = dp(4).toFloat()
				setColor(if (nightMode) 0xFF031006.toInt() else 0xFF0B0B0B.toInt())
			}
		}
	}

	private fun twitterRow(entry: LoraChatLine): View {
		val ctx = requireContext()
		val column = LinearLayout(ctx).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(0, dp(6), 0, dp(8))
		}
		val short = plugin.nodeMatching(entry.fromName)?.shortLabel() ?: entry.fromName
		column.addView(TextView(ctx).apply {
			text = "${entry.fromName}  @$short"
			textSize = 14f
			setTypeface(typeface, Typeface.BOLD)
			setTextColor(ColorUtilities.getPrimaryTextColor(ctx, nightMode))
		})
		column.addView(messageText(entry.text, 15f))
		column.addView(TextView(ctx).apply {
			val mark = deliveryMark(entry)
			text = "${clock(entry)} · ${messageAge(entry.timeMs)}" + if (mark.isEmpty()) "" else "  $mark"
			textSize = 12f
			setTextColor(ColorUtilities.getSecondaryTextColor(ctx, nightMode))
		})
		return column
	}

	private fun zshRow(entry: LoraChatLine): View {
		val ctx = requireContext()
		return TextView(ctx).apply {
			typeface = Typeface.MONOSPACE
			textSize = 14f
			setTextColor(ColorUtilities.getPrimaryTextColor(ctx, nightMode))
			val mark = deliveryMark(entry)
			text = "${clock(entry)} ${entry.fromName}% ${entry.text}" + if (mark.isEmpty()) "" else " $mark"
			setPadding(0, dp(3), 0, dp(3))
		}
	}

	private fun framedColumn(): LinearLayout {
		val ctx = requireContext()
		return LinearLayout(ctx).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(8), dp(6), dp(8), dp(6))
			background = GradientDrawable().apply {
				setColor(ColorUtilities.getActivityBgColor(ctx, nightMode))
				setStroke(dp(1).coerceAtLeast(1), ColorUtilities.getSecondaryTextColor(ctx, nightMode))
				cornerRadius = dp(8).toFloat()
			}
		}
	}

	private fun messageText(text: String, size: Float): TextView {
		return TextView(requireContext()).apply {
			this.text = text
			textSize = size
			setTextColor(ColorUtilities.getPrimaryTextColor(context, nightMode))
		}
	}

	private fun clock(entry: LoraChatLine): String {
		return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(entry.timeMs))
	}

	private fun deliveryMark(entry: LoraChatLine): String {
		return when (entry.delivery) {
			LoraDelivery.SENDING -> "⏳"
			LoraDelivery.SENT -> "✅"
			LoraDelivery.FAILED -> "⚠️"
			LoraDelivery.INCOMING -> ""
		}
	}

	private fun messageAge(timeMs: Long): String {
		val minutes = ((System.currentTimeMillis() - timeMs).coerceAtLeast(0L) / 60_000L).toInt()
		return when {
			minutes < 60 -> getString(R.string.lora_osmand_age_min, minutes)
			minutes < 60 * 24 -> getString(R.string.lora_osmand_age_hour, minutes / 60)
			else -> getString(R.string.lora_osmand_age_day, minutes / (60 * 24))
		}
	}

	private fun showSenderOnMap(entry: LoraChatLine) {
		if (entry.outgoing) {
			app.showToastMessage(R.string.lora_osmand_location_unknown)
			return
		}
		val node = plugin.nodeMatching(entry.fromName)
		if (node?.meshLatLon() == null) {
			app.showToastMessage(R.string.lora_osmand_location_unknown)
			return
		}
		dismissAllowingStateLoss()
		plugin.focusNode(node)
	}

	private fun buildJumpBar(root: View) {
		val row = root.findViewById<LinearLayout>(R.id.lora_osmand_jump_row)
		row.removeAllViews()
		for (jump in LoraSettingsFragment.SETTINGS_JUMPS) {
			row.addView(TextView(requireContext()).apply {
				text = jump.emoji
				textSize = 18f
				gravity = Gravity.CENTER
				minWidth = dp(40)
				layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
				contentDescription = getString(jump.titleRes)
				if (Build.VERSION.SDK_INT >= 26) {
					tooltipText = getString(jump.titleRes)
				}
				setOnClickListener {
					(childFragmentManager.findFragmentByTag(SETTINGS_TAG) as? LoraSettingsFragment)
						?.scrollToSettingsGroup(jump.key)
				}
			})
		}
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
		composeField?.setText(text)
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
		val nodes = plugin.nodes
			.filter { !onlineOnly || it.isMeshOnline(now) }
			.filter { !visibleOnly || plugin.nodeShownOnMap(it.num) }
			.sortedWith(compareByDescending<NodeInfo> { it.isMeshOnline(now) }.thenByDescending { it.last_heard })
		if (nodes.isEmpty()) {
			list.addView(line(getString(R.string.lora_osmand_no_nodes)))
		}
		for (node in nodes) {
			list.addView(nodeRow(node))
		}
		scroll.post { scroll.scrollTo(0, saved) }
	}

	private fun styleFilter(view: TextView, active: Boolean) {
		view.alpha = if (active) 1f else 0.35f
	}

	private fun nodeRow(node: NodeInfo): View {
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
		val metrics = formatNodeMetrics(ctx, plugin, node)
		if (metrics.isNotEmpty()) {
			column.addView(TextView(ctx).apply {
				text = metrics
				textSize = 12f
				maxLines = 2
			})
		}
		row.addView(column)
		val recording = plugin.isTrackRecording(node.num)
		row.addView(emojiButton(if (recording) "🔴" else "⚪", getString(R.string.lora_osmand_track_record)) {
			plugin.setTrackRecording(node.num, !plugin.isTrackRecording(node.num))
		})
		val trackShown = plugin.isTrackShown(node.num)
		row.addView(emojiButton(if (trackShown) "〰️" else "〰️", getString(R.string.lora_osmand_track_show)) {
			plugin.setTrackShown(node.num, !plugin.isTrackShown(node.num))
		}.also { it.alpha = if (trackShown) 1f else 0.35f })
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

	private fun emojiButton(emoji: String, description: String, click: () -> Unit): TextView {
		return TextView(requireContext()).apply {
			text = emoji
			textSize = 16f
			gravity = Gravity.CENTER
			minWidth = dp(36)
			contentDescription = description
			if (Build.VERSION.SDK_INT >= 26) {
				tooltipText = description
			}
			setOnClickListener { click() }
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
