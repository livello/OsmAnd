package net.osmand.plus.plugins.evbms

import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import net.osmand.PlatformUtil
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.voice.JsTtsCommandPlayer
import java.util.Locale

class EvVoiceAnnouncer(private val app: OsmandApplication) {

	companion object {
		private val LOG = PlatformUtil.getLog(EvVoiceAnnouncer::class.java)
		const val STOP_HOLD_MS = 5000L
		const val RANGE_REPEAT_MS = 60_000L
		const val RANGE_SHORT_REPEAT_MS = 180_000L
		const val RANGE_HYSTERESIS_KM = 0.5
	}

	data class StopReport(
		val rangeKm: Double,
		val routeLeftKm: Double?,
		val minCellV: Double?,
		val motorTempC: Double?,
		val batteryTempC: Double?,
		val controllerTempC: Double?
	)

	private var tts: TextToSpeech? = null
	private var ready = false
	private var lastSpokenSoc: Int? = null
	private var stoppedSinceMs: Long? = null
	private var lastRangeAnnounceMs = 0L
	private var lastRangeShortMs = 0L
	private var rangeShortActive = false

	fun init() {
		if (tts != null) {
			return
		}
		tts = TextToSpeech(app) { status ->
			ready = status == TextToSpeech.SUCCESS
			if (ready) {
				val locale = app.localeHelper.preferredLocale ?: app.localeHelper.defaultLocale
				tts?.language = locale
				tts?.setAudioAttributes(
					AudioAttributes.Builder()
						.setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
						.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
						.build()
				)
			} else {
				LOG.error("TTS init failed")
			}
		}
	}

	fun shutdown() {
		ready = false
		tts?.stop()
		tts?.shutdown()
		tts = null
		lastSpokenSoc = null
		stoppedSinceMs = null
		rangeShortActive = false
	}

	fun onSoc(soc: Int?, stepPercent: Int, enabled: Boolean) {
		if (!enabled || soc == null || stepPercent <= 0) {
			return
		}
		val last = lastSpokenSoc
		if (last == null || kotlin.math.abs(soc - last) >= stepPercent) {
			lastSpokenSoc = soc
			speak(app.getString(R.string.ev_bms_voice_soc, soc))
		}
	}

	fun onRangeVsRoute(rangeKm: Double?, routeLeftKm: Double?, enabled: Boolean) {
		if (!enabled || rangeKm == null || routeLeftKm == null || routeLeftKm <= 0) {
			rangeShortActive = false
			return
		}
		val now = System.currentTimeMillis()
		if (rangeKm + RANGE_HYSTERESIS_KM < routeLeftKm) {
			val first = !rangeShortActive
			rangeShortActive = true
			if (first || now - lastRangeShortMs >= RANGE_SHORT_REPEAT_MS) {
				lastRangeShortMs = now
				val range = Math.round(rangeKm).toInt().coerceAtLeast(0)
				val route = Math.round(routeLeftKm).toInt().coerceAtLeast(0)
				speak(app.getString(R.string.ev_bms_voice_range_short, range, route))
			}
		} else if (rangeKm >= routeLeftKm) {
			rangeShortActive = false
		}
	}

	fun onMotion(speedKmh: Double?, report: StopReport?, stopSpeedKmh: Double, enabled: Boolean) {
		if (!enabled) {
			return
		}
		val speed = speedKmh ?: return
		val now = System.currentTimeMillis()
		if (speed < stopSpeedKmh) {
			if (stoppedSinceMs == null) {
				stoppedSinceMs = now
			}
			val held = now - (stoppedSinceMs ?: now)
			if (held >= STOP_HOLD_MS && now - lastRangeAnnounceMs >= RANGE_REPEAT_MS && report != null) {
				lastRangeAnnounceMs = now
				speak(buildStopText(report))
			}
		} else {
			stoppedSinceMs = null
		}
	}

	private fun buildStopText(report: StopReport): String {
		val range = Math.round(report.rangeKm).toInt().coerceAtLeast(0)
		val parts = ArrayList<String>()
		parts.add(app.getString(R.string.ev_bms_voice_range, range))
		val routeKm = report.routeLeftKm
		if (routeKm != null) {
			val dest = Math.round(routeKm).toInt().coerceAtLeast(0)
			parts.add(app.getString(R.string.ev_bms_voice_to_charge, dest))
		}
		val vmin = report.minCellV
		if (vmin != null) {
			parts.add(app.getString(R.string.ev_bms_voice_voltage, String.format(Locale.US, "%.2f", vmin)))
		}
		val motor = report.motorTempC
		if (motor != null) {
			parts.add(app.getString(R.string.ev_bms_voice_motor, Math.round(motor).toInt()))
		}
		val batt = report.batteryTempC?.let { Math.round(it).toInt() }
		val ctrl = report.controllerTempC?.let { Math.round(it).toInt() }
		when {
			batt != null && ctrl != null ->
				parts.add(app.getString(R.string.ev_bms_voice_batt_ctrl, batt, ctrl))
			batt != null ->
				parts.add(app.getString(R.string.ev_bms_voice_battery, batt))
			ctrl != null ->
				parts.add(app.getString(R.string.ev_bms_voice_controller, ctrl))
		}
		return parts.joinToString(". ")
	}

	private fun speak(text: String) {
		val player = app.routingHelper.voiceRouter.player
		if (player is JsTtsCommandPlayer && player.speakAdditional(text)) {
			return
		}
		val engine = tts ?: return
		if (!ready) {
			return
		}
		engine.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), "ev-bms-${System.currentTimeMillis()}")
	}
}
