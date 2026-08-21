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
		BMS, CONTROLLER, SPEED, CADENCE
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
	var preferredDeviceName: String? = null

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
		fun onDeviceFound(role: Role, name: String, address: String, rssi: Int?, serviceLabel: String)
		fun onScanFinished(role: Role)
		fun onNotifyReady(role: Role) {}
		fun onBoundAddress(role: Role, name: String?, address: String) {}
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
		private const val CTRL_RX_LOG_MS = 1_000L

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

		fun advertisedNamesMatch(saved: String?, advertised: String?): Boolean {
			if (saved.isNullOrBlank() || advertised.isNullOrBlank()) {
				return false
			}
			val a = saved.trim()
			val b = advertised.trim()
			if (a.equals(b, ignoreCase = true)) {
				return true
			}
			val compactA = a.replace(" ", "")
			val compactB = b.replace(" ", "")
			return compactA.equals(compactB, ignoreCase = true)
		}

		fun matchesCadenceSensorName(name: String?): Boolean {
			if (name.isNullOrBlank()) {
				return false
			}
			val n = name.lowercase(Locale.US)
			if (n.contains("bk6ls")) {
				return false
			}
			return n.contains("bk6lc") || n.contains("cadence") || n.contains("crank") ||
					n.contains("bk-cad") || n.startsWith("cad")
		}

		fun matchesSpeedSensorName(name: String?): Boolean {
			if (name.isNullOrBlank()) {
				return false
			}
			val n = name.lowercase(Locale.US)
			if (n.contains("bk6lc") || (matchesCadenceSensorName(name) && !n.contains("speed"))) {
				return false
			}
			return n.contains("cycplus") || n.contains("coospo") || n.contains("bk467") ||
					n.contains("bk6ls") || n.contains("bk-467") || n.contains("magene") || n.contains("gemini") ||
					n.contains("s3+") || n.contains("magene_s3") ||
					n.contains("bike spd") || n.contains("spd cad") || n.startsWith("csc") ||
					(n.contains("speed") && (n.contains("sensor") || n.contains("wheel")))
		}

		fun describeBleServices(name: String?, serviceUuids: List<UUID>?, role: Role): String {
			val ids = serviceUuids ?: emptyList()
			return when {
				ids.contains(GattAttributes.UUID_SERVICE_CYCLING_SPEED_AND_CADENCE) &&
						(role == Role.CADENCE || matchesCadenceSensorName(name)) -> "CSC cad"
				ids.contains(GattAttributes.UUID_SERVICE_CYCLING_SPEED_AND_CADENCE) ||
						role == Role.SPEED || matchesSpeedSensorName(name) -> "CSC spd"
				ids.contains(JBD_SERVICE) || (role == Role.BMS && matchesBmsName(name) && !matchesAntName(name)) -> "JBD"
				matchesAntName(name) -> "ANT BMS"
				ids.contains(NUS_SERVICE) || matchesVescName(name) -> "VESC"
				ids.contains(FAR_SERVICE) || matchesFarDriverName(name) -> "FarDriver"
				else -> "BLE UART"
			}
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
	var rssiDbm: Int? = null
		private set
	@Volatile
	var txPackets: Long = 0
		private set
	@Volatile
	var rxPackets: Long = 0
		private set
	private var lastRxLogMs = 0L
	private var rxLogSkipped = 0

	data class LinkStats(val rssiDbm: Int?, val txPackets: Long, val rxPackets: Long)

	private data class FoundScan(val name: String, var rssi: Int?, var serviceLabel: String)

	fun linkStats(): LinkStats = LinkStats(rssiDbm, txPackets, rxPackets)

	private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
	@Volatile
	var notifyReady: Boolean = false
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

	private val found = ConcurrentHashMap<String, FoundScan>()
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

	private val rssiPollRunnable = object : Runnable {
		@SuppressLint("MissingPermission")
		override fun run() {
			if (!wantConnected || !connected) {
				return
			}
			try {
				gatt?.readRemoteRssi()
			} catch (_: Exception) {
			}
			mainHandler.postDelayed(this, 2_000L)
		}
	}

	private val notifyReadyFallback = Runnable {
		jw("notify ready fallback (CCCD write not confirmed)")
		markNotifyReady()
	}

	private val reconnectScanTimeout = Runnable {
		if (!reconnectScanning) {
			return@Runnable
		}
		stopReconnectScan()
		if (!wantConnected || connected || connecting) {
			return@Runnable
		}
		if (shouldBindByName()) {
			jw("reconnect scan timed out (name=${preferredDeviceName}), retry later")
			scheduleReconnect()
		} else {
			jw("reconnect scan timed out, falling back to connectGatt")
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
			handleReconnectAdvertisement(result)
		}

		override fun onBatchScanResults(results: MutableList<ScanResult>) {
			for (result in results) {
				handleReconnectAdvertisement(result)
			}
		}

		override fun onScanFailed(errorCode: Int) {
			jw("reconnect scan failed $errorCode")
			stopReconnectScan()
			if (!wantConnected || connected) {
				return
			}
			if (shouldBindByName()) {
				scheduleReconnect()
			} else {
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
				mainHandler.removeCallbacks(rssiPollRunnable)
				mainHandler.post(rssiPollRunnable)
				deviceName = gatt.device?.name ?: preferredDeviceName
				deviceAddress = gatt.device?.address
				val boundName = deviceName
				val boundAddress = deviceAddress
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
				mainHandler.post {
					if (!boundAddress.isNullOrBlank()) {
						listener.onBoundAddress(role, boundName, boundAddress)
					}
					listener.onConnectionChanged(role, true, boundName)
				}
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
				notifyReady = false
				mainHandler.removeCallbacks(notifyReadyFallback)
				mainHandler.removeCallbacks(rssiPollRunnable)
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
			if (role == Role.SPEED || role == Role.CADENCE) {
				enableCscNotifications(gatt)
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
			writeType = writeTypeOf(write)
			notifyReady = false
			jd("services notify=${notify?.uuid} write=${write?.uuid} writeType=$writeType bms=$detectedBmsKind ctrl=$detectedControllerKind")
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
					mainHandler.removeCallbacks(notifyReadyFallback)
					mainHandler.postDelayed(notifyReadyFallback, 2_000L)
				} else {
					markNotifyReady()
				}
			} else {
				markNotifyReady()
			}
		}

		override fun onDescriptorWrite(
			gatt: BluetoothGatt,
			descriptor: BluetoothGattDescriptor,
			status: Int
		) {
			if (gatt != this@EvBleUartClient.gatt) {
				return
			}
			if (status == BluetoothGatt.GATT_SUCCESS) {
				markNotifyReady()
			} else {
				jw("CCCD write status $status")
				mainHandler.removeCallbacks(notifyReadyFallback)
				mainHandler.postDelayed(notifyReadyFallback, 200L)
			}
		}

		override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
			val value = characteristic.value ?: return
			deliverRx(value)
		}

		override fun onCharacteristicChanged(
			gatt: BluetoothGatt,
			characteristic: BluetoothGattCharacteristic,
			value: ByteArray
		) {
			deliverRx(value)
		}

		private fun deliverRx(value: ByteArray) {
			if (value.isEmpty()) {
				return
			}
			rxPackets++
			logRx(value)
			listener.onBytes(role, value)
		}

		private fun logRx(value: ByteArray) {
			if (role == Role.BMS) {
				jd("RX ${value.size}B ${EvDebugJournal.hex(value)}")
				return
			}
			val now = System.currentTimeMillis()
			if (lastRxLogMs != 0L && now - lastRxLogMs < CTRL_RX_LOG_MS) {
				rxLogSkipped++
				return
			}
			val extra = if (rxLogSkipped > 0) " (+$rxLogSkipped skipped)" else ""
			jd("RX ${value.size}B ${EvDebugJournal.hex(value)}$extra")
			lastRxLogMs = now
			rxLogSkipped = 0
		}

		override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
			if (gatt != this@EvBleUartClient.gatt || status != BluetoothGatt.GATT_SUCCESS) {
				return
			}
			rssiDbm = rssi
		}
	}

	@SuppressLint("MissingPermission")
	private fun handleScanResult(result: ScanResult) {
		val device = result.device ?: return
		val name = device.name ?: result.scanRecord?.deviceName
		offerDevice(name, device.address, result.scanRecord?.serviceUuids?.map { it.uuid }, result.rssi)
	}

	private fun offerDevice(name: String?, address: String?, serviceUuids: List<UUID>? = null, rssi: Int? = null) {
		if (address.isNullOrBlank()) {
			return
		}
		if (role == Role.CONTROLLER && matchesAntName(name)) {
			return
		}
		if (role == Role.BMS && matchesControllerName(name) && !matchesBmsName(name)) {
			return
		}
		if (role == Role.SPEED && (
				matchesCadenceSensorName(name) && !matchesSpeedSensorName(name) ||
					(matchesBmsName(name) || matchesControllerName(name)) && !matchesSpeedSensorName(name)
			)
		) {
			return
		}
		if (role == Role.CADENCE && (
				matchesSpeedSensorName(name) && !matchesCadenceSensorName(name) ||
					(matchesBmsName(name) || matchesControllerName(name)) && !matchesCadenceSensorName(name)
			)
		) {
			return
		}
		val hasService = when (role) {
			Role.BMS -> serviceUuids?.any { it == JBD_SERVICE || it == FAR_SERVICE } == true
			Role.CONTROLLER -> serviceUuids?.any { it == FAR_SERVICE || it == NUS_SERVICE } == true
			Role.SPEED, Role.CADENCE -> serviceUuids?.any {
				it == GattAttributes.UUID_SERVICE_CYCLING_SPEED_AND_CADENCE
			} == true
		}
		val match = when (role) {
			Role.BMS -> matchesBmsName(name)
			Role.CONTROLLER -> matchesControllerName(name)
			Role.SPEED -> matchesSpeedSensorName(name)
			Role.CADENCE -> matchesCadenceSensorName(name)
		}
		if (!match && !hasService && !name.isNullOrBlank()) {
			return
		}
		val label = name?.takeIf { it.isNotBlank() } ?: address
		val serviceLabel = describeBleServices(name, serviceUuids, role)
		val prev = found[address]
		if (prev == null) {
			found[address] = FoundScan(label, rssi, serviceLabel)
			listener.onDeviceFound(role, label, address, rssi, serviceLabel)
			return
		}
		var changed = false
		if (rssi != null && (prev.rssi == null || rssi > prev.rssi!!)) {
			prev.rssi = rssi
			changed = true
		}
		if (serviceLabel != "BLE UART" && prev.serviceLabel == "BLE UART") {
			prev.serviceLabel = serviceLabel
			changed = true
		}
		if (changed) {
			listener.onDeviceFound(role, prev.name, address, prev.rssi, prev.serviceLabel)
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
	fun connect(activity: Activity, address: String, name: String? = null) {
		if (!AndroidUtils.hasBLEPermission(activity) && !AndroidUtils.requestBLEPermissions(activity)) {
			return
		}
		adapter = BLEUtils.getBluetoothAdapter(activity) ?: bluetoothAdapter()
		if (!name.isNullOrBlank()) {
			preferredDeviceName = name
			deviceName = name
		} else {
			deviceName = try {
				adapter?.getRemoteDevice(address)?.getAliasName(activity)
			} catch (_: Exception) {
				null
			}
		}
		beginConnect(address)
	}

	fun ensureConnected(address: String, name: String? = null) {
		if (!name.isNullOrBlank()) {
			preferredDeviceName = name
		}
		if (address.isBlank() && preferredDeviceName.isNullOrBlank()) {
			return
		}
		if (connected || connecting) {
			return
		}
		if (!AndroidUtils.hasBLEPermission(app)) {
			return
		}
		val sameTarget = wantConnected &&
			((address.isNotBlank() && deviceAddress == address) || shouldBindByName())
		if (sameTarget) {
			if (!reconnectPosted && !reconnectScanning) {
				scheduleReconnect()
			}
			return
		}
		if (!name.isNullOrBlank()) {
			deviceName = name
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
		notifyReady = false
		mainHandler.removeCallbacks(notifyReadyFallback)
		mainHandler.removeCallbacks(rssiPollRunnable)
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
		if (address.isNotBlank()) {
			deviceAddress = address
		}
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
		val byName = shouldBindByName()
		val address = deviceAddress
		if (address.isNullOrBlank() && !byName) {
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
		usingAutoConnect = !byName && attempt > 0 && attempt % 3 == 2
		if (byName) {
			startReconnectScan()
		} else if (attempt == 0 || usingAutoConnect) {
			openGatt(autoConnect = usingAutoConnect)
		} else {
			startReconnectScan()
		}
	}

	private fun shouldBindByName(): Boolean {
		if (role == Role.SPEED || role == Role.CADENCE) {
			return !preferredDeviceName.isNullOrBlank()
		}
		if (role != Role.CONTROLLER || preferredDeviceName.isNullOrBlank()) {
			return false
		}
		return preferredControllerKind == ControllerKind.FARDRIVER ||
			detectedControllerKind == ControllerKind.FARDRIVER ||
			matchesFarDriverName(preferredDeviceName)
	}

	private fun advertisementMatches(address: String?, advertisedName: String?): Boolean {
		if (role == Role.SPEED || role == Role.CADENCE) {
			if (shouldBindByName()) {
				return advertisedNamesMatch(preferredDeviceName, advertisedName)
			}
			return !address.isNullOrBlank() && !deviceAddress.isNullOrBlank() &&
					address.equals(deviceAddress, ignoreCase = true)
		}
		if (!address.isNullOrBlank() && !deviceAddress.isNullOrBlank() &&
			address.equals(deviceAddress, ignoreCase = true)
		) {
			return true
		}
		return shouldBindByName() && advertisedNamesMatch(preferredDeviceName, advertisedName)
	}

	@SuppressLint("MissingPermission")
	private fun handleReconnectAdvertisement(result: ScanResult) {
		val device = result.device ?: return
		if (!wantConnected || connected) {
			return
		}
		val advertised = result.scanRecord?.deviceName ?: device.name
		if (!advertisementMatches(device.address, advertised)) {
			return
		}
		jd("found advertising device name=$advertised addr=${device.address} rssi=${result.rssi}")
		rssiDbm = result.rssi
		if (!advertised.isNullOrBlank()) {
			deviceName = advertised
		}
		stopReconnectScan()
		openGatt(device, autoConnect = false)
	}

	@SuppressLint("MissingPermission")
	private fun startReconnectScan() {
		val scanner = bluetoothAdapter()?.bluetoothLeScanner
		if (scanner == null) {
			if (shouldBindByName()) {
				scheduleReconnect()
			} else {
				openGatt(autoConnect = false)
			}
			return
		}
		stopReconnectScan()
		reconnectScanning = true
		connecting = false
		val byName = shouldBindByName()
		jd(
			if (byName) {
				"scanning to reconnect name=${preferredDeviceName} attempt=$reconnectAttempt"
			} else {
				"scanning to reconnect $deviceAddress attempt=$reconnectAttempt"
			}
		)
		try {
			val filters = if (byName) {
				emptyList()
			} else {
				listOf(ScanFilter.Builder().setDeviceAddress(deviceAddress).build())
			}
			val settings = ScanSettings.Builder()
				.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
				.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
				.setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
				.setReportDelay(0)
				.build()
			scanner.startScan(filters, settings, reconnectScanCallback)
			mainHandler.postDelayed(reconnectScanTimeout, RECONNECT_SCAN_MS)
		} catch (e: Exception) {
			jw("reconnect scan start failed ${e.message}")
			reconnectScanning = false
			if (byName) {
				scheduleReconnect()
			} else {
				openGatt(autoConnect = false)
			}
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
		notifyReady = false
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
		notifyReady = false
		mainHandler.removeCallbacks(notifyReadyFallback)
		mainHandler.removeCallbacks(rssiPollRunnable)
		connecting = false
		detectedBmsKind = BmsKind.UNKNOWN
		detectedControllerKind = ControllerKind.UNKNOWN
		writeCharacteristic = null
		closeGatt()
	}

	@SuppressLint("MissingPermission")
	fun write(bytes: ByteArray): Boolean {
		if (role == Role.SPEED || role == Role.CADENCE) {
			return false
		}
		val g = gatt ?: run {
			jd("TX skipped, gatt=null ${bytes.size}B")
			return false
		}
		val ch = writeCharacteristic ?: run {
			jd("TX skipped, no write char ${bytes.size}B")
			return false
		}
		val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			val status = g.writeCharacteristic(ch, bytes, writeType)
			if (status != android.bluetooth.BluetoothStatusCodes.SUCCESS) {
				jd("TX fail status=$status type=$writeType ${bytes.size}B ${EvDebugJournal.hex(bytes)}")
				return false
			}
			true
		} else {
			ch.value = bytes
			ch.writeType = writeType
			g.writeCharacteristic(ch)
		}
		if (ok) {
			txPackets++
		}
		jd("TX ${bytes.size}B ${EvDebugJournal.hex(bytes)} ok=$ok")
		return ok
	}

	private fun writeTypeOf(ch: BluetoothGattCharacteristic?): Int {
		if (ch == null) {
			return BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
		}
		val props = ch.properties
		return if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 &&
			props and BluetoothGattCharacteristic.PROPERTY_WRITE == 0
		) {
			BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
		} else {
			BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
		}
	}

	@SuppressLint("MissingPermission")
	private fun enableCscNotifications(gatt: BluetoothGatt) {
		writeCharacteristic = null
		val service = gatt.getService(GattAttributes.UUID_SERVICE_CYCLING_SPEED_AND_CADENCE)
		val notify = service?.getCharacteristic(
			GattAttributes.UUID_CHARACTERISTIC_CYCLING_SPEED_AND_CADENCE_MEASUREMENT
		)
		jd("CSC service=${service != null} measure=${notify?.uuid}")
		if (notify == null) {
			jw("CSC measurement characteristic missing")
			markNotifyReady()
			return
		}
		enableNotify(gatt, notify)
	}

	@SuppressLint("MissingPermission")
	private fun enableNotify(gatt: BluetoothGatt, notify: BluetoothGattCharacteristic) {
		notifyReady = false
		gatt.setCharacteristicNotification(notify, true)
		val cccd = notify.getDescriptor(GattAttributes.UUID_CHARACTERISTIC_CLIENT_CONFIG)
		if (cccd != null) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
			} else {
				cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
				gatt.writeDescriptor(cccd)
			}
			mainHandler.removeCallbacks(notifyReadyFallback)
			mainHandler.postDelayed(notifyReadyFallback, 2_000L)
		} else {
			markNotifyReady()
		}
	}

	private fun markNotifyReady() {
		if (notifyReady) {
			return
		}
		notifyReady = true
		mainHandler.removeCallbacks(notifyReadyFallback)
		jd("notify ready")
		mainHandler.post { listener.onNotifyReady(role) }
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
