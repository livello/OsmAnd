package net.osmand.plus.plugins.evbms

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.fragment.app.FragmentManager
import net.osmand.plus.R
import net.osmand.plus.base.MenuBottomSheetDialogFragment
import net.osmand.plus.base.bottomsheetmenu.BaseBottomSheetItem
import net.osmand.plus.base.bottomsheetmenu.simpleitems.TitleItem
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.plugins.monitoring.TripRecordingBottomSheet
import net.osmand.plus.plugins.monitoring.TripRecordingBottomSheet.ItemType
import net.osmand.plus.utils.AndroidUtils
import net.osmand.plus.utils.InsetTarget
import net.osmand.plus.utils.InsetTarget.Type
import net.osmand.plus.utils.InsetTargetsCollection

class EvBmsSettingsBottomSheet : MenuBottomSheetDialogFragment() {

	companion object {
		val TAG: String = EvBmsSettingsBottomSheet::class.java.simpleName
		private const val SETTINGS_TAG = "ev_bms_settings_embedded"

		fun showInstance(fragmentManager: FragmentManager) {
			if (AndroidUtils.isFragmentCanBeAdded(fragmentManager, TAG)) {
				EvBmsSettingsBottomSheet().show(fragmentManager, TAG)
			}
		}
	}

	private val plugin = PluginsHelper.requirePlugin(EvBmsPlugin::class.java)
	private var buttonsParent: ViewGroup? = null
	private var buttonsBar: View? = null
	private var lastCalRunning = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		usedOnMap = true
	}

	override fun createMenuItems(savedInstanceState: Bundle?) {
		items.add(TitleItem(getString(R.string.ev_bms_plugin_name)))
		val host = inflate(R.layout.ev_bms_settings_bottom_sheet)
		val height = (AndroidUtils.getScreenHeight(requireActivity()) * 0.62f).toInt()
		host.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
		items.add(BaseBottomSheetItem.Builder().setCustomView(host).create())
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		if (childFragmentManager.findFragmentByTag(SETTINGS_TAG) == null) {
			val fragment = EvBmsSettingsFragment()
			fragment.arguments = Bundle().apply {
				putBoolean(EvBmsSettingsFragment.EMBEDDED_KEY, true)
			}
			childFragmentManager.beginTransaction()
				.replace(R.id.ev_bms_settings_container, fragment, SETTINGS_TAG)
				.commitNowAllowingStateLoss()
		}
	}

	override fun setupBottomButtons(view: ViewGroup) {
		buttonsParent = view
		lastCalRunning = plugin.isSpeedCalibrating()
		rebuildBottomButtons()
	}

	fun onCalibrationTick() {
		val running = plugin.isSpeedCalibrating()
		if (running != lastCalRunning) {
			lastCalRunning = running
			rebuildBottomButtons()
		}
	}

	private fun rebuildBottomButtons() {
		val parent = buttonsParent ?: return
		buttonsBar?.let { parent.removeView(it) }
		val contentPadding = getDimensionPixelSize(R.dimen.content_padding)
		val topPadding = getDimensionPixelSize(R.dimen.context_menu_first_line_top_margin)
		val paused = plugin.isTelemetryPaused()
		val bar = inflate(R.layout.preference_button_with_icon_triple)
		bar.setPadding(contentPadding, topPadding, contentPadding, contentPadding)
		parent.addView(bar)
		buttonsBar = bar

		val cancelButton = bar.findViewById<CardView>(R.id.button_left)
		TripRecordingBottomSheet.createItem(app, nightMode, cancelButton, ItemType.CANCEL, true, null)
		cancelButton.setOnClickListener { dismiss() }

		val centerButton = bar.findViewById<CardView>(R.id.button_center)
		val rightButton = bar.findViewById<CardView>(R.id.button_right)
		if (paused) {
			TripRecordingBottomSheet.createItem(app, nightMode, centerButton, ItemType.STOP, true, null)
			centerButton.setOnClickListener {
				plugin.stopTelemetryRecording()
				refreshRecordingPref()
				rebuildBottomButtons()
			}
			TripRecordingBottomSheet.createItemActive(app, nightMode, rightButton, ItemType.RESUME)
			rightButton.setOnClickListener {
				plugin.resumeTelemetryRecording()
				refreshRecordingPref()
				rebuildBottomButtons()
			}
		} else {
			bindCalibrateButton(centerButton)
			if (plugin.isTelemetryRecording()) {
				TripRecordingBottomSheet.createItem(app, nightMode, rightButton, ItemType.PAUSE, true, null)
				rightButton.setOnClickListener {
					plugin.pauseTelemetryRecording()
					refreshRecordingPref()
					rebuildBottomButtons()
				}
			} else {
				TripRecordingBottomSheet.createItemActive(app, nightMode, rightButton, ItemType.START_RECORDING)
				rightButton.setOnClickListener {
					plugin.startTelemetryRecording()
					refreshRecordingPref()
					rebuildBottomButtons()
				}
			}
		}
	}

	private fun bindCalibrateButton(button: CardView) {
		if (plugin.isSpeedCalibrating()) {
			TripRecordingBottomSheet.createItem(app, nightMode, button, ItemType.STOP, true, null)
			button.findViewById<TextView>(R.id.button_text)?.setText(R.string.ev_bms_calibrate_stop)
			button.setOnClickListener {
				plugin.stopSpeedCalibration()
				refreshCalibrationPref()
				rebuildBottomButtons()
			}
		} else {
			TripRecordingBottomSheet.createItem(app, nightMode, button, ItemType.START_NEW_SEGMENT, true, null)
			button.findViewById<TextView>(R.id.button_text)?.setText(R.string.ev_bms_calibrate)
			button.setOnClickListener {
				plugin.startSpeedCalibration()
				refreshCalibrationPref()
				rebuildBottomButtons()
			}
		}
	}

	private fun refreshRecordingPref() {
		(childFragmentManager.findFragmentByTag(SETTINGS_TAG) as? EvBmsSettingsFragment)
			?.refreshRecordingPref()
	}

	private fun refreshCalibrationPref() {
		(childFragmentManager.findFragmentByTag(SETTINGS_TAG) as? EvBmsSettingsFragment)
			?.refreshCalibrationPref()
	}

	override fun getInsetTargets(): InsetTargetsCollection {
		val collection = super.getInsetTargets()
		collection.removeType(Type.SCROLLABLE)
		collection.replace(InsetTarget.createBottomContainer(R.id.triple_bottom_buttons))
		return collection
	}

	override fun useScrollableItemsContainer(): Boolean = false
}
