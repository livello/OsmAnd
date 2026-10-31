package net.osmand.plus.plugins.loraosmand

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import net.osmand.plus.R
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.settings.fragments.BaseSettingsFragment
import net.osmand.plus.utils.AndroidUtils
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

class LoraSettingsFragment : BaseSettingsFragment() {

	companion object {
		const val EMBEDDED_KEY = "lora_osmand_settings_embedded"

		val SETTINGS_JUMPS = listOf(
			Jump("lora_cat_link", "📻", R.string.lora_osmand_cat_link),
			Jump("lora_cat_map", "🗺️", R.string.lora_osmand_cat_map),
			Jump("lora_cat_me", "📍", R.string.lora_osmand_cat_me),
			Jump("lora_cat_tracks", "〰️", R.string.lora_osmand_cat_tracks),
			Jump("lora_cat_messages", "✉️", R.string.lora_osmand_cat_messages),
		)
		private val CATEGORY_KEYS = SETTINGS_JUMPS.map { it.key }.toSet()

		data class Jump(val key: String, val emoji: String, val titleRes: Int)
	}

	private val plugin = PluginsHelper.requirePlugin(LoraOsmAndPlugin::class.java)
	private val refresh = Runnable {
		if (view != null) {
			bindDynamic()
			refreshScanDialog()
		}
	}
	private var scanDialog: AlertDialog? = null
	private var scanAdapter: ArrayAdapter<String>? = null

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		val view = super.onCreateView(inflater, container, savedInstanceState)!!
		if (arguments?.getBoolean(EMBEDDED_KEY) == true) {
			view.findViewById<View>(R.id.appbar)?.visibility = View.GONE
			view.setPadding(view.paddingLeft, 0, view.paddingRight, view.paddingBottom)
			setJumpInset(true)
		}
		return view
	}

	override fun setupPreferences() {
		decorateAll()
		applyCategoryCollapse()
		bindDynamic()
	}

	override fun onResume() {
		super.onResume()
		plugin.addUiListener(refresh)
		bindDynamic()
	}

	override fun onPause() {
		plugin.removeUiListener(refresh)
		super.onPause()
	}

	override fun onBindPreferenceViewHolder(preference: Preference, holder: PreferenceViewHolder) {
		super.onBindPreferenceViewHolder(preference, holder)
		val key = preference.key ?: return
		if (key !in CATEGORY_KEYS) {
			return
		}
		holder.itemView.isClickable = true
		holder.itemView.setOnClickListener { toggleSettingsGroup(key) }
	}

	fun scrollToSettingsGroup(key: String) {
		expandSettingsGroup(key)
		val pref = findPreference<Preference>(key) ?: return
		scrollToPreference(pref)
	}

	fun setJumpInset(visible: Boolean) {
		if (arguments?.getBoolean(EMBEDDED_KEY) != true) {
			return
		}
		val list = listView ?: return
		val bottom = AndroidUtils.dpToPx(app, if (visible) 48f else 8f)
		list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, bottom)
	}

	override fun onPreferenceClick(preference: Preference): Boolean {
		when (preference.key) {
			"lora_osmand_scan" -> {
				showScan()
				return true
			}
			"lora_osmand_notify" -> {
				startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
				return true
			}
			"lora_osmand_status" -> {
				if (plugin.connected && !plugin.usesMeshApp()) {
					plugin.disconnectRadio()
				}
				return true
			}
			"lora_osmand_interval" -> {
				pickNumber(
					R.string.lora_osmand_pos_interval,
					LoraOsmAndPlugin.INTERVAL_CHOICES,
					plugin.POS_INTERVAL_SEC.get(),
					::formatInterval,
				) { plugin.setPositionInterval(it) }
				return true
			}
			"lora_osmand_track_interval" -> {
				pickNumber(
					R.string.lora_osmand_track_interval,
					LoraOsmAndPlugin.INTERVAL_CHOICES,
					plugin.TRACK_INTERVAL_SEC.get(),
					::formatInterval,
				) { plugin.TRACK_INTERVAL_SEC.set(it) }
				return true
			}
			"lora_osmand_marker_style" -> {
				pickStyle()
				return true
			}
			"lora_osmand_marker_size" -> {
				pickNumber(
					R.string.lora_osmand_marker_size,
					LoraOsmAndPlugin.MARKER_SIZE_CHOICES,
					plugin.MARKER_SIZE.get(),
					{ getString(R.string.lora_osmand_percent, it) },
				) { plugin.setMarkerSize(it) }
				return true
			}
			"lora_osmand_marker_font" -> {
				pickNumber(
					R.string.lora_osmand_marker_font,
					LoraOsmAndPlugin.MARKER_FONT_CHOICES,
					plugin.MARKER_FONT.get(),
					{ getString(R.string.lora_osmand_font_sp, it) },
				) { plugin.setMarkerFont(it) }
				return true
			}
			"lora_osmand_offset" -> {
				pickNumber(
					R.string.lora_osmand_pos_offset,
					LoraOsmAndPlugin.OFFSET_CHOICES,
					plugin.POS_OFFSET_M.get(),
					::formatOffset,
				) { plugin.setPositionOffset(it) }
				return true
			}
			"lora_osmand_track_offset" -> {
				pickNumber(
					R.string.lora_osmand_track_offset,
					LoraOsmAndPlugin.OFFSET_CHOICES,
					plugin.TRACK_OFFSET_M.get(),
					::formatOffset,
				) { plugin.TRACK_OFFSET_M.set(it) }
				return true
			}
			"lora_osmand_message_style" -> {
				pickMessageStyle()
				return true
			}
			"lora_osmand_speak_count" -> {
				pickNumber(
					R.string.lora_osmand_speak_count,
					LoraOsmAndPlugin.SPEAK_RECENT_CHOICES,
					plugin.SPEAK_RECENT.get(),
					{ getString(R.string.lora_osmand_speak_count_value, it) },
				) { plugin.SPEAK_RECENT.set(it.coerceIn(1, 20)) }
				return true
			}
		}
		return super.onPreferenceClick(preference)
	}

	override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
		val ok = super.onPreferenceChange(preference, newValue)
		if (preference.key == LoraOsmAndPlugin.PREF_SHOW_MAP) {
			view?.post { app.osmandMap.mapView.refreshMap() }
		}
		return ok
	}

	private fun decorateAll() {
		decorate("lora_osmand_autoconnect", "🔄", R.drawable.ic_action_signal)
		decorate("lora_osmand_notify", "🔔", R.drawable.ic_action_notification)
		decorate("lora_osmand_scan", "🔎", R.drawable.ic_action_search_dark)
		decorate("lora_osmand_status", "📡", R.drawable.ic_action_signal)
		decorate("lora_osmand_marker_style", "🎨", R.drawable.ic_action_appearance)
		decorate("lora_osmand_marker_size", "🔘", R.drawable.ic_action_map_magnifier)
		decorate("lora_osmand_marker_font", "🔤", R.drawable.ic_action_map_text_size)
		decorate("lora_osmand_show_map", "🗺️", R.drawable.ic_action_map_style)
		decorate("lora_osmand_interval", "⏱️", R.drawable.ic_action_time_span)
		decorate("lora_osmand_offset", "📏", R.drawable.ic_action_distance)
		decorate("lora_osmand_pos_status", "📌", R.drawable.ic_action_gdirections_dark)
		decorate("lora_osmand_track_interval", "⏱️", R.drawable.ic_action_time_span)
		decorate("lora_osmand_track_offset", "📏", R.drawable.ic_action_distance)
		decorate("lora_osmand_speak_count", "🔊", R.drawable.ic_action_micro_dark)
		decorate("lora_osmand_message_style", "💬", R.drawable.ic_action_appearance)
		decorate("lora_osmand_speak_name", "🏷️", R.drawable.ic_action_micro_dark)
		decorate("lora_osmand_speak_short", "✂️", R.drawable.ic_action_micro_dark)
		decorate("lora_osmand_speak_plain", "🔤", R.drawable.ic_action_micro_dark)
	}

	private fun decorate(key: String, emoji: String, iconRes: Int) {
		val pref = findPreference<Preference>(key) ?: return
		val title = pref.title?.toString().orEmpty()
		if (title.isNotEmpty() && !title.startsWith(emoji)) {
			pref.title = "$emoji  $title"
		}
		pref.icon = getContentIcon(iconRes)
	}

	private fun decorateCategory(key: String, emoji: String, expanded: Boolean) {
		val pref = findPreference<Preference>(key) ?: return
		val jump = SETTINGS_JUMPS.firstOrNull { it.key == key }
		val label = if (jump != null) getString(jump.titleRes) else pref.title?.toString().orEmpty()
		pref.title = "$emoji  $label  ${if (expanded) "▾" else "▸"}"
		pref.isSelectable = true
		pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
			toggleSettingsGroup(key)
			true
		}
	}

	private fun expandSettingsGroup(key: String) {
		val keys = plugin.settingsGroups()
		if (keys.add(key)) {
			plugin.saveSettingsGroups(keys)
			applyCategoryCollapse()
		}
	}

	private fun toggleSettingsGroup(key: String) {
		val keys = plugin.settingsGroups()
		if (!keys.add(key)) {
			keys.remove(key)
		}
		plugin.saveSettingsGroups(keys)
		applyCategoryCollapse()
	}

	private fun applyCategoryCollapse() {
		val screen = preferenceScreen ?: return
		val expanded = plugin.settingsGroups()
		var current: String? = null
		for (i in 0 until screen.preferenceCount) {
			val pref = screen.getPreference(i)
			val key = pref.key
			if (key != null && key in CATEGORY_KEYS) {
				current = key
				val emoji = SETTINGS_JUMPS.firstOrNull { it.key == key }?.emoji ?: ""
				decorateCategory(key, emoji, key in expanded)
				continue
			}
			if (current != null) {
				pref.isVisible = current in expanded
			}
		}
	}

	private fun bindDynamic() {
		val name = plugin.DEVICE_NAME.get().ifBlank { plugin.DEVICE_ADDRESS.get() }
		findPreference<Preference>("lora_osmand_scan")?.summary = name.ifBlank {
			getString(R.string.lora_osmand_scan_summary)
		}
		findPreference<Preference>("lora_osmand_notify")?.summary = if (plugin.notificationAccess()) {
			getString(R.string.lora_osmand_notify_on)
		} else {
			getString(R.string.lora_osmand_notify_off)
		}
		findPreference<Preference>("lora_osmand_status")?.summary = if (plugin.connected) {
			getString(R.string.lora_osmand_connected, plugin.statusText)
		} else {
			plugin.statusText.ifBlank { getString(R.string.lora_osmand_disconnected) }
		}
		findPreference<Preference>("lora_osmand_marker_style")?.summary = styleLabel(plugin.markerStyle())
		findPreference<Preference>("lora_osmand_marker_size")?.summary =
			getString(R.string.lora_osmand_percent, plugin.MARKER_SIZE.get())
		findPreference<Preference>("lora_osmand_marker_font")?.summary =
			getString(R.string.lora_osmand_font_sp, plugin.MARKER_FONT.get())
		findPreference<Preference>("lora_osmand_interval")?.summary = formatInterval(plugin.POS_INTERVAL_SEC.get())
		findPreference<Preference>("lora_osmand_offset")?.summary = formatOffset(plugin.POS_OFFSET_M.get())
		findPreference<Preference>("lora_osmand_track_interval")?.summary = formatInterval(plugin.TRACK_INTERVAL_SEC.get())
		findPreference<Preference>("lora_osmand_track_offset")?.summary = formatOffset(plugin.TRACK_OFFSET_M.get())
		findPreference<Preference>("lora_osmand_speak_count")?.summary =
			getString(R.string.lora_osmand_speak_count_value, plugin.SPEAK_RECENT.get())
		findPreference<Preference>("lora_osmand_message_style")?.summary =
			getString(plugin.messageStyle().titleRes)
		val sent = plugin.lastSentMs()
		val whenText = if (sent <= 0L) {
			getString(R.string.lora_osmand_never_sent)
		} else {
			DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(sent))
		}
		val meters = plugin.metersSinceSend()
		val moved = if (meters == null) "—" else getString(R.string.lora_osmand_meters, meters.roundToInt())
		findPreference<Preference>("lora_osmand_pos_status")?.summary =
			getString(R.string.lora_osmand_pos_status_value, whenText, moved)
	}

	private fun showScan() {
		plugin.startScan(requireActivity())
		val names = plugin.foundRadios.map { "${it.name}  ${it.rssi}" }
		scanAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, names.toMutableList())
		scanDialog = AlertDialog.Builder(requireContext())
			.setTitle(R.string.lora_osmand_scan)
			.setAdapter(scanAdapter) { _, which ->
				val radio = plugin.foundRadios.getOrNull(which) ?: return@setAdapter
				plugin.connectFound(radio)
			}
			.setNegativeButton(R.string.shared_string_cancel, null)
			.setOnDismissListener { scanDialog = null }
			.show()
	}

	private fun refreshScanDialog() {
		val adapter = scanAdapter ?: return
		if (scanDialog?.isShowing != true) {
			return
		}
		adapter.clear()
		adapter.addAll(plugin.foundRadios.map { "${it.name}  ${it.rssi}" })
		adapter.notifyDataSetChanged()
	}

	private fun pickMessageStyle() {
		val styles = LoraMessageStyle.entries
		val ctx = requireContext()
		var selected = styles.indexOf(plugin.messageStyle()).coerceAtLeast(0)
		val root = LinearLayout(ctx).apply {
			orientation = LinearLayout.VERTICAL
		}
		val names = LinearLayout(ctx).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(16), dp(8), dp(16), dp(4))
		}
		val preview = LinearLayout(ctx).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(12), dp(8), dp(12), dp(8))
		}
		val nameViews = ArrayList<TextView>()
		fun render() {
			nameViews.forEachIndexed { index, label ->
				val active = index == selected
				label.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
				label.alpha = if (active) 1f else 0.45f
			}
			preview.removeAllViews()
			val now = System.currentTimeMillis()
			val samples = listOf(
				LoraChatLine(now - 15 * 60_000L, "Jef7", getString(R.string.lora_osmand_style_sample_short), false, 0),
				LoraChatLine(now - 2 * 60_000L, "Jef7", getString(R.string.lora_osmand_style_sample_long), false, 0),
				LoraChatLine(
					now,
					getString(R.string.lora_osmand_me),
					getString(R.string.lora_osmand_style_sample_out),
					true,
					0,
					LoraDelivery.SENT,
				),
			)
			for (line in samples) {
				preview.addView(
					LoraMessageRows.build(ctx, isNightMode, styles[selected], line) { "Jef" },
				)
			}
		}
		styles.forEachIndexed { index, style ->
			val label = TextView(ctx).apply {
				text = getString(style.titleRes)
				textSize = 16f
				setPadding(0, dp(8), 0, dp(8))
				setOnClickListener {
					selected = index
					render()
				}
			}
			nameViews.add(label)
			names.addView(label)
		}
		root.addView(names)
		root.addView(View(ctx).apply {
			layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
			setBackgroundColor(0x33888888)
		})
		root.addView(ScrollView(ctx).apply {
			isFillViewport = false
			addView(preview)
			layoutParams = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				dp(220),
			)
		})
		render()
		AlertDialog.Builder(ctx)
			.setTitle(R.string.lora_osmand_message_style)
			.setView(root)
			.setPositiveButton(R.string.shared_string_ok) { _, _ ->
				plugin.MESSAGE_STYLE.set(styles[selected].id)
				bindDynamic()
			}
			.setNegativeButton(R.string.shared_string_cancel, null)
			.show()
	}

	private fun dp(value: Int): Int = AndroidUtils.dpToPx(requireContext(), value.toFloat())

	private fun pickStyle() {
		val styles = LoraMarkerStyle.entries
		AlertDialog.Builder(requireContext())
			.setTitle(R.string.lora_osmand_marker_style)
			.setItems(styles.map { styleLabel(it) }.toTypedArray()) { _, which ->
				plugin.setMarkerStyle(styles[which])
			}
			.setNegativeButton(R.string.shared_string_cancel, null)
			.show()
	}

	private fun styleLabel(style: LoraMarkerStyle): String {
		val id = when (style) {
			LoraMarkerStyle.CIRCLE -> R.string.lora_osmand_style_circle
			LoraMarkerStyle.MESHTASTIC -> R.string.lora_osmand_style_meshtastic
			LoraMarkerStyle.PIN -> R.string.lora_osmand_style_pin
			LoraMarkerStyle.PLATE -> R.string.lora_osmand_style_plate
			LoraMarkerStyle.DOT -> R.string.lora_osmand_style_dot
		}
		return getString(id)
	}

	private fun pickNumber(title: Int, presets: IntArray, current: Int, label: (Int) -> String, apply: (Int) -> Unit) {
		val values = LinkedHashSet<Int>()
		presets.forEach { values.add(it) }
		values.add(current)
		val items = values.toList()
		AlertDialog.Builder(requireContext())
			.setTitle(title)
			.setItems(items.map { label(it) }.toTypedArray()) { _, which -> apply(items[which]) }
			.setNegativeButton(R.string.shared_string_cancel, null)
			.show()
	}

	private fun formatInterval(seconds: Int): String {
		if (seconds <= 0) {
			return getString(R.string.shared_string_off)
		}
		if (seconds < 60) {
			return getString(R.string.lora_osmand_seconds, seconds)
		}
		return getString(R.string.lora_osmand_minutes, seconds / 60)
	}

	private fun formatOffset(meters: Int): String {
		if (meters <= 0) {
			return getString(R.string.shared_string_off)
		}
		return getString(R.string.lora_osmand_meters, meters)
	}
}
