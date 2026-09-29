package net.osmand.plus.plugins.voicegps

import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.RecyclerView
import net.osmand.plus.R
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.settings.fragments.BaseSettingsFragment
import net.osmand.plus.settings.preferences.SwitchPreferenceEx
import net.osmand.plus.utils.AndroidUtils
import net.osmand.plus.utils.ColorUtilities

class VoiceGpsSettingsFragment : BaseSettingsFragment() {

	companion object {
		const val EMBEDDED_KEY = "voice_gps_settings_embedded"
	}

	private var localNoteBar: View? = null

	private class ChipOption(val value: String, val label: String, val description: String)

	private class ChipRow(
		val choices: List<ChipOption>,
		val read: () -> String,
		val write: (String) -> Unit,
	)

	private val plugin: VoiceGpsPlugin
		get() = PluginsHelper.requirePlugin(VoiceGpsPlugin::class.java)

	private val chipRows = HashMap<String, ChipRow>()

	private fun isEmbedded(): Boolean = arguments?.getBoolean(EMBEDDED_KEY) == true

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View {
		val content = super.onCreateView(inflater, container, savedInstanceState)!!
		attachScrollListener()
		if (isEmbedded()) {
			content.findViewById<View>(R.id.appbar)?.visibility = View.GONE
			content.setPadding(content.paddingLeft, 0, content.paddingRight, content.paddingBottom)
			setNoteBarInset(true)
			return content
		}
		val frame = FrameLayout(content.context)
		frame.layoutParams = ViewGroup.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.MATCH_PARENT
		)
		frame.addView(
			content,
			FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT
			)
		)
		val bar = VoiceGpsNoteBar.create(content.context, isNightMode()) { startVoiceNote() }
		bar.id = R.id.bottom_buttons_container
		frame.addView(
			bar,
			FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
				Gravity.BOTTOM
			)
		)
		localNoteBar = bar
		setNoteBarInset(true)
		return frame
	}

	override fun updateStatusBar() {
		if (!isEmbedded()) {
			super.updateStatusBar()
		}
	}

	private fun attachScrollListener() {
		listView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
			override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
				val atTop = !recyclerView.canScrollVertically(-1)
				if (isEmbedded()) {
					(parentFragment as? VoiceGpsSettingsBottomSheet)?.setNoteButtonVisible(atTop)
				} else {
					showLocalNoteBar(atTop)
				}
			}
		})
	}

	fun setNoteBarInset(barVisible: Boolean) {
		val list = listView ?: return
		val bottom = AndroidUtils.dpToPx(app, if (barVisible) 56f else 12f)
		list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, bottom)
	}

	private fun showLocalNoteBar(visible: Boolean) {
		val bar = localNoteBar ?: return
		val shown = bar.visibility == View.VISIBLE && bar.alpha > 0.5f
		if (shown == visible) {
			return
		}
		VoiceGpsNoteBar.setVisible(bar, visible, animate = true)
		setNoteBarInset(visible)
	}

	private fun startVoiceNote() {
		val host = activity
		dismissParentSheet()
		plugin.beginManualSession(host)
	}

	private fun dismissParentSheet() {
		(parentFragment as? VoiceGpsSettingsBottomSheet)?.dismissAllowingStateLoss()
	}

	override fun setupPreferences() {
		updatePermissionSummary()
		setupSwitch(plugin.LISTEN_IN_BACKGROUND.id, R.string.voice_gps_listen_background_desc)
		setupSwitch(plugin.PAUSE_WHEN_MOVING.id, R.string.voice_gps_pause_when_moving_desc)
		setupSpeedChips()
		setupSwitch(plugin.PARTIAL_WAKE.id, R.string.voice_gps_partial_wake_desc)
		setupSwitch(plugin.MANUAL_WAKE.id, R.string.voice_gps_manual_wake_desc)
		setupWakeKeyChips()
		setupSwitch(plugin.PREFER_ONLINE_STT.id, R.string.voice_gps_prefer_online_stt_desc)
		setupSwitch(plugin.SHOW_VOICE_GPX_ON_MAP.id, R.string.ev_voice_gpx_show_on_map_desc)
		decorateRows()
		updateDependentRows()
	}

	private fun setupSwitch(key: String, desc: Int) {
		val pref = findPreference<SwitchPreferenceEx>(key) ?: return
		val hint = getString(desc)
		pref.summary = hint
		pref.setDescription(hint)
	}

	private fun setupSpeedChips() {
		val pref = findPreference<Preference>(plugin.MOVING_SPEED_THRESHOLD_KMH.id) ?: return
		pref.summary = getString(R.string.voice_gps_moving_speed_threshold_desc)
		val values = intArrayOf(5, 8, 10, 15, 20)
		chipRows[pref.key] = ChipRow(
			choices = values.map { kmh ->
				ChipOption(
					kmh.toString(),
					kmh.toString(),
					getString(R.string.ev_bms_n_kmh, kmh),
				)
			},
			read = { plugin.movingSpeedThresholdKmh().toString() },
			write = { raw ->
				raw.toIntOrNull()?.let { plugin.MOVING_SPEED_THRESHOLD_KMH.set(it) }
			},
		)
	}

	private fun setupWakeKeyChips() {
		val pref = findPreference<Preference>(plugin.MANUAL_WAKE_KEY.id) ?: return
		pref.summary = getString(R.string.voice_gps_manual_wake_key_desc)
		chipRows[pref.key] = ChipRow(
			choices = listOf(
				ChipOption(
					VoiceGpsPlugin.MANUAL_WAKE_KEY_SIDE,
					getString(R.string.voice_gps_chip_side),
					getString(R.string.voice_gps_manual_wake_key_side),
				),
				ChipOption(
					VoiceGpsPlugin.MANUAL_WAKE_KEY_VOLUME_UP,
					getString(R.string.voice_gps_chip_vol_up),
					getString(R.string.voice_gps_manual_wake_key_volume_up),
				),
				ChipOption(
					VoiceGpsPlugin.MANUAL_WAKE_KEY_VOLUME_DOWN,
					getString(R.string.voice_gps_chip_vol_down),
					getString(R.string.voice_gps_manual_wake_key_volume_down),
				),
			),
			read = { plugin.MANUAL_WAKE_KEY.get() },
			write = { plugin.MANUAL_WAKE_KEY.set(it) },
		)
	}

	private fun decorateRows() {
		decorate("voice_gps_request_mic", "🎤", R.drawable.ic_action_micro_dark)
		decorate(plugin.LISTEN_IN_BACKGROUND.id, "🔋", R.drawable.ic_action_battery)
		decorate(plugin.PAUSE_WHEN_MOVING.id, "⏸️", R.drawable.ic_action_trip_rec_pause)
		decorate(plugin.MOVING_SPEED_THRESHOLD_KMH.id, "🚴", R.drawable.ic_action_speed)
		decorate(plugin.PARTIAL_WAKE.id, "👂", R.drawable.ic_action_micro_dark)
		decorate(plugin.MANUAL_WAKE.id, "✋", R.drawable.ic_action_keyboard)
		decorate(plugin.MANUAL_WAKE_KEY.id, "🔘", R.drawable.ic_action_keyboard)
		decorate(plugin.SHOW_VOICE_GPX_ON_MAP.id, "🗺️", R.drawable.ic_action_waypoint)
	}

	private fun decorate(key: String, emoji: String, iconRes: Int) {
		val pref = findPreference<Preference>(key) ?: return
		val title = pref.title?.toString().orEmpty()
		if (title.isNotEmpty() && !title.startsWith(emoji)) {
			pref.title = "$emoji $title"
		}
		pref.icon = getContentIcon(iconRes)
	}

	private fun updateDependentRows() {
		findPreference<Preference>(plugin.MOVING_SPEED_THRESHOLD_KMH.id)?.isEnabled =
			plugin.PAUSE_WHEN_MOVING.get()
		val manual = plugin.manualWakeEnabled()
		findPreference<Preference>(plugin.MANUAL_WAKE_KEY.id)?.isEnabled = manual
		findPreference<SwitchPreferenceEx>(plugin.PARTIAL_WAKE.id)?.isEnabled = !manual
	}

	override fun onBindPreferenceViewHolder(preference: Preference, holder: PreferenceViewHolder) {
		super.onBindPreferenceViewHolder(preference, holder)
		if (preference is SwitchPreferenceEx) {
			val sw = holder.findViewById(R.id.switchWidget) as? SwitchCompat
			if (sw != null) {
				sw.setOnCheckedChangeListener(null)
				sw.isChecked = preference.isChecked
				sw.isClickable = false
				sw.isFocusable = false
			}
		}
		bindChips(preference, holder)
	}

	private fun bindChips(preference: Preference, holder: PreferenceViewHolder) {
		val row = chipRows[preference.key] ?: return
		val container = holder.findViewById(R.id.voice_gps_chips) as? LinearLayout ?: return
		container.removeAllViews()
		val selected = row.read()
		val enabled = preference.isEnabled
		val ctx = preference.context
		val padH = AndroidUtils.dpToPx(ctx, 10f)
		val padV = AndroidUtils.dpToPx(ctx, 4f)
		val gap = AndroidUtils.dpToPx(ctx, 6f)
		val minH = AndroidUtils.dpToPx(ctx, 28f)
		for ((index, choice) in row.choices.withIndex()) {
			val chip = TextView(ctx)
			chip.text = choice.label
			chip.contentDescription = choice.description
			chip.gravity = Gravity.CENTER
			chip.minHeight = minH
			chip.setPadding(padH, padV, padH, padV)
			chip.setTextSize(
				TypedValue.COMPLEX_UNIT_PX,
				resources.getDimension(R.dimen.default_desc_text_size)
			)
			chip.isEnabled = enabled
			styleChip(chip, choice.value == selected, enabled)
			val lp = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT,
				LinearLayout.LayoutParams.WRAP_CONTENT
			)
			if (index > 0) {
				lp.marginStart = gap
			}
			chip.layoutParams = lp
			chip.setOnClickListener {
				if (!preference.isEnabled || row.read() == choice.value) {
					return@setOnClickListener
				}
				row.write(choice.value)
				plugin.syncListeningService()
				updatePreference(preference)
			}
			container.addView(chip)
		}
	}

	private fun styleChip(chip: TextView, selected: Boolean, enabled: Boolean) {
		val night = isNightMode()
		val active = ColorUtilities.getActiveColor(chip.context, night)
		val secondary = ColorUtilities.getSecondaryTextColor(chip.context, night)
		val primary = ColorUtilities.getPrimaryTextColor(chip.context, night)
		chip.setTextColor(
			when {
				!enabled -> secondary
				selected -> Color.WHITE
				else -> primary
			}
		)
		val bg = GradientDrawable()
		bg.cornerRadius = AndroidUtils.dpToPx(chip.context, 8f).toFloat()
		bg.setColor(
			if (selected && enabled) {
				active
			} else {
				ColorUtilities.getColorWithAlpha(secondary, 0.18f)
			}
		)
		chip.background = bg
	}

	override fun onDisplayPreferenceDialog(preference: Preference) {
		if (preference is SwitchPreferenceEx) {
			val next = !preference.isChecked
			if (preference.callChangeListener(next)) {
				preference.isChecked = next
			}
			return
		}
		super.onDisplayPreferenceDialog(preference)
	}

	private fun updatePermissionSummary() {
		val pref = findPreference<Preference>("voice_gps_request_mic")
		val granted = plugin.hasRecordAudioPermission()
		pref?.summary = getString(
			if (granted) R.string.voice_gps_mic_granted else R.string.voice_gps_mic_denied
		)
	}

	override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
		val result = super.onPreferenceChange(preference, newValue)
		when (preference.key) {
			plugin.PAUSE_WHEN_MOVING.id -> {
				val moving = newValue as? Boolean == true
				findPreference<Preference>(plugin.MOVING_SPEED_THRESHOLD_KMH.id)?.let {
					it.isEnabled = moving
					updatePreference(it)
				}
			}
			plugin.MANUAL_WAKE.id -> {
				val manual = newValue as? Boolean == true
				findPreference<Preference>(plugin.MANUAL_WAKE_KEY.id)?.let {
					it.isEnabled = manual
					updatePreference(it)
				}
				findPreference<SwitchPreferenceEx>(plugin.PARTIAL_WAKE.id)?.isEnabled = !manual
			}
			plugin.LISTEN_IN_BACKGROUND.id,
			plugin.PARTIAL_WAKE.id,
			plugin.SHOW_VOICE_GPX_ON_MAP.id -> Unit
			else -> return result
		}
		view?.post { plugin.syncListeningService() }
		return result
	}

	override fun onPreferenceClick(preference: Preference): Boolean {
		when (preference.key) {
			"voice_gps_request_mic" -> {
				val activity = activity ?: return true
				if (!plugin.hasRecordAudioPermission()) {
					plugin.requestRecordAudio(activity)
				} else {
					plugin.syncListeningService()
				}
				return true
			}
			"voice_gps_online_stt_button" -> {
				val activity = activity ?: return true
				dismissParentSheet()
				plugin.beginOnlineVoiceNote(activity)
				return true
			}
			plugin.MOVING_SPEED_THRESHOLD_KMH.id,
			plugin.MANUAL_WAKE_KEY.id -> return true
		}
		return super.onPreferenceClick(preference)
	}

	override fun onResume() {
		super.onResume()
		updatePermissionSummary()
	}

	override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
		if (requestCode == VoiceGpsPlugin.REQUEST_RECORD_AUDIO) {
			if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				plugin.syncListeningService()
			}
			updatePermissionSummary()
		} else {
			super.onRequestPermissionsResult(requestCode, permissions, grantResults)
		}
	}
}
