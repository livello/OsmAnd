package net.osmand.plus.plugins.externalsensors.devices.sensors.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import net.osmand.PlatformUtil
import net.osmand.plus.R
import net.osmand.plus.plugins.externalsensors.GattAttributes
import net.osmand.plus.plugins.externalsensors.devices.ble.BLEOBDDevice
import net.osmand.plus.plugins.externalsensors.devices.sensors.SensorData
import net.osmand.plus.plugins.externalsensors.devices.sensors.SensorDataField
import net.osmand.plus.plugins.externalsensors.devices.sensors.SensorWidgetDataFieldType
import org.apache.commons.logging.Log
import org.json.JSONException
import org.json.JSONObject
import java.util.Collections
import java.util.UUID

class BLEOBDSensor(device: BLEOBDDevice) : BLEAbstractSensor(device, device.deviceId + "_OBD") {
	private var lastOBDData: OBDData? = null

	class OBDData internal constructor(val timestamp: Long, val response: String) : SensorData {
		override fun getDataFields(): List<SensorDataField> {
			return listOf(
				SensorDataField(
					R.string.response,
					-1,
					response))
		}

		override fun getExtraDataFields(): List<SensorDataField> {
			return listOf(
				SensorDataField(R.string.shared_string_time, -1, timestamp))
		}

		override fun toString(): String {
			return "OBDData {" +
					"timestamp=" + timestamp +
					'}'
		}
	}

	override fun getSupportedWidgetDataFieldTypes(): List<SensorWidgetDataFieldType> {
		return Collections.emptyList()
	}

	override fun getRequestedCharacteristicUUID(): UUID {
		return GattAttributes.UUID_CHARACTERISTIC_OBD_READ
	}

	override fun getName(): String {
		return "OBD"
	}

	override fun getLastSensorDataList(): List<SensorData> {
		val data = lastOBDData
		return if (data == null) emptyList() else listOf<SensorData>(data)
	}

	override fun onCharacteristicRead(
		gatt: BluetoothGatt,
		characteristic: BluetoothGattCharacteristic,
		status: Int) {
		//"characteristic.value" is deprecated but should be used inside onCharacteristicRead callback
		log.debug(
			"OBD onCharacteristicRead " + characteristic.uuid + ". data " + String(
				characteristic.value))
		if (status == BluetoothGatt.GATT_SUCCESS) {
			if (requestedCharacteristicUUID == characteristic.uuid) {
				extrudeOBDData(characteristic.value)
			}
		}
	}

	override fun onCharacteristicChanged(
		gatt: BluetoothGatt,
		characteristic: BluetoothGattCharacteristic) {
		val charaUUID = characteristic.uuid
		//"characteristic.value" is deprecated but should be used inside onCharacteristicChanged callback

		log.debug(
			"OBD onCharacteristicChanged " + characteristic.uuid + ". data " + String(
				characteristic.value))
		if (requestedCharacteristicUUID == charaUUID) {
			extrudeOBDData(characteristic.value)
		}
	}

	private fun extrudeOBDData(data: ByteArray?) {
		lastOBDData = if (data == null) {
			null
		} else {
			OBDData(System.currentTimeMillis(), String(data))
		}
	}

	@Throws(JSONException::class)
	override fun writeSensorDataToJson(
		json: JSONObject,
		widgetDataFieldType: SensorWidgetDataFieldType) {
	}

	companion object {
		private val log: Log = PlatformUtil.getLog("OBD2")
	}
}
