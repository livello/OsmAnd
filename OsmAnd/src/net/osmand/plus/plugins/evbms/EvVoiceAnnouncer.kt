package net.osmand.plus.plugins.evbms

import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import net.osmand.PlatformUtil
import net.osmand.plus.OsmandApplication
import net.osmand.plus.voice.JsTtsCommandPlayer

class EvVoiceAnnouncer(private val app: OsmandApplication) {

	companion object {
		private val LOG = PlatformUtil.getLog(EvVoiceAnnouncer::class.java)
		const val STOP_HOLD_MS = 5000L
		const val RANGE_REPEAT_MS = 60_000L
	}

	private var tts: TextToSpeech? = null
	private var ready = false
	private var lastSpokenSoc: Int? = null
	private var stoppedSinceMs: Long? = null
	private var lastRangeAnnounceMs = 0L

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
	}

	fun onSoc(soc: Int?, stepPercent: Int, enabled: Boolean) {
		if (!enabled || soc == null || stepPercent <= 0) {
			return
		}
		val last = lastSpokenSoc
		if (last == null || kotlin.math.abs(soc - last) >= stepPercent) {
			lastSpokenSoc = soc
			speak(app.getString(net.osmand.plus.R.string.ev_bms_voice_soc, soc))
		}
	}

	fun onMotion(
		gpsSpeedKmh: Double?,
		rangeKm: Double?,
		routeLeftKm: Double?,
		stopSpeedKmh: Double,
		enabled: Boolean
	) {
		if (!enabled) {
			return
		}
		val speed = gpsSpeedKmh ?: return
		val now = System.currentTimeMillis()
		if (speed < stopSpeedKmh) {
			if (stoppedSinceMs == null) {
				stoppedSinceMs = now
			}
			val held = now - (stoppedSinceMs ?: now)
			if (held >= STOP_HOLD_MS && now - lastRangeAnnounceMs >= RANGE_REPEAT_MS && rangeKm != null) {
				lastRangeAnnounceMs = now
				val rangeRounded = Math.round(rangeKm).toInt().coerceAtLeast(0)
				val text = if (routeLeftKm != null) {
					val destRounded = Math.round(routeLeftKm).toInt().coerceAtLeast(0)
					app.getString(
						net.osmand.plus.R.string.ev_bms_voice_range_to_charge,
						rangeRounded,
						destRounded
					)
				} else {
					app.getString(net.osmand.plus.R.string.ev_bms_voice_range, rangeRounded)
				}
				speak(text)
			}
		} else {
			stoppedSinceMs = null
		}
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
