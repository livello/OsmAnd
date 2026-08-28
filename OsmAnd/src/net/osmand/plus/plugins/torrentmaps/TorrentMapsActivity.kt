package net.osmand.plus.plugins.torrentmaps

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.settings.enums.ThemeUsageContext
import net.osmand.plus.utils.AndroidUtils
import net.osmand.plus.utils.UiUtilities

/**
 * Flud-like full-screen BitTorrent client for OsmAnd map torrents.
 */
class TorrentMapsActivity : AppCompatActivity() {

	private val app: OsmandApplication
		get() = application as OsmandApplication

	private val plugin: TorrentMapsPlugin
		get() = PluginsHelper.requirePlugin(TorrentMapsPlugin::class.java)

	private val uiHandler = Handler(Looper.getMainLooper())
	private lateinit var statusTitle: TextView
	private lateinit var statusRates: TextView
	private lateinit var statusMeta: TextView
	private lateinit var pathBreadcrumb: TextView
	private lateinit var list: RecyclerView
	private lateinit var logsList: RecyclerView
	private lateinit var settingsPanel: View
	private lateinit var logsPanel: View
	private lateinit var fab: FloatingActionButton
	private lateinit var tabs: TabLayout
	private lateinit var adapter: BrowserAdapter
	private lateinit var logsAdapter: LogsAdapter
	private lateinit var enableSwitch: SwitchCompat
	private lateinit var wifiSwitch: SwitchCompat
	private lateinit var chargeSwitch: SwitchCompat
	private lateinit var downloadNewSwitch: SwitchCompat
	private lateinit var pathSummary: TextView
	private lateinit var topicUrl: TextView
	private lateinit var cookieSummary: TextView
	private lateinit var sortProgressBtn: AppCompatImageButton
	private lateinit var sortSizeBtn: AppCompatImageButton
	private lateinit var sortNameBtn: AppCompatImageButton
	private lateinit var queueBar: View
	private lateinit var queueSummary: TextView
	private lateinit var queuePause: View
	private lateinit var queueResume: View
	private lateinit var queueClear: View
	private lateinit var verifyHashes: TextView
	private lateinit var verifyStatusView: TextView
	private var bindingSettings = false
	private var currentFolderPath: String = ""
	private var sortKey: TorrentSortKey = TorrentSortKey.NAME
	private var sortAscending: Boolean = true
	private var allFileRows: List<TorrentFileRow> = emptyList()
	private var userStartedVerify = false

	private val torrentFileLauncher = registerForActivityResult(
		ActivityResultContracts.OpenDocument()
	) { uri ->
		if (uri == null) {
			return@registerForActivityResult
		}
		if (plugin.importTorrentFile(uri)) {
			currentFolderPath = ""
			refreshAll()
		} else {
			app.showToastMessage(R.string.torrent_maps_invalid)
		}
	}

	private val authLauncher = registerForActivityResult(
		ActivityResultContracts.StartActivityForResult()
	) {
		refreshAll()
	}

