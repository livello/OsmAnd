package net.osmand.plus.plugins.loraosmand

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import net.osmand.plus.R
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.settings.fragments.BaseSettingsFragment
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

class LoraSettingsFragment : BaseSettingsFragment() {

	companion object {
		const val EMBEDDED_KEY = "lora_osmand_settings_embedded"
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
		}
		return view
	}

	override fun setupPreferences() {
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
		}
		return super.onPreferenceClick(preference)
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
