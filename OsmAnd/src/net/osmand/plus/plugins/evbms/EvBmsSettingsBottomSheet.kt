package net.osmand.plus.plugins.evbms

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.RelativeSizeSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
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
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
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
		private val CHART_CLOCK = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

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
	private var sheetTitleView: TextView? = null

	private data class ChartRow(
		val field: TelemetryField,
		val chart: LineChart,
		val value: TextView,
		var selectedX: Float? = null
	)

	private val liveTick = object : Runnable {
		override fun run() {
			if (view == null) {
				return
			}
			when (currentTab) {
				EvBmsSheetTab.FIELDS -> refreshFieldValues()
				EvBmsSheetTab.CHARTS -> if (plugin.isChartsLive()) refreshCharts()
				EvBmsSheetTab.JOURNAL -> refreshJournalTail()
				else -> {}
			}
			if (shouldLiveTick()) {
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
		if (shouldLiveTick()) {
			uiHandler.removeCallbacks(liveTick)
			uiHandler.post(liveTick)
		} else {
			uiHandler.removeCallbacks(liveTick)
		}
	}

	private fun shouldLiveTick(): Boolean {
		return currentTab == EvBmsSheetTab.FIELDS ||
				currentTab == EvBmsSheetTab.JOURNAL ||
				(currentTab == EvBmsSheetTab.CHARTS && plugin.isChartsLive())
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
		sheetTitleView = root.findViewById(R.id.title)
		sheetTitleView?.setOnClickListener { toggleAllAnnouncesFromTitle() }
		refreshSheetTitleAnnounces()
	}

	fun refreshSheetTitleAnnounces() {
		val titleView = sheetTitleView ?: return
		val baseTitle = getString(R.string.ev_bms_plugin_name)
		if (plugin.hasAnyAnnounceEnabled()) {
			val emoji = "🔊"
			val spannable = SpannableStringBuilder()
			spannable.append(emoji)
			spannable.append(' ')
			spannable.append(baseTitle)
			spannable.setSpan(RelativeSizeSpan(1.25f), 0, emoji.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
			titleView.text = spannable
		} else {
			titleView.text = baseTitle
		}
	}

	private fun toggleAllAnnouncesFromTitle() {
		plugin.toggleAllAnnounces()
		settingsFragment()?.refreshAnnouncePrefs()
		refreshSheetTitleAnnounces()
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
		content.fitsSystemWindows = false
		content.findViewById<View>(R.id.fields_actions).visibility = View.GONE
		container.addView(content)
		val selected = plugin.selectedTelemetryFields().toMutableSet()
		val gpxSelected = plugin.selectedGpxTelemetryFields().toMutableSet()
		val boxes = ArrayList<Pair<TelemetryField, CheckBox>>()
		val gpxButtons = ArrayList<Pair<TelemetryField, RadioButton>>()
		val list = content.findViewById<LinearLayout>(R.id.fields_list)
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
		fun persistGpx() {
			plugin.setGpxTelemetryFields(TelemetryField.entries.filter { it in gpxSelected })
		}
		fun bindChecks() {
			for ((field, box) in boxes) {
				box.isChecked = field in selected
			}
			for ((field, button) in gpxButtons) {
				button.isChecked = field in gpxSelected
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
			contentDescription = getString(R.string.shared_string_deselect_all)
			setOnClickListener {
				selected.clear()
				bindChecks()
				app.showToastMessage(R.string.ev_bms_telemetry_fields_empty)
			}
		}
		for ((groupRes, fields) in TelemetryField.grouped()) {
			list.addView(telemetryGroupHeader(themed, groupRes))
			for (field in fields) {
				val row = inflater.inflate(R.layout.ev_bms_telemetry_field_row, list, false)
				val box = row.findViewById<CheckBox>(R.id.compound_button)
				val gpxBtn = row.findViewById<RadioButton>(R.id.gpx_button)
				val title = row.findViewById<TextView>(R.id.title)
				val value = row.findViewById<TextView>(R.id.value)
				title.text = "${field.emoji} ${getString(field.titleRes)}"
				value.text = field.liveValue(themed, plugin.latestTelemetry)
				box.isChecked = field in selected
				gpxBtn.isChecked = field in gpxSelected
				UiUtilities.setupCompoundButton(box, nightMode, UiUtilities.CompoundButtonType.GLOBAL)
				UiUtilities.setupCompoundButton(gpxBtn, nightMode, UiUtilities.CompoundButtonType.GLOBAL)
				row.setOnClickListener {
					box.isChecked = !box.isChecked
					if (box.isChecked) selected.add(field) else selected.remove(field)
					persist()
				}
				gpxBtn.setOnClickListener {
					if (field in gpxSelected) {
						gpxSelected.remove(field)
						gpxBtn.isChecked = false
					} else {
						gpxSelected.add(field)
						gpxBtn.isChecked = true
					}
					persistGpx()
				}
				fieldValueViews.add(field to value)
				boxes.add(field to box)
				gpxButtons.add(field to gpxBtn)
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
		bindChartsToolbar()
		val list = view?.findViewById<LinearLayout>(R.id.ev_bms_charts_list) ?: return
		val fields = plugin.chartFields()
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
			styleChartTitle(title, field)
			value.text = field.liveValue(ctx, plugin.latestTelemetry)
			styleChart(chart)
			val chartRow = ChartRow(field, chart, value)
			bindChartInspect(chartRow)
			chartRows.add(chartRow)
			list.addView(row)
		}
		refreshCharts()
	}

	private fun bindChartsToolbar() {
		val root = view ?: return
		val status = root.findViewById<TextView>(R.id.charts_live_status) ?: return
		val btn = root.findViewById<TextView>(R.id.charts_pause_btn) ?: return
		val live = plugin.isChartsLive()
		status.setText(if (live) R.string.ev_bms_charts_live else R.string.ev_bms_charts_paused)
		btn.text = if (live) "⏸️" else "▶️"
		btn.contentDescription = getString(
			if (live) R.string.ev_bms_charts_pause else R.string.ev_bms_charts_resume
		)
		btn.setOnClickListener {
			plugin.setChartsLive(!plugin.isChartsLive())
			bindChartsToolbar()
			if (plugin.isChartsLive()) {
				uiHandler.removeCallbacks(liveTick)
				uiHandler.post(liveTick)
			} else if (currentTab == EvBmsSheetTab.CHARTS) {
				uiHandler.removeCallbacks(liveTick)
			}
		}
	}

	private fun styleChartTitle(title: TextView, field: TelemetryField) {
		val ctx = title.context
		title.setTextColor(ColorUtilities.getActiveColor(ctx, nightMode))
		title.contentDescription = getString(R.string.ev_bms_chart_actions, getString(field.titleRes))
		title.setOnClickListener { showChartMenu(title, field) }
	}

	private fun showChartMenu(anchor: View, field: TelemetryField) {
		val popup = PopupMenu(requireContext(), anchor)
		popup.menu.add(0, 1, 0, R.string.ev_bms_chart_move_up)
		popup.menu.add(0, 2, 1, R.string.ev_bms_chart_move_down)
		popup.menu.add(0, 3, 2, R.string.ev_bms_chart_move_start)
		popup.menu.add(0, 4, 3, R.string.ev_bms_chart_move_end)
		popup.menu.add(0, 5, 4, R.string.ev_bms_charts_pause)
		popup.menu.add(0, 6, 5, R.string.ev_bms_charts_resume)
		popup.setOnMenuItemClickListener { item ->
			when (item.itemId) {
				1 -> plugin.moveChart(field.id, EvBmsPlugin.ChartMove.UP)
				2 -> plugin.moveChart(field.id, EvBmsPlugin.ChartMove.DOWN)
				3 -> plugin.moveChart(field.id, EvBmsPlugin.ChartMove.START)
				4 -> plugin.moveChart(field.id, EvBmsPlugin.ChartMove.END)
				5 -> plugin.setChartPaused(field.id, true)
				6 -> {
					plugin.setChartPaused(field.id, false)
					if (!plugin.isChartsLive()) {
						plugin.setChartsLive(true)
						bindChartsToolbar()
						uiHandler.removeCallbacks(liveTick)
						uiHandler.post(liveTick)
					}
				}
			}
			if (item.itemId in 1..4) {
				bindCharts(force = true)
			}
			true
		}
		popup.show()
	}

	private fun styleChart(chart: LineChart) {
		val ctx = requireContext()
		val secondary = ColorUtilities.getSecondaryTextColor(ctx, nightMode)
		val divider = ColorUtilities.getDividerColor(ctx, nightMode)
		chart.description.isEnabled = false
		chart.legend.isEnabled = false
		chart.setTouchEnabled(true)
		chart.setDragEnabled(false)
		chart.setScaleEnabled(false)
		chart.setPinchZoom(false)
		chart.setDoubleTapToZoomEnabled(false)
		chart.isHighlightPerTapEnabled = true
		chart.isHighlightPerDragEnabled = true
		chart.setMaxHighlightDistance(64f)
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
		chart.setOnTouchListener { v, event ->
			when (event.actionMasked) {
				MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
					v.parent?.requestDisallowInterceptTouchEvent(true)
				MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
					v.parent?.requestDisallowInterceptTouchEvent(false)
			}
			false
		}
	}

	private fun bindChartInspect(row: ChartRow) {
		row.chart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
			override fun onValueSelected(e: Entry?, h: Highlight?) {
				if (e == null) {
					return
				}
				row.selectedX = e.x
				showChartInspect(row, e)
			}

			override fun onNothingSelected() {
				row.selectedX = null
				val ctx = context ?: return
				row.value.text = row.field.liveValue(ctx, plugin.latestTelemetry)
			}
		})
	}

	private fun showChartInspect(row: ChartRow, entry: Entry) {
		val ctx = context ?: return
		val sample = entry.data as? EvTelemetry
		val value = if (sample != null) {
			row.field.liveValue(ctx, sample)
		} else {
			row.field.liveValue(ctx, plugin.latestTelemetry)
		}
		val time = if (sample != null) {
			CHART_CLOCK.format(Date(sample.timeMs))
		} else {
			formatElapsed(entry.x)
		}
		row.value.text = getString(R.string.ev_bms_chart_inspect, value, time)
	}

	private fun formatElapsed(seconds: Float): String {
		val total = seconds.toInt().coerceAtLeast(0)
		val m = total / 60
		val s = total % 60
		return String.format(Locale.getDefault(), "%d:%02d", m, s)
	}

	private fun applyChartValue(row: ChartRow, live: EvTelemetry?) {
		val ctx = context ?: return
		val selectedX = row.selectedX
		if (selectedX != null) {
			val entry = row.chart.data?.getDataSetByIndex(0)?.getEntryForXValue(selectedX, Float.NaN)
			if (entry != null) {
				showChartInspect(row, entry)
				return
			}
		}
		row.value.text = row.field.liveValue(ctx, live)
	}

	private fun refreshCharts() {
		val ctx = context ?: return
		val history = plugin.chartHistorySnapshot()
		val color = ColorUtilities.getActiveColor(ctx, nightMode)
		val sample = plugin.latestTelemetry
		for (row in chartRows) {
			if (!plugin.isChartsLive() || plugin.isChartPaused(row.field.id)) {
				applyChartValue(row, sample)
				continue
			}
			val entries = ArrayList<Entry>()
			val t0 = history.firstOrNull()?.timeMs ?: 0L
			for (item in history) {
				val y = row.field.chartValue(item) ?: continue
				val x = ((item.timeMs - t0) / 1000.0).toFloat()
				entries.add(Entry(x, y.toFloat(), item))
			}
			if (entries.isEmpty()) {
				row.chart.clear()
				row.chart.invalidate()
				applyChartValue(row, sample)
				continue
			}
			if (entries.size == 1) {
				val only = entries[0]
				entries.add(Entry(only.x + 1f, only.y, only.data))
			}
			val set = LineDataSet(entries, "")
			set.setDrawCircles(false)
			set.setDrawValues(false)
			set.setDrawFilled(true)
			set.lineWidth = 1.6f
			set.color = color
			set.fillColor = color
			set.fillAlpha = 48
			set.highLightColor = color
			set.setHighlightLineWidth(1.2f)
			set.setDrawHorizontalHighlightIndicator(false)
			set.setDrawVerticalHighlightIndicator(true)
			set.mode = LineDataSet.Mode.LINEAR
			row.chart.data = LineData(set)
			val selectedX = row.selectedX
			if (selectedX != null) {
				row.chart.highlightValue(selectedX, 0, false)
			} else {
				row.chart.highlightValues(null)
			}
			row.chart.invalidate()
			applyChartValue(row, sample)
		}
	}

	private fun bindHistory() {
		val list = view?.findViewById<LinearLayout>(R.id.ev_bms_history_list) ?: return
		list.removeAllViews()
		val inflater = layoutInflater
		val charges = plugin.chargeHistory()
		val trips = plugin.tripHistory()
		if (charges.isEmpty() && trips.isEmpty()) {
			list.addView(emptyHint(getString(R.string.ev_bms_history_empty)))
			return
		}
		val merged = ArrayList<HistoryRow>(charges.size + trips.size)
		charges.forEach { merged.add(HistoryRow.Charge(it)) }
		trips.forEach { merged.add(HistoryRow.Trip(it)) }
		merged.sortByDescending { it.sortMs }
		for (row in merged) {
			when (row) {
				is HistoryRow.Charge -> list.addView(bindChargeHistoryRow(inflater, list, row.record))
				is HistoryRow.Trip -> list.addView(bindTripHistoryRow(inflater, list, row.record))
			}
		}
	}

	private sealed class HistoryRow(val sortMs: Long) {
		class Charge(val record: EvHistoryStore.ChargeRecord) : HistoryRow(record.startMs)
		class Trip(val record: EvHistoryStore.ChargeTripRecord) : HistoryRow(record.startMs)
	}

	private fun bindChargeHistoryRow(
		inflater: android.view.LayoutInflater,
		list: LinearLayout,
		row: EvHistoryStore.ChargeRecord
	): View {
		val item = inflater.inflate(R.layout.ev_bms_history_row, list, false)
		item.findViewById<TextView>(R.id.title).text = historyHtml(
			if (row.isOpen()) {
				"🔌 ${fmtDateTime(row.startMs)} → ${getString(R.string.ev_bms_history_charging_now)}"
			} else {
				"🔌 ${fmtDateTime(row.startMs)} → ${fmtTime(row.endMs)}"
			}
		)
		item.findViewById<TextView>(R.id.description).text = historyHtml(
			buildString {
				append("⏱️ ").append(getString(R.string.ev_bms_history_duration, bNum(fmtDuration(row.durationMs()))))
				append(" · 🔋 ").append(getString(R.string.ev_bms_history_charged_ah, bNum(n(row.chargedAh))))
				if (row.energyWh != null) {
					append(" · ⚡ ").append(getString(R.string.ev_bms_history_charge_energy_wh, bNum(n0(row.energyWh))))
				}
				if (row.avgCurrentA != null) {
					append(" · 🔌 ").append(getString(R.string.ev_bms_history_avg_charge_a, bNum(n(row.avgCurrentA))))
				}
				append('\n')
				append("🌡️ ").append(getString(R.string.ev_bms_history_temp, bNum(nTemp(row.startTempC)), bNum(nTemp(row.endTempC))))
				append('\n')
				append("🔻 ").append(getString(R.string.ev_bms_history_min_cell_range, bNum(nVolt(row.startMinCellV)), bNum(nVolt(row.endMinCellV))))
				append('\n')
				append("⏸️ ").append(
					getString(
						R.string.ev_bms_history_stop_time,
						bNum(
							when {
								plugin.isChargeStopPending(row.startMs) || row.isOpen() ->
									getString(R.string.ev_bms_history_stop_pending)
								row.stopMs != null -> fmtDuration(row.stopMs)
								else -> getString(R.string.ev_bms_value_none)
							}
						)
					)
				)
			}
		)
		wireHistoryChart(
			item,
			EvHistoryChartStore.KIND_CHARGE,
			row.startMs,
			row.endMs,
			R.string.ev_bms_delete_charge_q
		) {
			plugin.deleteChargeRecord(row.startMs, row.endMs)
			settingsFragment()?.refreshHistoryPrefs()
			bindHistory()
		}
		return item
	}

	private fun bindTripHistoryRow(
		inflater: android.view.LayoutInflater,
		list: LinearLayout,
		row: EvHistoryStore.ChargeTripRecord
	): View {
		val item = inflater.inflate(R.layout.ev_bms_history_row, list, false)
		item.findViewById<TextView>(R.id.title).text = historyHtml(
			"🛵 ${fmtDateTime(row.startMs)} → ${fmtTime(row.endMs)}"
		)
		item.findViewById<TextView>(R.id.description).text = historyHtml(
			buildString {
				append("🛣️ ").append(getString(R.string.ev_bms_history_distance, bNum(n(row.distanceKm))))
				append(" · 🕒 ").append(
					getString(R.string.ev_bms_history_ride, bNum(fmtDuration(row.movingMs)), bNum(fmtDuration(row.durationMs())))
				)
				append('\n')
				append("⚡ ").append(
					getString(R.string.ev_bms_history_voltage, bNum(n(row.startVoltageV)), bNum(n(row.endVoltageV)))
				)
				append('\n')
				append("📊 ").append(getString(R.string.ev_bms_history_energy_wh, bNum(n0(row.energyWh))))
				append(" · 🔋 ").append(getString(R.string.ev_bms_history_used_ah, bNum(n(row.usedAh))))
				append(" · 📈 ").append(getString(R.string.ev_bms_history_specific_whkm, bNum(n0(row.specificWhKm))))
				append('\n')
				append("🚀 ").append(getString(R.string.ev_bms_history_avg_speed, bNum(n(row.avgMovingKmh))))
				append(" · ⏸️ ").append(
					getString(
						R.string.ev_bms_history_stop_time,
						bNum(row.stopMs?.let { fmtDuration(it) } ?: getString(R.string.ev_bms_value_none))
					)
				)
				append('\n')
				append("🌡️ ").append(getString(R.string.ev_bms_history_temp, bNum(nTemp(row.startTempC)), bNum(nTemp(row.endTempC))))
				append('\n')
				append("🔥 ").append(getString(R.string.ev_bms_history_motor_temp, bNum(nTemp(row.startMotorTempC)), bNum(nTemp(row.endMotorTempC))))
			}
		)
		wireHistoryChart(
			item,
			EvHistoryChartStore.KIND_TRIP,
			row.startMs,
			row.endMs,
			R.string.ev_bms_delete_trip_q
		) {
			plugin.deleteTripRecord(row.startMs, row.endMs)
			settingsFragment()?.refreshHistoryPrefs()
			bindHistory()
		}
		return item
	}

	private fun historyHtml(text: String): CharSequence =
		HtmlCompat.fromHtml(text.replace("\n", "<br>"), HtmlCompat.FROM_HTML_MODE_LEGACY)

	private fun bNum(value: String): String = "<b>${TextUtils.htmlEncode(value)}</b>"

	private fun confirmDelete(messageRes: Int, onConfirm: () -> Unit) {
		val themed = UiUtilities.getThemedContext(requireContext(), nightMode)
		AlertDialog.Builder(themed)
			.setTitle(R.string.shared_string_delete)
			.setMessage(messageRes)
			.setNegativeButton(R.string.shared_string_cancel, null)
			.setPositiveButton(R.string.shared_string_delete) { _, _ -> onConfirm() }
			.show()
	}

	private fun wireHistoryChart(
		item: View,
		kind: String,
		startMs: Long,
		endMs: Long,
		deleteMessageRes: Int,
		onDelete: () -> Unit
	) {
		val chart = item.findViewById<LineChart>(R.id.history_chart)
		val legend = item.findViewById<TextView>(R.id.history_chart_legend)
		val expand = item.findViewById<TextView>(R.id.expand_btn)
		styleChart(chart)
		chart.axisLeft.setDrawLabels(false)
		chart.axisLeft.setLabelCount(2, true)
		item.findViewById<View>(R.id.delete_btn).setOnClickListener {
			confirmDelete(deleteMessageRes, onDelete)
		}
		expand.setOnClickListener {
			val show = chart.visibility != View.VISIBLE
			if (show) {
				val data = plugin.historyChart(kind, startMs, endMs)
				if (data == null) {
					app.showToastMessage(R.string.ev_bms_history_chart_empty)
					return@setOnClickListener
				}
				fillHistoryChart(chart, legend, data)
			}
			chart.visibility = if (show) View.VISIBLE else View.GONE
			legend.visibility = if (show) View.VISIBLE else View.GONE
			expand.alpha = if (show) 1f else 0.55f
		}
	}

	private fun fillHistoryChart(
		chart: LineChart,
		legend: TextView,
		data: EvHistoryChartStore.Chart
	) {
		val sets = ArrayList<LineDataSet>()
		val legendParts = ArrayList<String>()
		for ((id, ys) in data.series) {
			val norm = normalizeSeries(ys)
			val entries = ArrayList<Entry>()
			for (i in data.xs.indices) {
				if (i >= norm.size || norm[i].isNaN()) {
					continue
				}
				entries.add(Entry(data.xs[i], norm[i]))
			}
			if (entries.isEmpty()) {
				continue
			}
			if (entries.size == 1) {
				val only = entries[0]
				entries.add(Entry(only.x + 1f, only.y))
			}
			val color = historySeriesColor(id)
			val set = LineDataSet(entries, id)
			set.setDrawCircles(false)
			set.setDrawValues(false)
			set.setDrawFilled(false)
			set.lineWidth = 1.6f
			set.color = color
			set.setDrawHorizontalHighlightIndicator(false)
			set.setDrawVerticalHighlightIndicator(true)
			set.highLightColor = color
			set.setHighlightLineWidth(1.2f)
			set.mode = LineDataSet.Mode.LINEAR
			sets.add(set)
			val last = data.last(id)
			legendParts.add(historySeriesLegend(id, last))
		}
		if (sets.isEmpty()) {
			chart.clear()
			legend.setText(R.string.ev_bms_history_chart_empty)
			return
		}
		chart.data = LineData(*sets.toTypedArray())
		chart.invalidate()
		legend.text = legendParts.joinToString("  ·  ")
	}

	private fun normalizeSeries(ys: FloatArray): FloatArray {
		var min = Float.POSITIVE_INFINITY
		var max = Float.NEGATIVE_INFINITY
		for (y in ys) {
			if (y.isNaN()) {
				continue
			}
			if (y < min) min = y
			if (y > max) max = y
		}
		if (!min.isFinite() || !max.isFinite()) {
			return FloatArray(ys.size) { Float.NaN }
		}
		val span = (max - min).coerceAtLeast(1e-4f)
		return FloatArray(ys.size) { i ->
			val y = ys[i]
			if (y.isNaN()) Float.NaN else (y - min) / span
		}
	}

	private fun historySeriesColor(id: String): Int = when (id) {
		EvHistoryChartStore.S_CURRENT, EvHistoryChartStore.S_POWER -> Color.parseColor("#1E88E5")
		EvHistoryChartStore.S_TEMP, EvHistoryChartStore.S_CONS -> Color.parseColor("#FB8C00")
		EvHistoryChartStore.S_MIN_V -> Color.parseColor("#E53935")
		EvHistoryChartStore.S_MAX_V, EvHistoryChartStore.S_MOTOR -> Color.parseColor("#00897B")
		else -> Color.parseColor("#5E35B1")
	}

	private fun historySeriesLegend(id: String, last: Float?): String {
		val name = when (id) {
			EvHistoryChartStore.S_CURRENT -> getString(R.string.ev_bms_widget_current)
			EvHistoryChartStore.S_TEMP -> getString(R.string.ev_bms_widget_battery_temp)
			EvHistoryChartStore.S_MIN_V -> getString(R.string.ev_bms_widget_min_cell)
			EvHistoryChartStore.S_MAX_V -> getString(R.string.ev_bms_history_max_cell)
			EvHistoryChartStore.S_POWER -> getString(R.string.ev_bms_widget_power)
			EvHistoryChartStore.S_CONS -> getString(R.string.ev_bms_widget_consumption)
			EvHistoryChartStore.S_MOTOR -> getString(R.string.ev_bms_widget_motor_temp)
			else -> id
		}
		val value = when {
			last == null -> getString(R.string.ev_bms_value_none)
			id == EvHistoryChartStore.S_MIN_V || id == EvHistoryChartStore.S_MAX_V ->
				String.format(Locale.getDefault(), "%.3f", last)
			id == EvHistoryChartStore.S_CONS -> String.format(Locale.getDefault(), "%.0f", last)
			else -> String.format(Locale.getDefault(), "%.1f", last)
		}
		val unit = when (id) {
			EvHistoryChartStore.S_CURRENT -> "A"
			EvHistoryChartStore.S_TEMP, EvHistoryChartStore.S_MOTOR -> "°C"
			EvHistoryChartStore.S_MIN_V, EvHistoryChartStore.S_MAX_V -> "V"
			EvHistoryChartStore.S_POWER -> "W"
			EvHistoryChartStore.S_CONS -> "Wh/km"
			else -> ""
		}
		return "$name $value $unit".trim()
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

	private fun nTemp(v: Double?): String {
		if (v == null || v.isNaN() || v.isInfinite()) {
			return getString(R.string.ev_bms_value_none)
		}
		return String.format(Locale.getDefault(), "%.0f", v)
	}

	private fun nVolt(v: Double?): String {
		if (v == null || v.isNaN() || v.isInfinite()) {
			return getString(R.string.ev_bms_value_none)
		}
		return String.format(Locale.getDefault(), "%.3f", v)
	}

	private fun n0(v: Double?): String {
		if (v == null || v.isNaN() || v.isInfinite()) {
			return getString(R.string.ev_bms_value_none)
		}
		return String.format(Locale.getDefault(), "%.0f", v)
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
		val html = try {
			plugin.descriptionHtml()
		} catch (e: Exception) {
			app.getString(R.string.ev_bms_plugin_description) +
					"<br/><br/>Changelog · ${EvBmsRevision.GIT_HASH}"
		}
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

	fun rebuildBottomButtons() {
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
				confirmAction(R.string.shared_string_control_stop, R.string.ev_bms_confirm_record_stop) {
					plugin.stopTelemetryRecording()
					refreshRecordingPref()
					rebuildBottomButtons()
				}
			}
			TripRecordingBottomSheet.createItemActive(app, nightMode, rightButton, ItemType.RESUME)
			rightButton.setOnClickListener {
				confirmAction(R.string.shared_string_continue, R.string.ev_bms_confirm_record_resume) {
					plugin.resumeTelemetryRecording()
					refreshRecordingPref()
					rebuildBottomButtons()
				}
			}
		} else {
			bindCalibrateButton(centerButton)
			if (plugin.isTelemetryRecording()) {
				TripRecordingBottomSheet.createItem(app, nightMode, rightButton, ItemType.PAUSE, true, null)
				rightButton.setOnClickListener {
					confirmAction(R.string.shared_string_pause, R.string.ev_bms_confirm_record_pause) {
						plugin.pauseTelemetryRecording()
						refreshRecordingPref()
						rebuildBottomButtons()
					}
				}
			} else {
				TripRecordingBottomSheet.createItemActive(app, nightMode, rightButton, ItemType.START_RECORDING)
				rightButton.setOnClickListener {
					confirmAction(R.string.ev_bms_record_telemetry, R.string.ev_bms_confirm_record_start) {
						plugin.startTelemetryRecording()
						refreshRecordingPref()
						rebuildBottomButtons()
					}
				}
			}
		}
	}

	private fun confirmAction(titleRes: Int, messageRes: Int, onYes: () -> Unit) {
		val ctx = context ?: return
		AlertDialog.Builder(ctx)
			.setTitle(titleRes)
			.setMessage(messageRes)
			.setNegativeButton(R.string.shared_string_cancel, null)
			.setPositiveButton(R.string.shared_string_yes) { _, _ -> onYes() }
			.show()
	}

	private fun bindCalibrateButton(button: CardView) {
		if (plugin.isSpeedCalibrating()) {
			TripRecordingBottomSheet.createItem(app, nightMode, button, ItemType.STOP, true, null)
			button.findViewById<TextView>(R.id.button_text)?.setText(R.string.ev_bms_calibrate_stop)
			button.setOnClickListener {
				confirmAction(R.string.ev_bms_calibrate_stop, R.string.ev_bms_confirm_cal_stop) {
					plugin.stopSpeedCalibration()
					refreshCalibrationPref()
					rebuildBottomButtons()
				}
			}
		} else {
			TripRecordingBottomSheet.createItem(app, nightMode, button, ItemType.START_NEW_SEGMENT, true, null)
			button.findViewById<TextView>(R.id.button_text)?.setText(R.string.ev_bms_calibrate)
			button.setOnClickListener {
				confirmAction(R.string.ev_bms_calibrate, R.string.ev_bms_confirm_cal_start) {
					plugin.startSpeedCalibration()
					refreshCalibrationPref()
					rebuildBottomButtons()
				}
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
