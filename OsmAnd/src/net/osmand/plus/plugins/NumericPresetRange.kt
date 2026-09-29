package net.osmand.plus.plugins

import android.app.Activity
import android.text.InputType
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import net.osmand.plus.R
import net.osmand.plus.utils.AndroidUtils
import net.osmand.plus.utils.UiUtilities

/**
 * Widens a sorted preset list by about 100% (half of the current span on each side)
 * and clamps every value to [hardMin, hardMax].
 */
object NumericPresetRange {

	fun expand(presets: IntArray, hardMin: Int, hardMax: Int): IntArray {
		if (hardMax < hardMin) {
			return intArrayOf()
		}
		val sorted = presets.filter { it in hardMin..hardMax }.distinct().sorted()
		if (sorted.size < 2) {
			return sorted.toIntArray()
		}
		val min = sorted.first()
		val max = sorted.last()
		val span = max - min
		val extra = (span + 1) / 2
		if (extra <= 0) {
			return sorted.toIntArray()
		}
		val targetMin = (min - extra).coerceIn(hardMin, hardMax)
		val targetMax = (max + extra).coerceIn(hardMin, hardMax)
		val gaps = sorted.zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
		val step = if (gaps.isEmpty()) 1 else gaps[gaps.size / 2].coerceAtLeast(1)
		val out = sorted.toMutableSet()
		var down = min
		while (down - step >= targetMin) {
			down -= step
			out.add(down)
		}
		val lowest = out.minOrNull() ?: min
		if (targetMin < lowest) {
			out.add(targetMin)
		}
		var up = max
		while (up + step <= targetMax) {
			up += step
			out.add(up)
		}
		val highest = out.maxOrNull() ?: max
		if (targetMax > highest) {
			out.add(targetMax)
		}
		return out.filter { it in hardMin..hardMax }.sorted().toIntArray()
	}
}

object ExactValuePrompt {

	fun show(
		activity: Activity,
		nightMode: Boolean,
		title: CharSequence,
		message: CharSequence,
		initial: String,
		inputType: Int = InputType.TYPE_CLASS_NUMBER,
		onApply: (String) -> Boolean,
	) {
		val themed = UiUtilities.getThemedContext(activity, nightMode)
		val pad = AndroidUtils.dpToPx(themed, 16f)
		val input = EditText(themed).apply {
			setText(initial)
			setSelection(text.length)
			this.inputType = inputType
			setSingleLine()
		}
		val wrap = FrameLayout(themed).apply {
			setPadding(pad, pad / 2, pad, 0)
			addView(
				input,
				FrameLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT,
				),
			)
		}
		val dialog = AlertDialog.Builder(themed)
			.setTitle(title)
			.setMessage(message)
			.setView(wrap)
			.setNegativeButton(R.string.shared_string_cancel, null)
			.setPositiveButton(R.string.shared_string_apply, null)
			.create()
		dialog.setOnShowListener {
			input.requestFocus()
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
				if (onApply(input.text?.toString().orEmpty())) {
					dialog.dismiss()
				}
			}
		}
		dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
		dialog.show()
	}
}