	private val refreshTick = object : Runnable {
		override fun run() {
			if (isFinishing) {
				return
			}
			refreshStatus()
			refreshVerifyUi()
			when (tabs.selectedTabPosition) {
				0 -> refreshBrowserList(keepFolder = true)
				1 -> refreshLogs()
			}
			uiHandler.postDelayed(this, 1000)
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		val night = app.daynightHelper.isNightMode(ThemeUsageContext.APP)
		setTheme(if (night) R.style.OsmandDarkTheme else R.style.OsmandLightTheme)
		super.onCreate(savedInstanceState)
		setContentView(R.layout.torrent_maps_activity)

		TorrentMapsLog.init(app)
		sortKey = plugin.getSortKey()
		sortAscending = plugin.isSortAscending()

		statusTitle = findViewById(R.id.torrent_status_title)
		statusRates = findViewById(R.id.torrent_status_rates)
		statusMeta = findViewById(R.id.torrent_status_meta)
		pathBreadcrumb = findViewById(R.id.torrent_path_breadcrumb)
		list = findViewById(R.id.torrent_file_list)
		logsList = findViewById(R.id.torrent_logs_list)
		settingsPanel = findViewById(R.id.torrent_settings_panel)
		logsPanel = findViewById(R.id.torrent_logs_panel)
		fab = findViewById(R.id.torrent_fab)
		tabs = findViewById(R.id.torrent_tabs)
		enableSwitch = findViewById(R.id.torrent_enable)
		wifiSwitch = findViewById(R.id.torrent_wifi_only)
		chargeSwitch = findViewById(R.id.torrent_seed_charge)
		downloadNewSwitch = findViewById(R.id.torrent_download_new)
		pathSummary = findViewById(R.id.torrent_path_summary)
		topicUrl = findViewById(R.id.torrent_topic_url)
		cookieSummary = findViewById(R.id.torrent_cookie_summary)
		sortProgressBtn = findViewById(R.id.torrent_sort_progress)
		sortSizeBtn = findViewById(R.id.torrent_sort_size)
		sortNameBtn = findViewById(R.id.torrent_sort_name)
		queueBar = findViewById(R.id.torrent_queue_bar)
		queueSummary = findViewById(R.id.torrent_queue_summary)
		queuePause = findViewById(R.id.torrent_queue_pause)
		queueResume = findViewById(R.id.torrent_queue_resume)
		queueClear = findViewById(R.id.torrent_queue_clear)
		verifyHashes = findViewById(R.id.torrent_verify_hashes)
		verifyStatusView = findViewById(R.id.torrent_verify_status)

		findViewById<View>(R.id.torrent_close).setOnClickListener { finish() }
		findViewById<View>(R.id.torrent_nearby).setOnClickListener {
			plugin.openNearbyMapsUi(this)
		}
		findViewById<View>(R.id.torrent_open_nearby).setOnClickListener {
			plugin.openNearbyMapsUi(this)
		}
		findViewById<View>(R.id.torrent_pick_file).setOnClickListener {
			confirmAction(R.string.torrent_maps_confirm_pick_file) {
				torrentFileLauncher.launch(
					arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")
				)
			}
		}
		findViewById<View>(R.id.torrent_refresh_rutracker).setOnClickListener {
			confirmAction(R.string.torrent_maps_confirm_refresh) {
				app.showToastMessage(R.string.torrent_maps_refresh_started)
				plugin.refreshTorrentFromRutracker { _, _ ->
					currentFolderPath = ""
					refreshAll()
				}
			}
		}
		verifyHashes.setOnClickListener {
			if (plugin.mapVerifyStatus().running) {
				plugin.cancelMapVerify()
				refreshVerifyUi()
				return@setOnClickListener
			}
			confirmAction(R.string.torrent_maps_confirm_verify) {
				userStartedVerify = true
				plugin.verifyDownloadedMaps()
				refreshVerifyUi()
			}
		}
		findViewById<View>(R.id.torrent_login_rutracker).setOnClickListener { openRutrackerAuth() }
		findViewById<View>(R.id.torrent_topic_row).setOnClickListener { editTopicUrl() }
		findViewById<View>(R.id.torrent_cookie_row).setOnClickListener { editCookie() }
		findViewById<View>(R.id.torrent_logs_clear).setOnClickListener {
			confirmAction(R.string.torrent_maps_confirm_clear_logs) {
				TorrentMapsLog.clear()
				refreshLogs()
			}
		}

		sortProgressBtn.setOnClickListener { toggleSort(TorrentSortKey.PROGRESS) }
		sortSizeBtn.setOnClickListener { toggleSort(TorrentSortKey.SIZE) }
		sortNameBtn.setOnClickListener { toggleSort(TorrentSortKey.NAME) }
		pathBreadcrumb.setOnClickListener {
			if (currentFolderPath.isNotEmpty()) {
				currentFolderPath = TorrentBrowser.parentPath(currentFolderPath)
				refreshBrowserList(keepFolder = true)
			}
		}

		queuePause.setOnClickListener {
			plugin.pauseTorrentQueue()
			refreshQueueUi()
		}
		queueResume.setOnClickListener {
			plugin.resumeTorrentQueue()
			refreshQueueUi()
		}
		queueClear.setOnClickListener {
			confirmAction(getString(R.string.torrent_maps_nearby_confirm_clear_queue)) {
				plugin.clearTorrentQueue()
				refreshQueueUi()
			}
		}

		adapter = BrowserAdapter(
			onClick = { row -> onBrowserClick(row) },
			onLongClick = { row -> onBrowserLongClick(row) },
			onFolderDownload = { folder -> confirmFolderDownload(folder) }
		)
		list.layoutManager = LinearLayoutManager(this)
		list.adapter = adapter

		logsAdapter = LogsAdapter()
		logsList.layoutManager = LinearLayoutManager(this)
		logsList.adapter = logsAdapter

		tabs.addTab(tabs.newTab().setText(R.string.torrent_maps_tab_torrents))
		tabs.addTab(tabs.newTab().setText(R.string.torrent_maps_tab_logs))
		tabs.addTab(tabs.newTab().setText(R.string.torrent_maps_tab_settings))
		tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
			override fun onTabSelected(tab: TabLayout.Tab) {
				applyTabVisibility(tab.position)
			}

			override fun onTabUnselected(tab: TabLayout.Tab?) {}
			override fun onTabReselected(tab: TabLayout.Tab?) {}
		})

