package net.osmand.plus.plugins.evbms

import android.net.Uri
import net.osmand.PlatformUtil
import net.osmand.plus.OsmandApplication
import net.osmand.plus.settings.backend.preferences.CommonPreference
import net.osmand.plus.settings.backend.preferences.OsmandPreference
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class EvBmsProfileStore(
	private val app: OsmandApplication,
	private val plugin: EvBmsPlugin,
	private val storePref: CommonPreference<String>,
	private val activePref: CommonPreference<String>
) {

	companion object {
		private val LOG = PlatformUtil.getLog(EvBmsProfileStore::class.java)
		const val TYPE = "ev_bms_profile"
		const val VERSION = 1
		const val DEFAULT_NAME = "Default"
		const val NEW_PROFILE_VALUE = "__new__"
		private val RUNTIME_IDS = setOf(
			"ev_bms_charge_history",
			"ev_bms_trip_history",
			"ev_bms_charge_session",
			"ev_bms_trip_session",
			"ev_bms_charge_rearm",
			"ev_bms_charge_stop_pending",
			"ev_bms_charge_cycle_active",
			"ev_bms_charge_end_track_m",
			"ev_bms_telemetry_session_state",
			"ev_bms_telemetry_session_csv",
			"ev_bms_telemetry_session_gpx",
			"ev_bms_telemetry_session_fields",
			"ev_bms_hike_snapshot",
			"ev_bms_hud_demo",
			"ev_bms_ctrl_trip_start_km",
			"ev_bms_speed_sensor_odo_km",
			"ev_bms_speed_sensor_trip_km",
			"ev_bms_settings_profiles",
			"ev_bms_settings_profile",
			"ev_bms_torrent_downloaded",
			"ev_bms_torrent_uploaded"
		)
	}

	data class ProfileFile(
		val name: String,
		val settings: JSONObject
	)

	fun ensureDefault() {
		val names = profileNames()
		if (names.isEmpty()) {
			saveProfile(DEFAULT_NAME, snapshotSettings())
			activePref.set(DEFAULT_NAME)
		} else if (activeName().isBlank() || activeName() !in names) {
			activePref.set(names.first())
		}
	}

	fun profileNames(): List<String> {
		val json = storeJson()
		val arr = json.optJSONArray("profiles") ?: return emptyList()
		return (0 until arr.length()).mapNotNull { i ->
			arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
		}
	}

	fun activeName(): String = activePref.get().orEmpty()

	fun captureActive() {
		ensureDefault()
		val name = activeName().ifBlank { DEFAULT_NAME }
		saveProfile(name, snapshotSettings())
		if (activeName() != name) {
			activePref.set(name)
		}
	}

	fun select(name: String): Boolean {
		if (name.isBlank() || name == NEW_PROFILE_VALUE) {
			return false
		}
		val settings = profileSettings(name) ?: return false
		captureActive()
		applySettings(settings)
		activePref.set(name)
		return true
	}

	fun create(name: String): Boolean {
		val clean = sanitizeName(name) ?: return false
		if (clean in profileNames()) {
			return false
		}
		captureActive()
		saveProfile(clean, snapshotSettings())
		activePref.set(clean)
		return true
	}

	fun rename(newName: String): Boolean {
		val clean = sanitizeName(newName) ?: return false
		val old = activeName()
		if (old.isBlank() || old == clean) {
			activePref.set(clean)
			return true
		}
		if (clean in profileNames()) {
			return false
		}
		val json = storeJson()
		val arr = json.optJSONArray("profiles") ?: JSONArray()
		for (i in 0 until arr.length()) {
			val obj = arr.optJSONObject(i) ?: continue
			if (obj.optString("name") == old) {
				obj.put("name", clean)
				obj.put("settings", snapshotSettings())
				persist(json)
				activePref.set(clean)
				return true
			}
		}
		saveProfile(clean, snapshotSettings())
		activePref.set(clean)
		return true
	}

	fun exportJson(): String {
		captureActive()
		val name = activeName().ifBlank { DEFAULT_NAME }
		val payload = JSONObject()
		payload.put("type", TYPE)
		payload.put("version", VERSION)
		payload.put("name", name)
		payload.put("settings", snapshotSettings())
		return payload.toString(2)
	}

	fun exportToUri(uri: Uri): Boolean {
		return try {
			app.contentResolver.openOutputStream(uri)?.use { out ->
				out.write(exportJson().toByteArray(StandardCharsets.UTF_8))
				out.flush()
			} ?: return false
			true
		} catch (e: Exception) {
			LOG.error("Cannot export profile", e)
			false
		}
	}

	fun importFromUri(uri: Uri): String? {
		val parsed = try {
			app.contentResolver.openInputStream(uri)?.use { input ->
				val text = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).readText()
				parseProfileFile(text)
			}
		} catch (e: Exception) {
			LOG.error("Cannot import profile", e)
			null
		} ?: return null
		var name = sanitizeName(parsed.name) ?: DEFAULT_NAME
		val existing = profileNames()
		if (name in existing) {
			var i = 2
			while ("$name $i" in existing) {
				i++
			}
			name = "$name $i"
		}
		captureActive()
		saveProfile(name, parsed.settings)
		applySettings(parsed.settings)
		activePref.set(name)
		return name
	}

	fun parseProfileFile(raw: String): ProfileFile? {
		return try {
			val json = JSONObject(raw)
			if (json.optString("type") != TYPE) {
				return null
			}
			val settings = json.optJSONObject("settings") ?: return null
			val name = json.optString("name").ifBlank { DEFAULT_NAME }
			ProfileFile(name, settings)
		} catch (_: Exception) {
			null
		}
	}

	fun exportFileName(): String {
		val name = sanitizeName(activeName()) ?: DEFAULT_NAME
		return "ev-telemetry-$name.json"
	}

	private fun snapshotSettings(): JSONObject {
		val json = JSONObject()
		for (pref in settingPrefs()) {
			try {
				val value = pref.asString()
				if (value != null) {
					json.put(pref.id, value)
				}
			} catch (_: Exception) {
			}
		}
		return json
	}

	@Suppress("UNCHECKED_CAST")
	private fun applySettings(settings: JSONObject) {
		for (pref in settingPrefs()) {
			if (!settings.has(pref.id) || settings.isNull(pref.id)) {
				continue
			}
			try {
				val raw = settings.get(pref.id).toString()
				val typed = pref as OsmandPreference<Any?>
				typed.set(typed.parseString(raw))
			} catch (e: Exception) {
				LOG.error("Cannot apply ${pref.id}", e)
			}
		}
	}

	private fun settingPrefs(): List<OsmandPreference<*>> {
		return plugin.getPreferences().filter { it.id !in RUNTIME_IDS }
	}

	private fun profileSettings(name: String): JSONObject? {
		val arr = storeJson().optJSONArray("profiles") ?: return null
		for (i in 0 until arr.length()) {
			val obj = arr.optJSONObject(i) ?: continue
			if (obj.optString("name") == name) {
				return obj.optJSONObject("settings")
			}
		}
		return null
	}

	private fun saveProfile(name: String, settings: JSONObject) {
		val json = storeJson()
		val arr = json.optJSONArray("profiles") ?: JSONArray().also { json.put("profiles", it) }
		var found = false
		for (i in 0 until arr.length()) {
			val obj = arr.optJSONObject(i) ?: continue
			if (obj.optString("name") == name) {
				obj.put("settings", settings)
				found = true
				break
			}
		}
		if (!found) {
			arr.put(JSONObject().put("name", name).put("settings", settings))
		}
		persist(json)
	}

	private fun storeJson(): JSONObject {
		val raw = storePref.get()
		if (raw.isNullOrBlank()) {
			return JSONObject().put("profiles", JSONArray())
		}
		return try {
			JSONObject(raw)
		} catch (_: Exception) {
			JSONObject().put("profiles", JSONArray())
		}
	}

	private fun persist(json: JSONObject) {
		storePref.set(json.toString())
	}

	fun sanitizeName(raw: String?): String? {
		val clean = raw?.trim()?.replace(Regex("[\\r\\n]+"), " ") ?: return null
		if (clean.isBlank() || clean == NEW_PROFILE_VALUE) {
			return null
		}
		return clean.take(40)
	}
}
