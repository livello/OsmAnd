package net.osmand.plus.track.cards;

import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import net.osmand.plus.R;
import net.osmand.plus.helpers.AndroidUiHelper;
import net.osmand.plus.routepreparationmenu.cards.BaseCard;
import net.osmand.plus.track.TrackDrawInfo;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.plus.utils.FontCache;
import net.osmand.plus.utils.OsmAndFormatter;
import net.osmand.plus.utils.OsmAndFormatterParams;
import net.osmand.plus.widgets.style.CustomTypefaceSpan;

public class GradientScaleCard extends BaseCard {

	private final TrackDrawInfo trackDrawInfo;

	public GradientScaleCard(@NonNull FragmentActivity activity, @NonNull TrackDrawInfo trackDrawInfo) {
		super(activity);
		this.trackDrawInfo = trackDrawInfo;
	}

	@Override
	public int getCardLayoutId() {
		return R.layout.bottom_sheet_item_with_right_descr;
	}

	@Override
	public void updateContent() {
		AndroidUiHelper.updateVisibility(view.findViewById(R.id.icon), false);

		TextView titleView = view.findViewById(R.id.title);
		titleView.setText(R.string.ev_bms_gradient_scale);

		int secondaryTextColor = AndroidUtils.getColorFromAttr(view.getContext(), R.attr.active_color_basic);
		SpannableStringBuilder summary = new SpannableStringBuilder(getSummary());
		summary.setSpan(new ForegroundColorSpan(secondaryTextColor), 0, summary.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
		summary.setSpan(new CustomTypefaceSpan(FontCache.getMediumFont()), 0, summary.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

		TextView descriptionView = view.findViewById(R.id.description);
		descriptionView.setText(summary);

		view.setOnClickListener(v -> notifyCardPressed());
	}

	@NonNull
	private String getSummary() {
		String consumptionUnit = app.getString(R.string.ev_bms_unit_wh_per_km);
		String consumption = app.getString(R.string.ev_bms_gradient_scale_range,
				String.valueOf((int) Math.round(trackDrawInfo.getConsumptionMinWhKm())),
				String.valueOf((int) Math.round(trackDrawInfo.getConsumptionMaxWhKm())),
				consumptionUnit);
		String window = OsmAndFormatter.getFormattedDistance((float) trackDrawInfo.getConsumptionWindowM(),
				app, OsmAndFormatterParams.NO_TRAILING_ZEROS);
		String speedUnit = app.getString(R.string.km_h);
		String speed = app.getString(R.string.ev_bms_gradient_scale_range,
				String.valueOf((int) Math.round(trackDrawInfo.getSpeedMinKmh())),
				String.valueOf((int) Math.round(trackDrawInfo.getSpeedMaxKmh())),
				speedUnit);
		return app.getString(R.string.ltr_or_rtl_combine_via_comma,
				app.getString(R.string.ltr_or_rtl_combine_via_comma, consumption, window),
				speed);
	}
}
