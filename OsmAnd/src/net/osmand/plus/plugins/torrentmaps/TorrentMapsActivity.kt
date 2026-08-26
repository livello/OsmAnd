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
	private var bindingSettings = false
	private var currentFolderPath: String = ""
	private var sortKey: TorrentSortKey = TorrentSortKey.NAME
	private var sortAscending: Boolean = true
	private var allFileRows: List<TorrentFileRow> = emptyList()

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

		findViewById<View>(R.id.torrent_close).setOnClickListener { finish() }
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

		adapter = BrowserAdapter { row -> onBrowserClick(row) }
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
			is TorrentBrowserRow.File -> {
				val statusText = statusLabel(this, row.row.state)
				android.widget.Toast.makeText(
					this,
					"$statusText · ${row.row.progressPercent}%",
					android.widget.Toast.LENGTH_SHORT
				).show()
			}
		}
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
		val themed = UiUtilities.getThemedContext(this, app.daynightHelper.isNightMode(ThemeUsageContext.APP))
		AlertDialog.Builder(themed)
			.setMessage(messageRes)
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
		refreshBrowserList(keepFolder = false)
		refreshLogs()
	}

	private fun idleCatalogRows(): List<TorrentFileRow> {
		return plugin.torrentCatalogEntries().map {
			val path = TorrentBrowser.normalizePath(it.torrentName)
			TorrentFileRow(
				index = it.index,
				displayName = path.substringAfterLast('/'),
				torrentPath = path,
				mapKey = it.mapKey,
				sizeBytes = it.sizeBytes,
				doneBytes = 0L,
				progressPercent = 0,
				state = TorrentFileState.IDLE
			)
		}
	}

	private fun refreshBrowserList(keepFolder: Boolean) {
		allFileRows = plugin.torrentFileRows().ifEmpty { idleCatalogRows() }
		if (!keepFolder) {
			currentFolderPath = ""
		} else if (currentFolderPath.isNotEmpty()) {
			val stillValid = allFileRows.any {
				val path = TorrentBrowser.normalizePath(it.torrentPath)
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
		private val onClick: (TorrentBrowserRow) -> Unit
	) : RecyclerView.Adapter<BrowserAdapter.Holder>() {
		private var rows: List<TorrentBrowserRow> = emptyList()

		fun submit(newRows: List<TorrentBrowserRow>) {
			rows = newRows
			notifyDataSetChanged()
		}

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
			val view = LayoutInflater.from(parent.context)
				.inflate(R.layout.torrent_maps_file_row, parent, false)
			return Holder(view, onClick)
		}

		override fun onBindViewHolder(holder: Holder, position: Int) {
			holder.bind(rows[position])
		}

		override fun getItemCount(): Int = rows.size

		class Holder(
			view: View,
			private val onClick: (TorrentBrowserRow) -> Unit
		) : RecyclerView.ViewHolder(view) {
			private val icon: ImageView = view.findViewById(R.id.file_icon)
			private val nameFrame: ProgressNameFrame = view.findViewById(R.id.file_name_frame)
			private val name: TextView = view.findViewById(R.id.file_name)
			private val meta: TextView = view.findViewById(R.id.file_meta)
			private val chip: TextView = view.findViewById(R.id.file_state)

			fun bind(row: TorrentBrowserRow) {
				val ctx = itemView.context
				itemView.setOnClickListener { onClick(row) }
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
						chip.text = statusEmoji(row.state)
						chip.contentDescription = statusText
						chip.setOnClickListener {
							android.widget.Toast.makeText(
								ctx,
								"$statusText · ${row.progressPercent}%",
								android.widget.Toast.LENGTH_SHORT
							).show()
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
						chip.setOnClickListener {
							android.widget.Toast.makeText(
								ctx,
								"$statusText · ${row.row.progressPercent}%",
								android.widget.Toast.LENGTH_SHORT
							).show()
						}
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
			}
	}
}
