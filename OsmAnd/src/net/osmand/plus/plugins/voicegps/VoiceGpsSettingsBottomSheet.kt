package net.osmand.plus.plugins.voicegps

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.fragment.app.FragmentManager
import net.osmand.plus.R
import net.osmand.plus.base.MenuBottomSheetDialogFragment
import net.osmand.plus.base.bottomsheetmenu.BaseBottomSheetItem
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.utils.AndroidUtils
import net.osmand.plus.utils.InsetTarget
import net.osmand.plus.utils.InsetTarget.Type
import net.osmand.plus.utils.InsetTargetsCollection

/**
 * Full-height settings sheet. The emoji note bar hides once the preference list
 * leaves the top, and returns when the list is scrolled back up — same as EV telemetry.
 */
class VoiceGpsSettingsBottomSheet : MenuBottomSheetDialogFragment() {

	companion object {
		val TAG: String = VoiceGpsSettingsBottomSheet::class.java.simpleName
		private const val SETTINGS_TAG = "voice_gps_settings_embedded"

		fun showInstance(fragmentManager: FragmentManager) {
			if (AndroidUtils.isFragmentCanBeAdded(fragmentManager, TAG)) {
				VoiceGpsSettingsBottomSheet().show(fragmentManager, TAG)
			}
		}
	}

	private var noteBar: View? = null
	private var noteButtonVisible = true

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		usedOnMap = true
	}

	override fun createMenuItems(savedInstanceState: Bundle?) {
		val host = inflate(R.layout.voice_gps_settings_bottom_sheet)
		val activity = requireActivity()
		val height = AndroidUtils.getScreenHeight(activity) -
				AndroidUtils.getStatusBarHeight(activity) -
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
		val available = AndroidUtils.getScreenHeight(activity) -
				sysBars.top - sysBars.bottom - AndroidUtils.dpToPx(activity, 8f)
		itemsContainer?.layoutParams?.height = available
		itemsContainer?.requestLayout()
		mainView.findViewById<View>(R.id.voice_gps_settings_host)?.let {
			it.layoutParams.height = available
			it.requestLayout()
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		if (childFragmentManager.findFragmentByTag(SETTINGS_TAG) == null) {
			val fragment = VoiceGpsSettingsFragment()
			fragment.arguments = Bundle().apply {
				putBoolean(VoiceGpsSettingsFragment.EMBEDDED_KEY, true)
			}
			childFragmentManager.beginTransaction()
				.replace(R.id.voice_gps_settings_container, fragment, SETTINGS_TAG)
				.commitNowAllowingStateLoss()
		}
	}

	override fun setupBottomButtons(view: ViewGroup) {
		val parent = view.findViewById<ViewGroup>(R.id.voice_gps_buttons_overlay) ?: return
		noteBar?.let { parent.removeView(it) }
		val bar = VoiceGpsNoteBar.create(requireContext(), nightMode) { startVoiceNote() }
		parent.addView(bar)
		noteBar = bar
		noteButtonVisible = true
		VoiceGpsNoteBar.setVisible(bar, visible = true, animate = false)
	}

	override fun hideButtonsContainer(): Boolean = true

	override fun useScrollableItemsContainer(): Boolean = false

	fun setNoteButtonVisible(visible: Boolean) {
		if (noteButtonVisible == visible) {
			return
		}
		noteButtonVisible = visible
		val bar = noteBar ?: return
		VoiceGpsNoteBar.setVisible(bar, visible, animate = true)
		settingsFragment()?.setNoteBarInset(visible)
	}

	private fun startVoiceNote() {
		val host = activity
		dismissAllowingStateLoss()
		PluginsHelper.getPlugin(VoiceGpsPlugin::class.java)?.beginManualSession(host)
	}

	private fun settingsFragment(): VoiceGpsSettingsFragment? {
		return childFragmentManager.findFragmentByTag(SETTINGS_TAG) as? VoiceGpsSettingsFragment
	}

	override fun getInsetTargets(): InsetTargetsCollection {
		val collection = super.getInsetTargets()
		collection.removeType(Type.SCROLLABLE)
		collection.replace(InsetTarget.createBottomContainer(R.id.voice_gps_buttons_overlay))
		return collection
	}
}
