package net.osmand.plus.plugins.torrentmaps

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.settings.enums.ThemeUsageContext
import net.osmand.plus.utils.AndroidUtils
import net.osmand.plus.utils.UiUtilities

/**
 * Offline peer map browser: NSD discovery + HTTP catalog / transfer.
 * Header controls scroll linearly with the list (single RecyclerView).
 */
class NearbyMapsActivity : AppCompatActivity(), NearbyMapsController.Listener {

	private val app: OsmandApplication
		get() = application as OsmandApplication

	private val plugin: TorrentMapsPlugin
		get() = PluginsHelper.requirePlugin(TorrentMapsPlugin::class.java)

	private val nearby: NearbyMapsController
		get() = plugin.nearby

	private val uiHandler = Handler(Looper.getMainLooper())
	private lateinit var list: RecyclerView
	private lateinit var adapter: NearbyAdapter

	private var bindingSwitch = false
	private var selectedPeer: NearbyPeer? = null
	private var peerRows: List<NearbyFileRow> = emptyList()
	private var folderPath: String = ""
	private var peersCache: List<NearbyPeer> = emptyList()
	private var statusText: String = ""
	private var breadcrumbText: String = ""
	private var showCatalogChrome = false
	private var selectedTorrent: NearbyTorrentOffer? = null

