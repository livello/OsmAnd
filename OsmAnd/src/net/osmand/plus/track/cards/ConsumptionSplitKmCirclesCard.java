package net.osmand.plus.track.cards;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import net.osmand.plus.R;
import net.osmand.plus.helpers.AndroidUiHelper;
import net.osmand.plus.track.GpxSplitType;
import net.osmand.plus.track.TrackDrawInfo;

public class ConsumptionSplitKmCirclesCard extends BaseSwitchCard {

	private final TrackDrawInfo trackDrawInfo;

	public ConsumptionSplitKmCirclesCard(@NonNull FragmentActivity activity, @NonNull TrackDrawInfo trackDrawInfo) {
		super(activity);
		this.trackDrawInfo = trackDrawInfo;
	}

	@Override
	protected void updateContent() {
		super.updateContent();
		boolean visible = trackDrawInfo.getSplitType() == GpxSplitType.CONSUMPTION.getType();
		AndroidUiHelper.updateVisibility(view, visible);
	}

	@Override
	int getTitleId() {
		return R.string.ev_bms_consumption_split_km_circles;
	}

	@Override
	protected boolean getChecked() {
		return trackDrawInfo.isConsumptionSplitShowKmCircles();
	}

	@Override
	protected void setChecked(boolean checked) {
		trackDrawInfo.setConsumptionSplitShowKmCircles(checked);
		app.getSettings().TRACK_CONSUMPTION_SPLIT_SHOW_KM_CIRCLES.set(checked);
	}
}
