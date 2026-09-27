package net.osmand.plus.plugins.evbms

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlin.math.abs

/**
 * Charge energy is the integral of current × voltage while the BMS link is live.
 * A dropped link freezes voltage, so the amp-hours that arrive on reconnect are
 * charged at the average of the voltage before the gap and the voltage after it.
 */
object EvChargeEnergy {

	const val LINK_GAP_MS = 8_000L
	const val MIN_CURRENT_A = 0.15
	const val MIN_VOLTAGE_V = 20.0

	data class Sample(
		val timeMs: Long,
		val voltageV: Double?,
		val currentA: Double?,
		val remainingAh: Double?
	)

	class Accumulator {
		var wattHours: Double = 0.0
			private set
		private var samples = 0
		private var lastTimeMs = 0L
		private var lastVoltageV = Double.NaN
		private var lastCurrentA = Double.NaN
		private var lastAh = Double.NaN
		private var gapOpen = false
		private var gapVoltageV = Double.NaN
		private var gapAh = Double.NaN

		fun add(sample: Sample) {
			val voltage = sample.voltageV
			val current = sample.currentA
			val linked = voltage != null && voltage > MIN_VOLTAGE_V &&
				current != null && current >= MIN_CURRENT_A
			if (!linked || sample.timeMs <= 0L) {
				openGap()
				return
			}
			if (lastTimeMs <= 0L) {
				remember(sample, voltage!!, current!!)
				return
			}
			val dt = sample.timeMs - lastTimeMs
			if (dt <= 0L) {
				return
			}
			val frozen = same(voltage!!, lastVoltageV, 0.02) &&
				same(current!!, lastCurrentA, 0.05) &&
				(sample.remainingAh == null || lastAh.isNaN() || same(sample.remainingAh, lastAh, 0.001))
			val hole = dt > LINK_GAP_MS
			if (gapOpen || frozen || hole) {
				if (!gapOpen) {
					openGap()
				}
				if (frozen && !hole) {
					return
				}
				val ah0 = gapAh
				val v0 = gapVoltageV
				gapOpen = false
				val ah1 = sample.remainingAh
				if (!ah0.isNaN() && ah1 != null && !v0.isNaN()) {
					val deltaAh = (ah1 - ah0).coerceAtLeast(0.0)
					if (deltaAh > 0.001) {
						wattHours += deltaAh * (v0 + voltage) / 2.0
					}
				}
				remember(sample, voltage, current)
				return
			}
			val vAvg = (lastVoltageV + voltage) / 2.0
			val iAvg = (lastCurrentA + current!!) / 2.0
			wattHours += vAvg * iAvg * (dt / 3_600_000.0)
			remember(sample, voltage, current)
		}

		fun hasEnergy(): Boolean = samples >= 2 && wattHours >= 1.0

		private fun openGap() {
			if (gapOpen || lastTimeMs <= 0L) {
				return
			}
			gapOpen = true
			gapVoltageV = lastVoltageV
			gapAh = lastAh
		}

		private fun remember(sample: Sample, voltage: Double, current: Double) {
			lastTimeMs = sample.timeMs
			lastVoltageV = voltage
			lastCurrentA = current
			if (sample.remainingAh != null) {
				lastAh = sample.remainingAh
			}
			samples++
		}
	}