	private val progressTick = object : Runnable {
		override fun run() {
			if (isFinishing) return
			if (selectedPeer != null) {
				refreshQueueUi()
			} else {
				refreshStatusLine()
			}
			uiHandler.postDelayed(this, 1000)
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		val night = app.daynightHelper.isNightMode(ThemeUsageContext.APP)
		setTheme(if (night) R.style.OsmandDarkTheme else R.style.OsmandLightTheme)
		super.onCreate(savedInstanceState)
		setContentView(R.layout.torrent_maps_nearby_activity)
		TorrentMapsLog.init(app)

		list = findViewById(R.id.nearby_list)
		adapter = NearbyAdapter(
			onClose = { onClosePressed() },
			onScan = {
				nearby.rescan()
				app.showToastMessage(R.string.torrent_maps_nearby_scanning)
				refreshPeerList()
			},
			onBackPeers = { showPeers() },
			onCancelDownload = {
				nearby.downloader.pauseQueue()
				refreshQueueUi()
			},
			onQueuePause = {
				nearby.downloader.pauseQueue()
				refreshQueueUi()
			},
			onQueueResume = {
				nearby.downloader.resumeQueue { refreshQueueUi() }
				refreshQueueUi()
			},
			onQueueClear = {
				confirmAction(getString(R.string.torrent_maps_nearby_confirm_clear_queue)) {
					nearby.downloader.clearQueue { refreshQueueUi() }
				}
			},
			onBreadcrumb = {
				if (folderPath.isNotEmpty()) {
					folderPath = TorrentBrowser.parentPath(folderPath)
					refreshCatalogList()
				}
			},
			onShareToggle = { switch, checked ->
				if (bindingSwitch) return@NearbyAdapter
				confirmToggle(switch, checked, if (checked) {
					R.string.torrent_maps_nearby_confirm_share_on
				} else {
					R.string.torrent_maps_nearby_confirm_share_off
				}) {
					if (checked) nearby.startSharing() else nearby.stopSharing()
				}
			},
			onRowClick = { row -> onRowClick(row) },
			onRowLongClick = { row -> onRowLongClick(row) },
			onFolderDownload = { folder -> confirmFolderDownload(folder) },
			shareChecked = { nearby.sharing },
			statusProvider = { statusText },
			breadcrumbProvider = { breadcrumbText },
			catalogChrome = { showCatalogChrome },
			downloadBusy = { nearby.downloader.busy },
			queueSummary = { queueSummaryText() },
			queueVisible = { nearby.downloader.queueSnapshot().isNotEmpty() },
			queuePaused = { nearby.downloader.queuePaused }
		)
		list.layoutManager = LinearLayoutManager(this)
		list.adapter = adapter

		showPeers()
		refreshStatusLine()
	}

	/**
	 * X on peer catalog → back to peer list (keep Scan).
	 * X on peer list → leave Nearby only (do not stop remote share / local share).
	 */
	private fun onClosePressed() {
		if (showCatalogChrome || selectedPeer != null) {
			TorrentMapsLog.append("nearby UI: leave peer catalog")
			showPeers()
			nearby.ensureDiscovery()
			return
		}
		TorrentMapsLog.append("nearby UI: exit activity")
		finish()
	}

	@Deprecated("Deprecated in Java")
	override fun onBackPressed() {
		if (showCatalogChrome || selectedPeer != null) {
			onClosePressed()
		} else {
			super.onBackPressed()
		}
	}

	override fun onResume() {
		super.onResume()
		nearby.addListener(this)
		nearby.ensureDiscovery()
		uiHandler.removeCallbacks(progressTick)
		uiHandler.post(progressTick)
	}

	override fun onPause() {
		uiHandler.removeCallbacks(progressTick)
		nearby.removeListener(this)
		// Do NOT stop discovery here — pausing for dialogs / multitasking must not
		// kill Scan or tear down the peer session. Discovery stops in onDestroy if
		// the user left Nearby and is not sharing.
		super.onPause()
	}

	override fun onDestroy() {
		if (isFinishing && !nearby.sharing) {
			nearby.stopDiscoveryOnly()
		}
		super.onDestroy()
	}

	override fun onPeersChanged(peers: List<NearbyPeer>) {
		peersCache = peers
		if (selectedPeer == null) {
			refreshPeerList()
		}
	}

	override fun onSharingChanged(sharing: Boolean, endpoint: String?) {
		bindingSwitch = true
		adapter.notifyHeaderChanged()
		bindingSwitch = false
		refreshStatusLine()
	}

	private fun showPeers() {
		selectedPeer = null
		peerRows = emptyList()
		folderPath = ""
		showCatalogChrome = false
		selectedTorrent = null
		refreshPeerList()
		refreshStatusLine()
	}

	private fun refreshPeerList() {
		val rows = peersCache.map { NearbyBrowserRow.Peer(it) }
		adapter.submit(rows)
		if (rows.isEmpty()) {
			statusText = getString(R.string.torrent_maps_nearby_no_peers)
			adapter.notifyHeaderChanged()
		}
	}

	private fun openPeer(peer: NearbyPeer) {
		confirmAction(
			getString(R.string.torrent_maps_nearby_confirm_open_peer, peer.deviceName)
		) {
			app.showToastMessage(R.string.torrent_maps_nearby_loading_catalog)
			nearby.fetchCatalog(peer) { catalog, error ->
				if (catalog == null) {
					app.showToastMessage(
						getString(R.string.torrent_maps_nearby_catalog_failed, error ?: "")
					)
					return@fetchCatalog
				}
				selectedPeer = peer
				folderPath = ""
				nearby.refreshLocalIndex()
				selectedTorrent = catalog.torrent
				peerRows = catalog.maps.map { entry ->
					NearbyFileRow(
						entry = entry,
						status = nearby.compareStatus(entry),
						browsePath = TorrentRegionPaths.browsePath(app, entry.mapKey, entry.displayName)
					)
				}
				showCatalogChrome = true
				refreshCatalogList()
				TorrentMapsLog.append("nearby catalog ${peer.deviceName}: ${catalog.maps.size} maps")
			}
		}
	}

	private fun refreshCatalogList() {
		applyQueueProgress()
		breadcrumbText = if (folderPath.isEmpty()) {
			getString(R.string.torrent_maps_path_root)
		} else {
			"/$folderPath"
		}
		adapter.submit(
			NearbyBrowser.buildRows(
				peerRows,
				folderPath,
				if (folderPath.isEmpty()) selectedTorrent else null
			)
		)
		refreshStatusLine()
	}

	private fun applyQueueProgress() {
		val dl = nearby.downloader
		peerRows = peerRows.map { row ->
			row.copy(progressPercent = dl.progressPercentFor(row.entry.path))
		}
	}

	private fun refreshQueueUi() {
		if (selectedPeer != null) {
			nearby.refreshLocalIndex()
			peerRows = peerRows.map {
				NearbyFileRow(
					it.entry,
					nearby.compareStatus(it.entry),
					nearby.downloader.progressPercentFor(it.entry.path),
					it.browsePath
				)
			}
			refreshCatalogList()
		} else {
			refreshStatusLine()
		}
	}

	private fun queueSummaryText(): String {
		val snap = nearby.downloader.queueSnapshot()
		if (snap.isEmpty()) return ""
		val state = if (nearby.downloader.queuePaused) {
			getString(R.string.torrent_maps_nearby_queue_paused)
		} else if (nearby.downloader.busy) {
			val pct = if (nearby.downloader.progressTotal > 0) {
				((nearby.downloader.progressBytes * 100L) / nearby.downloader.progressTotal).toInt()
			} else 0
			"${nearby.downloader.progressPath?.substringAfterLast('/')} $pct%"
		} else {
			getString(R.string.torrent_maps_file_queued)
		}
		return getString(R.string.torrent_maps_nearby_queue_summary, snap.size, state)
	}

	private fun onRowClick(row: NearbyBrowserRow) {
		when (row) {
			is NearbyBrowserRow.Peer -> openPeer(row.peer)
			is NearbyBrowserRow.Up -> {
				folderPath = row.parentPath
				refreshCatalogList()
			}
			is NearbyBrowserRow.Folder -> {
				folderPath = row.path
				refreshCatalogList()
			}
			is NearbyBrowserRow.File -> confirmDownload(row.row)
			is NearbyBrowserRow.TorrentOffer -> confirmReplaceTorrent(row.offer)
		}
	}

	private fun onRowLongClick(row: NearbyBrowserRow): Boolean {
		when (row) {
			is NearbyBrowserRow.File -> {
				val item = nearby.downloader.queueSnapshot()
					.firstOrNull { it.entry.path == row.row.entry.path }
				if (item != null) {
					confirmAction(
						getString(R.string.torrent_maps_nearby_confirm_remove, row.row.entry.fileName)
					) {
						nearby.downloader.removeItem(item.id) { refreshQueueUi() }
					}
					return true
				}
			}
			is NearbyBrowserRow.Folder -> {
				confirmFolderDownload(row)
				return true
			}
			else -> {}
		}
		return false
	}

	private fun confirmDownload(row: NearbyFileRow) {
		selectedPeer ?: return
		val statusLabel = statusLabel(row.status)
		if (!NearbyMapCompare.isDownloadable(row.status) && row.status != NearbyMapStatus.UNKNOWN) {
			android.widget.Toast.makeText(
				this,
				"$statusLabel · ${row.entry.fileName}",
				android.widget.Toast.LENGTH_SHORT
			).show()
			return
		}
		val msg = getString(
			R.string.torrent_maps_nearby_confirm_download,
			row.entry.fileName,
			AndroidUtils.formatSize(this, row.entry.sizeBytes),
			statusLabel
		)
		confirmAction(msg) {
			enqueueEntries(listOf(row.entry))
		}
	}

	private fun confirmFolderDownload(folder: NearbyBrowserRow.Folder) {
		val prefix = "${folder.path}/"
		val files = peerRows.filter {
			val path = TorrentBrowser.normalizePath(it.browsePath.ifBlank { it.entry.path })
			(path == folder.path || path.startsWith(prefix)) &&
				NearbyMapCompare.isDownloadable(it.status)
		}
		if (files.isEmpty()) {
			app.showToastMessage(R.string.torrent_maps_nearby_folder_empty)
			return
		}
		val size = files.sumOf { it.entry.sizeBytes }
		confirmAction(
			getString(
				R.string.torrent_maps_nearby_confirm_folder,
				folder.name,
				files.size,
				AndroidUtils.formatSize(this, size)
			)
		) {
			enqueueEntries(files.map { it.entry })
		}
	}

	private fun enqueueEntries(entries: List<NearbyMapEntry>) {
		val peer = selectedPeer ?: return
		val added = nearby.downloader.enqueue(peer, entries, { entry ->
			peerRows.firstOrNull { it.entry.path == entry.path }?.browsePath.orEmpty()
		}) { refreshQueueUi() }
		if (added > 0) {
			app.showToastMessage(getString(R.string.torrent_maps_nearby_queued, added))
		}
		refreshQueueUi()
	}

	private fun confirmReplaceTorrent(offer: NearbyTorrentOffer) {
		val peer = selectedPeer ?: return
		confirmAction(
			getString(R.string.torrent_maps_nearby_confirm_torrent, peer.deviceName)
		) {
			app.showToastMessage(R.string.torrent_maps_nearby_loading_catalog)
			if (offer.torrentAvailable) {
				nearby.fetchPeerTorrent(peer) { bytes, error ->
					if (bytes == null) {
						app.showToastMessage(
							getString(R.string.torrent_maps_nearby_torrent_failed, error ?: "")
						)
					} else {
						applyTorrentBytes(bytes, offer)
					}
				}
			} else if (offer.magnet.isNotBlank()) {
				Thread({
					val result = RutrackerTorrentFetcher.resolveMagnet(offer.magnet)
					uiHandler.post {
						if (result.ok && result.bytes != null) {
							applyTorrentBytes(result.bytes, offer.copy(torrentName = result.fileName ?: offer.torrentName))
						} else {
							app.showToastMessage(
								getString(
									R.string.torrent_maps_nearby_torrent_failed,
									result.message
								)
							)
						}
					}
				}, "nearby-magnet").start()
			} else {
				app.showToastMessage(
					getString(R.string.torrent_maps_nearby_torrent_failed, "empty")
				)
			}
		}
	}

	private fun applyTorrentBytes(bytes: ByteArray, offer: NearbyTorrentOffer) {
		val name = offer.torrentName.ifBlank { "maps.torrent" }
		val ok = plugin.applyTorrentBytes(bytes, name, offer.magnet)
		app.showToastMessage(
			if (ok) getString(R.string.torrent_maps_nearby_torrent_ok, name)
			else getString(R.string.torrent_maps_nearby_torrent_failed, name)
		)
	}

	private fun refreshStatusLine() {
		val dl = nearby.downloader
		val sharePart = if (nearby.sharing) {
			getString(R.string.torrent_maps_nearby_endpoint, nearby.endpoint ?: nearby.localWifiHint())
		} else {
			getString(R.string.torrent_maps_nearby_share_off, nearby.localWifiHint())
		}
		val peerPart = selectedPeer?.let {
			getString(R.string.torrent_maps_nearby_peer_open, it.deviceName, peerRows.size)
		} ?: getString(R.string.torrent_maps_nearby_peers_count, peersCache.size)
		val progress = when {
			dl.busy -> {
				val pct = if (dl.progressTotal > 0) {
					((dl.progressBytes * 100L) / dl.progressTotal).toInt()
				} else 0
				val rate = NearbyMapsDownloader.formatRate(dl.progressBytesPerSec)
				" · ↓ ${dl.progressPath?.substringAfterLast('/')} $pct% · $rate"
			}
			dl.queueSnapshot().isNotEmpty() -> " · ${queueSummaryText()}"
			else -> ""
		}
		statusText = "$sharePart · $peerPart$progress"
		adapter.notifyHeaderChanged()
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
				bindingSwitch = true
				switch.isChecked = !newValue
				bindingSwitch = false
			}
			.setOnCancelListener {
				bindingSwitch = true
				switch.isChecked = !newValue
				bindingSwitch = false
			}
			.show()
	}

