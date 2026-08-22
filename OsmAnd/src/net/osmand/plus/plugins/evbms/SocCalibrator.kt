package net.osmand.plus.plugins.evbms

import org.json.JSONObject
import kotlin.math.abs

/**
 * Per-BMS SOC from min-cell voltage.
 * Rest samples while riding (throttle closed) are true OCV.
 * Under charge the pack voltage is high by I·R, so OCV is compensated with learned cell resistance.
 */
class SocCalibrator {

	companion object {
		private const val REST_A = 2.0
		private const val LOAD_A = 8.0
		private const val REST_HOLD_MS = 400L
		private const val SAVE_INTERVAL_MS = 8_000L
		private const val R_MIN = 0.0015
		private const val R_MAX = 0.06
		private const val DEFAULT_R = 0.012
		private val NMC = doubleArrayOf(
			3.00, 3.40, 3.50, 3.62, 3.70, 3.76, 3.82, 3.87, 3.93, 4.00, 4.08, 4.20
		)
		private val NMC_SOC = intArrayOf(0, 5, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
		private val LFP = doubleArrayOf(
			2.50, 3.00, 3.20, 3.26, 3.29, 3.31, 3.33, 3.34, 3.36, 3.40
		)
		private val LFP_SOC = intArrayOf(0, 5, 10, 20, 40, 60, 80, 90, 95, 100)
	}

	private class Profile {
		var vEmpty = 3.00
		var vFull = 4.20
		var rCell = DEFAULT_R
		var lfp = false
		var restSamples = 0
		var maxRestOcv = 0.0
	}

	private val profiles = HashMap<String, Profile>()
	private var restSinceMs = 0L
	private var loadMinV: Double? = null
	private var loadA: Double? = null
	private var smoothedSoc: Double? = null
	private var lastMac: String? = null
	private var lastSaveMs = 0L
	var dirty = false
		private set

	fun decode(raw: String?) {
		profiles.clear()
		if (raw.isNullOrBlank()) {
			return
		}
		try {
			val json = JSONObject(raw)
			val keys = json.keys()
			while (keys.hasNext()) {
				val mac = keys.next()
				val row = json.optJSONObject(mac) ?: continue
				val p = Profile()
				p.vEmpty = row.optDouble("vEmpty", p.vEmpty)
				p.vFull = row.optDouble("vFull", p.vFull)
				p.rCell = row.optDouble("rCell", p.rCell).coerceIn(R_MIN, R_MAX)
				p.lfp = row.optBoolean("lfp", false)
				p.restSamples = row.optInt("restSamples", 0)
				p.maxRestOcv = row.optDouble("maxRestOcv", 0.0)
				profiles[normalize(mac)] = p
			}
		} catch (_: Exception) {
		}
	}

	fun encode(): String {
		val json = JSONObject()
		for ((mac, p) in profiles) {
			json.put(
				mac,
				JSONObject()
					.put("vEmpty", p.vEmpty)
					.put("vFull", p.vFull)
					.put("rCell", p.rCell)
					.put("lfp", p.lfp)
					.put("restSamples", p.restSamples)
					.put("maxRestOcv", p.maxRestOcv)
			)
		}
		return json.toString()
	}

	fun flushDue(now: Long): Boolean {
		if (!dirty || now - lastSaveMs < SAVE_INTERVAL_MS) {
			return false
		}
		dirty = false
		lastSaveMs = now
		return true
	}

	fun tick(
		mac: String?,
		minCellV: Double?,
		currentA: Double?,
		charging: Boolean,
		now: Long
	): Int? {
		if (minCellV == null || minCellV < 2.0 || minCellV > 4.5) {
			return smoothedSoc?.toInt()
		}
		val key = normalize(mac)
		if (key != lastMac) {
			smoothedSoc = null
			restSinceMs = 0L
			loadMinV = null
			loadA = null
			lastMac = key
		}
		val p = profiles.getOrPut(key) { Profile() }
		val iAbs = currentA?.let { abs(it) } ?: 0.0
		val rest = iAbs < REST_A
		if (rest) {
			if (restSinceMs == 0L) {
				restSinceMs = now
			}
			val coasted = loadMinV != null || now - restSinceMs >= REST_HOLD_MS
			if (coasted) {
				learnRest(p, minCellV, charging)
				learnResistance(p, minCellV)
				loadMinV = null
				loadA = null
			}
		} else {
			restSinceMs = 0L
			if (!charging && iAbs >= LOAD_A) {
				loadMinV = minCellV
				loadA = iAbs
			}
		}
		val ocv = if (charging) {
			(minCellV - iAbs * p.rCell).coerceAtLeast(p.vEmpty - 0.05)
		} else if (rest) {
			minCellV
		} else {
			(minCellV + iAbs * p.rCell).coerceAtMost(p.vFull + 0.05)
		}
		val raw = socFromOcv(p, ocv)
		val prev = smoothedSoc
		smoothedSoc = if (prev == null) {
			raw
		} else {
			(prev * 0.65 + raw * 0.35).coerceIn(0.0, 100.0)
		}
		return smoothedSoc!!.toInt().coerceIn(0, 100)
	}

	fun remainingAhFromOcv(fullAh: Double?, socPercent: Int?): Double? {
		if (fullAh == null || fullAh < 0.5 || socPercent == null) {
			return null
		}
		return (fullAh * socPercent / 100.0).coerceAtLeast(0.0)
	}

	fun isLfp(): Boolean {
		val key = lastMac ?: return false
		return profiles[key]?.lfp == true
	}

	fun cellFullThresholdV(): Double = if (isLfp()) 3.45 else 4.12

	fun packOcvV(
		mac: String?,
		packV: Double?,
		currentA: Double?,
		charging: Boolean,
		series: Int?
	): Double? {
		if (packV == null || packV <= 0.0) {
			return null
		}
		val iAbs = currentA?.let { abs(it) } ?: return packV
		if (iAbs < REST_A) {
			return packV
		}
		val p = profiles[normalize(mac)]
		val n = series?.takeIf { it in 1..48 }
		if (n == null) {
			return packV
		}
		val drop = iAbs * (p?.rCell ?: DEFAULT_R) * n
		return if (charging) {
			(packV - drop).coerceAtLeast(packV * 0.85)
		} else {
			(packV + drop).coerceAtMost(packV * 1.15)
		}
	}

	private fun learnRest(p: Profile, ocv: Double, charging: Boolean) {
		p.restSamples++
		p.maxRestOcv = maxOf(p.maxRestOcv, ocv)
		if (p.maxRestOcv >= 3.70) {
			p.lfp = false
		} else if (p.restSamples >= 6 && p.maxRestOcv <= 3.55) {
			if (!p.lfp) {
				p.lfp = true
				p.vEmpty = 2.80
				p.vFull = p.maxRestOcv.coerceAtLeast(3.38)
				dirty = true
			}
		}
		val span = (p.vFull - p.vEmpty).coerceAtLeast(0.15)
		if (!charging && ocv < p.vEmpty + span * 0.12) {
			p.vEmpty = blend(p.vEmpty, ocv.coerceIn(2.40, 3.40), if (p.restSamples < 6) 0.35 else 0.12)
			dirty = true
		}
		if (!charging && ocv > p.vFull - span * 0.12) {
			val hi = ocv.coerceIn(3.25, 4.25)
			p.vFull = blend(p.vFull, hi, if (p.restSamples < 6) 0.25 else 0.08)
			dirty = true
		}
		if (p.vFull - p.vEmpty < 0.18) {
			p.vFull = p.vEmpty + 0.18
		}
	}

	private fun learnResistance(p: Profile, restOcv: Double) {
		val vLoad = loadMinV ?: return
		val iLoad = loadA ?: return
		if (iLoad < LOAD_A) {
			return
		}
		val r = (restOcv - vLoad) / iLoad
		if (r !in R_MIN..R_MAX) {
			return
		}
		p.rCell = blend(p.rCell, r, 0.25).coerceIn(R_MIN, R_MAX)
		dirty = true
	}

	private fun socFromOcv(p: Profile, ocv: Double): Double {
		val (curve, socs) = if (p.lfp) LFP to LFP_SOC else NMC to NMC_SOC
		val cEmpty = curve.first()
		val cFull = curve.last()
		val span = (p.vFull - p.vEmpty).coerceAtLeast(0.12)
		val t = ((ocv - p.vEmpty) / span).coerceIn(0.0, 1.0)
		val mapped = cEmpty + t * (cFull - cEmpty)
		return lookup(mapped, curve, socs)
	}

	private fun lookup(v: Double, curve: DoubleArray, socs: IntArray): Double {
		if (v <= curve.first()) {
			return 0.0
		}
		if (v >= curve.last()) {
			return 100.0
		}
		for (i in 1 until curve.size) {
			if (v <= curve[i]) {
				val a = curve[i - 1]
				val b = curve[i]
				val frac = if (b > a) (v - a) / (b - a) else 0.0
				return socs[i - 1] + frac * (socs[i] - socs[i - 1])
			}
		}
		return 100.0
	}

	private fun blend(old: Double, sample: Double, alpha: Double): Double {
		return old * (1.0 - alpha) + sample * alpha
	}

	private fun normalize(mac: String?): String {
		val text = mac?.trim()?.lowercase().orEmpty()
		return text.ifEmpty { "default" }
	}
}
