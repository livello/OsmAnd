package net.osmand.plus.plugins.torrentmaps

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
	private lateinit var list: RecyclerView
	private lateinit var settingsPanel: View
	private lateinit var fab: FloatingActionButton
	private lateinit var tabs: TabLayout
	private lateinit var adapter: FileAdapter
	private lateinit var enableSwitch: SwitchCompat
	private lateinit var wifiSwitch: SwitchCompat
	private lateinit var chargeSwitch: SwitchCompat
	private lateinit var downloadNewSwitch: SwitchCompat
	private lateinit var pathSummary: TextView
	private lateinit var topicUrl: TextView
	private lateinit var cookieSummary: TextView

	private val torrentFileLauncher = registerForActivityResult(
		ActivityResultContracts.OpenDocument()
	) { uri ->
		if (uri == null) {
			return@registerForActivityResult
		}
		if (plugin.importTorrentFile(uri)) {
			refreshAll()
		} else {
			app.showToastMessage(R.string.torrent_maps_invalid)
		}
	}

	private val refreshTick = object : Runnable {
		override fun run() {
			if (isFinishing) {
				return
			}
			refreshStatus()
			if (tabs.selectedTabPosition == 0) {
				adapter.submit(plugin.torrentFileRows().ifEmpty { idleCatalogRows() })
			}
			uiHandler.postDelayed(this, 1000)
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		val night = app.daynightHelper.isNightMode(ThemeUsageContext.APP)
		setTheme(if (night) R.style.OsmandDarkTheme else R.style.OsmandLightTheme)
		super.onCreate(savedInstanceState)
		setContentView(R.layout.torrent_maps_activity)

		statusTitle = findViewById(R.id.torrent_status_title)
		statusRates = findViewById(R.id.torrent_status_rates)
		statusMeta = findViewById(R.id.torrent_status_meta)
		list = findViewById(R.id.torrent_file_list)
		settingsPanel = findViewById(R.id.torrent_settings_panel)
		fab = findViewById(R.id.torrent_fab)
		tabs = findViewById(R.id.torrent_tabs)
		enableSwitch = findViewById(R.id.torrent_enable)
		wifiSwitch = findViewById(R.id.torrent_wifi_only)
		chargeSwitch = findViewById(R.id.torrent_seed_charge)
		downloadNewSwitch = findViewById(R.id.torrent_download_new)
		pathSummary = findViewById(R.id.torrent_path_summary)
		topicUrl = findViewById(R.id.torrent_topic_url)
		cookieSummary = findViewById(R.id.torrent_cookie_summary)

		findViewById<View>(R.id.torrent_close).setOnClickListener { finish() }
		findViewById<View>(R.id.torrent_pick_file).setOnClickListener {
			torrentFileLauncher.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*"))
		}
		findViewById<View>(R.id.torrent_refresh_rutracker).setOnClickListener {
			app.showToastMessage(R.string.torrent_maps_refresh_started)
			plugin.refreshTorrentFromRutracker { _, _ -> refreshAll() }
		}
		findViewById<View>(R.id.torrent_topic_row).setOnClickListener { editTopicUrl() }
		findViewById<View>(R.id.torrent_cookie_row).setOnClickListener { editCookie() }

		adapter = FileAdapter()
		list.layoutManager = LinearLayoutManager(this)
		list.adapter = adapter

		tabs.addTab(tabs.newTab().setText(R.string.torrent_maps_tab_torrents))
		tabs.addTab(tabs.newTab().setText(R.string.torrent_maps_tab_settings))
		tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
			override fun onTabSelected(tab: TabLayout.Tab) {
				val settings = tab.position == 1
				list.isVisible = !settings
				settingsPanel.isVisible = settings
				fab.isVisible = !settings
			}

			override fun onTabUnselected(tab: TabLayout.Tab?) {}
			override fun onTabReselected(tab: TabLayout.Tab?) {}
		})

		fab.setOnClickListener {
			if (plugin.isTorrentRunning()) {
				plugin.stopMapTorrent()
			} else {
				if (!plugin.TORRENT_ENABLED.get()) {
					plugin.TORRENT_ENABLED.set(true)
					enableSwitch.isChecked = true
				}
				plugin.startMapTorrentManual()
			}
			refreshStatus()
		}

		bindSettingsSwitches()
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

	private fun bindSettingsSwitches() {
		enableSwitch.isChecked = plugin.TORRENT_ENABLED.get()
		wifiSwitch.isChecked = plugin.TORRENT_WIFI_ONLY.get()
		chargeSwitch.isChecked = plugin.TORRENT_SEED_ON_CHARGE.get()
		downloadNewSwitch.isChecked = plugin.TORRENT_DOWNLOAD_NEW.get()
		enableSwitch.setOnCheckedChangeListener { _, checked ->
			plugin.TORRENT_ENABLED.set(checked)
			plugin.syncMapTorrent()
			refreshStatus()
		}
		wifiSwitch.setOnCheckedChangeListener { _, checked ->
			plugin.TORRENT_WIFI_ONLY.set(checked)
			plugin.syncMapTorrent()
		}
		chargeSwitch.setOnCheckedChangeListener { _, checked ->
			plugin.TORRENT_SEED_ON_CHARGE.set(checked)
			plugin.syncMapTorrent()
		}
		downloadNewSwitch.setOnCheckedChangeListener { _, checked ->
			plugin.TORRENT_DOWNLOAD_NEW.set(checked)
		}
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
		adapter.submit(plugin.torrentFileRows().ifEmpty { idleCatalogRows() })
	}

	private fun idleCatalogRows(): List<TorrentFileRow> {
		return plugin.torrentCatalogEntries().map {
			TorrentFileRow(
				index = it.index,
				displayName = it.torrentName.substringAfterLast('/').substringAfterLast('\\'),
				mapKey = it.mapKey,
				sizeBytes = it.sizeBytes,
				doneBytes = 0L,
				progressPercent = 0,
				state = TorrentFileState.IDLE
			)
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
		androidx.appcompat.app.AlertDialog.Builder(themed)
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
		androidx.appcompat.app.AlertDialog.Builder(themed)
			.setTitle(R.string.torrent_maps_rutracker_cookie)
			.setView(input)
			.setPositiveButton(R.string.shared_string_save) { _, _ ->
				plugin.TORRENT_RUTRACKER_COOKIE.set(input.text?.toString().orEmpty())
				refreshAll()
			}
			.setNegativeButton(R.string.shared_string_cancel, null)
			.show()
	}

	private class FileAdapter : RecyclerView.Adapter<FileAdapter.Holder>() {
		private var rows: List<TorrentFileRow> = emptyList()

		fun submit(newRows: List<TorrentFileRow>) {
			rows = newRows
			notifyDataSetChanged()
		}

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
			val view = LayoutInflater.from(parent.context)
				.inflate(R.layout.torrent_maps_file_row, parent, false)
			return Holder(view)
		}

		override fun onBindViewHolder(holder: Holder, position: Int) {
			holder.bind(rows[position])
		}

		override fun getItemCount(): Int = rows.size

		class Holder(view: View) : RecyclerView.ViewHolder(view) {
			private val name: TextView = view.findViewById(R.id.file_name)
			private val meta: TextView = view.findViewById(R.id.file_meta)
			private val chip: TextView = view.findViewById(R.id.file_state)
			private val progress: ProgressBar = view.findViewById(R.id.file_progress)

			fun bind(row: TorrentFileRow) {
				val ctx = itemView.context
				name.text = row.displayName
				meta.text = AndroidUtils.formatSize(ctx, row.sizeBytes) +
						" · " + row.progressPercent + "%"
				chip.text = when (row.state) {
					TorrentFileState.SEEDING -> ctx.getString(R.string.torrent_maps_state_seeding)
					TorrentFileState.DOWNLOADING -> ctx.getString(R.string.torrent_maps_file_downloading)
					TorrentFileState.UPDATING -> ctx.getString(R.string.torrent_maps_file_updating)
					TorrentFileState.QUEUED -> ctx.getString(R.string.torrent_maps_file_queued)
					TorrentFileState.COMPLETE -> ctx.getString(R.string.torrent_maps_file_complete)
					TorrentFileState.SKIPPED -> ctx.getString(R.string.torrent_maps_file_skipped)
					TorrentFileState.IDLE -> ctx.getString(R.string.torrent_maps_file_idle)
				}
				progress.progress = row.progressPercent
				progress.isIndeterminate = false
			}
		}
	}
}
