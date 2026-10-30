package net.osmand.plus.plugins.loraosmand

import android.content.Context
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/**
 * Meshtastic 2.8 keeps the radio on BLE and publishes the node database to a
 * loopback TAK server (127.0.0.1:8089, mutual TLS). GeoChat written here is
 * forwarded by that app onto the channel selected in its TAK settings.
 */
internal class LoraTakClient(
	private val context: Context,
	private val events: Events,
) {
	interface Events {
		fun onTakStatus(up: Boolean, detail: String)
		fun onTakNode(node: TakNodeUpdate)
		fun onTakChat(sender: String, text: String)
	}

	private val running = AtomicBoolean(false)
	private val writeLock = Any()
	@Volatile private var output: OutputStream? = null
	private var worker: Thread? = null

	fun start() {
		if (!running.compareAndSet(false, true)) {
			return
		}
		worker = Thread({ loop() }, "lora-tak").also { it.start() }
	}

	fun stop() {
		running.set(false)
		closeSocket()
		worker?.interrupt()
		worker = null
	}

	fun sendGeoChat(text: String): Boolean {
		val stream = output ?: return false
		val stamp = stamp()
		val stale = stamp(System.currentTimeMillis() + 120_000L)
		val id = Integer.toHexString(text.hashCode() xor System.nanoTime().toInt())
		val body = xml(text)
		val event = """
			<event version="2.0" uid="GeoChat.$CLIENT_UID.All Chat Rooms.$id" type="b-t-f" how="h-g-i-g-o" time="$stamp" start="$stamp" stale="$stale">
			<point lat="0.0" lon="0.0" hae="9999999.0" ce="9999999.0" le="9999999.0"/>
			<detail>
			<__chat parent="RootContactGroup" groupOwner="false" messageId="$id" chatroom="All Chat Rooms" id="All Chat Rooms" senderCallsign="OsmAnd">
			<chatgrp uid0="$CLIENT_UID" uid1="All Chat Rooms" id="All Chat Rooms"/>
			</__chat>
			<link uid="$CLIENT_UID" type="a-f-G-U-C" relation="p-p"/>
			<remarks source="BAO.F.ATAK.$CLIENT_UID" to="All Chat Rooms" time="$stamp">$body</remarks>
			</detail>
			</event>
		""".trimIndent().replace("\n", "")
		return write(stream, event)
	}

	private fun loop() {
		while (running.get()) {
			try {
				val socket = connect()
				events.onTakStatus(true, "127.0.0.1:$PORT")
				read(socket.getInputStream())
			} catch (e: Exception) {
				if (running.get()) {
					Log.i(TAG, "tak reconnect: ${e.message}")
					events.onTakStatus(false, e.message ?: "tak")
				}
			} finally {
				closeSocket()
			}
			if (!running.get()) {
				break
			}
			try {
				Thread.sleep(4_000L)
			} catch (_: InterruptedException) {
				break
			}
		}
		events.onTakStatus(false, "")
	}

	private fun connect(): SSLSocket {
		val passwords = PASSWORD.toCharArray()
		val keys = KeyStore.getInstance("PKCS12")
		context.assets.open(CLIENT_ASSET).use { keys.load(it, passwords) }
		val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
			init(keys, passwords)
		}
		val trust = KeyStore.getInstance(KeyStore.getDefaultType())
		trust.load(null, null)
		val factory = CertificateFactory.getInstance("X.509")
		val ca = context.assets.open(CA_ASSET).use { factory.generateCertificate(it) }
		trust.setCertificateEntry("tak-ca", ca)
		val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
			init(trust)
		}
		val ssl = SSLContext.getInstance("TLS")
		ssl.init(keyManagers.keyManagers, trustManagers.trustManagers, null)
		val socket = ssl.socketFactory.createSocket(HOST, PORT) as SSLSocket
		socket.useClientMode = true
		socket.startHandshake()
		synchronized(writeLock) { output = socket.outputStream }
		return socket
	}

	private fun read(input: InputStream) {
		val buffer = ByteArray(4096)
		val pending = StringBuilder()
		while (running.get()) {
			val count = input.read(buffer)
			if (count < 0) {
				break
			}
			pending.append(String(buffer, 0, count, Charsets.UTF_8))
			while (true) {
				val end = pending.indexOf(EVENT_END)
				if (end < 0) {
					break
				}
				val raw = pending.substring(0, end + EVENT_END.length)
				pending.delete(0, end + EVENT_END.length)
				val start = raw.indexOf("<event")
				if (start >= 0) {
					handle(raw.substring(start))
				}
			}
		}
	}

	private fun handle(xml: String) {
		val event = parse(xml) ?: return
		when {
			event.type == "b-t-f" -> {
				val text = event.remarks?.trim().orEmpty()
				if (text.isNotEmpty() && event.sender != "OsmAnd") {
					events.onTakChat(event.sender ?: "mesh", text)
				}
			}
			event.type.startsWith("a-") && event.latitude != null && event.longitude != null -> {
				if (event.latitude == 0.0 && event.longitude == 0.0) {
					return
				}
				val num = nodeNum(event.uid) ?: return
				val (shortName, longName) = splitCallsign(event.callsign, num)
				events.onTakNode(
					TakNodeUpdate(
						num = num,
						shortName = shortName,
						longName = longName,
						latitude = event.latitude,
						longitude = event.longitude,
						altitudeMeters = event.altitudeMeters,
						telemetry = parseRemarks(event.remarks),
					),
				)
			}
		}
	}

	private fun parse(xml: String): CotEvent? {
		return try {
			val parser = Xml.newPullParser()
			parser.setInput(StringReader(xml))
			var type = ""
			var uid = ""
			var lat: Double? = null
			var lon: Double? = null
			var altitude: Int? = null
			var callsign: String? = null
			var sender: String? = null
			var remarks: String? = null
			while (parser.eventType != XmlPullParser.END_DOCUMENT) {
				if (parser.eventType == XmlPullParser.START_TAG) {
					when (parser.name) {
						"event" -> {
							type = parser.getAttributeValue(null, "type") ?: ""
							uid = parser.getAttributeValue(null, "uid") ?: ""
						}
						"point" -> {
							lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
							lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
							altitude = parser.getAttributeValue(null, "hae")?.toDoubleOrNull()
								?.takeIf { it in -500.0..20000.0 }
								?.toInt()
						}
						"contact" -> callsign = parser.getAttributeValue(null, "callsign")
						"__chat" -> sender = parser.getAttributeValue(null, "senderCallsign")
						"remarks" -> remarks = readText(parser)
					}
				}
				parser.next()
			}
			CotEvent(type, uid, lat, lon, altitude, callsign, sender, remarks)
		} catch (e: Exception) {
			Log.i(TAG, "cot parse: ${e.message}")
			null
		}
	}

	private fun readText(parser: XmlPullParser): String {
		return if (parser.next() == XmlPullParser.TEXT) parser.text ?: "" else ""
	}

	private fun write(stream: OutputStream, event: String): Boolean {
		return try {
			synchronized(writeLock) {
				stream.write(event.toByteArray(Charsets.UTF_8))
				stream.flush()
			}
			true
		} catch (e: Exception) {
			Log.i(TAG, "cot write: ${e.message}")
			false
		}
	}

	private fun closeSocket() {
		val stream = output
		output = null
		try {
			stream?.close()
		} catch (_: Exception) {
		}
	}

	private fun nodeNum(uid: String): Int? {
		if (!uid.startsWith(UID_PREFIX)) {
			return null
		}
		return uid.removePrefix(UID_PREFIX).toLongOrNull(16)?.toInt()
	}

	private fun splitCallsign(callsign: String?, num: Int): Pair<String, String> {
		val label = callsign?.trim().orEmpty()
		if (label.isEmpty()) {
			val hex = num.toUInt().toString(16).padStart(8, '0')
			return hex.takeLast(4) to "!$hex"
		}
		val split = label.indexOf(" - ")
		if (split <= 0) {
			return label.take(4) to label
		}
		return label.substring(0, split).take(4) to label.substring(split + 3)
	}

	private fun stamp(timeMs: Long = System.currentTimeMillis()): String {
		val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
		format.timeZone = TimeZone.getTimeZone("UTC")
		return format.format(Date(timeMs))
	}

	private fun xml(text: String): String {
		return text
			.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
	}

	private fun parseRemarks(remarks: String?): TakTelemetry {
		val text = remarks ?: return TakTelemetry()
		return TakTelemetry(
			batteryPercent = match(text, """Battery:\s*(-?\d+)%""")?.toIntOrNull(),
			voltage = match(text, """Voltage:\s*(-?\d+(?:\.\d+)?)V""")?.toFloatOrNull(),
			channelUtil = match(text, """Chan Util:\s*(-?\d+(?:\.\d+)?)%""")?.toFloatOrNull(),
			airUtil = match(text, """Air Util Tx:\s*(-?\d+(?:\.\d+)?)%""")?.toFloatOrNull(),
			rssi = match(text, """RSSI:\s*(-?\d+)\s*dBm""")?.toIntOrNull(),
			snr = match(text, """SNR:\s*(-?\d+(?:\.\d+)?)\s*dB""")?.toFloatOrNull(),
		)
	}

	private fun match(text: String, pattern: String): String? {
		return Regex(pattern).find(text)?.groupValues?.getOrNull(1)
	}

	private data class CotEvent(
		val type: String,
		val uid: String,
		val latitude: Double?,
		val longitude: Double?,
		val altitudeMeters: Int?,
		val callsign: String?,
		val sender: String?,
		val remarks: String?,
	)

	companion object {
		private const val TAG = "LoraTak"
		private const val HOST = "127.0.0.1"
		private const val PORT = 8089
		private const val PASSWORD = "meshtastic"
		private const val CLIENT_ASSET = "lora/client.p12"
		private const val CA_ASSET = "lora/ca.pem"
		private const val CLIENT_UID = "OSMAND-LORA"
		private const val UID_PREFIX = "MESHTASTIC-"
		private const val EVENT_END = "</event>"
	}
}

data class TakTelemetry(
	val batteryPercent: Int? = null,
	val voltage: Float? = null,
	val channelUtil: Float? = null,
	val airUtil: Float? = null,
	val rssi: Int? = null,
	val snr: Float? = null,
)

data class TakNodeUpdate(
	val num: Int,
	val shortName: String,
	val longName: String,
	val latitude: Double,
	val longitude: Double,
	val altitudeMeters: Int?,
	val telemetry: TakTelemetry,
)
