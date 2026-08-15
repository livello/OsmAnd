package net.osmand.plus.plugins.evbms

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import net.osmand.plus.R
import net.osmand.plus.views.mapwidgets.OutlinedTextContainer
import net.osmand.plus.views.mapwidgets.WidgetsPanel
import net.osmand.plus.views.mapwidgets.widgets.SimpleWidget

object EvWidgetChrome {

	@JvmStatic
	fun attach(
		widget: SimpleWidget,
		container: ViewGroup,
		panel: WidgetsPanel
	) {
		val view = widget.view
		applySideLayout(view, panel)
		if (!panel.isPanelVertical && container is LinearLayout) {
			val params = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT
			)
			params.gravity = sideGravity(panel)
			container.addView(view, params)
		} else {
			container.addView(view)
		}
	}

	@JvmStatic
	fun applySideLayout(root: View, panel: WidgetsPanel) {
		if (panel.isPanelVertical) {
			return
		}
		val gravity = sideGravity(panel)
		root.minimumWidth = 0
		val rootParams = LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.WRAP_CONTENT,
			ViewGroup.LayoutParams.WRAP_CONTENT
		)
		rootParams.gravity = gravity
		root.layoutParams = rootParams
		wrapToContent(root.findViewById(R.id.widget_bg), gravity)
		wrapToContent(root.findViewById(R.id.container), gravity)
		wrapToContent(root.findViewById(R.id.widget_bottom_layout), gravity)
		val textGravity = gravity or Gravity.CENTER_VERTICAL
		root.findViewById<OutlinedTextContainer>(R.id.widget_text)?.setGravity(textGravity)
		root.findViewById<OutlinedTextContainer>(R.id.widget_text_small)?.setGravity(textGravity)
	}

	@JvmStatic
	fun sideGravity(panel: WidgetsPanel): Int {
		return if (panel == WidgetsPanel.RIGHT) Gravity.END else Gravity.START
	}

	private fun wrapToContent(view: View?, gravity: Int) {
		if (view == null) {
			return
		}
		view.minimumWidth = 0
		when (val params = view.layoutParams) {
			is LinearLayout.LayoutParams -> {
				params.width = ViewGroup.LayoutParams.WRAP_CONTENT
				params.gravity = gravity
				view.layoutParams = params
			}
			is FrameLayout.LayoutParams -> {
				params.width = ViewGroup.LayoutParams.WRAP_CONTENT
				params.gravity = gravity
				view.layoutParams = params
			}
			null -> {
				view.layoutParams = ViewGroup.LayoutParams(
					ViewGroup.LayoutParams.WRAP_CONTENT,
					ViewGroup.LayoutParams.WRAP_CONTENT
				)
			}
			else -> {
				params.width = ViewGroup.LayoutParams.WRAP_CONTENT
				view.layoutParams = params
			}
		}
	}
}
