package net.osmand.plus.plugins.torrentmaps

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * Draws the map name twice: normal color, then a clipped glow over the filled
 * progress region so the letters light up where the rectangle fill has reached.
 */
class ProgressGlowTextView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

	private var progressPercent: Int = 0
	private val glowRadius = resources.displayMetrics.density * 10f
	private val glowColor = 0xFFB3E5FC.toInt()
	private val glowTextColor = 0xFFE1F5FE.toInt()

	init {
		setLayerType(LAYER_TYPE_SOFTWARE, null)
	}

	fun setProgressPercent(percent: Int) {
		val clamped = percent.coerceIn(0, 100)
		if (clamped != progressPercent) {
			progressPercent = clamped
			invalidate()
		}
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		val textLayout = layout ?: return
		if (progressPercent <= 0 || text.isNullOrEmpty()) return
		val fillRight = width * progressPercent / 100f
		if (fillRight <= 0f) return
		val paint = paint
		val oldColor = paint.color
		canvas.save()
		canvas.clipRect(0f, 0f, fillRight, height.toFloat())
		canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
		paint.setShadowLayer(glowRadius, 0f, 0f, glowColor)
		paint.color = glowTextColor
		textLayout.draw(canvas)
		paint.clearShadowLayer()
		paint.color = oldColor
		canvas.restore()
	}
}
