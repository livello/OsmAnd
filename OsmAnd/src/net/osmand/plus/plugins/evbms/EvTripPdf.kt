package net.osmand.plus.plugins.evbms

import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import net.osmand.plus.R
import net.osmand.plus.utils.AndroidUtils
import java.io.File
import java.io.FileOutputStream

object EvTripPdf {

	fun share(
		activity: android.app.Activity,
		title: String,
		lines: List<String>,
		fileName: String = "trip.pdf"
	) {
		val dir = File(activity.cacheDir, "share")
		dir.mkdirs()
		val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "trip.pdf" }
		val file = File(dir, safe)
		write(file, title, lines)
		val uri = AndroidUtils.getUriForFile(activity, file)
		val send = Intent(Intent.ACTION_SEND).apply {
			type = "application/pdf"
			putExtra(Intent.EXTRA_STREAM, uri)
			putExtra(Intent.EXTRA_SUBJECT, title)
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			clipData = android.content.ClipData.newRawUri(title, uri)
		}
		activity.startActivity(Intent.createChooser(send, activity.getString(R.string.shared_string_share)))
	}

	private fun write(file: File, title: String, lines: List<String>) {
		val doc = PdfDocument()
		val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.BLACK
			textSize = 18f
		}
		val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.BLACK
			textSize = 13f
		}
		val maxWidth = PAGE_WIDTH - MARGIN * 2f
		var pageNumber = 1
		var page = doc.startPage(pageInfo(pageNumber))
		var canvas = page.canvas
		var y = 48f
		fun nextPage() {
			doc.finishPage(page)
			pageNumber++
			page = doc.startPage(pageInfo(pageNumber))
			canvas = page.canvas
			y = 48f
		}
		for (chunk in wrap(title, titlePaint, maxWidth)) {
			if (y > MAX_Y) {
				nextPage()
			}
			canvas.drawText(chunk, MARGIN, y, titlePaint)
			y += 24f
		}
		y += 8f
		for (line in lines) {
			val parts = if (line.isEmpty()) listOf("") else wrap(line, bodyPaint, maxWidth)
			for (part in parts) {
				if (y > MAX_Y) {
					nextPage()
				}
				if (part.isNotEmpty()) {
					canvas.drawText(part, MARGIN, y, bodyPaint)
				}
				y += 22f
			}
		}
		doc.finishPage(page)
		FileOutputStream(file).use { doc.writeTo(it) }
		doc.close()
	}

	private fun pageInfo(number: Int): PdfDocument.PageInfo =
		PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, number).create()

	private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
		if (text.isEmpty() || paint.measureText(text) <= maxWidth) {
			return listOf(text)
		}
		val out = ArrayList<String>()
		var rest = text
		while (rest.isNotEmpty()) {
			var end = rest.length
			while (end > 1 && paint.measureText(rest.substring(0, end)) > maxWidth) {
				val space = rest.lastIndexOf(' ', end - 1)
				end = if (space > 0) space else end - 1
			}
			val chunk = rest.substring(0, end).trim()
			if (chunk.isEmpty()) {
				break
			}
			out.add(chunk)
			rest = rest.substring(end).trim()
		}
		return if (out.isEmpty()) listOf(text) else out
	}

	private const val PAGE_WIDTH = 595
	private const val PAGE_HEIGHT = 842
	private const val MARGIN = 40f
	private const val MAX_Y = 800f
}