		fab.setOnClickListener {
			if (plugin.isTorrentRunning()) {
				confirmAction(R.string.torrent_maps_confirm_stop) {
					plugin.stopMapTorrent()
					refreshStatus()
				}
			} else {
				confirmAction(R.string.torrent_maps_confirm_start) {
					if (!plugin.TORRENT_ENABLED.get()) {
						plugin.TORRENT_ENABLED.set(true)
						bindingSettings = true
						enableSwitch.isChecked = true
						bindingSettings = false
					}
					plugin.startMapTorrentManual()
					refreshStatus()
				}
			}
		}

		bindSettingsSwitches()
		updateSortButtonTints()
		refreshAll()
	}

	override fun onResume() {
		super.onResume()
		uiHandler.removeCallbacks(refreshTick)
		uiHandler.post(refreshTick)
	}

	override fun onPause() {
		uiHandler.removeCallbacks(refreshTick)
		super.onPause()
	}

	private fun applyTabVisibility(position: Int) {
		list.isVisible = position == 0
		pathBreadcrumb.isVisible = position == 0
		sortProgressBtn.isVisible = position == 0
		sortSizeBtn.isVisible = position == 0
		sortNameBtn.isVisible = position == 0
		logsPanel.isVisible = position == 1
		settingsPanel.isVisible = position == 2
		fab.isVisible = position != 2
		when (position) {
			0 -> refreshBrowserList(keepFolder = true)
			1 -> refreshLogs()
		}
	}

	private fun toggleSort(key: TorrentSortKey) {
		if (sortKey == key) {
			sortAscending = !sortAscending
		} else {
			sortKey = key
			sortAscending = key == TorrentSortKey.NAME
		}
		plugin.setSortKey(sortKey)
		plugin.setSortAscending(sortAscending)
		updateSortButtonTints()
		refreshBrowserList(keepFolder = true)
	}

	private fun updateSortButtonTints() {
		val active = 0xFF42A5F5.toInt()
		val inactive = 0xFFB0BEC5.toInt()
		sortProgressBtn.setColorFilter(if (sortKey == TorrentSortKey.PROGRESS) active else inactive)
		sortSizeBtn.setColorFilter(if (sortKey == TorrentSortKey.SIZE) active else inactive)
		sortNameBtn.setColorFilter(if (sortKey == TorrentSortKey.NAME) active else inactive)
	}

	private fun onBrowserClick(row: TorrentBrowserRow) {
		when (row) {
			is TorrentBrowserRow.Up -> {
				currentFolderPath = row.parentPath
				refreshBrowserList(keepFolder = true)
			}
			is TorrentBrowserRow.Folder -> {
				currentFolderPath = row.path
				refreshBrowserList(keepFolder = true)
			}
			is TorrentBrowserRow.File -> confirmFileDownload(row.row)
		}
	}

	private fun onBrowserLongClick(row: TorrentBrowserRow): Boolean {
		when (row) {
			is TorrentBrowserRow.File -> {
				val queued = plugin.torrentQueuedKeys()
				if (row.row.mapKey in queued) {
					confirmAction(
						getString(R.string.torrent_maps_nearby_confirm_remove, row.row.displayName)
					) {
						plugin.removeTorrentQueueKey(row.row.mapKey)
						refreshQueueUi()
					}
					return true
				}
			}
			is TorrentBrowserRow.Folder -> {
				confirmFolderDownload(row)
				return true
			}
			else -> {}
		}
		return false
	}

	private fun confirmFileDownload(row: TorrentFileRow) {
		if (!TorrentBrowser.isDownloadable(row.state) &&
			row.state != TorrentFileState.DOWNLOADING &&
			row.state != TorrentFileState.UPDATING
		) {
			val statusText = statusLabel(this, row.state)
			android.widget.Toast.makeText(
				this,
				"$statusText · ${row.progressPercent}%",
				android.widget.Toast.LENGTH_SHORT
			).show()
			return
		}
		confirmAction(
			getString(
				R.string.torrent_maps_nearby_confirm_download,
				row.displayName,
				AndroidUtils.formatSize(this, row.sizeBytes),
				statusLabel(this, row.state)
			)
		) {
			enqueueTorrent(listOf(row.mapKey))
		}
	}

	private fun confirmFolderDownload(folder: TorrentBrowserRow.Folder) {
		val prefix = "${folder.path}/"
		val files = allFileRows.filter {
			val path = TorrentBrowser.normalizePath(it.browsePath.ifBlank { it.torrentPath })
			(path == folder.path || path.startsWith(prefix)) && TorrentBrowser.isDownloadable(it.state)
		}
		if (files.isEmpty()) {
			app.showToastMessage(R.string.torrent_maps_nearby_folder_empty)
			return
		}
		val size = files.sumOf { it.sizeBytes }
		confirmAction(
			getString(
				R.string.torrent_maps_nearby_confirm_folder,
				folder.name,
				files.size,
				AndroidUtils.formatSize(this, size)
			)
		) {
			enqueueTorrent(files.map { it.mapKey })
		}
	}

	private fun enqueueTorrent(keys: List<String>) {
		plugin.downloadMapsFromTorrent(keys)
		refreshQueueUi()
	}

	private fun refreshQueueUi() {
		val n = plugin.torrentQueuedCount()
		val paused = plugin.isTorrentPaused()
		queueBar.isVisible = n > 0
		val state = when {
			paused -> getString(R.string.torrent_maps_nearby_queue_paused)
			n > 0 -> getString(R.string.torrent_maps_file_queued)
			else -> ""
		}
		queueSummary.text = if (n > 0) {
			getString(R.string.torrent_maps_nearby_queue_summary, n, state)
		} else {
			""
		}
		queuePause.isVisible = n > 0 && !paused
		queueResume.isVisible = n > 0 && paused
	}

	private fun bindSettingsSwitches() {
		bindingSettings = true
		enableSwitch.isChecked = plugin.TORRENT_ENABLED.get()
		wifiSwitch.isChecked = plugin.TORRENT_WIFI_ONLY.get()
		chargeSwitch.isChecked = plugin.TORRENT_SEED_ON_CHARGE.get()
		downloadNewSwitch.isChecked = plugin.TORRENT_DOWNLOAD_NEW.get()
		bindingSettings = false

		enableSwitch.setOnCheckedChangeListener { _, checked ->
			if (bindingSettings) return@setOnCheckedChangeListener
			confirmToggle(
				enableSwitch,
				checked,
				if (checked) R.string.torrent_maps_confirm_enable else R.string.torrent_maps_confirm_disable
			) {
				plugin.TORRENT_ENABLED.set(checked)
				plugin.syncMapTorrent()
				refreshStatus()
			}
		}
		wifiSwitch.setOnCheckedChangeListener { _, checked ->
			if (bindingSettings) return@setOnCheckedChangeListener
			confirmToggle(wifiSwitch, checked, R.string.torrent_maps_confirm_wifi) {
				plugin.TORRENT_WIFI_ONLY.set(checked)
				plugin.syncMapTorrent()
			}
		}
		chargeSwitch.setOnCheckedChangeListener { _, checked ->
			if (bindingSettings) return@setOnCheckedChangeListener
			confirmToggle(chargeSwitch, checked, R.string.torrent_maps_confirm_seed_charge) {
				plugin.TORRENT_SEED_ON_CHARGE.set(checked)
				plugin.syncMapTorrent()
			}
		}
		downloadNewSwitch.setOnCheckedChangeListener { _, checked ->
			if (bindingSettings) return@setOnCheckedChangeListener
			confirmToggle(downloadNewSwitch, checked, R.string.torrent_maps_confirm_download_new) {
				plugin.TORRENT_DOWNLOAD_NEW.set(checked)
			}
		}
	}

	private fun confirmToggle(
		switch: SwitchCompat,
		newValue: Boolean,
		messageRes: Int,
		onConfirm: () -> Unit
	) {
		val themed = UiUtilities.getThemedContext(this, app.daynightHelper.isNightMode(ThemeUsageContext.APP))
		AlertDialog.Builder(themed)
			.setMessage(messageRes)
			.setPositiveButton(R.string.shared_string_yes) { _, _ -> onConfirm() }
			.setNegativeButton(R.string.shared_string_no) { _, _ ->
				bindingSettings = true
				switch.isChecked = !newValue
				bindingSettings = false
			}
			.setOnCancelListener {
				bindingSettings = true
				switch.isChecked = !newValue
				bindingSettings = false
			}
			.show()
	}

	private fun confirmAction(messageRes: Int, onConfirm: () -> Unit) {
		confirmAction(getString(messageRes), onConfirm)
	}

	private fun confirmAction(message: String, onConfirm: () -> Unit) {
		val themed = UiUtilities.getThemedContext(this, app.daynightHelper.isNightMode(ThemeUsageContext.APP))
		AlertDialog.Builder(themed)
			.setMessage(message)
			.setPositiveButton(R.string.shared_string_yes) { _, _ -> onConfirm() }
			.setNegativeButton(R.string.shared_string_no, null)
			.show()
	}

	private fun openRutrackerAuth() {
		val intent = Intent(this, RutrackerAuthActivity::class.java).apply {
			putExtra(RutrackerAuthActivity.EXTRA_TOPIC_URL, plugin.TORRENT_TOPIC_URL.get())
		}
		authLauncher.launch(intent)
	}

	private fun refreshAll() {
		pathSummary.text = plugin.torrentPathSummary()
		topicUrl.text = plugin.TORRENT_TOPIC_URL.get().orEmpty()
		val cookie = plugin.TORRENT_RUTRACKER_COOKIE.get().orEmpty()
		cookieSummary.text = if (cookie.isBlank()) {
			getString(R.string.torrent_maps_rutracker_cookie_empty)
		} else {
			getString(R.string.torrent_maps_rutracker_cookie_set)
		}
		refreshStatus()
		refreshVerifyUi()
		refreshBrowserList(keepFolder = false)
		refreshLogs()
	}

	private fun idleCatalogRows(): List<TorrentFileRow> {
		return plugin.torrentCatalogEntries().map {
			val path = TorrentBrowser.normalizePath(it.torrentName)
			val display = path.substringAfterLast('/')
			TorrentFileRow(
				index = it.index,
				displayName = display,
				torrentPath = path,
				mapKey = it.mapKey,
				sizeBytes = it.sizeBytes,
				doneBytes = 0L,
				progressPercent = 0,
				state = TorrentFileState.IDLE,
				browsePath = TorrentRegionPaths.browsePath(app, it.mapKey, display)
			)
		}
	}

	private fun refreshBrowserList(keepFolder: Boolean) {
		allFileRows = plugin.torrentFileRows().ifEmpty { idleCatalogRows() }.map { row ->
			if (row.browsePath.isNotBlank()) {
				row
			} else {
				row.copy(
					browsePath = TorrentRegionPaths.browsePath(app, row.mapKey, row.displayName)
				)
			}
		}
		if (!keepFolder) {
			currentFolderPath = ""
		} else if (currentFolderPath.isNotEmpty()) {
			val stillValid = allFileRows.any {
				val path = TorrentBrowser.normalizePath(
					it.browsePath.ifBlank { it.torrentPath }
				)
				path == currentFolderPath || path.startsWith("$currentFolderPath/")
			}
			if (!stillValid) {
				currentFolderPath = ""
			}
		}
		pathBreadcrumb.text = if (currentFolderPath.isEmpty()) {
			getString(R.string.torrent_maps_path_root)
		} else {
			"/$currentFolderPath"
		}
		adapter.submit(
			TorrentBrowser.buildRows(allFileRows, currentFolderPath, sortKey, sortAscending)
		)
	}

	private fun refreshLogs() {
		val lines = TorrentMapsLog.snapshot()
		logsAdapter.submit(lines)
		if (lines.isNotEmpty()) {
			logsList.scrollToPosition(lines.size - 1)
		}
	}

	private fun refreshStatus() {
		val st = plugin.mapTorrentStatus()
		statusTitle.text = when {
			!st.error.isNullOrBlank() -> st.error
			!st.waitingReason.isNullOrBlank() && !st.running -> st.waitingReason
			st.state.isNotBlank() -> st.state
			else -> getString(R.string.torrent_maps_state_stopped)
		}
		statusRates.text = getString(
			R.string.torrent_maps_status_rates,
			AndroidUtils.formatSize(this, st.downloadRate) + "/s",
			AndroidUtils.formatSize(this, st.uploadRate) + "/s"
		)
		statusMeta.text = getString(
			R.string.torrent_maps_notification,
			st.state.ifBlank { getString(R.string.torrent_maps_state_stopped) },
			st.peers,
			st.seeds,
			AndroidUtils.formatSize(this, st.totalDownloaded),
			AndroidUtils.formatSize(this, st.totalUploaded)
		)
		val running = plugin.isTorrentRunning()
		fab.setImageResource(
			if (running) R.drawable.ic_action_trip_rec_pause else R.drawable.ic_action_play_dark
		)
		fab.contentDescription = getString(
			if (running) R.string.shared_string_control_stop else R.string.shared_string_control_start
		)
		refreshQueueUi()
	}

	private fun refreshVerifyUi() {
		val st = plugin.mapVerifyStatus()
		verifyStatusView.text = when {
			st.running -> {
				val base = getString(R.string.torrent_maps_verify_running, st.done, st.total)
				if (st.currentName.isBlank()) base else "$base · ${st.currentName}"
			}
			st.cancelled -> getString(R.string.torrent_maps_verify_cancelled)
			st.finished && st.total == 0 -> getString(R.string.torrent_maps_verify_none)
			st.finished && st.badCount == 0 -> getString(R.string.torrent_maps_verify_ok, st.okCount)
			st.finished && st.badCount > 0 -> {
				val head = getString(R.string.torrent_maps_verify_bad, st.badCount, st.total)
				val names = st.badNames.take(8).joinToString("\n")
				if (names.isBlank()) head else "$head\n$names"
			}
			else -> getString(R.string.torrent_maps_verify_hashes_desc)
		}
		if (userStartedVerify && st.finished && !st.running) {
			userStartedVerify = false
			showVerifyResult(st)
		}
	}

	private fun showVerifyResult(st: TorrentVerifyStatus) {
		val message = when {
			st.cancelled -> getString(R.string.torrent_maps_verify_cancelled)
			st.total == 0 -> getString(R.string.torrent_maps_verify_none)
			st.badCount == 0 -> getString(R.string.torrent_maps_verify_ok, st.okCount)
			else -> {
				val head = getString(R.string.torrent_maps_verify_bad, st.badCount, st.total)
				val names = st.badNames.take(12).joinToString("\n")
				if (names.isBlank()) head else "$head\n$names"
			}
		}
		val themed = UiUtilities.getThemedContext(this, app.daynightHelper.isNightMode(ThemeUsageContext.APP))
		val builder = AlertDialog.Builder(themed).setMessage(message)
		if (st.badCount > 0 && !st.cancelled) {
			builder.setPositiveButton(R.string.torrent_maps_verify_redownload) { _, _ ->
				plugin.redownloadCorruptMaps()
				refreshQueueUi()
			}
			builder.setNegativeButton(R.string.shared_string_close, null)
		} else {
			builder.setPositiveButton(R.string.shared_string_ok, null)
		}
		builder.show()
	}

	private fun editTopicUrl() {
		val themed = UiUtilities.getThemedContext(this, app.daynightHelper.isNightMode(ThemeUsageContext.APP))
		val input = android.widget.EditText(themed).apply {
			setText(plugin.TORRENT_TOPIC_URL.get())
			setSelection(text.length)
		}
		AlertDialog.Builder(themed)
			.setTitle(R.string.torrent_maps_topic_url)
			.setView(input)
			.setPositiveButton(R.string.shared_string_save) { _, _ ->
				plugin.TORRENT_TOPIC_URL.set(input.text?.toString()?.trim().orEmpty())
				refreshAll()
			}
			.setNegativeButton(R.string.shared_string_cancel, null)
			.show()
	}

	private fun editCookie() {
		val themed = UiUtilities.getThemedContext(this, app.daynightHelper.isNightMode(ThemeUsageContext.APP))
		val input = android.widget.EditText(themed).apply {
			setText(plugin.TORRENT_RUTRACKER_COOKIE.get())
			hint = getString(R.string.torrent_maps_rutracker_cookie_desc)
		}
		AlertDialog.Builder(themed)
			.setTitle(R.string.torrent_maps_rutracker_cookie_manual)
			.setView(input)
			.setPositiveButton(R.string.shared_string_save) { _, _ ->
				plugin.TORRENT_RUTRACKER_COOKIE.set(input.text?.toString().orEmpty())
				refreshAll()
			}
			.setNegativeButton(R.string.shared_string_cancel, null)
			.show()
	}

	private class BrowserAdapter(
		private val onClick: (TorrentBrowserRow) -> Unit,
		private val onLongClick: (TorrentBrowserRow) -> Boolean,
		private val onFolderDownload: (TorrentBrowserRow.Folder) -> Unit
	) : RecyclerView.Adapter<BrowserAdapter.Holder>() {
		private var rows: List<TorrentBrowserRow> = emptyList()

		fun submit(newRows: List<TorrentBrowserRow>) {
			rows = newRows
			notifyDataSetChanged()
		}

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
			val view = LayoutInflater.from(parent.context)
				.inflate(R.layout.torrent_maps_file_row, parent, false)
			return Holder(view, onClick, onLongClick, onFolderDownload)
		}

		override fun onBindViewHolder(holder: Holder, position: Int) {
			holder.bind(rows[position])
		}

		override fun getItemCount(): Int = rows.size

		class Holder(
			view: View,
			private val onClick: (TorrentBrowserRow) -> Unit,
			private val onLongClick: (TorrentBrowserRow) -> Boolean,
			private val onFolderDownload: (TorrentBrowserRow.Folder) -> Unit
		) : RecyclerView.ViewHolder(view) {
			private val icon: ImageView = view.findViewById(R.id.file_icon)
			private val nameFrame: ProgressNameFrame = view.findViewById(R.id.file_name_frame)
			private val name: TextView = view.findViewById(R.id.file_name)
			private val meta: TextView = view.findViewById(R.id.file_meta)
			private val chip: TextView = view.findViewById(R.id.file_state)

			fun bind(row: TorrentBrowserRow) {
				val ctx = itemView.context
				itemView.setOnClickListener { onClick(row) }
				itemView.setOnLongClickListener { onLongClick(row) }
				when (row) {
					is TorrentBrowserRow.Up -> {
						icon.isVisible = true
						icon.setImageResource(R.drawable.ic_action_folder_open)
						name.text = ctx.getString(R.string.torrent_maps_folder_up)
						meta.text = ""
						nameFrame.setProgressPercent(0)
						chip.text = "⬆"
						chip.contentDescription = ctx.getString(R.string.torrent_maps_folder_up)
						chip.setOnClickListener { onClick(row) }
					}
					is TorrentBrowserRow.Folder -> {
						icon.isVisible = true
						icon.setImageResource(R.drawable.ic_action_folder)
						name.text = row.name
						meta.text = ctx.getString(
							R.string.torrent_maps_folder_meta,
							row.childCount,
							AndroidUtils.formatSize(ctx, row.sizeBytes)
						)
						nameFrame.setProgressPercent(row.progressPercent)
						val statusText = statusLabel(ctx, row.state)
						chip.text = if (row.downloadableCount > 0) "⬇️" else statusEmoji(row.state)
						chip.contentDescription = statusText
						chip.setOnClickListener {
							if (row.downloadableCount > 0) {
								onFolderDownload(row)
							} else {
								onClick(row)
							}
						}
					}
					is TorrentBrowserRow.File -> {
						icon.isVisible = false
						name.text = row.row.displayName
						meta.text = AndroidUtils.formatSize(ctx, row.row.sizeBytes) +
								" · " + row.row.progressPercent + "%"
						nameFrame.setProgressPercent(row.row.progressPercent)
						val statusText = statusLabel(ctx, row.row.state)
						chip.text = statusEmoji(row.row.state)
						chip.contentDescription = statusText
						chip.setOnClickListener { onClick(row) }
					}
				}
			}
		}
	}

	private class LogsAdapter : RecyclerView.Adapter<LogsAdapter.Holder>() {
		private var lines: List<String> = emptyList()

		fun submit(newLines: List<String>) {
			lines = newLines
			notifyDataSetChanged()
		}

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
			val view = LayoutInflater.from(parent.context)
				.inflate(R.layout.torrent_maps_log_row, parent, false)
			return Holder(view as TextView)
		}

		override fun onBindViewHolder(holder: Holder, position: Int) {
			holder.bind(lines[position])
		}

		override fun getItemCount(): Int = lines.size

		class Holder(private val text: TextView) : RecyclerView.ViewHolder(text) {
			fun bind(line: String) {
				text.text = line
			}
		}
	}

	companion object {
		fun statusEmoji(state: TorrentFileState): String = when (state) {
			TorrentFileState.SEEDING -> "🌱"
			TorrentFileState.DOWNLOADING -> "⬇️"
			TorrentFileState.UPDATING -> "🔄"
			TorrentFileState.QUEUED -> "⏳"
			TorrentFileState.COMPLETE -> "✅"
			TorrentFileState.SKIPPED -> "⏭"
			TorrentFileState.IDLE -> "⏸"
			TorrentFileState.VERIFYING -> "🔍"
			TorrentFileState.CORRUPT -> "⚠️"
		}

		fun statusLabel(ctx: android.content.Context, state: TorrentFileState): String =
			when (state) {
				TorrentFileState.SEEDING -> ctx.getString(R.string.torrent_maps_state_seeding)
				TorrentFileState.DOWNLOADING -> ctx.getString(R.string.torrent_maps_file_downloading)
				TorrentFileState.UPDATING -> ctx.getString(R.string.torrent_maps_file_updating)
				TorrentFileState.QUEUED -> ctx.getString(R.string.torrent_maps_file_queued)
				TorrentFileState.COMPLETE -> ctx.getString(R.string.torrent_maps_file_complete)
				TorrentFileState.SKIPPED -> ctx.getString(R.string.torrent_maps_file_skipped)
				TorrentFileState.IDLE -> ctx.getString(R.string.torrent_maps_file_idle)
				TorrentFileState.VERIFYING -> ctx.getString(R.string.torrent_maps_file_verifying)
				TorrentFileState.CORRUPT -> ctx.getString(R.string.torrent_maps_file_corrupt)
			}
	}
}
