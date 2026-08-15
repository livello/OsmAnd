package net.osmand.plus.plugins.evbms

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView
import androidx.core.graphics.Insets
import androidx.core.text.HtmlCompat
import androidx.fragment.app.FragmentManager
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import net.osmand.plus.R
import net.osmand.plus.base.MenuBottomSheetDialogFragment
import net.osmand.plus.base.bottomsheetmenu.BaseBottomSheetItem
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.plugins.monitoring.TripRecordingBottomSheet
import net.osmand.plus.plugins.monitoring.TripRecordingBottomSheet.ItemType
import net.osmand.plus.utils.AndroidUtils
import net.osmand.plus.utils.ColorUtilities
import net.osmand.plus.utils.InsetTarget
import net.osmand.plus.utils.InsetTarget.Type
import net.osmand.plus.utils.InsetTargetsCollection
import net.osmand.plus.utils.OsmAndFormatter
import net.osmand.plus.utils.UiUtilities
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
	private val uiHandler = Handler(Looper.getMainLooper())
	private var buttonsParent: ViewGroup? = null
	private var buttonsBar: View? = null
	private var lastCalRunning = false
	private var actionButtonsVisible = true
	private var currentTab = EvBmsSheetTab.SETTINGS
	private val tabButtons = HashMap<EvBmsSheetTab, TextView>()
	private val fieldValueViews = ArrayList<Pair<TelemetryField, TextView>>()
	private val chartRows = ArrayList<ChartRow>()
	private var chartsKey = ""
	private var fieldsBound = false
	private var aboutBound = false
	private var journalBound = false

	private data class ChartRow(
		val field: TelemetryField,
		val chart: LineChart,
		val value: TextView
	)

	private val liveTick = object : Runnable {
		override fun run() {
			if (view == null) {
				return
			}
			when (currentTab) {
				EvBmsSheetTab.FIELDS -> refreshFieldValues()
				EvBmsSheetTab.CHARTS -> refreshCharts()
				EvBmsSheetTab.JOURNAL -> refreshJournalTail()
				else -> {}
			}
			if (currentTab == EvBmsSheetTab.FIELDS || currentTab == EvBmsSheetTab.CHARTS ||
				currentTab == EvBmsSheetTab.JOURNAL
			) {
				uiHandler.postDelayed(this, 1000)
			}
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		usedOnMap = true
	}

	override fun createMenuItems(savedInstanceState: Bundle?) {
		val host = inflate(R.layout.ev_bms_settings_bottom_sheet)
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
		mainView.findViewById<View>(R.id.ev_bms_settings_host)?.let {
			it.layoutParams.height = available
			it.requestLayout()
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		bindTabs(view)
		if (childFragmentManager.findFragmentByTag(SETTINGS_TAG) == null) {
			val fragment = EvBmsSettingsFragment()
			fragment.arguments = Bundle().apply {
				putBoolean(EvBmsSettingsFragment.EMBEDDED_KEY, true)
			}
			childFragmentManager.beginTransaction()
				.replace(R.id.ev_bms_settings_container, fragment, SETTINGS_TAG)
				.commitNowAllowingStateLoss()
		}
		showTab(plugin.sheetTab(), persist = false)
	}

	override fun onDestroyView() {
		uiHandler.removeCallbacks(liveTick)
		super.onDestroyView()
	}

	override fun setupBottomButtons(view: ViewGroup) {
		buttonsParent = view.findViewById(R.id.ev_bms_buttons_overlay) ?: view
		lastCalRunning = plugin.isSpeedCalibrating()
		rebuildBottomButtons()
	}

	override fun hideButtonsContainer(): Boolean = true

	fun showTab(tab: EvBmsSheetTab, persist: Boolean = true) {
		currentTab = tab
		if (persist) {
			plugin.setSheetTab(tab)
		}
		val root = view ?: return
		root.findViewById<View>(R.id.ev_bms_settings_container).visibility =
			visibleIf(tab == EvBmsSheetTab.SETTINGS)
		root.findViewById<View>(R.id.ev_bms_fields_container).visibility =
			visibleIf(tab == EvBmsSheetTab.FIELDS)
		root.findViewById<View>(R.id.ev_bms_charts_scroll).visibility =
			visibleIf(tab == EvBmsSheetTab.CHARTS)
		root.findViewById<View>(R.id.ev_bms_history_scroll).visibility =
			visibleIf(tab == EvBmsSheetTab.HISTORY)
		root.findViewById<View>(R.id.ev_bms_journal_container).visibility =
			visibleIf(tab == EvBmsSheetTab.JOURNAL)
		root.findViewById<View>(R.id.ev_bms_about_scroll).visibility =
			visibleIf(tab == EvBmsSheetTab.ABOUT)
		for ((key, button) in tabButtons) {
			button.alpha = if (key == tab) 1f else 0.38f
		}
		when (tab) {
			EvBmsSheetTab.FIELDS -> bindFields()
			EvBmsSheetTab.CHARTS -> bindCharts(force = true)
			EvBmsSheetTab.HISTORY -> bindHistory()
			EvBmsSheetTab.JOURNAL -> bindJournal()
			EvBmsSheetTab.ABOUT -> bindAbout()
			EvBmsSheetTab.SETTINGS -> {}
		}
		val showActions = tab == EvBmsSheetTab.SETTINGS
		buttonsParent?.visibility = if (showActions && actionButtonsVisible) View.VISIBLE else View.GONE
		if (tab == EvBmsSheetTab.FIELDS || tab == EvBmsSheetTab.CHARTS || tab == EvBmsSheetTab.JOURNAL) {
			uiHandler.removeCallbacks(liveTick)
			uiHandler.post(liveTick)
		} else {
			uiHandler.removeCallbacks(liveTick)
		}
	}

	fun setActionButtonsVisible(visible: Boolean) {
		if (currentTab != EvBmsSheetTab.SETTINGS) {
			return
		}
		if (actionButtonsVisible == visible) {
			return
		}
		actionButtonsVisible = visible
		val overlay = buttonsParent
		val bar = buttonsBar
		if (overlay == null || bar == null) {
			return
		}
		bar.animate().cancel()
		overlay.animate().cancel()
		settingsFragment()?.setActionFooterInset(visible)
		if (visible) {
			overlay.visibility = View.VISIBLE
			bar.visibility = View.VISIBLE
			overlay.animate().alpha(1f).setDuration(160).start()
			bar.animate().alpha(1f).setDuration(160).start()
		} else {
			overlay.animate().alpha(0f).setDuration(160).withEndAction {
				if (!actionButtonsVisible) {
					overlay.visibility = View.GONE
					bar.visibility = View.GONE
				}
			}.start()
			bar.animate().alpha(0f).setDuration(160).start()
		}
	}

	fun onCalibrationTick() {
		val running = plugin.isSpeedCalibrating()
		if (running != lastCalRunning) {
			lastCalRunning = running
			rebuildBottomButtons()
		}
	}

	private fun bindTabs(root: View) {
		tabButtons[EvBmsSheetTab.SETTINGS] = root.findViewById(R.id.tab_settings)
		tabButtons[EvBmsSheetTab.FIELDS] = root.findViewById(R.id.tab_fields)
		tabButtons[EvBmsSheetTab.CHARTS] = root.findViewById(R.id.tab_charts)
		tabButtons[EvBmsSheetTab.HISTORY] = root.findViewById(R.id.tab_history)
		tabButtons[EvBmsSheetTab.JOURNAL] = root.findViewById(R.id.tab_journal)
		tabButtons[EvBmsSheetTab.ABOUT] = root.findViewById(R.id.tab_about)
		tabButtons[EvBmsSheetTab.SETTINGS]?.contentDescription = getString(R.string.shared_string_settings)
		tabButtons[EvBmsSheetTab.FIELDS]?.contentDescription = getString(R.string.ev_bms_telemetry_fields)
		tabButtons[EvBmsSheetTab.CHARTS]?.contentDescription = getString(R.string.ev_bms_tab_charts)
		tabButtons[EvBmsSheetTab.HISTORY]?.contentDescription = getString(R.string.ev_bms_tab_history)
		tabButtons[EvBmsSheetTab.JOURNAL]?.contentDescription = getString(R.string.ev_bms_tab_journal)
		tabButtons[EvBmsSheetTab.ABOUT]?.contentDescription = getString(R.string.ev_bms_tab_about)
		for ((tab, button) in tabButtons) {
			button.setOnClickListener { showTab(tab) }
		}
	}

	private fun bindFields() {
		val container = view?.findViewById<ViewGroup>(R.id.ev_bms_fields_container) ?: return
		if (fieldsBound && container.childCount > 0) {
			refreshFieldValues()
			return
		}
		container.removeAllViews()
		fieldValueViews.clear()
		val themed = UiUtilities.getThemedContext(requireActivity(), nightMode)
		val inflater = layoutInflater
		val content = inflater.inflate(R.layout.ev_bms_telemetry_fields_dialog, container, false)
		content.findViewById<View>(R.id.fields_actions).visibility = View.GONE
		container.addView(content)
		val selected = plugin.selectedTelemetryFields().toMutableSet()
		val list = content.findViewById<LinearLayout>(R.id.fields_list)
		val checkboxes = ArrayList<Pair<TelemetryField, CheckBox>>()
		fun persist() {
			val chosen = TelemetryField.entries.filter { it in selected }
			if (chosen.isEmpty()) {
				app.showToastMessage(R.string.ev_bms_telemetry_fields_empty)
				return
			}
			plugin.setTelemetryFields(chosen)
			settingsFragment()?.refreshTelemetryFieldsPref()
			chartsKey = ""
		}
		fun bindChecks() {
			for ((field, box) in checkboxes) {
				box.isChecked = field in selected
			}
		}
		content.findViewById<TextView>(R.id.select_all).apply {
			contentDescription = getString(R.string.shared_string_select_all)
			setOnClickListener {
				selected.clear()
				selected.addAll(TelemetryField.entries)
				bindChecks()
				persist()
			}
		}
		content.findViewById<TextView>(R.id.reset).apply {
			contentDescription = getString(R.string.shared_string_reset)
			setOnClickListener {
				selected.clear()
				selected.addAll(TelemetryField.parse(TelemetryField.DEFAULT_IDS))
				bindChecks()
				persist()
			}
		}
		for ((groupRes, fields) in TelemetryField.grouped()) {
			list.addView(telemetryGroupHeader(themed, groupRes))
			for (field in fields) {
				val row = inflater.inflate(R.layout.ev_bms_telemetry_field_row, list, false)
				val box = row.findViewById<CheckBox>(R.id.compound_button)
				val title = row.findViewById<TextView>(R.id.title)
				val value = row.findViewById<TextView>(R.id.value)
				title.text = "${field.emoji} ${getString(field.titleRes)}"
				value.text = field.liveValue(themed, plugin.latestTelemetry)
				box.isChecked = field in selected
				UiUtilities.setupCompoundButton(box, nightMode, UiUtilities.CompoundButtonType.GLOBAL)
				row.setOnClickListener {
					box.isChecked = !box.isChecked
					if (box.isChecked) selected.add(field) else selected.remove(field)
					persist()
				}
				checkboxes.add(field to box)
				fieldValueViews.add(field to value)
				list.addView(row)
			}
		}
		fieldsBound = true
	}

	private fun telemetryGroupHeader(themed: android.content.Context, groupRes: Int): View {
		val hPad = AndroidUtils.dpToPx(themed, 16f)
		val line = View(themed).apply {
			layoutParams = LinearLayout.LayoutParams(0, AndroidUtils.dpToPx(themed, 1f), 1f)
			setBackgroundColor(ColorUtilities.getDividerColor(themed, nightMode))
		}
		val label = TextView(themed).apply {
			text = "${TelemetryField.groupEmoji(groupRes)} ${getString(groupRes)}"
			setTextColor(ColorUtilities.getSecondaryTextColor(themed, nightMode))
			textSize = 12f
			maxLines = 1
			setPadding(AndroidUtils.dpToPx(themed, 8f), 0, 0, 0)
		}
		return LinearLayout(themed).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = android.view.Gravity.CENTER_VERTICAL
			setPadding(hPad, AndroidUtils.dpToPx(themed, 4f), hPad, AndroidUtils.dpToPx(themed, 2f))
			addView(line)
			addView(label)
		}
	}

	private fun refreshFieldValues() {
		val ctx = context ?: return
		val sample = plugin.latestTelemetry
		for ((field, view) in fieldValueViews) {
			view.text = field.liveValue(ctx, sample)
		}
	}

	private fun bindCharts(force: Boolean) {
		val list = view?.findViewById<LinearLayout>(R.id.ev_bms_charts_list) ?: return
		val fields = plugin.selectedTelemetryFields().filter { it.isChartable() }
		val key = fields.joinToString(",") { it.id }
		if (!force && key == chartsKey && chartRows.isNotEmpty()) {
			refreshCharts()
			return
		}
		chartsKey = key
		list.removeAllViews()
		chartRows.clear()
		if (fields.isEmpty()) {
			list.addView(emptyHint(getString(R.string.ev_bms_charts_no_fields)))
			return
		}
		val inflater = layoutInflater
		val ctx = requireContext()
		for (field in fields) {
			val row = inflater.inflate(R.layout.ev_bms_chart_row, list, false)
			val title = row.findViewById<TextView>(R.id.title)
			val value = row.findViewById<TextView>(R.id.value)
			val chart = row.findViewById<LineChart>(R.id.chart)
			title.text = "${field.emoji} ${getString(field.titleRes)}"
			value.text = field.liveValue(ctx, plugin.latestTelemetry)
			styleChart(chart)
			chartRows.add(ChartRow(field, chart, value))
			list.addView(row)
		}
		refreshCharts()
	}

	private fun styleChart(chart: LineChart) {
		val ctx = requireContext()
		val secondary = ColorUtilities.getSecondaryTextColor(ctx, nightMode)
		val divider = ColorUtilities.getDividerColor(ctx, nightMode)
		chart.description.isEnabled = false
		chart.legend.isEnabled = false
		chart.setTouchEnabled(false)
		chart.setScaleEnabled(false)
		chart.setPinchZoom(false)
		chart.setDrawGridBackground(false)
		chart.setDrawBorders(false)
		chart.minOffset = 0f
		chart.extraTopOffset = 2f
		chart.extraBottomOffset = 2f
		chart.extraRightOffset = 4f
		chart.axisRight.isEnabled = false
		chart.xAxis.position = XAxis.XAxisPosition.BOTTOM
		chart.xAxis.setDrawAxisLine(false)
		chart.xAxis.setDrawGridLines(false)
		chart.xAxis.setDrawLabels(false)
		chart.xAxis.setAvoidFirstLastClipping(true)
		chart.axisLeft.setDrawAxisLine(false)
		chart.axisLeft.setDrawGridLines(true)
		chart.axisLeft.gridColor = divider
		chart.axisLeft.textColor = secondary
		chart.axisLeft.textSize = 9f
		chart.axisLeft.setLabelCount(3, true)
		chart.axisLeft.setDrawTopYLabelEntry(true)
		chart.setNoDataText(getString(R.string.ev_bms_charts_empty))
		chart.setNoDataTextColor(secondary)
	}

	private fun refreshCharts() {
		val ctx = context ?: return
		val history = plugin.chartHistorySnapshot()
		val color = ColorUtilities.getActiveColor(ctx, nightMode)
		val sample = plugin.latestTelemetry
		for (row in chartRows) {
			row.value.text = row.field.liveValue(ctx, sample)
			val entries = ArrayList<Entry>()
			val t0 = history.firstOrNull()?.timeMs ?: 0L
			for (item in history) {
				val y = row.field.chartValue(item) ?: continue
				val x = ((item.timeMs - t0) / 1000.0).toFloat()
				entries.add(Entry(x, y.toFloat()))
			}
			if (entries.isEmpty()) {
				row.chart.clear()
				row.chart.invalidate()
				continue
			}
			if (entries.size == 1) {
				val only = entries[0]
				entries.add(Entry(only.x + 1f, only.y))
			}
			val set = LineDataSet(entries, "")
			set.setDrawCircles(false)
			set.setDrawValues(false)
			set.setDrawFilled(true)
			set.lineWidth = 1.6f
			set.color = color
			set.fillColor = color
			set.fillAlpha = 48
			set.setDrawHorizontalHighlightIndicator(false)
			set.setDrawVerticalHighlightIndicator(false)
			set.mode = LineDataSet.Mode.LINEAR
			row.chart.data = LineData(set)
			row.chart.invalidate()
		}
	}

	private fun bindHistory() {
		val list = view?.findViewById<LinearLayout>(R.id.ev_bms_history_list) ?: return
		list.removeAllViews()
		val inflater = layoutInflater
		val themed = UiUtilities.getThemedContext(requireContext(), nightMode)
		val charges = plugin.chargeHistory().asReversed()
		val trips = plugin.tripHistory().asReversed()
		list.addView(telemetryGroupHeader(themed, R.string.ev_bms_charge_history))
		if (charges.isEmpty()) {
			list.addView(emptyHint(getString(R.string.ev_bms_history_empty)))
		} else {
			for (row in charges) {
				val item = inflater.inflate(R.layout.ev_bms_history_row, list, false)
				item.findViewById<TextView>(R.id.title).text =
					"${fmtDateTime(row.startMs)} → ${fmtTime(row.endMs)}"
				item.findViewById<TextView>(R.id.description).text = buildString {
					append(getString(R.string.ev_bms_history_duration, fmtDuration(row.durationMs())))
					append(" · ")
					append(getString(R.string.ev_bms_history_charged_ah, n(row.chargedAh)))
				}
				item.findViewById<View>(R.id.delete_btn).setOnClickListener {
					plugin.deleteChargeRecord(row.startMs, row.endMs)
					settingsFragment()?.refreshHistoryPrefs()
					bindHistory()
				}
				list.addView(item)
			}
		}
		list.addView(telemetryGroupHeader(themed, R.string.ev_bms_trip_history))
		if (trips.isEmpty()) {
			list.addView(emptyHint(getString(R.string.ev_bms_history_empty)))
		} else {
			for (row in trips) {
				val item = inflater.inflate(R.layout.ev_bms_history_row, list, false)
				item.findViewById<TextView>(R.id.title).text =
					"${fmtDateTime(row.startMs)} → ${fmtTime(row.endMs)}"
				item.findViewById<TextView>(R.id.description).text = buildString {
					append(getString(R.string.ev_bms_history_distance, n(row.distanceKm)))
					append(" · ")
					append(getString(R.string.ev_bms_history_ride, fmtDuration(row.movingMs), fmtDuration(row.durationMs())))
				}
				item.findViewById<View>(R.id.delete_btn).setOnClickListener {
					plugin.deleteTripRecord(row.startMs, row.endMs)
					settingsFragment()?.refreshHistoryPrefs()
					bindHistory()
				}
				list.addView(item)
			}
		}
	}

	private fun fmtDateTime(ms: Long): String =
		SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(ms))

	private fun fmtTime(ms: Long): String =
		SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

	private fun fmtDuration(ms: Long): String =
		OsmAndFormatter.getFormattedDurationShort((ms / 1000L).toInt().coerceAtLeast(0))

	private fun n(v: Double?): String {
		if (v == null || v.isNaN() || v.isInfinite()) {
			return getString(R.string.ev_bms_value_none)
		}
		return String.format(Locale.getDefault(), "%.2f", v)
	}

	private fun bindJournal() {
		val container = view?.findViewById<ViewGroup>(R.id.ev_bms_journal_container) ?: return
		if (!journalBound || container.childCount == 0) {
			container.removeAllViews()
			layoutInflater.inflate(R.layout.ev_bms_journal, container, true)
			journalBound = true
			val enableRow = container.findViewById<View>(R.id.journal_enable_row)
			val sw = container.findViewById<SwitchCompat>(R.id.journal_enable_switch)
			sw.isChecked = plugin.isDebugJournalEnabled()
			sw.setOnCheckedChangeListener { _, on ->
				if (plugin.isDebugJournalEnabled() != on) {
					plugin.setDebugJournalEnabled(on)
					refreshJournalTail()
				}
			}
			enableRow.setOnClickListener { sw.isChecked = !sw.isChecked }
			container.findViewById<View>(R.id.journal_export).setOnClickListener {
				val act = activity ?: return@setOnClickListener
				if (!plugin.shareDebugJournal(act)) {
					app.showToastMessage(R.string.ev_bms_journal_empty)
				}
			}
			container.findViewById<View>(R.id.journal_clear).setOnClickListener {
				val themed = UiUtilities.getThemedContext(requireContext(), nightMode)
				AlertDialog.Builder(themed)
					.setTitle(R.string.shared_string_clear)
					.setMessage(R.string.ev_bms_journal_clear_q)
					.setNegativeButton(R.string.shared_string_cancel, null)
					.setPositiveButton(R.string.shared_string_clear) { _, _ ->
						plugin.clearDebugJournal()
						app.showToastMessage(R.string.ev_bms_journal_cleared)
						refreshJournalTail()
					}
					.show()
			}
			val color = ColorUtilities.getPrimaryTextColor(requireContext(), nightMode)
			container.findViewById<TextView>(R.id.journal_export).setTextColor(color)
			container.findViewById<TextView>(R.id.journal_clear).setTextColor(color)
			container.findViewById<TextView>(R.id.journal_tail)
				.setTextColor(ColorUtilities.getSecondaryTextColor(requireContext(), nightMode))
		}
		refreshJournalTail()
	}

	private fun refreshJournalTail() {
		val container = view?.findViewById<View>(R.id.ev_bms_journal_container) ?: return
		val status = container.findViewById<TextView>(R.id.journal_status) ?: return
		val tail = container.findViewById<TextView>(R.id.journal_tail) ?: return
		val sw = container.findViewById<SwitchCompat>(R.id.journal_enable_switch)
		val enabled = plugin.isDebugJournalEnabled()
		sw?.isChecked = enabled
		val size = formatJournalSize(plugin.debugJournalSize())
		status.text = getString(
			if (enabled) R.string.ev_bms_journal_status_on else R.string.ev_bms_journal_status_off,
			size
		)
		val text = plugin.debugJournalTail()
		tail.text = text.ifBlank { getString(R.string.ev_bms_journal_empty) }
	}

	private fun formatJournalSize(bytes: Long): String {
		return when {
			bytes < 1024L -> "$bytes B"
			bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
			else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
		}
	}

	private fun bindAbout() {
		if (aboutBound) {
			return
		}
		val text = view?.findViewById<TextView>(R.id.ev_bms_about_text) ?: return
		val html = app.getString(R.string.ev_bms_plugin_description) +
				app.getString(R.string.ev_bms_changelog, EvBmsRevision.GIT_HASH)
		text.setTextColor(ColorUtilities.getPrimaryTextColor(requireContext(), nightMode))
		text.movementMethod = LinkMovementMethod.getInstance()
		text.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
		aboutBound = true
	}

	private fun emptyHint(message: String): TextView {
		val pad = AndroidUtils.dpToPx(requireContext(), 16f)
		return TextView(requireContext()).apply {
			text = message
			setPadding(pad, pad, pad, pad)
			setTextColor(ColorUtilities.getSecondaryTextColor(context, nightMode))
		}
	}

	private fun visibleIf(show: Boolean): Int = if (show) View.VISIBLE else View.GONE

	private fun rebuildBottomButtons() {
		val parent = buttonsParent ?: return
		buttonsBar?.let { parent.removeView(it) }
		val contentPadding = getDimensionPixelSize(R.dimen.content_padding)
		val topPadding = getDimensionPixelSize(R.dimen.context_menu_first_line_top_margin)
		val paused = plugin.isTelemetryPaused()
		val bar = inflate(R.layout.preference_button_with_icon_triple)
		bar.setPadding(contentPadding, topPadding, contentPadding, contentPadding)
		parent.setBackgroundColor(ColorUtilities.getListBgColor(app, nightMode))
		parent.addView(bar)
		buttonsBar = bar
		bar.alpha = if (actionButtonsVisible) 1f else 0f
		parent.alpha = if (actionButtonsVisible) 1f else 0f
		val overlayState =
			if (currentTab == EvBmsSheetTab.SETTINGS && actionButtonsVisible) View.VISIBLE else View.GONE
		bar.visibility = overlayState
		parent.visibility = overlayState
		settingsFragment()?.setActionFooterInset(actionButtonsVisible)

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

	private fun settingsFragment(): EvBmsSettingsFragment? {
		return childFragmentManager.findFragmentByTag(SETTINGS_TAG) as? EvBmsSettingsFragment
	}

	private fun refreshRecordingPref() {
		settingsFragment()?.refreshRecordingPref()
	}

	private fun refreshCalibrationPref() {
		settingsFragment()?.refreshCalibrationPref()
	}

	override fun getInsetTargets(): InsetTargetsCollection {
		val collection = super.getInsetTargets()
		collection.removeType(Type.SCROLLABLE)
		collection.replace(InsetTarget.createBottomContainer(R.id.ev_bms_buttons_overlay))
		return collection
	}

	override fun useScrollableItemsContainer(): Boolean = false
}