	fun scan(
		input: InputStream,
		fromMs: Long,
		toMs: Long,
		onSample: (Sample) -> Unit
	) {
		BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
			val headerLine = reader.readLine()?.trimStart('\uFEFF') ?: return
			val cols = headerLine.split(';').mapIndexed { index, name -> name.trim() to index }.toMap()
			val timeIdx = cols["time_ms"] ?: return
			val voltageIdx = cols["voltage_v"] ?: -1
			val currentIdx = cols["current_a"] ?: -1
			val ahIdx = cols["remaining_ah"] ?: -1
			var line = reader.readLine()
			while (line != null) {
				if (line.isNotBlank()) {
					val parts = line.split(';')
					val timeMs = parts.getOrNull(timeIdx)?.trim()?.toLongOrNull()
					if (timeMs != null && timeMs > toMs && timeMs > fromMs) {
						break
					}
					if (timeMs != null && timeMs >= fromMs && timeMs <= toMs) {
						onSample(
							Sample(
								timeMs = timeMs,
								voltageV = parts.numberAt(voltageIdx),
								currentA = parts.numberAt(currentIdx),
								remainingAh = parts.numberAt(ahIdx)
							)
						)
					}
				}
				line = reader.readLine()
			}
		}
	}

	private fun List<String>.numberAt(index: Int): Double? {
		if (index < 0 || index >= size) {
			return null
		}
		return this[index].trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
	}

	private fun same(a: Double, b: Double, eps: Double): Boolean {
		if (a.isNaN() || b.isNaN()) {
			return false
		}
		return abs(a - b) <= eps
	}

	data class SessionMetrics(
		val bmsDeltaAh: Double?,
		val integralAh: Double,
		val energyWh: Double,
		val avgCurrentA: Double?,
		val durationMs: Long,
	)

	/**
	 * Journal Ah must match I·dt; BMS remaining_ah delta can be wrong when [chargeStartAh]
	 * predates the real charge window or when merge/split duplicated Ah sums.
	 */
	fun consistentChargedAh(bmsDelta: Double?, integralAh: Double): Double? {
		if (integralAh > 0.05) {
			if (bmsDelta == null || bmsDelta <= 0.05) {
				return integralAh
			}
			if (bmsDelta > integralAh * 1.08 && bmsDelta > integralAh + 0.35) {
				return integralAh
			}
			return minOf(bmsDelta, integralAh)
		}
		return bmsDelta?.takeIf { it > 0.05 }
	}

	fun sessionMetrics(samples: List<Sample>): SessionMetrics {
		var linkedStartAh: Double? = null
		var linkedEndAh: Double? = null
		var lastTimeMs = 0L
		var lastVoltageV = Double.NaN
		var lastCurrentA = Double.NaN
		var lastAh = Double.NaN
		var integralAh = 0.0
		var energyWh = 0.0
		var gapOpen = false
		var gapVoltageV = Double.NaN
		var gapAh = Double.NaN
		var firstLinkedMs = 0L
		var lastLinkedMs = 0L

		for (sample in samples.sortedBy { it.timeMs }) {
			val voltage = sample.voltageV
			val current = sample.currentA
			val linked = voltage != null && voltage > MIN_VOLTAGE_V &&
				current != null && current >= MIN_CURRENT_A
			if (!linked || sample.timeMs <= 0L) {
				if (lastTimeMs > 0L && !gapOpen) {
					gapOpen = true
					gapVoltageV = lastVoltageV
					gapAh = lastAh
				}
				continue
			}
			if (linkedStartAh == null && sample.remainingAh != null) {
				linkedStartAh = sample.remainingAh
			}
			if (sample.remainingAh != null) {
				linkedEndAh = sample.remainingAh
			}
			if (firstLinkedMs == 0L) {
				firstLinkedMs = sample.timeMs
			}
			lastLinkedMs = sample.timeMs
			if (lastTimeMs <= 0L) {
				lastTimeMs = sample.timeMs
				lastVoltageV = voltage!!
				lastCurrentA = current!!
				if (sample.remainingAh != null) {
					lastAh = sample.remainingAh
				}
				continue
			}
			val dt = sample.timeMs - lastTimeMs
			if (dt <= 0L) {
				continue
			}
			val frozen = same(voltage!!, lastVoltageV, 0.02) &&
				same(current!!, lastCurrentA, 0.05) &&
				(sample.remainingAh == null || lastAh.isNaN() ||
					same(sample.remainingAh, lastAh, 0.001))
			val hole = dt > LINK_GAP_MS
			if (gapOpen || frozen || hole) {
				if (!gapOpen) {
					gapOpen = true
					gapVoltageV = lastVoltageV
					gapAh = lastAh
				}
				if (frozen && !hole) {
					continue
				}
				val ah0 = gapAh
				gapOpen = false
				val ah1 = sample.remainingAh
				if (!ah0.isNaN() && ah1 != null && !gapVoltageV.isNaN()) {
					val deltaAh = (ah1 - ah0).coerceAtLeast(0.0)
					if (deltaAh > 0.001) {
						integralAh += deltaAh
						energyWh += deltaAh * (gapVoltageV + voltage) / 2.0
					}
				}
				lastTimeMs = sample.timeMs
				lastVoltageV = voltage
				lastCurrentA = current
				if (sample.remainingAh != null) {
					lastAh = sample.remainingAh
				}
				continue
			}
			val vAvg = (lastVoltageV + voltage) / 2.0
			val iAvg = (lastCurrentA + current) / 2.0
			val stepAh = iAvg * (dt / 3_600_000.0)
			integralAh += stepAh
			energyWh += vAvg * iAvg * (dt / 3_600_000.0)
			lastTimeMs = sample.timeMs
			lastVoltageV = voltage
			lastCurrentA = current
			if (sample.remainingAh != null) {
				lastAh = sample.remainingAh
			}
		}
		val bmsDelta = if (linkedStartAh != null && linkedEndAh != null) {
			(linkedEndAh!! - linkedStartAh!!).coerceAtLeast(0.0)
		} else {
			null
		}
		val durationMs = if (firstLinkedMs > 0L && lastLinkedMs > firstLinkedMs) {
			lastLinkedMs - firstLinkedMs
		} else {
			0L
		}
		val avgI = if (durationMs > 0L && integralAh > 0.01) {
			integralAh / (durationMs / 3_600_000.0)
		} else {
			null
		}
		return SessionMetrics(
			bmsDeltaAh = bmsDelta,
			integralAh = integralAh,
			energyWh = energyWh,
			avgCurrentA = avgI,
			durationMs = durationMs,
		)
	}

	fun scanSessionMetrics(input: InputStream, fromMs: Long, toMs: Long): SessionMetrics {
		val samples = ArrayList<Sample>()
		scan(input, fromMs, toMs) { samples.add(it) }
		return sessionMetrics(samples)
	}
}
