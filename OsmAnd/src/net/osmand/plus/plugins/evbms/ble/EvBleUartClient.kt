package net.osmand.plus.plugins.evbms.ble

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import net.osmand.PlatformUtil
import net.osmand.plus.OsmandApplication
import net.osmand.plus.plugins.evbms.EvDebugJournal
import net.osmand.plus.plugins.externalsensors.GattAttributes
import net.osmand.plus.utils.AndroidUtils
import net.osmand.plus.utils.BLEUtils
import net.osmand.plus.utils.BLEUtils.getAliasName
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

class EvBleUartClient(
	private val app: OsmandApplication,
	val role: Role,
	private val listener: Listener,
	private val journal: EvDebugJournal
) {

	enum class Role {
		BMS, CONTROLLER
	}

	enum class BmsKind {
		UNKNOWN, JBD, ANT
	}

	enum class ControllerKind {
		UNKNOWN, FARDRIVER, VESC
	}

	@Volatile
	var preferredBmsKind: BmsKind = BmsKind.UNKNOWN

	@Volatile
	var detectedBmsKind: BmsKind = BmsKind.UNKNOWN
		private set

	@Volatile
	var preferredControllerKind: ControllerKind = ControllerKind.UNKNOWN

	@Volatile
	var detectedControllerKind: ControllerKind = ControllerKind.UNKNOWN
		private set

	fun noteControllerKind(kind: ControllerKind) {
		if (detectedControllerKind == ControllerKind.UNKNOWN) {
			detectedControllerKind = kind
		}
	}

	interface Listener {
		fun onConnectionChanged(role: Role, connected: Boolean, name: String?)
		fun onBytes(role: Role, data: ByteArray)
		fun onDeviceFound(role: Role, name: String, address: String)
		fun onScanFinished(role: Role)
	}

	companion object {
		private val LOG = PlatformUtil.getLog(EvBleUartClient::class.java)

		val JBD_SERVICE: UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
		val JBD_NOTIFY: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
		val JBD_WRITE: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
		val FAR_SERVICE: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
		val FAR_CHAR: UUID = UUID.fromString("0000ffec-0000-1000-8000-00805f9b34fb")
		val ANT_CHAR: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
		val NUS_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
		val NUS_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
		val NUS_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

		private const val RECONNECT_MIN_MS = 400L
		private const val RECONNECT_MAX_MS = 15_000L
		private const val CONNECT_TIMEOUT_MS = 8_000L
		private const val AUTOCONNECT_TIMEOUT_MS = 25_000L
		private const val RECONNECT_SCAN_MS = 5_000L

		fun matchesAntName(name: String?): Boolean {
			if (name.isNullOrBlank()) {
				return false
			}
			val n = name.lowercase(Locale.US)
			return n.contains("antbms") || n.contains("ant-bms") || n.contains("ant_bms") ||
					n.startsWith("ant-") || (n.contains("ant") && n.contains("bms"))
		}

		fun matchesBmsName(name: String?): Boolean {
			if (name.isNullOrBlank()) {
				return false
			}
			val n = name.lowercase(Locale.US)
			return n.contains("xiaoxiang") || n.contains("jbd") || n.startsWith("sp") ||
					n.contains("bms") || n.contains("overkill") || n.contains("smart bms") ||
					matchesAntName(name)
		}

		fun matchesFarDriverName(name: String?): Boolean {
			if (name.isNullOrBlank()) {
				return false
			}
			val n = name.lowercase(Locale.US)
			return n.contains("yuanqu") || n.contains("fardriver") || n.contains("controldm") ||
					n.startsWith("fd") || n.contains("nd96") || n.contains("nd-") ||
					n.contains("far-") || n.contains("ble-uart")
		}

		fun matchesVescName(name: String?): Boolean {
			if (name.isNullOrBlank()) {
				return false
			}
			val n = name.lowercase(Locale.US)
			return n.contains("vesc") || n.contains("nrf52") || n.contains("nrf51") ||
					n.contains("unity") || n.contains("spintend") || n.contains("makerx") ||
					n.contains("trampa") || n.contains("flipsky") || n.contains("little focer") ||
					n.contains("75_300") || n.contains("60_75") || n.startsWith("vesc")
		}

		fun matchesControllerName(name: String?): Boolean {
			return matchesFarDriverName(name) || matchesVescName(name)
		}
	}

	private val mainHandler = Handler(Looper.getMainLooper())
	private var adapter: BluetoothAdapter? = null
	private var gatt: BluetoothGatt? = null
	private var writeCharacteristic: BluetoothGattCharacteristic? = null
	@Volatile
	var connected: Boolean = false
		private set
	@Volatile
	var deviceName: String? = null
		private set
	@Volatile
	var deviceAddress: String? = null
		private set

	@Volatile
	private var wantConnected = false
	@Volatile
	private var connecting = false
	private var reconnectAttempt = 0
	private var reconnectPosted = false
	private var reconnectScanning = false
	private var usingAutoConnect = false
	private var connectedAtMs = 0L

	private val found = ConcurrentHashMap<String, String>()
	private var scanning = false

	private val reconnectRunnable = Runnable {
		reconnectPosted = false
		connectInternal()
	}

	private val connectTimeoutRunnable = Runnable {
		if (!wantConnected || connected) {
			return@Runnable
		}
		jw("connect timeout auto=$usingAutoConnect attempt=$reconnectAttempt")
		connecting = false
		closeGatt()
		scheduleReconnect()
	}

	private val reconnectScanTimeout = Runnable {
		if (!reconnectScanning) {
			return@Runnable
		}
		jw("reconnect scan timed out, falling back to connectGatt")
		stopReconnectScan()
		if (wantConnected && !connected && !connecting) {
			openGatt(autoConnect = usingAutoConnect)
		}
	}

	private val scanCallback = object : ScanCallback() {
		override fun onScanResult(callbackType: Int, result: ScanResult) {
			handleScanResult(result)
		}

		override fun onBatchScanResults(results: MutableList<ScanResult>) {
			for (result in results) {
				handleScanResult(result)
			}
		}

		override fun onScanFailed(errorCode: Int) {
			je("scan failed $errorCode")
			stopScan()
		}
	}

	private val reconnectScanCallback = object : ScanCallback() {
		override fun onScanResult(callbackType: Int, result: ScanResult) {
			val device = result.device ?: return
			if (!wantConnected || device.address != deviceAddress) {
				return
			}
			jd("found advertising device, connecting")
			stopReconnectScan()
			openGatt(device, autoConnect = false)
		}

		override fun onScanFailed(errorCode: Int) {
			jw("reconnect scan failed $errorCode")
			stopReconnectScan()
			if (wantConnected && !connected) {
				openGatt(autoConnect = false)
			}
		}
	}

	private val gattCallback = object : BluetoothGattCallback() {
		@SuppressLint("MissingPermission")
		override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
			if (gatt != this@EvBleUartClient.gatt) {
				if (newState == BluetoothProfile.STATE_DISCONNECTED) {
					try {
						gatt.close()
					} catch (_: Exception) {
					}
				}
				return
			}
			if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
				connecting = false
				reconnectAttempt = 0
				connected = true
				connectedAtMs = System.currentTimeMillis()
				deviceName = gatt.device?.name
				deviceAddress = gatt.device?.address
				mainHandler.removeCallbacks(connectTimeoutRunnable)
				try {
					gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
				} catch (_: Exception) {
				}
				if (!gatt.discoverServices()) {
					jw("discoverServices failed, retrying connect")
					connecting = false
					connected = false
					closeGatt()
					scheduleReconnect()
					return
				}
				mainHandler.post { listener.onConnectionChanged(role, true, deviceName) }
			} else if (newState == BluetoothProfile.STATE_CONNECTED) {
				jw("connected with status $status, retrying")
				connecting = false
				connected = false
				try {
					gatt.close()
				} catch (_: Exception) {
				}
				if (this@EvBleUartClient.gatt === gatt) {
					this@EvBleUartClient.gatt = null
				}
				scheduleReconnect()
			} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
				val wasConnected = connected
				connecting = false
				connected = false
				connectedAtMs = 0L
				detectedBmsKind = BmsKind.UNKNOWN
				detectedControllerKind = ControllerKind.UNKNOWN
				writeCharacteristic = null
				mainHandler.removeCallbacks(connectTimeoutRunnable)
				try {
					gatt.close()
				} catch (_: Exception) {
				}
				if (this@EvBleUartClient.gatt === gatt) {
					this@EvBleUartClient.gatt = null
				}
				if (wasConnected) {
					mainHandler.post { listener.onConnectionChanged(role, false, deviceName) }
				}
				if (wantConnected) {
					jw("disconnected status=$status, scheduling reconnect")
					scheduleReconnect()
				}
			}
		}

		@SuppressLint("MissingPermission")
		override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
			if (gatt != this@EvBleUartClient.gatt) {
				return
			}
			if (status != BluetoothGatt.GATT_SUCCESS) {
				jw("service discovery status $status")
				connecting = false
				connected = false
				closeGatt()
				scheduleReconnect()
				return
			}
			var jbdNotify: BluetoothGattCharacteristic? = null
			var jbdWrite: BluetoothGattCharacteristic? = null
			var antNotify: BluetoothGattCharacteristic? = null
			var antWrite: BluetoothGattCharacteristic? = null
			var nusNotify: BluetoothGattCharacteristic? = null
			var nusWrite: BluetoothGattCharacteristic? = null
			var farNotify: BluetoothGattCharacteristic? = null
			var fallbackNotify: BluetoothGattCharacteristic? = null
			var fallbackWrite: BluetoothGattCharacteristic? = null
			for (service in gatt.services) {
				for (ch in service.characteristics) {
					val props = ch.properties
					val canNotify = props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
					val canWrite = props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
							props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
					when (ch.uuid) {
						JBD_NOTIFY -> if (canNotify) jbdNotify = ch
						JBD_WRITE -> if (canWrite) jbdWrite = ch
						ANT_CHAR -> {
							if (canNotify) antNotify = ch
							if (canWrite) antWrite = ch
						}
						FAR_CHAR -> {
							if (canNotify) farNotify = ch
							if (canWrite && fallbackWrite == null) fallbackWrite = ch
						}
						NUS_TX -> if (canNotify) nusNotify = ch
						NUS_RX -> if (canWrite) nusWrite = ch
						else -> {
							if (canNotify && fallbackNotify == null) fallbackNotify = ch
							if (canWrite && fallbackWrite == null) fallbackWrite = ch
						}
					}
				}
			}
			val notify: BluetoothGattCharacteristic?
			val write: BluetoothGattCharacteristic?
			if (role == Role.BMS) {
				val preferAnt = preferredBmsKind == BmsKind.ANT ||
						(preferredBmsKind != BmsKind.JBD && jbdNotify == null && antNotify != null)
				if (preferAnt && antNotify != null) {
					notify = antNotify
					write = antWrite ?: antNotify
					detectedBmsKind = BmsKind.ANT
				} else if (jbdNotify != null || jbdWrite != null) {
					notify = jbdNotify ?: fallbackNotify
					write = jbdWrite ?: fallbackWrite
					detectedBmsKind = BmsKind.JBD
				} else if (antNotify != null) {
					notify = antNotify
					write = antWrite ?: antNotify
					detectedBmsKind = BmsKind.ANT
				} else {
					notify = fallbackNotify
					write = fallbackWrite
				}
			} else {
				val preferVesc = preferredControllerKind == ControllerKind.VESC ||
						(preferredControllerKind != ControllerKind.FARDRIVER && nusNotify != null)
				if (preferVesc && (nusNotify != null || nusWrite != null)) {
					notify = nusNotify ?: fallbackNotify
					write = nusWrite ?: fallbackWrite
					detectedControllerKind = ControllerKind.VESC
				} else if (farNotify != null) {
					notify = farNotify
					write = if (farNotify.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
						farNotify.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
					) {
						farNotify
					} else {
						fallbackWrite
					}
					detectedControllerKind = ControllerKind.FARDRIVER
				} else {
					notify = fallbackNotify
					write = fallbackWrite
					detectedControllerKind = when (preferredControllerKind) {
						ControllerKind.VESC -> ControllerKind.VESC
						ControllerKind.FARDRIVER -> ControllerKind.FARDRIVER
						else -> ControllerKind.UNKNOWN
					}
				}
			}
			writeCharacteristic = write
			jd("services notify=${notify?.uuid} write=${write?.uuid} bms=$detectedBmsKind ctrl=$detectedControllerKind")
			if (notify != null) {
				gatt.setCharacteristicNotification(notify, true)
				val cccd = notify.getDescriptor(GattAttributes.UUID_CHARACTERISTIC_CLIENT_CONFIG)
				if (cccd != null) {
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
						gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
					} else {
						cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
						gatt.writeDescriptor(cccd)
					}
				}
			}
		}

		override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
			val value = characteristic.value ?: return
			if (value.isNotEmpty()) {
				jd("RX ${value.size}B ${EvDebugJournal.hex(value)}")
				listener.onBytes(role, value)
			}
		}
	}

	@SuppressLint("MissingPermission")
	private fun handleScanResult(result: ScanResult) {
		val device = result.device ?: return
		val name = device.name ?: result.scanRecord?.deviceName
		offerDevice(name, device.address, result.scanRecord?.serviceUuids?.map { it.uuid })
	}

	private fun offerDevice(name: String?, address: String?, serviceUuids: List<UUID>? = null) {
		if (address.isNullOrBlank()) {
			return
		}
		if (role == Role.CONTROLLER && matchesAntName(name)) {
			return
		}
		if (role == Role.BMS && matchesControllerName(name) && !matchesBmsName(name)) {
			return
		}
		val hasService = if (role == Role.BMS) {
			serviceUuids?.any { it == JBD_SERVICE || it == FAR_SERVICE } == true
		} else {
			serviceUuids?.any { it == FAR_SERVICE || it == NUS_SERVICE } == true
		}
		val match = if (role == Role.BMS) matchesBmsName(name) else matchesControllerName(name)
		if (!match && !hasService && !name.isNullOrBlank()) {
			return
		}
		val label = name?.takeIf { it.isNotBlank() } ?: address
		if (found.putIfAbsent(address, label) == null) {
			listener.onDeviceFound(role, label, address)
		}
	}

	@SuppressLint("MissingPermission")
	fun startScan(activity: Activity, timeoutMs: Long = 15000L) {
		if (!AndroidUtils.hasBLEPermission(activity) && !AndroidUtils.requestBLEPermissions(activity)) {
			return
		}
		if (!BLEUtils.isBLEEnabled(activity)) {
			app.showToastMessage(net.osmand.plus.R.string.ant_plus_bluetooth_off)
			listener.onScanFinished(role)
			return
		}
		cancelReconnect()
		stopReconnectScan()
		stopScanInternal(notify = false)
		found.clear()
		adapter = BLEUtils.getBluetoothAdapter(activity)
		offerBondedDevices()
		val scanner = adapter?.bluetoothLeScanner
		if (scanner == null) {
			listener.onScanFinished(role)
			return
		}
		scanning = true
		scanner.startScan(
			null,
			ScanSettings.Builder()
				.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
				.setReportDelay(0)
				.build(),
			scanCallback
		)
		mainHandler.postDelayed({ stopScan() }, timeoutMs)
	}

	@SuppressLint("MissingPermission")
	private fun offerBondedDevices() {
		val bonded = adapter?.bondedDevices ?: return
		for (device in bonded) {
			offerDevice(device.name, device.address)
		}
	}

	fun stopScan() {
		stopScanInternal(notify = true)
		resumeReconnectIfNeeded()
	}

	@SuppressLint("MissingPermission")
	private fun stopScanInternal(notify: Boolean) {
		if (!scanning) {
			return
		}
		scanning = false
		try {
			bluetoothAdapter()?.bluetoothLeScanner?.stopScan(scanCallback)
		} catch (_: Exception) {
		}
		if (notify) {
			listener.onScanFinished(role)
		}
	}

	@SuppressLint("MissingPermission")
	fun connect(activity: Activity, address: String) {
		if (!AndroidUtils.hasBLEPermission(activity) && !AndroidUtils.requestBLEPermissions(activity)) {
			return
		}
		adapter = BLEUtils.getBluetoothAdapter(activity) ?: bluetoothAdapter()
		deviceName = try {
			adapter?.getRemoteDevice(address)?.getAliasName(activity)
		} catch (_: Exception) {
			null
		}
		beginConnect(address)
	}

	fun ensureConnected(address: String) {
		if (address.isBlank()) {
			return
		}
		if (connected || connecting) {
			return
		}
		if (!AndroidUtils.hasBLEPermission(app)) {
			return
		}
		if (wantConnected && deviceAddress == address) {
			if (!reconnectPosted && !reconnectScanning) {
				scheduleReconnect()
			}
			return
		}
		beginConnect(address)
	}

	fun forceReconnect(reason: String) {
		if (!wantConnected) {
			return
		}
		jw("forceReconnect $reason")
		connecting = false
		connected = false
		connectedAtMs = 0L
		writeCharacteristic = null
		cancelReconnect()
		closeGatt()
		reconnectAttempt = 0
		scheduleReconnect()
	}

	fun isAutoReconnectEnabled(): Boolean = wantConnected

	fun millisSinceConnected(): Long {
		val started = connectedAtMs
		return if (!connected || started == 0L) 0L else System.currentTimeMillis() - started
	}

	private fun beginConnect(address: String) {
		wantConnected = true
		deviceAddress = address
		reconnectAttempt = 0
		connecting = false
		cancelReconnect()
		stopReconnectScan()
		stopScanInternal(notify = false)
		closeGatt()
		connectInternal()
	}

	private fun connectInternal() {
		if (!wantConnected || connected || connecting) {
			return
		}
		val address = deviceAddress
		if (address.isNullOrBlank()) {
			return
		}
		if (!AndroidUtils.hasBLEPermission(app)) {
			scheduleReconnect()
			return
		}
		val bt = bluetoothAdapter()
		if (bt == null || !bt.isEnabled) {
			jw("bluetooth off, retrying later")
			scheduleReconnect()
			return
		}
		val attempt = reconnectAttempt
		reconnectAttempt++
		usingAutoConnect = attempt > 0 && attempt % 3 == 2
		if (attempt == 0 || usingAutoConnect) {
			openGatt(autoConnect = usingAutoConnect)
		} else {
			startReconnectScan()
		}
	}

	@SuppressLint("MissingPermission")
	private fun startReconnectScan() {
		val address = deviceAddress ?: return
		val scanner = bluetoothAdapter()?.bluetoothLeScanner
		if (scanner == null) {
			openGatt(autoConnect = false)
			return
		}
		stopReconnectScan()
		reconnectScanning = true
		connecting = false
		jd("scanning to reconnect $address attempt=$reconnectAttempt")
		try {
			val filter = ScanFilter.Builder().setDeviceAddress(address).build()
			val settings = ScanSettings.Builder()
				.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
				.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
				.setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
				.setReportDelay(0)
				.build()
			scanner.startScan(listOf(filter), settings, reconnectScanCallback)
			mainHandler.postDelayed(reconnectScanTimeout, RECONNECT_SCAN_MS)
		} catch (e: Exception) {
			jw("reconnect scan start failed ${e.message}")
			reconnectScanning = false
			openGatt(autoConnect = false)
		}
	}

	@SuppressLint("MissingPermission")
	private fun stopReconnectScan() {
		mainHandler.removeCallbacks(reconnectScanTimeout)
		if (!reconnectScanning) {
			return
		}
		reconnectScanning = false
		try {
			bluetoothAdapter()?.bluetoothLeScanner?.stopScan(reconnectScanCallback)
		} catch (_: Exception) {
		}
	}

	@SuppressLint("MissingPermission")
	private fun openGatt(autoConnect: Boolean) {
		val address = deviceAddress ?: return
		val device = try {
			bluetoothAdapter()?.getRemoteDevice(address)
		} catch (e: Exception) {
			je("invalid address $address ${e.message}")
			return
		}
		if (device == null) {
			scheduleReconnect()
			return
		}
		openGatt(device, autoConnect)
	}

	@SuppressLint("MissingPermission")
	private fun openGatt(device: BluetoothDevice, autoConnect: Boolean) {
		if (!wantConnected) {
			return
		}
		closeGatt()
		connecting = true
		usingAutoConnect = autoConnect
		deviceAddress = device.address
		jd("connectGatt ${device.address} auto=$autoConnect attempt=$reconnectAttempt")
		gatt = try {
			device.connectGatt(app, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
		} catch (e: Exception) {
			je("connectGatt failed ${e.message}")
			null
		}
		if (gatt == null) {
			connecting = false
			scheduleReconnect()
			return
		}
		mainHandler.removeCallbacks(connectTimeoutRunnable)
		val timeout = if (autoConnect) AUTOCONNECT_TIMEOUT_MS else CONNECT_TIMEOUT_MS
		mainHandler.postDelayed(connectTimeoutRunnable, timeout)
	}

	private fun scheduleReconnect() {
		if (!wantConnected || connected || connecting || reconnectPosted || scanning || reconnectScanning) {
			return
		}
		val shift = min(reconnectAttempt, 5)
		val delay = min(RECONNECT_MAX_MS, RECONNECT_MIN_MS shl shift)
		reconnectPosted = true
		jd("reconnect in ${delay}ms attempt=$reconnectAttempt")
		mainHandler.postDelayed(reconnectRunnable, delay)
	}

	private fun cancelReconnect() {
		reconnectPosted = false
		mainHandler.removeCallbacks(reconnectRunnable)
		mainHandler.removeCallbacks(connectTimeoutRunnable)
		stopReconnectScan()
	}

	private fun resumeReconnectIfNeeded() {
		if (wantConnected && !connected && !connecting && !scanning && !reconnectScanning) {
			scheduleReconnect()
		}
	}

	private fun bluetoothAdapter(): BluetoothAdapter? {
		if (adapter == null) {
			val manager = app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
			adapter = manager?.adapter
		}
		return adapter
	}

	@SuppressLint("MissingPermission")
	private fun closeGatt() {
		val current = gatt
		gatt = null
		writeCharacteristic = null
		try {
			current?.disconnect()
			current?.close()
		} catch (_: Exception) {
		}
	}

	@SuppressLint("MissingPermission")
	fun disconnect() {
		wantConnected = false
		cancelReconnect()
		stopScanInternal(notify = false)
		connected = false
		connectedAtMs = 0L
		connecting = false
		detectedBmsKind = BmsKind.UNKNOWN
		detectedControllerKind = ControllerKind.UNKNOWN
		writeCharacteristic = null
		closeGatt()
	}

	@SuppressLint("MissingPermission")
	fun write(bytes: ByteArray): Boolean {
		val g = gatt ?: run {
			jd("TX skipped, gatt=null ${bytes.size}B")
			return false
		}
		val ch = writeCharacteristic ?: run {
			jd("TX skipped, no write char ${bytes.size}B")
			return false
		}
		val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
					android.bluetooth.BluetoothStatusCodes.SUCCESS
		} else {
			ch.value = bytes
			ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
			g.writeCharacteristic(ch)
		}
		jd("TX ${bytes.size}B ${EvDebugJournal.hex(bytes)} ok=$ok")
		return ok
	}

	private fun jd(msg: String) {
		LOG.debug("$role $msg")
		journal.d("BLE", "$role $msg")
	}

	private fun jw(msg: String) {
		LOG.warn("$role $msg")
		journal.w("BLE", "$role $msg")
	}

	private fun je(msg: String) {
		LOG.error("$role $msg")
		journal.e("BLE", "$role $msg")
	}
}
