package net.osmand.plus.plugins.voicegps

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.TooltipCompat
import androidx.core.graphics.Insets
import androidx.fragment.app.FragmentManager
import net.osmand.plus.R
import net.osmand.plus.base.MenuBottomSheetDialogFragment
import net.osmand.plus.base.bottomsheetmenu.BaseBottomSheetItem
import net.osmand.plus.OsmAndTaskManager.OsmAndTaskRunnable
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.utils.AndroidUtils
import net.osmand.plus.utils.InsetTarget
import net.osmand.plus.utils.InsetTarget.Type
import net.osmand.plus.utils.InsetTargetsCollection

class VoiceGpsSettingsBottomSheet : MenuBottomSheetDialogFragment() {

	companion object {
		val TAG: String = VoiceGpsSettingsBottomSheet::class.java.simpleName
		private const val SETTINGS_TAG = "voice_gps_settings_embedded"
		private const val NOTES_TAG = "voice_gps_notes_embedded"

		fun showInstance(fragmentManager: FragmentManager) {
			if (AndroidUtils.isFragmentCanBeAdded(fragmentManager, TAG)) {
				VoiceGpsSettingsBottomSheet().show(fragmentManager, TAG)
			}
		}
	}

	private val plugin: VoiceGpsPlugin
		get() = PluginsHelper.requirePlugin(VoiceGpsPlugin::class.java)

	private var noteBar: View? = null
	private var noteButtonVisible = true
	private var activeTab = VoiceGpsSheetTab.SETTINGS
	private val tabButtons = HashMap<VoiceGpsSheetTab, TextView>()
	private var offlineDialogShown = false

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
		bindTabs(view)
		if (childFragmentManager.findFragmentByTag(SETTINGS_TAG) == null) {
			val fragment = VoiceGpsSettingsFragment()
			fragment.arguments = Bundle().apply {
				putBoolean(VoiceGpsSettingsFragment.EMBEDDED_KEY, true)
			}
			childFragmentManager.beginTransaction()
				.replace(R.id.voice_gps_settings_container, fragment, SETTINGS_TAG)
				.commitNowAllowingStateLoss()
		}
		if (childFragmentManager.findFragmentByTag(NOTES_TAG) == null) {
			childFragmentManager.beginTransaction()
				.replace(R.id.voice_gps_notes_container, VoiceGpsNotesFragment(), NOTES_TAG)
				.commitNowAllowingStateLoss()
		}
		showTab(VoiceGpsSheetTab.SETTINGS)
		view.post { maybeShowOfflineSpeechDialog() }
	}

	private fun bindTabs(root: View) {
		tabButtons[VoiceGpsSheetTab.SETTINGS] = root.findViewById(R.id.tab_settings)
		tabButtons[VoiceGpsSheetTab.NOTES] = root.findViewById(R.id.tab_notes)
		tabButtons[VoiceGpsSheetTab.SETTINGS]?.contentDescription = getString(R.string.shared_string_settings)
		tabButtons[VoiceGpsSheetTab.NOTES]?.contentDescription = getString(R.string.voice_gps_tab_notes)
		for ((tab, button) in tabButtons) {
			TooltipCompat.setTooltipText(button, button.contentDescription)
			button.setOnClickListener { showTab(tab) }
		}
	}

	private fun showTab(tab: VoiceGpsSheetTab) {
		activeTab = tab
		val root = view ?: return
		root.findViewById<View>(R.id.voice_gps_settings_container).visibility =
			if (tab == VoiceGpsSheetTab.SETTINGS) View.VISIBLE else View.GONE
		root.findViewById<View>(R.id.voice_gps_notes_container).visibility =
			if (tab == VoiceGpsSheetTab.NOTES) View.VISIBLE else View.GONE
		root.findViewById<View>(R.id.voice_gps_buttons_overlay).visibility =
			if (tab == VoiceGpsSheetTab.SETTINGS) View.VISIBLE else View.GONE
		for ((key, button) in tabButtons) {
			button.alpha = if (key == tab) 1f else 0.38f
		}
		if (tab == VoiceGpsSheetTab.NOTES) {
			(childFragmentManager.findFragmentByTag(NOTES_TAG) as? VoiceGpsNotesFragment)?.reload()
		}
		if (tab == VoiceGpsSheetTab.SETTINGS) {
			noteBar?.let { VoiceGpsNoteBar.setVisible(it, noteButtonVisible, animate = false) }
		}
	}

	private fun maybeShowOfflineSpeechDialog() {
		if (offlineDialogShown || plugin.preferOnlineStt()) {
			return
		}
		val ctx = context ?: return
		app.getTaskManager().runInBackground(object : OsmAndTaskRunnable<Void, Void, List<VoiceGpsOfflineSpeechHelper.MissingLocale>>() {
			override fun doInBackground(vararg params: Void?): List<VoiceGpsOfflineSpeechHelper.MissingLocale> {
				return VoiceGpsOfflineSpeechHelper.missingOfflineLocales(ctx)
			}

			override fun onPostExecute(missing: List<VoiceGpsOfflineSpeechHelper.MissingLocale>?) {
				if (missing.isNullOrEmpty() || offlineDialogShown || !isAdded) {
					return
				}
				offlineDialogShown = true
				val message = VoiceGpsOfflineSpeechHelper.missingLocalesMessage(ctx, missing)
				AlertDialog.Builder(ctx)
					.setTitle(R.string.voice_gps_offline_stt_missing_title)
					.setMessage(message)
					.setPositiveButton(R.string.voice_gps_offline_stt_download) { _, _ ->
						VoiceGpsOfflineSpeechHelper.openOfflineSpeechDownloadSettings(ctx)
					}
					.setNegativeButton(R.string.shared_string_cancel, null)
					.show()
			}
		})
	}

	override fun setupBottomButtons(view: ViewGroup) {
		val parent = view.findViewById<ViewGroup>(R.id.voice_gps_buttons_overlay) ?: return
		noteBar?.let { parent.removeView(it) }
		val bar = VoiceGpsNoteBar.create(requireContext(), nightMode) { startVoiceNote() }
		parent.addView(bar)
		noteBar = bar
		noteButtonVisible = true
		VoiceGpsNoteBar.setVisible(bar, visible = activeTab == VoiceGpsSheetTab.SETTINGS, animate = false)
	}

	override fun hideButtonsContainer(): Boolean = true

	override fun useScrollableItemsContainer(): Boolean = false

	fun setNoteButtonVisible(visible: Boolean) {
		if (activeTab != VoiceGpsSheetTab.SETTINGS) {
			return
		}
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
		plugin.beginManualSession(host)
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
