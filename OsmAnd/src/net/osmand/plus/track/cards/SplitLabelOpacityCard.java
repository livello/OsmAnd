package net.osmand.plus.track.cards;

import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.slider.Slider;

import net.osmand.plus.R;
import net.osmand.plus.routepreparationmenu.cards.BaseCard;
import net.osmand.plus.track.TrackDrawInfo;
import net.osmand.plus.utils.UiUtilities;

public class SplitLabelOpacityCard extends BaseCard {

	private final TrackDrawInfo trackDrawInfo;

	public SplitLabelOpacityCard(@NonNull FragmentActivity activity, @NonNull TrackDrawInfo trackDrawInfo) {
		super(activity);
		this.trackDrawInfo = trackDrawInfo;
	}

	@Override
	public int getCardLayoutId() {
		return R.layout.bottom_sheet_item_slider_with_two_text;
	}

	@Override
	public void updateContent() {
		TextView title = view.findViewById(android.R.id.title);
		title.setText(R.string.track_split_label_opacity);

		TextView summary = view.findViewById(android.R.id.summary);
		Slider slider = view.findViewById(R.id.slider);
		UiUtilities.setupSlider(slider, nightMode, null, true);
		slider.setValueFrom(0);
		slider.setValueTo(100);
		slider.setStepSize(5);
		slider.clearOnChangeListeners();
		slider.setValue(trackDrawInfo.getSplitLabelOpacity());
		summary.setText(formatOpacity(trackDrawInfo.getSplitLabelOpacity()));
		slider.addOnChangeListener((changedSlider, value, fromUser) -> {
			trackDrawInfo.setSplitLabelOpacity(Math.round(value));
			summary.setText(formatOpacity(trackDrawInfo.getSplitLabelOpacity()));
			if (fromUser) {
				notifyCardPressed();
			}
		});
	}

	@NonNull
	private String formatOpacity(int percent) {
		return percent + "%";
	}
}
