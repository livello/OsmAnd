package net.osmand.plus.plugins.evbms.protocol

data class BmsSnapshot(
	val voltageV: Double,
	val currentA: Double,
	val remainingMah: Int,
	val fullMah: Int,
	val cycles: Int,
	val socPercent: Int,
	val chargeEnabled: Boolean,
	val dischargeEnabled: Boolean,
	val cellCount: Int,
	val temperaturesC: List<Float>,
	val cells: List<Double>? = null
)
