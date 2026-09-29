package net.osmand.plus.plugins.voicegps

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import net.osmand.plus.R
import net.osmand.plus.utils.AndroidUtils
import net.osmand.plus.widgets.TextViewEx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VoiceGpsNotesFragment : Fragment() {

	private lateinit var recycler: RecyclerView
	private lateinit var emptyView: TextView
	private lateinit var deleteBar: View
	private lateinit var selectedCount: TextView
	private lateinit var deleteButton: MaterialButton

	private val selected = LinkedHashSet<EvVoiceGpxStore.VoiceNoteEntry>()
	private var rows: List<Row> = emptyList()

	private sealed class Row {
		data class Header(val label: String) : Row()
		data class Note(val entry: EvVoiceGpxStore.VoiceNoteEntry) : Row()
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View {
		val view = inflater.inflate(R.layout.voice_gps_notes_fragment, container, false)
		recycler = view.findViewById(R.id.recycler)
		emptyView = view.findViewById(R.id.empty_view)
		deleteBar = view.findViewById(R.id.delete_bar)
		selectedCount = view.findViewById(R.id.selected_count)
		deleteButton = view.findViewById(R.id.delete_button)
		recycler.layoutManager = LinearLayoutManager(requireContext())
		recycler.adapter = NotesAdapter()
		deleteButton.setText(R.string.shared_string_delete)
		deleteButton.setOnClickListener { confirmDelete() }
		reload()
		return view
	}

	fun reload() {
		val app = AndroidUtils.getApp(requireContext()) ?: return
		val notes = EvVoiceGpxStore.listVoiceNotes(app)
		val dayFmt = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
		val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
		val built = ArrayList<Row>()
		var lastDay: String? = null
		for (entry in notes) {
			val day = dayFmt.format(Date(entry.timeMs))
			if (day != lastDay) {
				built.add(Row.Header(day))
				lastDay = day
			}
			built.add(Row.Note(entry))
		}
		rows = built
		val favStillPresent = notes.map { it.favorite }.toSet()
		selected.removeIf { it.favorite !in favStillPresent }
		val byFav = notes.associateBy { it.favorite }
		val refreshed = LinkedHashSet<EvVoiceGpxStore.VoiceNoteEntry>()
		for (entry in selected) {
			byFav[entry.favorite]?.let { refreshed.add(it) }
		}
		selected.clear()
		selected.addAll(refreshed)
		(recycler.adapter as? NotesAdapter)?.notifyDataSetChanged()
		updateEmpty(notes.isEmpty())
		updateDeleteBar()
	}

	private fun updateEmpty(empty: Boolean) {
		emptyView.visibility = if (empty) View.VISIBLE else View.GONE
		recycler.visibility = if (empty) View.GONE else View.VISIBLE
	}

	private fun updateDeleteBar() {
		val count = selected.size
		deleteBar.visibility = if (count > 0) View.VISIBLE else View.GONE
		selectedCount.text = getString(R.string.shared_string_selected) + ": $count"
	}

	private fun confirmDelete() {
		if (selected.isEmpty()) {
			return
		}
		val count = selected.size
		AlertDialog.Builder(requireContext())
			.setMessage(getString(R.string.voice_gps_notes_delete_confirm, count))
			.setPositiveButton(R.string.shared_string_delete) { _, _ ->
				val app = AndroidUtils.getApp(requireContext()) ?: return@setPositiveButton
				val removed = EvVoiceGpxStore.deleteVoiceNotes(app, selected.toList())
				selected.clear()
				reload()
				app.showShortToastMessage(getString(R.string.voice_gps_notes_deleted, removed))
			}
			.setNegativeButton(R.string.shared_string_cancel, null)
			.show()
	}

	private inner class NotesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

		override fun getItemViewType(position: Int): Int =
			when (rows[position]) {
				is Row.Header -> 0
				is Row.Note -> 1
			}

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
			val inflater = LayoutInflater.from(parent.context)
			return if (viewType == 0) {
				val tv = TextViewEx(parent.context)
				val pad = AndroidUtils.dpToPx(parent.context, 16f)
				tv.setPadding(pad, pad, pad, AndroidUtils.dpToPx(parent.context, 4f))
				tv.setTextColor(AndroidUtils.getColorFromAttr(parent.context, android.R.attr.textColorPrimary))
				HeaderHolder(tv)
			} else {
				NoteHolder(inflater.inflate(R.layout.voice_gps_note_list_item, parent, false))
			}
		}

		override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
			when (val row = rows[position]) {
				is Row.Header -> (holder as HeaderHolder).title.text = row.label
				is Row.Note -> (holder as NoteHolder).bind(row.entry)
			}
		}

		override fun getItemCount(): Int = rows.size
	}

	private class HeaderHolder(val title: TextView) : RecyclerView.ViewHolder(title)

	private inner class NoteHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
		private val check: AppCompatCheckBox = itemView.findViewById(R.id.checkbox)
		private val title: TextView = itemView.findViewById(R.id.title)
		private val subtitle: TextView = itemView.findViewById(R.id.subtitle)

		fun bind(entry: EvVoiceGpxStore.VoiceNoteEntry) {
			val fav = entry.favorite
			title.text = fav.name ?: fav.getName()
			val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
			val desc = fav.description?.takeIf { it.isNotBlank() } ?: ""
			subtitle.text = if (desc.isEmpty()) {
				timeFmt.format(Date(entry.timeMs))
			} else {
				"${timeFmt.format(Date(entry.timeMs))} — $desc"
			}
			check.setOnCheckedChangeListener(null)
			check.isChecked = selected.contains(entry)
			check.setOnCheckedChangeListener { _, checked ->
				if (checked) {
					selected.add(entry)
				} else {
					selected.remove(entry)
				}
				updateDeleteBar()
			}
			itemView.setOnClickListener {
				check.isChecked = !check.isChecked
			}
		}
	}
}
