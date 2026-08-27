package net.osmand.plus.plugins.torrentmaps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Compact rectangular progress frame: left-to-right fill behind children + stroke border.
 */
class ProgressNameFrame @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

	private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.FILL
		color = 0x8842A5F5.toInt()
	}
	private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = resources.displayMetrics.density * 1.5f
		color = 0xFF42A5F5.toInt()
	}
	private val rect = RectF()
	private var progressPercent: Int = 0

	init {
		setWillNotDraw(false)
		val pad = (resources.displayMetrics.density * 4f).toInt()
		setPadding(pad, pad / 2, pad, pad / 2)
	}

	fun setProgressPercent(percent: Int) {
		val clamped = percent.coerceIn(0, 100)
		if (clamped != progressPercent) {
			progressPercent = clamped
			invalidate()
		}
		for (i in 0 until childCount) {
			val child = getChildAt(i)
			if (child is ProgressGlowTextView) {
				child.setProgressPercent(clamped)
			}
		}
	}

	fun setAccentColor(color: Int) {
		fillPaint.color = (color and 0x00FFFFFF) or 0x33000000
		borderPaint.color = color
		invalidate()
	}

	override fun dispatchDraw(canvas: Canvas) {
		val inset = borderPaint.strokeWidth / 2f
		rect.set(inset, inset, width - inset, height - inset)
		val radius = resources.displayMetrics.density * 3f
		if (progressPercent > 0) {
			val fillW = rect.width() * progressPercent / 100f
			canvas.save()
			canvas.clipRect(rect.left, rect.top, rect.left + fillW, rect.bottom)
			canvas.drawRoundRect(rect, radius, radius, fillPaint)
			canvas.restore()
		}
		canvas.drawRoundRect(rect, radius, radius, borderPaint)
		super.dispatchDraw(canvas)
	}
}
