package net.osmand.plus.track.cards;

import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.slider.Slider;

import net.osmand.plus.R;
import net.osmand.plus.helpers.AndroidUiHelper;
import net.osmand.plus.routepreparationmenu.cards.BaseCard;
import net.osmand.plus.track.GpxSplitType;
import net.osmand.plus.track.TrackDrawInfo;
import net.osmand.plus.utils.UiUtilities;

public class ConsumptionSplitKmCircleSizeCard extends BaseCard {

	public static final int MIN_SCALE_PERCENT = 50;
	public static final int MAX_SCALE_PERCENT = 200;

	private final TrackDrawInfo trackDrawInfo;

	public ConsumptionSplitKmCircleSizeCard(@NonNull FragmentActivity activity, @NonNull TrackDrawInfo trackDrawInfo) {
		super(activity);
		this.trackDrawInfo = trackDrawInfo;
	}

	@Override
	public int getCardLayoutId() {
		return R.layout.bottom_sheet_item_slider_with_two_text;
	}

	@Override
	public void updateContent() {
		boolean visible = trackDrawInfo.getSplitType() == GpxSplitType.CONSUMPTION.getType();
		AndroidUiHelper.updateVisibility(view, visible);
		if (!visible) {
			return;
		}

		TextView title = view.findViewById(android.R.id.title);
		title.setText(R.string.ev_bms_consumption_split_km_circle_size);

		TextView summary = view.findViewById(android.R.id.summary);
		Slider slider = view.findViewById(R.id.slider);
		UiUtilities.setupSlider(slider, nightMode, null, true);
		slider.setValueFrom(MIN_SCALE_PERCENT);
		slider.setValueTo(MAX_SCALE_PERCENT);
		slider.setStepSize(5);
		slider.clearOnChangeListeners();
		slider.setValue(trackDrawInfo.getConsumptionSplitKmCircleScalePercent());
		slider.setEnabled(trackDrawInfo.isConsumptionSplitShowKmCircles());
		summary.setText(formatScale(trackDrawInfo.getConsumptionSplitKmCircleScalePercent()));
		slider.addOnChangeListener((changedSlider, value, fromUser) -> {
			trackDrawInfo.setConsumptionSplitKmCircleScalePercent(Math.round(value));
			summary.setText(formatScale(trackDrawInfo.getConsumptionSplitKmCircleScalePercent()));
			if (fromUser) {
				app.getSettings().TRACK_CONSUMPTION_SPLIT_KM_CIRCLE_SCALE_PERCENT
						.set(trackDrawInfo.getConsumptionSplitKmCircleScalePercent());
				notifyCardPressed();
			}
		});
	}

	@NonNull
	private String formatScale(int percent) {
		return percent + "%";
	}
}
