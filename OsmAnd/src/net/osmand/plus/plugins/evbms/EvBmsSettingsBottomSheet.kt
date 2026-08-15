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
	private var startStopButton: CardView? = null

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
		val contentPadding = getDimensionPixelSize(R.dimen.content_padding)
		val topPadding = getDimensionPixelSize(R.dimen.context_menu_first_line_top_margin)
		val buttonsContainer = inflate(R.layout.preference_button_with_icon_double)
		buttonsContainer.setPadding(contentPadding, topPadding, contentPadding, contentPadding)
		view.addView(buttonsContainer)

		val cancelButton = buttonsContainer.findViewById<CardView>(R.id.button_left)
		TripRecordingBottomSheet.createItem(app, nightMode, cancelButton, ItemType.CANCEL, true, null)
		cancelButton.setOnClickListener { dismiss() }

		val actionButton = buttonsContainer.findViewById<CardView>(R.id.button_right)
		startStopButton = actionButton
		bindStartStopButton(actionButton)
		actionButton.setOnClickListener { toggleTelemetryRecording() }
	}

	private fun toggleTelemetryRecording() {
		if (plugin.isTelemetryRecording()) {
			plugin.stopTelemetryRecording()
		} else {
			plugin.startTelemetryRecording()
		}
		startStopButton?.let { bindStartStopButton(it) }
		(childFragmentManager.findFragmentByTag(SETTINGS_TAG) as? EvBmsSettingsFragment)
			?.refreshRecordingPref()
	}

	private fun bindStartStopButton(button: CardView) {
		if (plugin.isTelemetryRecording()) {
			TripRecordingBottomSheet.createItem(app, nightMode, button, ItemType.STOP, true, null)
		} else {
			TripRecordingBottomSheet.createItemActive(app, nightMode, button, ItemType.START_RECORDING)
		}
	}

	override fun getInsetTargets(): InsetTargetsCollection {
		val collection = super.getInsetTargets()
		collection.removeType(Type.SCROLLABLE)
		collection.replace(InsetTarget.createBottomContainer(R.id.double_bottom_buttons))
		return collection
	}

	override fun useScrollableItemsContainer(): Boolean = false
}
