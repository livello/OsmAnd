package net.osmand.shared.filters

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import net.osmand.shared.gpx.TrackItem
import net.osmand.shared.util.KCollectionUtils

@Serializable
class SmartFolder(@Serializable var folderName: String) : TracksGroup, ComparableTracksGroup {

	@Transient
	private var trackItems: MutableList<TrackItem>? = null

	constructor() : this("") {
	}

	@Serializable
	var creationTime = 0L

	@Serializable
	var filters: MutableList<BaseTrackFilter>? = null

	@Transient
	private var folderAnalysis: TrackFolderAnalysis? = null

	override fun getName(): String {
		return folderName
	}

	override fun getTrackItems(): MutableList<TrackItem> {
		var trackItems:MutableList<TrackItem>? = this.trackItems
		if(trackItems == null) {
			trackItems = ArrayList()
			this.trackItems = trackItems
		}
		return trackItems
	}

	fun addTrackItem(trackItem: TrackItem) {
		if (!getTrackItems().contains(trackItem)) {
			trackItems = KCollectionUtils.addToList(getTrackItems(), trackItem)
			folderAnalysis = null
		}
	}

	override fun getFolderAnalysis(): TrackFolderAnalysis {
		var analysis = folderAnalysis
		if (analysis == null) {
			analysis = TrackFolderAnalysis(this)
			folderAnalysis = analysis
		}
		return analysis
	}

	override fun getDirName(): String {
		return folderName
	}

	override fun lastModified(): Long {
		return creationTime
	}

	fun resetItems() {
		trackItems = ArrayList()
		folderAnalysis = null
	}
}