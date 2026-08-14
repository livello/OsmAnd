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

		fun matchesBmsName(name: String?): Boolean {
			if (name.isNullOrBlank()) {
				return false
			}
			val n = name.lowercase(Locale.US)
			return n.contains("xiaoxiang") || n.contains("jbd") || n.startsWith("sp") ||
					n.contains("bms") || n.contains("overkill")
		}

		fun matchesFarDriverName(name: String?): Boolean {
			if (name.isNullOrBlank()) {
				return false
			}
			val n = name.lowercase(Locale.US)
			return n.contains("yuanqu") || n.contains("fardriver") || n.contains("controldm") ||
					n.startsWith("fd") || n.contains("nd96")
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
		@SuppressLint("MissingPermission")
		override fun onScanResult(callbackType: Int, result: ScanResult) {
			val device = result.device ?: return
			val name = device.name ?: result.scanRecord?.deviceName
			val expectedService = if (role == Role.BMS) JBD_SERVICE else FAR_SERVICE
			val hasService = result.scanRecord?.serviceUuids?.any { it.uuid == expectedService } == true
			val match = if (role == Role.BMS) matchesBmsName(name) else matchesFarDriverName(name)
			if (!match && !hasService) {
				return
			}
			val address = device.address ?: return
			if (found.putIfAbsent(address, name ?: address) == null) {
				listener.onDeviceFound(role, name ?: address, address)
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
			var notify: BluetoothGattCharacteristic? = null
			var write: BluetoothGattCharacteristic? = null
			for (service in gatt.services) {
				for (ch in service.characteristics) {
					val props = ch.properties
					val preferNotify = if (role == Role.BMS) {
						ch.uuid == JBD_NOTIFY
					} else {
						ch.uuid == FAR_CHAR
					}
					val preferWrite = if (role == Role.BMS) {
						ch.uuid == JBD_WRITE
					} else {
						ch.uuid == FAR_CHAR
					}
					if (preferNotify && props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
						notify = ch
					} else if (notify == null && props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
						notify = ch
					}
					if (preferWrite && (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
								props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
					) {
						write = ch
					} else if (write == null && (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
								props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
					) {
						write = ch
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
	fun startScan(activity: Activity, timeoutMs: Long = 15000L) {
		if (!AndroidUtils.hasBLEPermission(activity) && !AndroidUtils.requestBLEPermissions(activity)) {
			return
		}
		if (!BLEUtils.isBLEEnabled(activity)) {
			app.showToastMessage(net.osmand.plus.R.string.ant_plus_bluetooth_off)
			return
		}
		stopScan()
		found.clear()
		adapter = BLEUtils.getBluetoothAdapter(activity)
		val scanner = adapter?.bluetoothLeScanner ?: return
		scanning = true
		scanner.startScan(
			null,
			ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
			scanCallback
		)
		mainHandler.postDelayed({ stopScan() }, timeoutMs)
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
