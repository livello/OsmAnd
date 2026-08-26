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
		const val LINK_LOSS_HOLD_MS = 4000L
	}

	data class StopReport(
		val rangeKm: Double,
		val routeLeftKm: Double?,
		val rangeReserveKm: Double?,
		val minCellV: Double?,
		val motorTempC: Double?,
		val batteryTempC: Double?,
		val controllerTempC: Double?
	)

	private var tts: TextToSpeech? = null
	private var ready = false
	private var lastSpokenVoltageV: Double? = null
	private var stoppedSinceMs: Long? = null
	private var stopAnnounceCount = 0
	private var lastRangeAnnounceMs = 0L
	private var lastRangeShortMs = 0L
	private var rangeShortActive = false
	private var lastReserveAlertMs = 0L
	private var lastReserveAlertLevel = 0
	private var lastCellAlertMs = 0L
	private var lastCellAlertLevel = 0
	private var lastMotorHeatMs = 0L
	private var motorHeatActive = false
	private var lastBatteryOverheatMs = 0L
	private var batteryOverheatActive = false
	private var lastBatteryFreezeMs = 0L
	private var batteryFreezeActive = false
	private var bmsHadLink = false
	private var ctrlHadLink = false
	private var bmsDownSinceMs: Long? = null
	private var ctrlDownSinceMs: Long? = null
	private var announcedBmsLost = false
	private var announcedCtrlLost = false
	private var lastChargeEtaMs = 0L
	private var lastChargeEtaSpokenMs: Long? = null
	private var chargeEtaActive = false
	private var lastChargeSoc: Int? = null
	private var lastSpokenChargeTempC: Double? = null

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
		lastSpokenVoltageV = null
		lastChargeSoc = null
		lastSpokenChargeTempC = null
		stoppedSinceMs = null
		stopAnnounceCount = 0
		rangeShortActive = false
		lastReserveAlertLevel = 0
		lastCellAlertLevel = 0
		motorHeatActive = false
		batteryOverheatActive = false
		batteryFreezeActive = false
		bmsHadLink = false
		ctrlHadLink = false
		bmsDownSinceMs = null
		ctrlDownSinceMs = null
		announcedBmsLost = false
		announcedCtrlLost = false
		chargeEtaActive = false
		lastChargeEtaSpokenMs = null
	}

	fun onLink(
		bmsUp: Boolean,
		controllerUp: Boolean,
		expectBms: Boolean,
		expectController: Boolean,
		enabled: Boolean
	) {
		if (!enabled) {
			bmsDownSinceMs = null
			ctrlDownSinceMs = null
			return
		}
		updateLink(
			up = bmsUp,
			expect = expectBms,
			hadLink = { bmsHadLink },
			setHadLink = { bmsHadLink = it },
			downSince = { bmsDownSinceMs },
			setDownSince = { bmsDownSinceMs = it },
			announcedLost = { announcedBmsLost },
			setAnnouncedLost = { announcedBmsLost = it },
			lostRes = R.string.ev_bms_voice_bms_lost,
			restoredRes = R.string.ev_bms_voice_bms_restored
		)
		updateLink(
			up = controllerUp,
			expect = expectController,
			hadLink = { ctrlHadLink },
			setHadLink = { ctrlHadLink = it },
			downSince = { ctrlDownSinceMs },
			setDownSince = { ctrlDownSinceMs = it },
			announcedLost = { announcedCtrlLost },
			setAnnouncedLost = { announcedCtrlLost = it },
			lostRes = R.string.ev_bms_voice_controller_lost,
			restoredRes = R.string.ev_bms_voice_controller_restored
		)
	}

	private fun updateLink(
		up: Boolean,
		expect: Boolean,
		hadLink: () -> Boolean,
		setHadLink: (Boolean) -> Unit,
		downSince: () -> Long?,
		setDownSince: (Long?) -> Unit,
		announcedLost: () -> Boolean,
		setAnnouncedLost: (Boolean) -> Unit,
		lostRes: Int,
		restoredRes: Int
	) {
		if (!expect) {
			setDownSince(null)
			return
		}
		val now = System.currentTimeMillis()
		if (up) {
			setDownSince(null)
			if (announcedLost()) {
				speak(app.getString(restoredRes))
			}
			setAnnouncedLost(false)
			setHadLink(true)
			return
		}
		if (!hadLink()) {
			return
		}
		val started = downSince() ?: now.also { setDownSince(it) }
		if (!announcedLost() && now - started >= LINK_LOSS_HOLD_MS) {
			setAnnouncedLost(true)
			speak(app.getString(lostRes))
		}
	}

	fun onChargeProgress(
		voltageV: Double?,
		socPercent: Int?,
		tempC: Double?,
		announceCharge: Boolean,
		announceTemp: Boolean
	) {
		if (announceCharge) {
			val soc = socPercent?.takeIf { it in 0..100 }
			if (soc != null) {
				val last = lastChargeSoc
				if (last == null || soc >= last + 1) {
					lastChargeSoc = soc
					speak(app.getString(R.string.ev_bms_voice_charge_soc, soc))
				}
			} else {
				onChargeVoltage(voltageV, 0.5, true)
			}
		}
		if (announceTemp && tempC != null) {
			val last = lastSpokenChargeTempC
			if (last == null || kotlin.math.abs(tempC - last) >= 1.5) {
				lastSpokenChargeTempC = tempC
				speak(app.getString(R.string.ev_bms_voice_charge_temp, Math.round(tempC).toInt()))
			}
		}
	}

	fun onChargeVoltage(voltageV: Double?, stepV: Double, enabled: Boolean) {
		if (!enabled || voltageV == null || stepV <= 0) {
			return
		}
		val last = lastSpokenVoltageV
		if (last == null || kotlin.math.abs(voltageV - last) >= stepV) {
			lastSpokenVoltageV = voltageV
			val volts = String.format(Locale.US, "%.1f", voltageV)
			speak(app.getString(R.string.ev_bms_voice_soc, volts))
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

	fun onRangeReserve(
		shortfallKm: Double?,
		smallKm: Double,
		lowKm: Double,
		announceSmall: Boolean,
		announceLow: Boolean
	) {
		if ((!announceSmall && !announceLow) || shortfallKm == null) {
			lastReserveAlertLevel = 0
			return
		}
		val small = kotlin.math.min(smallKm, lowKm)
		val low = kotlin.math.max(smallKm, lowKm)
		val level = when {
			announceLow && shortfallKm >= low -> 2
			announceSmall && shortfallKm >= small -> 1
			else -> 0
		}
		if (level == 0) {
			if (shortfallKm < small - RANGE_HYSTERESIS_KM) {
				lastReserveAlertLevel = 0
			}
			return
		}
		val now = System.currentTimeMillis()
		val escalate = level > lastReserveAlertLevel
		if (!escalate && now - lastReserveAlertMs < RANGE_SHORT_REPEAT_MS) {
			lastReserveAlertLevel = level
			return
		}
		lastReserveAlertMs = now
		lastReserveAlertLevel = level
		val km = Math.round(shortfallKm).toInt().coerceAtLeast(0)
		speak(
			app.getString(
				if (level >= 2) R.string.ev_bms_voice_range_reserve_low else R.string.ev_bms_voice_range_reserve_small,
				km
			)
		)
	}

	fun onRestCellVoltage(
		minCellV: Double?,
		currentA: Double?,
		restCurrentA: Double,
		lowV: Double,
		criticalV: Double,
		intervalMs: Long,
		enabled: Boolean
	) {
		if (!enabled || minCellV == null || currentA == null) {
			lastCellAlertLevel = 0
			return
		}
		if (kotlin.math.abs(currentA) > restCurrentA) {
			return
		}
		val crit = kotlin.math.min(criticalV, lowV - 0.05)
		val level = when {
			minCellV < crit -> 2
			minCellV < lowV -> 1
			else -> 0
		}
		if (level == 0) {
			lastCellAlertLevel = 0
			return
		}
		val now = System.currentTimeMillis()
		val escalate = level > lastCellAlertLevel
		if (!escalate && now - lastCellAlertMs < intervalMs) {
			lastCellAlertLevel = level
			return
		}
		lastCellAlertMs = now
		lastCellAlertLevel = level
		val voltage = String.format(Locale.US, "%.2f", minCellV)
		speak(
			app.getString(
				if (level >= 2) R.string.ev_bms_voice_cell_critical else R.string.ev_bms_voice_cell_low,
				voltage
			)
		)
	}

	fun onMotorHeat(tempC: Double?, thresholdC: Int, intervalMs: Long, enabled: Boolean) {
		onRisingTemp(
			tempC,
			thresholdC.toDouble(),
			5.0,
			intervalMs,
			enabled,
			{ motorHeatActive },
			{ motorHeatActive = it },
			{ lastMotorHeatMs },
			{ lastMotorHeatMs = it },
			R.string.ev_bms_voice_motor_heat
		)
	}

	fun onBatteryOverheat(tempC: Double?, thresholdC: Int, intervalMs: Long, enabled: Boolean) {
		onRisingTemp(
			tempC,
			thresholdC.toDouble(),
			3.0,
			intervalMs,
			enabled,
			{ batteryOverheatActive },
			{ batteryOverheatActive = it },
			{ lastBatteryOverheatMs },
			{ lastBatteryOverheatMs = it },
			R.string.ev_bms_voice_battery_overheat
		)
	}

	fun onBatteryFreeze(tempC: Double?, thresholdC: Int, intervalMs: Long, enabled: Boolean) {
		if (!enabled || tempC == null) {
			batteryFreezeActive = false
			return
		}
		val threshold = thresholdC.toDouble()
		if (tempC > threshold + 3.0) {
			batteryFreezeActive = false
			return
		}
		if (tempC > threshold) {
			return
		}
		val now = System.currentTimeMillis()
		val first = !batteryFreezeActive
		batteryFreezeActive = true
		if (first || now - lastBatteryFreezeMs >= intervalMs) {
			lastBatteryFreezeMs = now
			speak(app.getString(R.string.ev_bms_voice_battery_freeze, Math.round(tempC).toInt()))
		}
	}

	private fun onRisingTemp(
		tempC: Double?,
		thresholdC: Double,
		hysteresisC: Double,
		intervalMs: Long,
		enabled: Boolean,
		getActive: () -> Boolean,
		setActive: (Boolean) -> Unit,
		getLastMs: () -> Long,
		setLastMs: (Long) -> Unit,
		voiceRes: Int
	) {
		if (!enabled || tempC == null) {
			setActive(false)
			return
		}
		if (tempC < thresholdC - hysteresisC) {
			setActive(false)
			return
		}
		if (tempC < thresholdC) {
			return
		}
		val now = System.currentTimeMillis()
		val first = !getActive()
		setActive(true)
		if (first || now - getLastMs() >= intervalMs) {
			setLastMs(now)
			speak(app.getString(voiceRes, Math.round(tempC).toInt()))
		}
	}

	fun onMotion(
		speedKmh: Double?,
		report: StopReport?,
		stopSpeedKmh: Double,
		maxRepeats: Int,
		enabled: Boolean
	) {
		if (!enabled) {
			return
		}
		val speed = speedKmh ?: return
		val now = System.currentTimeMillis()
		if (speed < stopSpeedKmh) {
			if (stoppedSinceMs == null) {
				stoppedSinceMs = now
				stopAnnounceCount = 0
			}
			val held = now - (stoppedSinceMs ?: now)
			val cap = maxRepeats.coerceAtLeast(1)
			if (held >= STOP_HOLD_MS &&
				stopAnnounceCount < cap &&
				now - lastRangeAnnounceMs >= RANGE_REPEAT_MS &&
				report != null
			) {
				lastRangeAnnounceMs = now
				stopAnnounceCount++
				speak(buildStopText(report))
			}
		} else {
			stoppedSinceMs = null
			stopAnnounceCount = 0
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
		val reserve = report.rangeReserveKm
		if (reserve != null) {
			val km = Math.round(kotlin.math.abs(reserve)).toInt()
			parts.add(
				when {
					reserve > 0.5 -> app.getString(R.string.ev_bms_voice_range_reserve_short, km)
					reserve < -0.5 -> app.getString(R.string.ev_bms_voice_range_reserve_extra, km)
					else -> app.getString(R.string.ev_bms_voice_range_reserve_ok)
				}
			)
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

	fun onChargeEta(remainingMs: Long?, enabled: Boolean) {
		if (!enabled) {
			chargeEtaActive = false
			lastChargeEtaSpokenMs = null
			return
		}
		if (remainingMs != null && remainingMs >= 0L) {
			lastChargeEtaSpokenMs = remainingMs
		}
		val eta = remainingMs ?: lastChargeEtaSpokenMs ?: return
		val intervalMs = when {
			eta > 2 * 3_600_000L -> 30 * 60_000L
			eta >= 30 * 60_000L -> 15 * 60_000L
			else -> 5 * 60_000L
		}
		val minutes = ((eta + 59_999L) / 60_000L).toInt().coerceAtLeast(0)
		val now = System.currentTimeMillis()
		val first = !chargeEtaActive
		chargeEtaActive = true
		if (first || now - lastChargeEtaMs >= intervalMs) {
			lastChargeEtaMs = now
			speak(formatChargeEta(minutes))
		}
	}

	fun onChargeFinished() {
		chargeEtaActive = false
		lastChargeEtaSpokenMs = null
		lastChargeSoc = null
		lastSpokenChargeTempC = null
		speak(app.getString(R.string.ev_bms_voice_charge_done))
	}

	private fun formatChargeEta(totalMinutes: Int): String {
		val hours = totalMinutes / 60
		val minutes = totalMinutes % 60
		return if (hours > 0) {
			app.getString(R.string.ev_bms_voice_charge_eta, hours, minutes)
		} else {
			app.getString(R.string.ev_bms_voice_charge_eta_min, minutes)
		}
	}

	fun speakNow(text: String) {
		speak(text)
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