	private fun confirmAction(message: String, onConfirm: () -> Unit) {
		val themed = UiUtilities.getThemedContext(this, app.daynightHelper.isNightMode(ThemeUsageContext.APP))
		AlertDialog.Builder(themed)
			.setMessage(message)
			.setPositiveButton(R.string.shared_string_yes) { _, _ -> onConfirm() }
			.setNegativeButton(R.string.shared_string_no, null)
			.show()
	}

	private class NearbyAdapter(
		private val onClose: () -> Unit,
		private val onScan: () -> Unit,
		private val onBackPeers: () -> Unit,
		private val onCancelDownload: () -> Unit,
		private val onQueuePause: () -> Unit,
		private val onQueueResume: () -> Unit,
		private val onQueueClear: () -> Unit,
		private val onBreadcrumb: () -> Unit,
		private val onShareToggle: (SwitchCompat, Boolean) -> Unit,
		private val onRowClick: (NearbyBrowserRow) -> Unit,
		private val onRowLongClick: (NearbyBrowserRow) -> Boolean,
		private val onFolderDownload: (NearbyBrowserRow.Folder) -> Unit,
		private val shareChecked: () -> Boolean,
		private val statusProvider: () -> String,
		private val breadcrumbProvider: () -> String,
		private val catalogChrome: () -> Boolean,
		private val downloadBusy: () -> Boolean,
		private val queueSummary: () -> String,
		private val queueVisible: () -> Boolean,
		private val queuePaused: () -> Boolean
	) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

