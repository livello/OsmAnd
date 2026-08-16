package net.osmand.plus.plugins.evbms

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-screen anti-speeding HUD: a high-contrast circular arc over the map.
 * Color and stroke thickness are meant to be read with peripheral vision.
 */
class EvSpeedometerHudView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

	enum class Zone {
		GREEN, YELLOW, ORANGE, STRIPE, RED
	}

	private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeCap = Paint.Cap.ROUND
		color = TRACK_COLOR
	}
	private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeCap = Paint.Cap.ROUND
	}
	private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		textAlign = Paint.Align.CENTER
		color = 0xB3FFFFFF.toInt()
	}
	private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		textAlign = Paint.Align.CENTER
		color = 0x66FFFFFF.toInt()
	}
	private val arcBounds = RectF()
	private val stripeEffect = DashPathEffect(floatArrayOf(28f, 18f), 0f)

	private var speedKmh = 0f
	private var zone = Zone.GREEN
	private var limit2Kmh = 60f
	private var buffer2Kmh = 70f
	private var flashAlpha = 1f
	private var flash: ValueAnimator? = null

	init {
		setWillNotDraw(false)
		isClickable = false
		isFocusable = false
		importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
	}

	fun updateHud(speedKmh: Float, zone: Zone, limit2Kmh: Int, buffer2Kmh: Int) {
		val changed = this.speedKmh != speedKmh ||
			this.zone != zone ||
			this.limit2Kmh != limit2Kmh.toFloat() ||
			this.buffer2Kmh != buffer2Kmh.toFloat()
		this.speedKmh = speedKmh.coerceAtLeast(0f)
		this.zone = zone
		this.limit2Kmh = limit2Kmh.toFloat()
		this.buffer2Kmh = buffer2Kmh.toFloat().coerceAtLeast(this.limit2Kmh + 1f)
		if (zone == Zone.RED) {
			startFlash()
		} else {
			stopFlash()
		}
		if (changed) {
			invalidate()
		}
	}

	override fun onDetachedFromWindow() {
		stopFlash()
		super.onDetachedFromWindow()
	}

	override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

	override fun onDraw(canvas: Canvas) {
		val w = width.toFloat()
		val h = height.toFloat()
		if (w < 8f || h < 8f) {
			return
		}
		val maxStroke = min(w, h) * 0.11f
		val minStroke = maxStroke * 0.28f
		val speedRatio = (speedKmh / buffer2Kmh).coerceIn(0f, 1.25f)
		val stroke = minStroke + (maxStroke - minStroke) * speedRatio.coerceAtMost(1f)
		val inset = stroke * 0.55f + dp(10f)
		arcBounds.set(inset, inset, w - inset, h - inset)

		trackPaint.strokeWidth = stroke * 0.42f
		canvas.drawArc(arcBounds, START_ANGLE, SWEEP_ANGLE, false, trackPaint)

		val fillSweep = SWEEP_ANGLE * (speedKmh / (buffer2Kmh * 1.15f)).coerceIn(0.04f, 1f)
		arcPaint.strokeWidth = stroke
		arcPaint.pathEffect = if (zone == Zone.STRIPE) stripeEffect else null
		arcPaint.color = zoneColor(zone)
		arcPaint.alpha = (zoneAlpha(zone) * flashAlpha).roundToInt().coerceIn(80, 255)
		canvas.drawArc(arcBounds, START_ANGLE, fillSweep, false, arcPaint)

		val cx = w * 0.5f
		val baseline = h - dp(28f)
		textPaint.textSize = min(w, h) * 0.07f
		unitPaint.textSize = textPaint.textSize * 0.38f
		canvas.drawText(speedKmh.roundToInt().toString(), cx, baseline, textPaint)
		canvas.drawText("km/h", cx, baseline + unitPaint.textSize * 1.15f, unitPaint)
	}

	private fun zoneColor(zone: Zone): Int = when (zone) {
		Zone.GREEN -> COLOR_GREEN
		Zone.YELLOW -> COLOR_YELLOW
		Zone.ORANGE -> COLOR_ORANGE
		Zone.STRIPE -> COLOR_STRIPE
		Zone.RED -> COLOR_RED
	}

	private fun zoneAlpha(zone: Zone): Int = when (zone) {
		Zone.GREEN -> 220
		Zone.YELLOW -> 235
		Zone.ORANGE -> 245
		Zone.STRIPE -> 250
		Zone.RED -> 255
	}

	private fun startFlash() {
		if (flash?.isRunning == true) {
			return
		}
		flash = ValueAnimator.ofFloat(1f, 0.38f).apply {
			duration = 280L
			repeatMode = ValueAnimator.REVERSE
			repeatCount = ValueAnimator.INFINITE
			interpolator = LinearInterpolator()
			addUpdateListener { anim ->
				flashAlpha = anim.animatedValue as Float
				invalidate()
			}
			start()
		}
	}

	private fun stopFlash() {
		flash?.cancel()
		flash = null
		flashAlpha = 1f
	}

	private fun dp(value: Float): Float = value * resources.displayMetrics.density

	companion object {
		private const val START_ANGLE = 150f
		private const val SWEEP_ANGLE = 240f
		private const val TRACK_COLOR = 0x33FFFFFF
		private const val COLOR_GREEN = 0xFF00E676.toInt()
		private const val COLOR_YELLOW = 0xFFFFEE58.toInt()
		private const val COLOR_ORANGE = 0xFFFF9100.toInt()
		private const val COLOR_STRIPE = 0xFFFF6D00.toInt()
		private const val COLOR_RED = 0xFFFF1744.toInt()
	}
}
