package net.osmand.plus.plugins.evbms

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rebuilds charge / ride journal rows from a telemetry CSV when the live trip
 * stayed open across the whole day and never recorded the charges in between.
 */
object EvTelemetryHistory {

	data class Rebuild(
		val fileStartMs: Long,
		val fileEndMs: Long,
		val charges: List<EvHistoryStore.ChargeRecord>,
		val trips: List<EvHistoryStore.ChargeTripRecord>
	)

	private const val CHARGE_ON_A = 2.0
	private const val CHARGE_OFF_A = 0.4
	private const val CHARGE_ON_HOLD_MS = 20_000L
	private const val CHARGE_OFF_HOLD_MS = 45_000L
	private const val MIN_CHARGE_MS = 8 * 60_000L
	private const val MAX_SAMPLE_GAP_MS = 30_000L
	private const val ODO_STEP_MAX_KM = 0.5
	private const val WALK_GPS_KMH = 8.0
	private const val SPEED_MIN_KMH = 3.0
	private const val MIN_RIDE_KM = 0.2
	private const val DISCHARGE_A = -0.15
	private const val CHARGE_ENERGY_A = 0.15

	fun parse(input: InputStream, chargeOnA: Double = CHARGE_ON_A): Rebuild {
		val onA = chargeOnA.coerceAtLeast(0.5)
		BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
			val headerLine = reader.readLine()?.trimStart('\uFEFF') ?: return emptyRebuild()
			val cols = headerLine.split(';').mapIndexed { index, name -> name.trim() to index }.toMap()
			val timeIdx = cols["time_ms"] ?: return emptyRebuild()

			fun idx(name: String): Int = cols[name] ?: -1
			val iLat = idx("lat")
			val iLon = idx("lon")
			val iGpsSpeed = idx("gps_speed_kmh")
			val iVoltage = idx("voltage_v")
			val iCurrent = idx("current_a")
			val iAh = idx("remaining_ah")
			val iTemp = idx("bms_temp_c")
			val iMinCell = idx("min_cell_v")
			val iRpm = idx("rpm")
			val iMotor = idx("motor_temp_c")
			val iCtrlOdo = idx("odometer_km")
			val iCtrlSpeed = idx("controller_speed_kmh")
			val iWheelSpeed = idx("wheel_speed_kmh")
			val iWheelOdo = idx("wheel_odometer_km")

			fun cell(parts: List<String>, index: Int): String? =
				if (index >= 0 && index < parts.size) parts[index].trim().takeIf { it.isNotEmpty() } else null

			fun num(parts: List<String>, index: Int): Double? = cell(parts, index)?.toDoubleOrNull()

			val charges = ArrayList<EvHistoryStore.ChargeRecord>()
			val trips = ArrayList<EvHistoryStore.ChargeTripRecord>()
			var fileStart = 0L
			var fileEnd = 0L
			var inCharge = false
			var onSince: Long? = null
			var offSince: Long? = null
			var ride = Leg()
			var charge = Leg()

			fun flushRide(endMs: Long) {
				val row = ride.toTrip(endMs)
				if (row != null && (row.distanceKm ?: 0.0) >= MIN_RIDE_KM) {
					trips.add(row)
				}
				ride = Leg()
			}

			fun flushCharge(endMs: Long) {
				if (endMs - charge.startMs >= MIN_CHARGE_MS) {
					charge.toCharge(endMs)?.let { charges.add(it) }
				}
				charge = Leg()
			}

			var line: String?
			while (reader.readLine().also { line = it } != null) {
				val raw = line ?: continue
				if (raw.isBlank()) {
					continue
				}
				val parts = raw.split(';')
				val t = cell(parts, timeIdx)?.toLongOrNull() ?: continue
				if (fileStart == 0L) {
					fileStart = t
				}
				fileEnd = t
				val currentA = num(parts, iCurrent)
				if (!inCharge) {
					if (onSince == null) {
						ride.add(
							t, parts, currentA,
							iLat, iLon, iGpsSpeed, iVoltage, iAh, iTemp, iMinCell, iRpm, iMotor,
							iCtrlOdo, iCtrlSpeed, iWheelSpeed, iWheelOdo, charging = false, ::num
						)
					}
					if (currentA != null && currentA >= onA) {
						onSince = onSince ?: t
						if (t - onSince!! >= CHARGE_ON_HOLD_MS) {
							val start = onSince!!
							flushRide(start)
							inCharge = true
							offSince = null
							onSince = null
							charge = Leg()
							charge.add(
								t, parts, currentA,
								iLat, iLon, iGpsSpeed, iVoltage, iAh, iTemp, iMinCell, iRpm, iMotor,
								iCtrlOdo, iCtrlSpeed, iWheelSpeed, iWheelOdo, charging = true, ::num
							)
							charge.forceStart(start)
						}
					} else {
						onSince = null
					}
				} else {
					charge.add(
						t, parts, currentA,
						iLat, iLon, iGpsSpeed, iVoltage, iAh, iTemp, iMinCell, iRpm, iMotor,
						iCtrlOdo, iCtrlSpeed, iWheelSpeed, iWheelOdo, charging = true, ::num
					)
					if (currentA != null && currentA <= CHARGE_OFF_A) {
						offSince = offSince ?: t
						if (t - offSince!! >= CHARGE_OFF_HOLD_MS) {
							val end = offSince!!
							flushCharge(end)
							inCharge = false
							offSince = null
							ride = Leg()
							ride.add(
								t, parts, currentA,
								iLat, iLon, iGpsSpeed, iVoltage, iAh, iTemp, iMinCell, iRpm, iMotor,
								iCtrlOdo, iCtrlSpeed, iWheelSpeed, iWheelOdo, charging = false, ::num
							)
							ride.forceStart(end)
						}
					} else if (currentA != null && currentA > CHARGE_OFF_A) {
						offSince = null
					}
				}
			}
			if (inCharge) {
				flushCharge(fileEnd)
			} else {
				flushRide(fileEnd)
			}
			return Rebuild(fileStart, fileEnd, charges, trips)
		}
	}

	private fun emptyRebuild() = Rebuild(0L, 0L, emptyList(), emptyList())

	private class Leg {
		var startMs = 0L
		var endMs = 0L
		var startAh: Double? = null
		var endAh: Double? = null
		var startV: Double? = null
		var endV: Double? = null
		var startTemp: Double? = null
		var endTemp: Double? = null
		var startMinCell: Double? = null
		var endMinCell: Double? = null
		var startMotor: Double? = null
		var endMotor: Double? = null
		var startLat: Double? = null
		var startLon: Double? = null
		var endLat: Double? = null
		var endLon: Double? = null
		var energyWh = 0.0
		var currentIntegralAms = 0.0
		var movingMs = 0L
		var lastT = 0L
		var lastWheel: Double? = null
		var lastCtrl: Double? = null
		var lastLat: Double? = null
		var lastLon: Double? = null
		var lastGpsT = 0L
		var wheelKm = 0.0
		var ctrlKm = 0.0
		var gpsKm = 0.0
		var speedKm = 0.0

		fun forceStart(ms: Long) {
			if (startMs == 0L || startMs > ms) {
				startMs = ms
			}
		}

		fun add(
			t: Long,
			parts: List<String>,
			currentA: Double?,
			iLat: Int,
			iLon: Int,
			iGpsSpeed: Int,
			iVoltage: Int,
			iAh: Int,
			iTemp: Int,
			iMinCell: Int,
			iRpm: Int,
			iMotor: Int,
			iCtrlOdo: Int,
			iCtrlSpeed: Int,
			iWheelSpeed: Int,
			iWheelOdo: Int,
			charging: Boolean,
			num: (List<String>, Int) -> Double?
		) {
			if (startMs == 0L) {
				startMs = t
			}
			endMs = t
			val dt = if (lastT > 0L) t - lastT else 0L
			val voltage = num(parts, iVoltage)
			val ah = num(parts, iAh)
			val temp = num(parts, iTemp)
			val minCell = num(parts, iMinCell)
			val motor = num(parts, iMotor)
			val lat = num(parts, iLat)
			val lon = num(parts, iLon)
			if (startAh == null && ah != null) startAh = ah
			if (ah != null) endAh = ah
			if (startV == null && voltage != null) startV = voltage
			if (voltage != null) endV = voltage
			if (startTemp == null && temp != null) startTemp = temp
			if (temp != null) endTemp = temp
			if (startMinCell == null && minCell != null) startMinCell = minCell
			if (minCell != null) endMinCell = minCell
			if (startMotor == null && motor != null) startMotor = motor
			if (motor != null) endMotor = motor
			if (startLat == null && lat != null && lon != null) {
				startLat = lat
				startLon = lon
			}
			if (lat != null && lon != null) {
				endLat = lat
				endLon = lon
			}
			if (dt in 1 until MAX_SAMPLE_GAP_MS && voltage != null && currentA != null) {
				if (charging && currentA >= CHARGE_ENERGY_A) {
					energyWh += voltage * currentA * (dt / 3_600_000.0)
					currentIntegralAms += currentA * dt
				} else if (!charging && currentA <= DISCHARGE_A) {
					energyWh += voltage * abs(currentA) * (dt / 3_600_000.0)
					currentIntegralAms += abs(currentA) * dt
				}
			}
			val wheel = num(parts, iWheelOdo)
			if (lastWheel != null && wheel != null) {
				val d = wheel - lastWheel!!
				if (d in 0.0..ODO_STEP_MAX_KM) {
					wheelKm += d
				}
			}
			if (wheel != null) lastWheel = wheel
			val ctrl = num(parts, iCtrlOdo)
			if (lastCtrl != null && ctrl != null) {
				val d = ctrl - lastCtrl!!
				if (d in 0.0..ODO_STEP_MAX_KM) {
					ctrlKm += d
				}
			}
			if (ctrl != null) lastCtrl = ctrl
			if (lat != null && lon != null && lastLat != null && lastLon != null && lastGpsT > 0L) {
				val d = haversineKm(lastLat!!, lastLon!!, lat, lon)
				val gdt = t - lastGpsT
				if (d in 0.002..0.3 && gdt > 0L) {
					val kmh = d / (gdt / 3_600_000.0)
					if (kmh in WALK_GPS_KMH..160.0) {
						gpsKm += d
					}
				}
			}
			if (lat != null && lon != null) {
				lastLat = lat
				lastLon = lon
				lastGpsT = t
			}
			val speed = sequenceOf(
				num(parts, iWheelSpeed),
				num(parts, iCtrlSpeed),
				num(parts, iGpsSpeed)
			).firstOrNull { it != null && it >= SPEED_MIN_KMH }
			if (dt in 1 until MAX_SAMPLE_GAP_MS && speed != null) {
				speedKm += speed * (dt / 3_600_000.0)
			}
			val wheelSpeed = num(parts, iWheelSpeed) ?: 0.0
			val ctrlSpeed = num(parts, iCtrlSpeed) ?: 0.0
			val rpm = num(parts, iRpm) ?: 0.0
			if (dt in 1 until MAX_SAMPLE_GAP_MS &&
				(wheelSpeed >= SPEED_MIN_KMH || ctrlSpeed >= SPEED_MIN_KMH || rpm >= 30.0 ||
					(currentA != null && currentA <= -2.0))
			) {
				movingMs += dt
			}
			lastT = t
		}

		fun distanceKm(): Double = maxOf(wheelKm, ctrlKm, gpsKm, speedKm)

		fun toCharge(endMs: Long): EvHistoryStore.ChargeRecord? {
			if (startMs <= 0L || endMs <= startMs) {
				return null
			}
			val dur = (endMs - startMs).coerceAtLeast(1L)
			val chargedAh = if (startAh != null && endAh != null) {
				(endAh!! - startAh!!).coerceAtLeast(0.0)
			} else {
				null
			}
			return EvHistoryStore.ChargeRecord(
				startMs = startMs,
				endMs = endMs,
				startTempC = startTemp,
				endTempC = endTemp,
				chargedAh = chargedAh,
				avgCurrentA = if (currentIntegralAms > 0.0) currentIntegralAms / dur else null,
				startMinCellV = startMinCell,
				endMinCellV = endMinCell,
				stopMs = 0L,
				energyWh = energyWh.takeIf { it > 1.0 },
				startLat = startLat,
				startLon = startLon,
				endLat = endLat,
				endLon = endLon
			)
		}

		fun toTrip(endMs: Long): EvHistoryStore.ChargeTripRecord? {
			if (startMs <= 0L || endMs <= startMs) {
				return null
			}
			val distance = distanceKm()
			val usedAh = if (startAh != null && endAh != null) {
				(startAh!! - endAh!!).coerceAtLeast(0.0)
			} else {
				null
			}
			val energy = energyWh.takeIf { it > 1.0 }
			val specific = if (energy != null && distance > 0.05) energy / distance else null
			val avgMoving = if (movingMs > 5_000L && distance > 0.02) {
				distance / (movingMs / 3_600_000.0)
			} else {
				null
			}
			val stopMs = (endMs - startMs - movingMs).coerceAtLeast(0L)
			return EvHistoryStore.ChargeTripRecord(
				startMs = startMs,
				endMs = endMs,
				startVoltageV = startV,
				endVoltageV = endV,
				minCellV = minOfNullable(startMinCell, endMinCell),
				startTempC = startTemp,
				endTempC = endTemp,
				startMotorTempC = startMotor,
				endMotorTempC = endMotor,
				distanceKm = distance.takeIf { it > 0.0 },
				movingMs = movingMs,
				energyWh = energy,
				usedAh = usedAh,
				specificWhKm = specific,
				avgMovingKmh = avgMoving,
				stopMs = stopMs,
				startLat = startLat,
				startLon = startLon,
				endLat = endLat,
				endLon = endLon
			)
		}
	}

	private fun minOfNullable(a: Double?, b: Double?): Double? {
		return when {
			a == null -> b
			b == null -> a
			else -> min(a, b)
		}
	}

	private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
		val r = 6371.0
		val dLat = Math.toRadians(lat2 - lat1)
		val dLon = Math.toRadians(lon2 - lon1)
		val a = sin(dLat / 2) * sin(dLat / 2) +
			cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
			sin(dLon / 2) * sin(dLon / 2)
		return 2 * r * asin(sqrt(min(1.0, a)))
	}
}