		companion object {
			private const val TYPE_HEADER = 0
			private const val TYPE_ROW = 1
		}

		private var rows: List<NearbyBrowserRow> = emptyList()

		fun submit(newRows: List<NearbyBrowserRow>) {
			rows = newRows
			notifyDataSetChanged()
		}

		fun notifyHeaderChanged() {
			notifyItemChanged(0)
		}

		override fun getItemViewType(position: Int): Int =
			if (position == 0) TYPE_HEADER else TYPE_ROW

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
			val inflater = LayoutInflater.from(parent.context)
			return if (viewType == TYPE_HEADER) {
				HeaderHolder(inflater.inflate(R.layout.torrent_maps_nearby_header, parent, false))
			} else {
				RowHolder(
					inflater.inflate(R.layout.torrent_maps_file_row, parent, false),
					onRowClick,
					onRowLongClick,
					onFolderDownload
				)
			}
		}

		override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
			when (holder) {
				is HeaderHolder -> holder.bind(
					status = statusProvider(),
					breadcrumb = breadcrumbProvider(),
					shareOn = shareChecked(),
					catalogMode = catalogChrome(),
					busy = downloadBusy(),
					queueText = queueSummary(),
					showQueue = queueVisible(),
					paused = queuePaused(),
					onClose = onClose,
					onScan = onScan,
					onBackPeers = onBackPeers,
					onCancelDownload = onCancelDownload,
					onQueuePause = onQueuePause,
					onQueueResume = onQueueResume,
					onQueueClear = onQueueClear,
					onBreadcrumb = onBreadcrumb,
					onShareToggle = onShareToggle
				)
				is RowHolder -> holder.bind(rows[position - 1])
			}
		}

		override fun getItemCount(): Int = rows.size + 1

		class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
			private val status: TextView = view.findViewById(R.id.nearby_status)
			private val hint: TextView = view.findViewById(R.id.nearby_hint)
			private val breadcrumb: TextView = view.findViewById(R.id.nearby_path_breadcrumb)
			private val shareSwitch: SwitchCompat = view.findViewById(R.id.nearby_share_switch)
			private val scanBtn: TextView = view.findViewById(R.id.nearby_scan)
			private val backPeersBtn: TextView = view.findViewById(R.id.nearby_back_peers)
			private val cancelBtn: TextView = view.findViewById(R.id.nearby_cancel_download)
			private val closeBtn: View = view.findViewById(R.id.nearby_close)
			private val queueBar: View = view.findViewById(R.id.nearby_queue_bar)
			private val queueSummary: TextView = view.findViewById(R.id.nearby_queue_summary)
			private val queuePause: View = view.findViewById(R.id.nearby_queue_pause)
			private val queueResume: View = view.findViewById(R.id.nearby_queue_resume)
			private val queueClear: View = view.findViewById(R.id.nearby_queue_clear)

			fun bind(
				status: String,
				breadcrumb: String,
				shareOn: Boolean,
				catalogMode: Boolean,
				busy: Boolean,
				queueText: String,
				showQueue: Boolean,
				paused: Boolean,
				onClose: () -> Unit,
				onScan: () -> Unit,
				onBackPeers: () -> Unit,
				onCancelDownload: () -> Unit,
				onQueuePause: () -> Unit,
				onQueueResume: () -> Unit,
				onQueueClear: () -> Unit,
				onBreadcrumb: () -> Unit,
				onShareToggle: (SwitchCompat, Boolean) -> Unit
			) {
				this.status.text = status
				hint.isVisible = !catalogMode
				this.breadcrumb.text = breadcrumb
				this.breadcrumb.isVisible = catalogMode
				scanBtn.isVisible = !catalogMode
				scanBtn.isEnabled = true
				backPeersBtn.isVisible = catalogMode
				cancelBtn.isVisible = busy
				queueBar.isVisible = showQueue
				queueSummary.text = queueText
				queuePause.isVisible = showQueue && !paused
				queueResume.isVisible = showQueue && paused
				shareSwitch.setOnCheckedChangeListener(null)
				shareSwitch.isChecked = shareOn
				shareSwitch.setOnCheckedChangeListener { _, checked ->
					onShareToggle(shareSwitch, checked)
				}
				closeBtn.setOnClickListener { onClose() }
				scanBtn.setOnClickListener { onScan() }
				backPeersBtn.setOnClickListener { onBackPeers() }
				cancelBtn.setOnClickListener { onCancelDownload() }
				queuePause.setOnClickListener { onQueuePause() }
				queueResume.setOnClickListener { onQueueResume() }
				queueClear.setOnClickListener { onQueueClear() }
				this.breadcrumb.setOnClickListener { onBreadcrumb() }
			}
		}

		class RowHolder(
			view: View,
			private val onClick: (NearbyBrowserRow) -> Unit,
			private val onLongClick: (NearbyBrowserRow) -> Boolean,
			private val onFolderDownload: (NearbyBrowserRow.Folder) -> Unit
		) : RecyclerView.ViewHolder(view) {
			private val icon: ImageView = view.findViewById(R.id.file_icon)
			private val nameFrame: ProgressNameFrame = view.findViewById(R.id.file_name_frame)
			private val name: TextView = view.findViewById(R.id.file_name)
			private val meta: TextView = view.findViewById(R.id.file_meta)
			private val chip: TextView = view.findViewById(R.id.file_state)

			fun bind(row: NearbyBrowserRow) {
				val ctx = itemView.context
				itemView.setOnClickListener { onClick(row) }
				itemView.setOnLongClickListener { onLongClick(row) }
				when (row) {
					is NearbyBrowserRow.Peer -> {
						icon.isVisible = true
						icon.setImageResource(R.drawable.ic_action_gsave_dark)
						name.text = row.peer.deviceName
						meta.text = "${row.peer.host}:${row.peer.port}"
						nameFrame.setProgressPercent(0)
						chip.text = "📡"
						chip.contentDescription = row.peer.deviceName
						chip.setOnClickListener { onClick(row) }
					}
					is NearbyBrowserRow.Up -> {
						icon.isVisible = true
						icon.setImageResource(R.drawable.ic_action_folder_open)
						name.text = ctx.getString(R.string.torrent_maps_folder_up)
						meta.text = ""
						nameFrame.setProgressPercent(0)
						chip.text = "⬆"
						chip.setOnClickListener { onClick(row) }
					}
					is NearbyBrowserRow.Folder -> {
						icon.isVisible = true
						icon.setImageResource(R.drawable.ic_action_folder)
						name.text = row.name
						meta.text = ctx.getString(
							R.string.torrent_maps_folder_meta,
							row.childCount,
							AndroidUtils.formatSize(ctx, row.sizeBytes)
						)
						nameFrame.setProgressPercent(row.progressPercent)
						chip.text = if (row.downloadableCount > 0) "⬇️" else "📁"
						chip.setOnClickListener {
							if (row.downloadableCount > 0) onFolderDownload(row) else onClick(row)
						}
					}
					is NearbyBrowserRow.File -> {
						icon.isVisible = false
						name.text = row.row.entry.displayName
						meta.text = AndroidUtils.formatSize(ctx, row.row.entry.sizeBytes)
						nameFrame.setProgressPercent(row.row.progressPercent)
						val label = statusLabel(ctx, row.row.status)
						chip.text = statusEmoji(row.row.status)
						chip.contentDescription = label
						chip.setOnClickListener {
							android.widget.Toast.makeText(ctx, label, android.widget.Toast.LENGTH_SHORT).show()
						}
					}
					is NearbyBrowserRow.TorrentOffer -> {
						icon.isVisible = true
						icon.setImageResource(R.drawable.ic_action_gsave_dark)
						name.text = ctx.getString(R.string.torrent_maps_nearby_torrent_row)
						val date = if (row.offer.torrentDateMs > 0L) {
							java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.US)
								.format(java.util.Date(row.offer.torrentDateMs))
						} else {
							ctx.getString(R.string.torrent_maps_nearby_torrent_none)
						}
						meta.text = ctx.getString(R.string.torrent_maps_nearby_torrent_meta, date)
						nameFrame.setProgressPercent(0)
						chip.text = "🧲"
						chip.setOnClickListener { onClick(row) }
					}
				}
			}
		}
	}

	companion object {
		fun statusEmoji(status: NearbyMapStatus): String = when (status) {
			NearbyMapStatus.MISSING -> "⬇️"
			NearbyMapStatus.SAME -> "✅"
			NearbyMapStatus.LOCAL_OLDER -> "🔄"
			NearbyMapStatus.LOCAL_NEWER -> "⏭"
			NearbyMapStatus.SIZE_MISMATCH -> "⚠️"
			NearbyMapStatus.UNKNOWN -> "❓"
		}

		fun statusLabel(ctx: android.content.Context, status: NearbyMapStatus): String =
			when (status) {
				NearbyMapStatus.MISSING -> ctx.getString(R.string.torrent_maps_nearby_status_missing)
				NearbyMapStatus.SAME -> ctx.getString(R.string.torrent_maps_nearby_status_same)
				NearbyMapStatus.LOCAL_OLDER -> ctx.getString(R.string.torrent_maps_nearby_status_older)
				NearbyMapStatus.LOCAL_NEWER -> ctx.getString(R.string.torrent_maps_nearby_status_newer)
				NearbyMapStatus.SIZE_MISMATCH -> ctx.getString(R.string.torrent_maps_nearby_status_mismatch)
				NearbyMapStatus.UNKNOWN -> ctx.getString(R.string.torrent_maps_nearby_status_unknown)
			}
	}

	private fun statusLabel(status: NearbyMapStatus): String = statusLabel(this, status)
}
