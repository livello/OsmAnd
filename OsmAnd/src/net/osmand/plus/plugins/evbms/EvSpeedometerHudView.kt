package net.osmand.plus.plugins.evbms

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import net.osmand.plus.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-screen anti-speeding HUD: a high-contrast circular arc over the map.
 * Color and stroke thickness are meant to be read with peripheral vision,
 * including on a light daytime map.
 */
class EvSpeedometerHudView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

	enum class Zone {
		GREEN, YELLOW, ORANGE, STRIPE, RED
	}

	private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeCap = Paint.Cap.ROUND
		color = HALO_COLOR
	}
	private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeCap = Paint.Cap.ROUND
	}
	private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeCap = Paint.Cap.ROUND
	}
	private val textHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		textAlign = Paint.Align.CENTER
		color = TEXT_HALO
		typeface = Typeface.DEFAULT_BOLD
	}
	private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.FILL
		textAlign = Paint.Align.CENTER
		color = TEXT_FILL
		typeface = Typeface.DEFAULT_BOLD
	}
	private val unitHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		textAlign = Paint.Align.CENTER
		color = TEXT_HALO
		typeface = Typeface.DEFAULT_BOLD
	}
	private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.FILL
		textAlign = Paint.Align.CENTER
		color = TEXT_FILL
		typeface = Typeface.DEFAULT_BOLD
	}
	private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.FILL
		color = BADGE_COLOR
	}
	private val arcBounds = RectF()
	private val badgeBounds = RectF()
	private val stripeEffect = DashPathEffect(floatArrayOf(28f, 18f), 0f)

	private var speedKmh = 0f
	private var zone = Zone.GREEN
	private var limit2Kmh = 60f
	private var buffer2Kmh = 70f
	private var flashAlpha = 1f
	private var flash: ValueAnimator? = null

	init {
		id = R.id.ev_speedometer_hud
		tag = HUD_TAG
		contentDescription = "Speedometer HUD"
		setWillNotDraw(false)
		isClickable = false
		isFocusable = false
		importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
	}

	fun updateHud(speedKmh: Float, zone: Zone, limit2Kmh: Int, buffer2Kmh: Int) {
		this.speedKmh = speedKmh.coerceAtLeast(0f)
		this.zone = zone
		this.limit2Kmh = limit2Kmh.toFloat()
		this.buffer2Kmh = buffer2Kmh.toFloat().coerceAtLeast(this.limit2Kmh + 1f)
		if (zone == Zone.RED) {
			startFlash()
		} else {
			stopFlash()
		}
		invalidate()
	}

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
		super.onSizeChanged(w, h, oldw, oldh)
		invalidate()
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
		val color = zoneColor(zone)
		val maxStroke = min(w, h) * 0.10f
		val minStroke = maxStroke * 0.55f
		val speedRatio = (speedKmh / buffer2Kmh).coerceIn(0f, 1.25f)
		val stroke = minStroke + (maxStroke - minStroke) * speedRatio.coerceAtMost(1f)
		val inset = stroke * 0.7f + dp(16f)
		arcBounds.set(inset, inset, w - inset, h - inset)

		haloPaint.strokeWidth = stroke + dp(6f)
		canvas.drawArc(arcBounds, START_ANGLE, SWEEP_ANGLE, false, haloPaint)

		trackPaint.strokeWidth = stroke * 0.72f
		trackPaint.color = color
		trackPaint.alpha = 110
		trackPaint.pathEffect = null
		canvas.drawArc(arcBounds, START_ANGLE, SWEEP_ANGLE, false, trackPaint)

		val fillSweep = SWEEP_ANGLE * (speedKmh / (buffer2Kmh * 1.15f)).coerceIn(0.12f, 1f)
		arcPaint.strokeWidth = stroke
		arcPaint.pathEffect = if (zone == Zone.STRIPE) stripeEffect else null
		arcPaint.color = color
		arcPaint.alpha = (zoneAlpha(zone) * flashAlpha).roundToInt().coerceIn(160, 255)
		canvas.drawArc(arcBounds, START_ANGLE, fillSweep, false, arcPaint)

		val speedText = speedKmh.roundToInt().toString()
		val textSize = min(w, h) * 0.13f
		textPaint.textSize = textSize
		textHaloPaint.textSize = textSize
		textHaloPaint.strokeWidth = textSize * 0.14f
		unitPaint.textSize = textSize * 0.32f
		unitHaloPaint.textSize = unitPaint.textSize
		unitHaloPaint.strokeWidth = unitPaint.textSize * 0.16f

		val cx = w * 0.5f
		val baseline = h - dp(136f)
		val textWidth = textPaint.measureText(speedText)
		val badgePadX = dp(22f)
		val badgeTop = baseline - textSize * 0.82f
		val badgeBottom = baseline + unitPaint.textSize * 1.55f
		badgeBounds.set(
			cx - textWidth * 0.5f - badgePadX,
			badgeTop,
			cx + textWidth * 0.5f + badgePadX,
			badgeBottom
		)
		canvas.drawRoundRect(badgeBounds, dp(18f), dp(18f), badgePaint)
		canvas.drawText(speedText, cx, baseline, textHaloPaint)
		canvas.drawText(speedText, cx, baseline, textPaint)
		val unitY = baseline + unitPaint.textSize * 1.2f
		canvas.drawText("km/h", cx, unitY, unitHaloPaint)
		canvas.drawText("km/h", cx, unitY, unitPaint)
	}

	private fun zoneColor(zone: Zone): Int = when (zone) {
		Zone.GREEN -> COLOR_GREEN
		Zone.YELLOW -> COLOR_YELLOW
		Zone.ORANGE -> COLOR_ORANGE
		Zone.STRIPE -> COLOR_STRIPE
		Zone.RED -> COLOR_RED
	}

	private fun zoneAlpha(zone: Zone): Int = when (zone) {
		Zone.GREEN -> 230
		Zone.YELLOW -> 240
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
		const val HUD_TAG = "ev_speedometer_hud"
		private const val START_ANGLE = 150f
		private const val SWEEP_ANGLE = 240f
		private const val HALO_COLOR = 0xCC000000.toInt()
		private const val BADGE_COLOR = 0xCC000000.toInt()
		private const val TEXT_HALO = 0xFF000000.toInt()
		private const val TEXT_FILL = 0xFFFFFFFF.toInt()
		private const val COLOR_GREEN = 0xFF00E676.toInt()
		private const val COLOR_YELLOW = 0xFFFFEE58.toInt()
		private const val COLOR_ORANGE = 0xFFFF9100.toInt()
		private const val COLOR_STRIPE = 0xFFFF6D00.toInt()
		private const val COLOR_RED = 0xFFFF1744.toInt()
	}
}
