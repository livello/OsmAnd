package net.osmand.plus.plugins.evbms.ble

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.os.Handler
import android.os.Looper
import net.osmand.PlatformUtil
import net.osmand.plus.OsmandApplication
import net.osmand.plus.plugins.externalsensors.GattAttributes
import net.osmand.plus.utils.AndroidUtils
import net.osmand.plus.utils.BLEUtils
import net.osmand.plus.utils.BLEUtils.getAliasName
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class EvBleUartClient(
	private val app: OsmandApplication,
	private val role: Role,
	private val listener: Listener
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

	private val found = ConcurrentHashMap<String, String>()
	private var scanning = false

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
			LOG.error("BLE scan failed $errorCode")
			stopScan()
		}
	}

	private val gattCallback = object : BluetoothGattCallback() {
		@SuppressLint("MissingPermission")
		override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
			if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
				connected = true
				deviceName = gatt.device?.name
				deviceAddress = gatt.device?.address
				gatt.discoverServices()
				mainHandler.post { listener.onConnectionChanged(role, true, deviceName) }
			} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
				connected = false
				detectedBmsKind = BmsKind.UNKNOWN
				detectedControllerKind = ControllerKind.UNKNOWN
				writeCharacteristic = null
				mainHandler.post { listener.onConnectionChanged(role, false, deviceName) }
				try {
					gatt.close()
				} catch (_: Exception) {
				}
				this@EvBleUartClient.gatt = null
			}
		}

		@SuppressLint("MissingPermission")
		override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
			if (status != BluetoothGatt.GATT_SUCCESS) {
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
		stopScan()
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

	@SuppressLint("MissingPermission")
	fun stopScan() {
		if (!scanning) {
			return
		}
		scanning = false
		try {
			adapter?.bluetoothLeScanner?.stopScan(scanCallback)
		} catch (_: Exception) {
		}
		listener.onScanFinished(role)
	}

	@SuppressLint("MissingPermission")
	fun connect(activity: Activity, address: String) {
		if (!AndroidUtils.hasBLEPermission(activity) && !AndroidUtils.requestBLEPermissions(activity)) {
			return
		}
		disconnect()
		adapter = BLEUtils.getBluetoothAdapter(activity) ?: return
		val device: BluetoothDevice = try {
			adapter!!.getRemoteDevice(address)
		} catch (e: Exception) {
			LOG.error("Invalid BLE address $address", e)
			return
		}
		deviceName = device.getAliasName(activity)
		deviceAddress = address
		gatt = device.connectGatt(app, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
	}

	@SuppressLint("MissingPermission")
	fun disconnect() {
		connected = false
		detectedBmsKind = BmsKind.UNKNOWN
		detectedControllerKind = ControllerKind.UNKNOWN
		writeCharacteristic = null
		try {
			gatt?.disconnect()
			gatt?.close()
		} catch (_: Exception) {
		}
		gatt = null
	}

	@SuppressLint("MissingPermission")
	fun write(bytes: ByteArray): Boolean {
		val g = gatt ?: return false
		val ch = writeCharacteristic ?: return false
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
					android.bluetooth.BluetoothStatusCodes.SUCCESS
		} else {
			ch.value = bytes
			ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
			g.writeCharacteristic(ch)
		}
	}
}
