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
 */
class NearbyMapsActivity : AppCompatActivity(), NearbyMapsController.Listener {

	private val app: OsmandApplication
		get() = application as OsmandApplication

	private val plugin: TorrentMapsPlugin
		get() = PluginsHelper.requirePlugin(TorrentMapsPlugin::class.java)

	private val nearby: NearbyMapsController
		get() = plugin.nearby

	private val uiHandler = Handler(Looper.getMainLooper())
	private lateinit var status: TextView
	private lateinit var hint: TextView
	private lateinit var breadcrumb: TextView
	private lateinit var shareSwitch: SwitchCompat
	private lateinit var scanBtn: TextView
	private lateinit var backPeersBtn: TextView
	private lateinit var list: RecyclerView
	private lateinit var adapter: NearbyAdapter

	private var bindingSwitch = false
	private var selectedPeer: NearbyPeer? = null
	private var peerRows: List<NearbyFileRow> = emptyList()
	private var folderPath: String = ""
	private var peersCache: List<NearbyPeer> = emptyList()

	private val progressTick = object : Runnable {
		override fun run() {
			if (isFinishing) return
			refreshStatusLine()
			uiHandler.postDelayed(this, 1000)
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		val night = app.daynightHelper.isNightMode(ThemeUsageContext.APP)
		setTheme(if (night) R.style.OsmandDarkTheme else R.style.OsmandLightTheme)
		super.onCreate(savedInstanceState)
		setContentView(R.layout.torrent_maps_nearby_activity)
		TorrentMapsLog.init(app)

		status = findViewById(R.id.nearby_status)
		hint = findViewById(R.id.nearby_hint)
		breadcrumb = findViewById(R.id.nearby_path_breadcrumb)
		shareSwitch = findViewById(R.id.nearby_share_switch)
		scanBtn = findViewById(R.id.nearby_scan)
		backPeersBtn = findViewById(R.id.nearby_back_peers)
		list = findViewById(R.id.nearby_list)

		findViewById<View>(R.id.nearby_close).setOnClickListener { finish() }
		scanBtn.setOnClickListener {
			nearby.ensureDiscovery()
			app.showToastMessage(R.string.torrent_maps_nearby_scanning)
			refreshPeerList()
		}
		backPeersBtn.setOnClickListener { showPeers() }
		breadcrumb.setOnClickListener {
			if (folderPath.isNotEmpty()) {
				folderPath = TorrentBrowser.parentPath(folderPath)
				refreshCatalogList()
			}
		}

		adapter = NearbyAdapter { row -> onRowClick(row) }
		list.layoutManager = LinearLayoutManager(this)
		list.adapter = adapter

		bindingSwitch = true
		shareSwitch.isChecked = nearby.sharing
		bindingSwitch = false
		shareSwitch.setOnCheckedChangeListener { _, checked ->
			if (bindingSwitch) return@setOnCheckedChangeListener
			confirmToggle(shareSwitch, checked, if (checked) {
				R.string.torrent_maps_nearby_confirm_share_on
			} else {
				R.string.torrent_maps_nearby_confirm_share_off
			}) {
				if (checked) nearby.startSharing() else nearby.stopSharing()
			}
		}

		showPeers()
		refreshStatusLine()
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
		if (!nearby.sharing) {
			nearby.stopDiscoveryOnly()
		}
		super.onPause()
	}

	override fun onPeersChanged(peers: List<NearbyPeer>) {
		peersCache = peers
		if (selectedPeer == null) {
			refreshPeerList()
		}
	}

	override fun onSharingChanged(sharing: Boolean, endpoint: String?) {
		bindingSwitch = true
		shareSwitch.isChecked = sharing
		bindingSwitch = false
		refreshStatusLine()
	}

	private fun showPeers() {
		selectedPeer = null
		peerRows = emptyList()
		folderPath = ""
		breadcrumb.isVisible = false
		backPeersBtn.isVisible = false
		scanBtn.isVisible = true
		refreshPeerList()
		refreshStatusLine()
	}

	private fun refreshPeerList() {
		val rows = peersCache.map { NearbyBrowserRow.Peer(it) }
		adapter.submit(rows)
		if (rows.isEmpty()) {
			status.text = getString(R.string.torrent_maps_nearby_no_peers)
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
				peerRows = catalog.maps.map { entry ->
					NearbyFileRow(entry, nearby.compareStatus(entry))
				}
				breadcrumb.isVisible = true
				backPeersBtn.isVisible = true
				scanBtn.isVisible = false
				refreshCatalogList()
				TorrentMapsLog.append("nearby catalog ${peer.deviceName}: ${catalog.maps.size} maps")
			}
		}
	}

	private fun refreshCatalogList() {
		breadcrumb.text = if (folderPath.isEmpty()) {
			getString(R.string.torrent_maps_path_root)
		} else {
			"/$folderPath"
		}
		adapter.submit(NearbyBrowser.buildRows(peerRows, folderPath))
		refreshStatusLine()
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
		}
	}

	private fun confirmDownload(row: NearbyFileRow) {
		val peer = selectedPeer ?: return
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
			if (nearby.downloader.busy) {
				app.showToastMessage(R.string.torrent_maps_nearby_busy)
				return@confirmAction
			}
			nearby.downloader.download(peer, row.entry, { done, total ->
				uiHandler.post { refreshStatusLine() }
			}) { ok, message ->
				uiHandler.post {
					app.showToastMessage(message)
					if (ok) {
						nearby.refreshLocalIndex()
						peerRows = peerRows.map {
							NearbyFileRow(it.entry, nearby.compareStatus(it.entry), it.progressPercent)
						}
						refreshCatalogList()
					}
					refreshStatusLine()
				}
			}
		}
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
		val progress = if (dl.busy) {
			val pct = if (dl.progressTotal > 0) {
				((dl.progressBytes * 100L) / dl.progressTotal).toInt()
			} else 0
			" · ↓ ${dl.progressPath?.substringAfterLast('/')} $pct%"
		} else ""
		status.text = "$sharePart · $peerPart$progress"
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
		private val onClick: (NearbyBrowserRow) -> Unit
	) : RecyclerView.Adapter<NearbyAdapter.Holder>() {
		private var rows: List<NearbyBrowserRow> = emptyList()

		fun submit(newRows: List<NearbyBrowserRow>) {
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
			private val onClick: (NearbyBrowserRow) -> Unit
		) : RecyclerView.ViewHolder(view) {
			private val icon: ImageView = view.findViewById(R.id.file_icon)
			private val nameFrame: ProgressNameFrame = view.findViewById(R.id.file_name_frame)
			private val name: TextView = view.findViewById(R.id.file_name)
			private val meta: TextView = view.findViewById(R.id.file_meta)
			private val chip: TextView = view.findViewById(R.id.file_state)

			fun bind(row: NearbyBrowserRow) {
				val ctx = itemView.context
				itemView.setOnClickListener { onClick(row) }
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
						nameFrame.setProgressPercent(0)
						chip.text = "📁"
						chip.setOnClickListener { onClick(row) }
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
