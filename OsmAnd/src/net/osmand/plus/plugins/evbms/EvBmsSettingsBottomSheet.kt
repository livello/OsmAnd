package net.osmand.plus.plugins.evbms

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
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
		rebuildBottomButtons()
	}

	private fun rebuildBottomButtons() {
		val parent = buttonsParent ?: return
		buttonsBar?.let { parent.removeView(it) }
		val contentPadding = getDimensionPixelSize(R.dimen.content_padding)
		val topPadding = getDimensionPixelSize(R.dimen.context_menu_first_line_top_margin)
		val paused = plugin.isTelemetryPaused()
		val bar = inflate(
			if (paused) R.layout.preference_button_with_icon_triple
			else R.layout.preference_button_with_icon_double
		)
		bar.setPadding(contentPadding, topPadding, contentPadding, contentPadding)
		parent.addView(bar)
		buttonsBar = bar

		val cancelButton = bar.findViewById<CardView>(R.id.button_left)
		TripRecordingBottomSheet.createItem(app, nightMode, cancelButton, ItemType.CANCEL, true, null)
		cancelButton.setOnClickListener { dismiss() }

		if (paused) {
			val stopButton = bar.findViewById<CardView>(R.id.button_center)
			TripRecordingBottomSheet.createItem(app, nightMode, stopButton, ItemType.STOP, true, null)
			stopButton.setOnClickListener {
				plugin.stopTelemetryRecording()
				refreshRecordingPref()
				rebuildBottomButtons()
			}
			val resumeButton = bar.findViewById<CardView>(R.id.button_right)
			TripRecordingBottomSheet.createItemActive(app, nightMode, resumeButton, ItemType.RESUME)
			resumeButton.setOnClickListener {
				plugin.resumeTelemetryRecording()
				refreshRecordingPref()
				rebuildBottomButtons()
			}
		} else {
			val actionButton = bar.findViewById<CardView>(R.id.button_right)
			if (plugin.isTelemetryRecording()) {
				TripRecordingBottomSheet.createItem(app, nightMode, actionButton, ItemType.PAUSE, true, null)
				actionButton.setOnClickListener {
					plugin.pauseTelemetryRecording()
					refreshRecordingPref()
					rebuildBottomButtons()
				}
			} else {
				TripRecordingBottomSheet.createItemActive(app, nightMode, actionButton, ItemType.START_RECORDING)
				actionButton.setOnClickListener {
					plugin.startTelemetryRecording()
					refreshRecordingPref()
					rebuildBottomButtons()
				}
			}
		}
	}

	private fun refreshRecordingPref() {
		(childFragmentManager.findFragmentByTag(SETTINGS_TAG) as? EvBmsSettingsFragment)
			?.refreshRecordingPref()
	}

	override fun getInsetTargets(): InsetTargetsCollection {
		val collection = super.getInsetTargets()
		collection.removeType(Type.SCROLLABLE)
		val id = if (plugin.isTelemetryPaused()) {
			R.id.triple_bottom_buttons
		} else {
			R.id.double_bottom_buttons
		}
		collection.replace(InsetTarget.createBottomContainer(id))
		return collection
	}

	override fun useScrollableItemsContainer(): Boolean = false
}
