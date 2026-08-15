package net.osmand.plus.plugins.evbms

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import net.osmand.plus.R
import net.osmand.plus.views.mapwidgets.WidgetsPanel

object EvWidgetLinkFrame {

	private const val GREEN = 0xFF22C55E.toInt()
	private const val RED = 0xFFEF4444.toInt()
	private const val STRIP_PX = 5

	fun apply(root: View?, connected: Boolean, panel: WidgetsPanel?) {
		val target = root?.findViewById<View>(R.id.widget_bg) ?: root ?: return
		val atEnd = panel != WidgetsPanel.LEFT
		if (atEnd) {
			target.setPadding(target.paddingLeft, target.paddingTop, STRIP_PX, target.paddingBottom)
		} else {
			target.setPadding(STRIP_PX, target.paddingTop, target.paddingRight, target.paddingBottom)
		}
		target.foreground = EdgeStripDrawable(
			if (connected) GREEN else RED,
			STRIP_PX,
			atEnd
		)
	}

	private class EdgeStripDrawable(
		private val color: Int,
		private val thicknessPx: Int,
		private val atEnd: Boolean
	) : Drawable() {

		private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			this.color = color
			style = Paint.Style.FILL
		}

		override fun draw(canvas: Canvas) {
			val b = bounds
			if (b.width() <= 0 || b.height() <= 0) {
				return
			}
			val width = thicknessPx.coerceAtMost(b.width())
			val left = if (atEnd) b.right - width else b.left
			canvas.drawRect(
				left.toFloat(),
				b.top.toFloat(),
				(left + width).toFloat(),
				b.bottom.toFloat(),
				paint
			)
		}

		override fun setAlpha(alpha: Int) {
			paint.alpha = alpha
		}

		override fun setColorFilter(colorFilter: ColorFilter?) {
			paint.colorFilter = colorFilter
		}

		@Deprecated("Deprecated in Java")
		override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
	}
}
