package net.osmand.plus.plugins.loraosmand

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.ToRadio
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

data class FoundRadio(val name: String, val address: String, val rssi: Int)

data class LoraChatLine(
	val timeMs: Long,
	val fromName: String,
	val text: String,
	val outgoing: Boolean,
	val channel: Int,
)

/**
 * Thin BLE GATT link to a Meshtastic radio.
 * Payloads are Wire classes from org.meshtastic:protobufs-jvm.
 * The radio encrypts channel traffic; this phone sends decoded packets.
 */
@SuppressLint("MissingPermission")
internal class LoraSession(
	private val context: Context,
	private val listener: Listener,
) {
	interface Listener {
		fun onStatus(text: String, connected: Boolean)
		fun onNodes(nodes: List<NodeInfo>)
		fun onChannels(channels: List<Channel>)
		fun onChat(line: LoraChatLine)
		fun onScan(devices: List<FoundRadio>)
	}

	private val main = Handler(context.mainLooper)
	private val gattThread = Handler(HandlerThread("lora-gatt").apply { start() }.looper)
	private val adminMutex = Mutex()
	private val ids = AtomicInteger(1)
	private val nodes = LinkedHashMap<Int, NodeInfo>()
	private val channels = TreeMap<Int, Channel>()
	private val found = LinkedHashMap<String, FoundRadio>()
	private val ops = ArrayDeque<GattOp>()

	private var gatt: BluetoothGatt? = null
	private var toRadio: BluetoothGattCharacteristic? = null
	private var fromRadio: BluetoothGattCharacteristic? = null
	private var scanCallback: ScanCallback? = null
	private var busy = false
	@Volatile private var ownNode = 0
	@Volatile private var sessionPasskey = ByteString.EMPTY
	private var originalFixedPosition: Boolean? = null
	private var adminWaiter: CompletableDeferred<AdminMessage>? = null
	private var linkUp = false

	var connectedAddress: String? = null
		private set

	fun isConnected(): Boolean = linkUp

	fun ownNodeNum(): Int? = ownNode.takeIf { it != 0 }

	fun startScan(nameHint: String) {
		stopScan()
		found.clear()
		emit { listener.onScan(emptyList()) }
		val adapter = bluetoothAdapter() ?: run {
			emit { listener.onStatus("Bluetooth off", false) }
			return
		}
		val scanner = adapter.bluetoothLeScanner ?: return
		val callback = object : ScanCallback() {
			override fun onScanResult(callbackType: Int, result: ScanResult) {
				acceptScan(result, nameHint)
			}

			override fun onBatchScanResults(results: MutableList<ScanResult>) {
				results.forEach { acceptScan(it, nameHint) }
			}
		}
		scanCallback = callback
		val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
		scanner.startScan(null, settings, callback)
	}

	fun stopScan() {
		val callback = scanCallback ?: return
		scanCallback = null
		bluetoothAdapter()?.bluetoothLeScanner?.stopScan(callback)
	}

	fun connect(address: String) {
		stopScan()
		gattThread.post {
			closeGatt()
			val device = bluetoothAdapter()?.getRemoteDevice(address) ?: return@post
			connectedAddress = address
			emit { listener.onStatus("Connecting", false) }
			gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
			} else {
				device.connectGatt(context, false, gattCallback)
			}
		}
	}

	fun disconnect() {
		gattThread.post {
			closeGatt()
			emit { listener.onStatus("Disconnected", false) }
		}
	}

	suspend fun sendText(text: String, channelIndex: Int): Result<Unit> {
		if (!linkUp) {
			return Result.failure(IllegalStateException("not connected"))
		}
		val data = Data.Builder().apply {
			portnum = PortNum.TEXT_MESSAGE_APP
			payload = ByteString.of(*text.encodeToByteArray())
		}.build()
		return writePacket(data, channelIndex, BROADCAST)
	}

	suspend fun createChannel(name: String): Result<String> = adminMutex.withLock {
		val trimmed = name.trim()
		if (trimmed.isEmpty() || trimmed.length > 11) {
			return Result.failure(IllegalStateException("channel name"))
		}
		val current = snapshotChannels()
		val slot = (1..7).firstOrNull { index ->
			current.none { it.index == index && it.role != Channel.Role.DISABLED }
		} ?: return Result.failure(IllegalStateException("no free channel"))
		val settings = ChannelSettings.Builder().apply {
			this.name = trimmed
			psk = ByteString.of(0x01)
			uplink_enabled = true
			downlink_enabled = true
		}.build()
		val channel = Channel.Builder().apply {
			index = slot
			role = Channel.Role.SECONDARY
			this.settings = settings
		}.build()
		val response = sendAdmin {
			set_channel = channel
		} ?: return Result.failure(IllegalStateException("no admin response"))
		if (response.set_channel == null && response.session_passkey.size == 0 && response.get_channel_response == null) {
			return Result.failure(IllegalStateException(response.toString()))
		}
		onGatt {
			channels[slot] = channel
			publishChannels()
		}
		Result.success(channelLabel(channel))
	}

	suspend fun sendPhonePosition(lat: Double, lon: Double, altMeters: Int?, timeSec: Int): Result<Unit> {
		if (!linkUp) {
			return Result.failure(IllegalStateException("not connected"))
		}
		val position = Position.Builder().apply {
			latitude_i = (lat * 1e7).toInt()
			longitude_i = (lon * 1e7).toInt()
			if (altMeters != null) {
				altitude = altMeters
			}
			time = timeSec
			location_source = Position.LocSource.LOC_EXTERNAL
		}.build()
		val sent = writePacket(
			Data.Builder().apply {
				portnum = PortNum.POSITION_APP
				payload = ByteString.of(*Position.ADAPTER.encode(position))
			}.build(),
			0,
			BROADCAST,
		)
		if (sent.isFailure) {
			return sent
		}
		adminMutex.withLock {
			sendAdmin { set_fixed_position = position }
		}
		return Result.success(Unit)
	}

	suspend fun applyPositionSchedule(intervalSec: Int, offsetMeters: Int): Result<Unit> = adminMutex.withLock {
		if (!linkUp) {
			return Result.failure(IllegalStateException("not connected"))
		}
		val response = sendAdmin {
			get_config_request = AdminMessage.ConfigType.POSITION_CONFIG
		} ?: return Result.failure(IllegalStateException("no position config"))
		val existing = response.get_config_response?.position
			?: return Result.failure(IllegalStateException("no position config"))
		if (originalFixedPosition == null) {
			originalFixedPosition = existing.fixed_position
		}
		val triggersOn = intervalSec > 0 || offsetMeters > 0
		val updated = existing.newBuilder().apply {
			position_broadcast_secs = if (intervalSec > 0) intervalSec else Int.MAX_VALUE
			position_broadcast_smart_enabled = offsetMeters > 0
			broadcast_smart_minimum_distance = if (offsetMeters > 0) offsetMeters else 0
			if (offsetMeters > 0) {
				broadcast_smart_minimum_interval_secs = 15
			}
			fixed_position = if (triggersOn) true else originalFixedPosition == true
		}.build()
		val config = Config.Builder().apply { position = updated }.build()
		val written = sendAdmin { set_config = config }
			?: return Result.failure(IllegalStateException("position config not written"))
		if (written.set_config == null && written.session_passkey.size == 0) {
			return Result.failure(IllegalStateException(written.toString()))
		}
		Result.success(Unit)
	}

	private fun acceptScan(result: ScanResult, nameHint: String) {
		val record = result.scanRecord
		val name = record?.deviceName?.takeIf { it.isNotBlank() } ?: result.device.name?.takeIf { it.isNotBlank() } ?: ""
		val address = result.device.address ?: return
		val mesh = record?.serviceUuids?.any { it == MESH_PARCEL } == true
		val hinted = nameHint.isNotBlank() && name.contains(nameHint, ignoreCase = true)
		val named = name.contains("meshtastic", ignoreCase = true)
		if (!mesh && !hinted && !named) {
			return
		}
		found[address] = FoundRadio(name.ifBlank { address }, address, result.rssi)
		val snapshot = found.values.sortedByDescending { it.rssi }
		emit { listener.onScan(snapshot) }
	}

	private suspend fun snapshotChannels(): List<Channel> = onGatt { channels.values.toList() }

	private suspend fun writePacket(data: Data, channel: Int, destination: Int): Result<Unit> {
		val packet = MeshPacket.Builder().apply {
			to = destination
			this.channel = channel
			decoded = data
			id = nextId()
			hop_limit = 3
		}.build()
		return writeBytes(ToRadio.ADAPTER.encode(ToRadio.Builder().apply { this.packet = packet }.build()))
	}

	private suspend fun sendAdmin(fill: AdminMessage.Builder.() -> Unit): AdminMessage? {
		val deferred = CompletableDeferred<AdminMessage>()
		val message = AdminMessage.Builder().apply {
			fill()
			if (sessionPasskey.size > 0) {
				session_passkey = sessionPasskey
			}
		}.build()
		val data = Data.Builder().apply {
			portnum = PortNum.ADMIN_APP
			payload = ByteString.of(*AdminMessage.ADAPTER.encode(message))
			want_response = true
		}.build()
		val packet = MeshPacket.Builder().apply {
			to = ownNode
			channel = 0
			decoded = data
			id = nextId()
			hop_limit = 3
		}.build()
		val bytes = ToRadio.ADAPTER.encode(ToRadio.Builder().apply { this.packet = packet }.build())
		val writeDone = CompletableDeferred<Unit>()
		val queued = suspendCoroutine { cont ->
			gattThread.post {
				if (gatt == null || toRadio == null) {
					cont.resume(false)
					return@post
				}
				adminWaiter?.cancel()
				adminWaiter = deferred
				ops.add(GattOp.Write(bytes, writeDone))
				pump()
				cont.resume(true)
			}
		}
		if (!queued) {
			return null
		}
		try {
			if (withTimeoutOrNull(8_000) { writeDone.await() } == null) {
				return null
			}
		} catch (e: Exception) {
			return null
		}
		return withTimeoutOrNull(8_000) { deferred.await() }
	}

	private suspend fun writeBytes(bytes: ByteArray): Result<Unit> {
		val done = CompletableDeferred<Unit>()
		gattThread.post {
			if (gatt == null || toRadio == null) {
				done.completeExceptionally(IllegalStateException("not connected"))
			} else {
				ops.add(GattOp.Write(bytes, done))
				pump()
			}
		}
		return try {
			if (withTimeoutOrNull(8_000) { done.await() } == null) {
				Result.failure(IllegalStateException("write timeout"))
			} else {
				Result.success(Unit)
			}
		} catch (e: Exception) {
			Result.failure(e)
		}
	}

	private fun enqueueWrite(bytes: ByteArray) {
		ops.add(GattOp.Write(bytes, CompletableDeferred()))
		pump()
	}

	private val gattCallback = object : BluetoothGattCallback() {
		override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
			gattThread.post {
				if (gatt != this@LoraSession.gatt) {
					return@post
				}
				if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
					if (!gatt.requestMtu(517)) {
						gatt.discoverServices()
					}
				} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
					closeGatt()
					emit { listener.onStatus("Disconnected", false) }
				}
			}
		}

		override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
			gattThread.post {
				if (gatt == this@LoraSession.gatt) {
					gatt.discoverServices()
				}
			}
		}

		override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
			gattThread.post {
				if (gatt != this@LoraSession.gatt || status != BluetoothGatt.GATT_SUCCESS) {
					emit { listener.onStatus("Services", false) }
					return@post
				}
				val service = gatt.getService(MESH_SERVICE) ?: run {
					emit { listener.onStatus("No mesh service", false) }
					return@post
				}
				toRadio = service.getCharacteristic(TO_RADIO)
				fromRadio = service.getCharacteristic(FROM_RADIO)
				val fromNum = service.getCharacteristic(FROM_NUM)
				if (toRadio == null || fromRadio == null || fromNum == null) {
					emit { listener.onStatus("Missing characteristic", false) }
					return@post
				}
				gatt.setCharacteristicNotification(fromNum, true)
				val cccd = fromNum.getDescriptor(CCCD) ?: return@post
				if (Build.VERSION.SDK_INT >= 33) {
					gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
				} else {
					cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
					gatt.writeDescriptor(cccd)
				}
			}
		}

		override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
			gattThread.post {
				if (status != BluetoothGatt.GATT_SUCCESS) {
					emit { listener.onStatus("Notify failed", false) }
					return@post
				}
				linkUp = true
				emit { listener.onStatus("Connected", true) }
				val want = nextId()
				val hello = ToRadio.Builder().apply { want_config_id = want }.build()
				enqueueWrite(ToRadio.ADAPTER.encode(hello))
				requestDrain()
			}
		}

		override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
			gattThread.post {
				val write = current as? GattOp.Write
				busy = false
				current = null
				if (status == BluetoothGatt.GATT_SUCCESS) {
					write?.done?.complete(Unit)
				} else {
					write?.done?.completeExceptionally(IllegalStateException("write $status"))
				}
				pump()
			}
		}

		override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
			if (Build.VERSION.SDK_INT >= 33) {
				gattThread.post { requestDrain() }
			}
		}

		@Deprecated("Deprecated in Java")
		override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
			if (Build.VERSION.SDK_INT < 33) {
				gattThread.post { requestDrain() }
			}
		}

		override fun onCharacteristicRead(
			gatt: BluetoothGatt,
			characteristic: BluetoothGattCharacteristic,
			value: ByteArray,
			status: Int,
		) {
			if (Build.VERSION.SDK_INT >= 33) {
				gattThread.post { finishRead(status, value) }
			}
		}

		@Deprecated("Deprecated in Java")
		override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
			if (Build.VERSION.SDK_INT < 33) {
				gattThread.post { finishRead(status, characteristic.value ?: ByteArray(0)) }
			}
		}
	}

	private var current: GattOp? = null

	private fun requestDrain() {
		if (current is GattOp.Read || ops.any { it is GattOp.Read }) {
			return
		}
		ops.addFirst(GattOp.Read)
		pump()
	}

	private fun finishRead(status: Int, value: ByteArray) {
		busy = false
		current = null
		if (status == BluetoothGatt.GATT_SUCCESS && value.isNotEmpty()) {
			handleFromRadio(value)
			ops.addFirst(GattOp.Read)
		}
		pump()
	}

	private fun pump() {
		if (busy) {
			return
		}
		val op = ops.removeFirstOrNull() ?: return
		val link = gatt
		val radio = fromRadio
		if (link == null || radio == null) {
			if (op is GattOp.Write) {
				op.done.completeExceptionally(IllegalStateException("not connected"))
			}
			return
		}
		current = op
		busy = true
		val started = when (op) {
			is GattOp.Read -> link.readCharacteristic(radio)
			is GattOp.Write -> writeCharacteristic(link, op.bytes)
		}
		if (!started) {
			busy = false
			current = null
			if (op is GattOp.Write) {
				op.done.completeExceptionally(IllegalStateException("gatt busy"))
			}
			pump()
		}
	}

	private fun writeCharacteristic(link: BluetoothGatt, bytes: ByteArray): Boolean {
		val radio = toRadio ?: return false
		return if (Build.VERSION.SDK_INT >= 33) {
			link.writeCharacteristic(radio, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
				BluetoothStatusCodes.SUCCESS
		} else {
			radio.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
			radio.value = bytes
			link.writeCharacteristic(radio)
		}
	}

	private fun handleFromRadio(value: ByteArray) {
		val from = runCatching { FromRadio.ADAPTER.decode(value) }.getOrNull() ?: return
		from.my_info?.let { ownNode = it.my_node_num }
		from.node_info?.let { node ->
			nodes[node.num] = node
			publishNodes()
		}
		from.channel?.let { channel ->
			channels[channel.index] = channel
			publishChannels()
		}
		from.config?.position?.let {
			if (originalFixedPosition == null) {
				originalFixedPosition = it.fixed_position
			}
		}
		from.packet?.let { handlePacket(it) }
		if (from.rebooted == true) {
			val want = nextId()
			enqueueWrite(ToRadio.ADAPTER.encode(ToRadio.Builder().apply { want_config_id = want }.build()))
		}
	}

	private fun handlePacket(packet: MeshPacket) {
		val data = packet.decoded ?: return
		when (data.portnum) {
			PortNum.TEXT_MESSAGE_APP -> {
				if (ownNode != 0 && packet.from == ownNode) {
					return
				}
				val text = data.payload.utf8()
				if (text.isBlank()) {
					return
				}
				val node = nodes[packet.from]
				val name = node?.shortLabel() ?: ("!" + packet.from.toUInt().toString(16))
				emit {
					listener.onChat(LoraChatLine(System.currentTimeMillis(), name, text, false, packet.channel))
				}
			}
			PortNum.ADMIN_APP -> {
				val message = runCatching { AdminMessage.ADAPTER.decode(data.payload.toByteArray()) }.getOrNull()
					?: return
				if (message.session_passkey.size > 0) {
					sessionPasskey = message.session_passkey
				}
				adminWaiter?.complete(message)
				adminWaiter = null
			}
			PortNum.TELEMETRY_APP -> {
				val telemetry = runCatching { Telemetry.ADAPTER.decode(data.payload.toByteArray()) }.getOrNull()
					?: return
				val metrics = telemetry.device_metrics ?: return
				val node = nodes[packet.from] ?: return
				nodes[packet.from] = node.newBuilder().apply { device_metrics = metrics }.build()
				publishNodes()
			}
			else -> Unit
		}
	}

	private fun publishNodes() {
		val snapshot = nodes.values.toList()
		emit { listener.onNodes(snapshot) }
	}

	private fun publishChannels() {
		val snapshot = channels.values.toList()
		emit { listener.onChannels(snapshot) }
	}

	private fun closeGatt() {
		linkUp = false
		ops.clear()
		busy = false
		current = null
		adminWaiter?.cancel()
		adminWaiter = null
		sessionPasskey = ByteString.EMPTY
		originalFixedPosition = null
		toRadio = null
		fromRadio = null
		gatt?.close()
		gatt = null
		connectedAddress = null
	}

	private fun nextId(): Int = ids.updateAndGet { current -> if (current >= Int.MAX_VALUE) 1 else current + 1 }

	private fun bluetoothAdapter() =
		(context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

	private fun emit(block: () -> Unit) {
		if (android.os.Looper.myLooper() == context.mainLooper) {
			block()
		} else {
			main.post(block)
		}
	}

	private suspend fun <T> onGatt(block: () -> T): T = suspendCoroutine { cont ->
		gattThread.post {
			try {
				cont.resume(block())
			} catch (e: Exception) {
				cont.resumeWithException(e)
			}
		}
	}

	private sealed class GattOp {
		data object Read : GattOp()
		class Write(val bytes: ByteArray, val done: CompletableDeferred<Unit>) : GattOp()
	}

	companion object {
		private val MESH_SERVICE: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
		private val TO_RADIO: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
		private val FROM_RADIO: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
		private val FROM_NUM: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")
		private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
		private val MESH_PARCEL = ParcelUuid(MESH_SERVICE)
		private const val BROADCAST = -1

		fun channelLabel(channel: Channel): String {
			val name = channel.settings?.name?.takeIf { it.isNotBlank() }
			val role = when (channel.role) {
				Channel.Role.PRIMARY -> "Primary"
				Channel.Role.SECONDARY -> "Secondary"
				else -> "Off"
			}
			return if (name != null) "$name (${channel.index})" else "$role (${channel.index})"
		}

		fun usableChannels(channels: List<Channel>): List<Channel> {
			return channels.filter { it.role != Channel.Role.DISABLED }
		}
	}
}
