package net.osmand.plus.plugins.evbms

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import net.osmand.plus.R
import net.osmand.plus.utils.ColorUtilities
import net.osmand.plus.utils.UiUtilities
import net.osmand.plus.widgets.TextViewEx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EvBmsSyncDialog(
	private val activity: Activity,
	private val plugin: EvBmsPlugin,
	private val nightMode: Boolean
) : EvBmsSyncController.Listener {

	private val themed = UiUtilities.getThemedContext(activity, nightMode)
	private val sync = plugin.sync
	private val uiHandler = Handler(Looper.getMainLooper())
	private val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
	private var dialog: AlertDialog? = null
	private lateinit var masterSwitch: SwitchCompat
	private lateinit var portField: EditText
	private lateinit var ipsView: TextView
	private lateinit var masterStatus: TextView
	private lateinit var peersEmpty: TextView
	private lateinit var peersList: LinearLayout
	private lateinit var hostField: EditText
	private lateinit var syncButton: Button
	private lateinit var progress: ProgressBar
	private lateinit var progressText: TextView
	private lateinit var lastResult: TextView
	private var selectedPeerId: String? = null

	private val tick = object : Runnable {
		override fun run() {
			if (dialog?.isShowing != true) {
				return
			}
			refreshMaster()
			refreshPeers()
			uiHandler.postDelayed(this, 1000)
		}
	}

	fun show() {
		val content = LayoutInflater.from(themed).inflate(R.layout.ev_bms_sync_dialog, null)
		masterSwitch = content.findViewById(R.id.sync_master_switch)
		portField = content.findViewById(R.id.sync_port)
		ipsView = content.findViewById(R.id.sync_ips)
		masterStatus = content.findViewById(R.id.sync_master_status)
		peersEmpty = content.findViewById(R.id.sync_peers_empty)
		peersList = content.findViewById(R.id.sync_peers)
		hostField = content.findViewById(R.id.sync_host)
		syncButton = content.findViewById(R.id.sync_now)
		progress = content.findViewById(R.id.sync_progress)
		progressText = content.findViewById(R.id.sync_progress_text)
		lastResult = content.findViewById(R.id.sync_last_result)

		val textColor = ColorUtilities.getPrimaryTextColor(themed, nightMode)
		portField.setTextColor(textColor)
		hostField.setTextColor(textColor)
		portField.setText(plugin.syncPort().toString())
		hostField.setText(plugin.SYNC_HOST.get().orEmpty())

		masterSwitch.setOnCheckedChangeListener { _, checked ->
			toggleServing(checked)
		}
		syncButton.setOnClickListener {
			if (!commitPort()) {
				return@setOnClickListener
			}
			val host = hostField.text?.toString().orEmpty().trim()
			plugin.SYNC_HOST.set(host)
			progress.visibility = View.VISIBLE
			progress.isIndeterminate = true
			sync.syncNow(host, manual = true)
			refreshClient()
		}

		dialog = AlertDialog.Builder(themed)
			.setTitle(R.string.ev_bms_sync)
			.setView(content)
			.setPositiveButton(R.string.shared_string_close, null)
			.setOnDismissListener {
				uiHandler.removeCallbacks(tick)
				sync.removeListener(this)
				commitPort()
				plugin.SYNC_HOST.set(hostField.text?.toString().orEmpty().trim())
			}
			.show()
		sync.addListener(this)
		refreshMaster()
		refreshPeers()
		refreshClient()
		uiHandler.post(tick)
	}

	override fun onMasterChanged() {
		refreshMaster()
	}

	override fun onPeersChanged() {
		refreshPeers()
	}

	override fun onSyncProgress(done: Int, total: Int, name: String) {
		progress.visibility = View.VISIBLE
		progress.isIndeterminate = total <= 0
		if (total > 0) {
			progress.max = total
			progress.progress = done
		}
		progressText.text = if (name.isBlank()) {
			""
		} else {
			themed.getString(R.string.ev_bms_sync_progress, done, total, name)
		}
		refreshClient()
	}

	override fun onSyncFinished(result: EvBmsSyncController.SyncResult) {
		progress.visibility = View.GONE
		progress.isIndeterminate = false
		progressText.text = ""
		refreshClient()
	}

	private fun toggleServing(checked: Boolean) {
		if (checked == sync.serving) {
			return
		}
		if (!commitPort()) {
			masterSwitch.isChecked = false
			return
		}
		if (checked) {
			if (!sync.startMaster()) {
				masterSwitch.isChecked = false
			}
		} else {
			sync.stopMaster()
		}
		refreshMaster()
		refreshPeers()
	}

	private fun refreshMaster() {
		if (dialog?.isShowing != true) {
			return
		}
		val serving = sync.serving
		if (masterSwitch.isChecked != serving) {
			masterSwitch.setOnCheckedChangeListener(null)
			masterSwitch.isChecked = serving
			masterSwitch.setOnCheckedChangeListener { _, checked ->
				toggleServing(checked)
			}
		}
		val ips = sync.localIpv4Addresses()
		ipsView.text = if (ips.isEmpty()) {
			themed.getString(R.string.ev_bms_sync_ip_none)
		} else {
			themed.getString(R.string.ev_bms_sync_ip, ips.joinToString(", "))
		}
		val last = sync.lastServeMs.takeIf { it > 0L }?.let { clock.format(Date(it)) }
			?: themed.getString(R.string.ev_bms_sync_status_never)
		val peers = sync.discoveredPeers().size
		masterStatus.text = if (serving) {
			themed.getString(R.string.ev_bms_sync_status_on, plugin.syncPort()) +
				"\n" + themed.getString(R.string.ev_bms_sync_status_clients, sync.clientCount(), last) +
				"\n" + themed.getString(R.string.ev_bms_sync_status_found, peers)
		} else {
			themed.getString(R.string.ev_bms_sync_status_off)
		}
		portField.isEnabled = !serving
	}

	private fun refreshPeers() {
		if (dialog?.isShowing != true) {
			return
		}
		val peers = sync.discoveredPeers()
		if (selectedPeerId != null && peers.none { it.id == selectedPeerId }) {
			selectedPeerId = null
		}
		peersEmpty.visibility = if (peers.isEmpty()) View.VISIBLE else View.GONE
		peersList.removeAllViews()
		val textColor = ColorUtilities.getPrimaryTextColor(themed, nightMode)
		val secondary = ColorUtilities.getSecondaryTextColor(themed, nightMode)
		for (peer in peers) {
			val selected = peer.id == selectedPeerId
			val row = TextViewEx(themed)
			row.setPadding(0, 12, 0, 12)
			row.setTextColor(if (selected) textColor else secondary)
			row.textSize = 16f
			row.text = themed.getString(
				R.string.ev_bms_sync_peer_item,
				peer.name,
				peer.host,
				peer.port
			)
			row.setOnClickListener {
				selectedPeerId = peer.id
				hostField.setText("${peer.host}:${peer.port}")
				plugin.SYNC_HOST.set("${peer.host}:${peer.port}")
				refreshPeers()
			}
			peersList.addView(row)
		}
	}

	private fun refreshClient() {
		if (dialog?.isShowing != true) {
			return
		}
		syncButton.isEnabled = !sync.isPulling()
		val result = sync.lastResult
		lastResult.text = if (result == null) {
			themed.getString(R.string.ev_bms_sync_result_none)
		} else {
			result.message + " · " + clock.format(Date(result.timeMs))
		}
	}

	private fun commitPort(): Boolean {
		val parsed = portField.text?.toString()?.trim()?.toIntOrNull()
		if (parsed == null || parsed !in EvBmsSyncController.MIN_PORT..EvBmsSyncController.MAX_PORT) {
			plugin.showToast(R.string.ev_bms_sync_port_invalid)
			return false
		}
		if (plugin.SYNC_PORT.get() != parsed) {
			plugin.SYNC_PORT.set(parsed)
			if (sync.serving) {
				sync.stopMaster()
				sync.startMaster()
			}
		}
		return true
	}
}
