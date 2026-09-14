package net.osmand.plus.track.fragments;

import static net.osmand.plus.utils.OsmAndFormatterParams.NO_TRAILING_ZEROS;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.slider.RangeSlider;
import com.google.android.material.slider.Slider;

import net.osmand.plus.R;
import net.osmand.plus.base.MenuBottomSheetDialogFragment;
import net.osmand.plus.base.bottomsheetmenu.SimpleBottomSheetItem;
import net.osmand.plus.base.bottomsheetmenu.simpleitems.TitleItem;
import net.osmand.plus.track.TrackDrawInfo;
import net.osmand.plus.track.TrackGradientHelper;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.plus.utils.OsmAndFormatter;
import net.osmand.plus.utils.UiUtilities;
import net.osmand.shared.gpx.EvConsumptionScale;

import java.util.List;

public class GradientScaleBottomSheet extends MenuBottomSheetDialogFragment {

	public static final String TAG = GradientScaleBottomSheet.class.getSimpleName();

	private static final String WINDOW_INDEX_KEY = "window_index";
	private static final String CONSUMPTION_MIN_KEY = "consumption_min";
	private static final String CONSUMPTION_MAX_KEY = "consumption_max";
	private static final String SPEED_MIN_KEY = "speed_min";
	private static final String SPEED_MAX_KEY = "speed_max";

	private static final float CONSUMPTION_FROM = 0f;
	private static final float CONSUMPTION_TO = 400f;
	private static final float CONSUMPTION_STEP = 5f;
	private static final float SPEED_FROM = 0f;
	private static final float SPEED_TO = 120f;
	private static final float SPEED_STEP = 5f;

	private TrackDrawInfo trackDrawInfo;
	private int windowIndex;
	private float consumptionMin;
	private float consumptionMax;
	private float speedMin;
	private float speedMax;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (getTargetFragment() instanceof TrackAppearanceFragment fragment) {
			trackDrawInfo = fragment.getTrackDrawInfo();
		}
		if (savedInstanceState != null) {
			windowIndex = savedInstanceState.getInt(WINDOW_INDEX_KEY);
			consumptionMin = savedInstanceState.getFloat(CONSUMPTION_MIN_KEY);
			consumptionMax = savedInstanceState.getFloat(CONSUMPTION_MAX_KEY);
			speedMin = savedInstanceState.getFloat(SPEED_MIN_KEY);
			speedMax = savedInstanceState.getFloat(SPEED_MAX_KEY);
		} else if (trackDrawInfo != null) {
			windowIndex = TrackGradientHelper.windowIndex(trackDrawInfo.getConsumptionWindowM());
			consumptionMin = snap(trackDrawInfo.getConsumptionMinWhKm(), CONSUMPTION_FROM, CONSUMPTION_TO, CONSUMPTION_STEP);
			consumptionMax = snap(trackDrawInfo.getConsumptionMaxWhKm(), CONSUMPTION_FROM, CONSUMPTION_TO, CONSUMPTION_STEP);
			speedMin = snap(trackDrawInfo.getSpeedMinKmh(), SPEED_FROM, SPEED_TO, SPEED_STEP);
			speedMax = snap(trackDrawInfo.getSpeedMaxKmh(), SPEED_FROM, SPEED_TO, SPEED_STEP);
			if (consumptionMax <= consumptionMin) {
				consumptionMax = Math.min(CONSUMPTION_TO, consumptionMin + CONSUMPTION_STEP);
			}
			if (speedMax <= speedMin) {
				speedMax = Math.min(SPEED_TO, speedMin + SPEED_STEP);
			}
		} else {
			windowIndex = TrackGradientHelper.windowIndex(EvConsumptionScale.DEFAULT_WINDOW_M);
			consumptionMin = (float) EvConsumptionScale.MIN_WH_KM;
			consumptionMax = (float) EvConsumptionScale.MAX_WH_KM;
			speedMin = 0f;
			speedMax = (float) EvConsumptionScale.DEFAULT_SPEED_MAX_KMH;
		}
	}

	@Override
	public void createMenuItems(Bundle savedInstanceState) {
		items.add(new TitleItem(getString(R.string.ev_bms_gradient_scale)));

		View view = inflate(R.layout.track_gradient_scale);
		setupWindowSlider(view);
		setupConsumptionSlider(view);
		setupSpeedSlider(view);
		items.add(new SimpleBottomSheetItem.Builder().setCustomView(view).create());
	}

	private void setupWindowSlider(@NonNull View view) {
		Slider slider = view.findViewById(R.id.window_slider);
		TextView valueView = view.findViewById(R.id.window_value);
		TextView minView = view.findViewById(R.id.window_value_min);
		TextView maxView = view.findViewById(R.id.window_value_max);
		UiUtilities.setupSlider(slider, nightMode, null, true);
		slider.setValueFrom(0);
		slider.setValueTo(TrackGradientHelper.WINDOW_METERS.length - 1);
		slider.setValue(windowIndex);
		minView.setText(formatDistance(TrackGradientHelper.WINDOW_METERS[0]));
		maxView.setText(formatDistance(TrackGradientHelper.WINDOW_METERS[TrackGradientHelper.WINDOW_METERS.length - 1]));
		valueView.setText(formatDistance(TrackGradientHelper.WINDOW_METERS[windowIndex]));
		slider.addOnChangeListener((changedSlider, value, fromUser) -> {
			windowIndex = (int) value;
			valueView.setText(formatDistance(TrackGradientHelper.WINDOW_METERS[windowIndex]));
		});
	}

	private void setupConsumptionSlider(@NonNull View view) {
		RangeSlider slider = view.findViewById(R.id.consumption_slider);
		TextView minView = view.findViewById(R.id.consumption_value_min);
		TextView maxView = view.findViewById(R.id.consumption_value_max);
		UiUtilities.setupSlider(slider, nightMode, null, true);
		slider.setValueFrom(CONSUMPTION_FROM);
		slider.setValueTo(CONSUMPTION_TO);
		slider.setValues(consumptionMin, consumptionMax);
		minView.setText(formatConsumption(consumptionMin));
		maxView.setText(formatConsumption(consumptionMax));
		slider.addOnChangeListener((changedSlider, value, fromUser) -> {
			List<Float> values = changedSlider.getValues();
			if (values.size() >= 2) {
				consumptionMin = values.get(0);
				consumptionMax = values.get(1);
				minView.setText(formatConsumption(consumptionMin));
				maxView.setText(formatConsumption(consumptionMax));
			}
		});
	}

	private void setupSpeedSlider(@NonNull View view) {
		RangeSlider slider = view.findViewById(R.id.speed_slider);
		TextView minView = view.findViewById(R.id.speed_value_min);
		TextView maxView = view.findViewById(R.id.speed_value_max);
		UiUtilities.setupSlider(slider, nightMode, null, true);
		slider.setValueFrom(SPEED_FROM);
		slider.setValueTo(SPEED_TO);
		slider.setValues(speedMin, speedMax);
		minView.setText(formatSpeed(speedMin));
		maxView.setText(formatSpeed(speedMax));
		slider.addOnChangeListener((changedSlider, value, fromUser) -> {
			List<Float> values = changedSlider.getValues();
			if (values.size() >= 2) {
				speedMin = values.get(0);
				speedMax = values.get(1);
				minView.setText(formatSpeed(speedMin));
				maxView.setText(formatSpeed(speedMax));
			}
		});
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putInt(WINDOW_INDEX_KEY, windowIndex);
		outState.putFloat(CONSUMPTION_MIN_KEY, consumptionMin);
		outState.putFloat(CONSUMPTION_MAX_KEY, consumptionMax);
		outState.putFloat(SPEED_MIN_KEY, speedMin);
		outState.putFloat(SPEED_MAX_KEY, speedMax);
	}

	@Override
	protected int getRightBottomButtonTextId() {
		return R.string.shared_string_apply;
	}

	@Override
	protected void onRightBottomButtonClick() {
		if (trackDrawInfo != null) {
			trackDrawInfo.setConsumptionWindowM(TrackGradientHelper.WINDOW_METERS[windowIndex]);
			trackDrawInfo.setConsumptionMinWhKm(consumptionMin);
			trackDrawInfo.setConsumptionMaxWhKm(Math.max(consumptionMin + CONSUMPTION_STEP, consumptionMax));
			trackDrawInfo.setSpeedMinKmh(speedMin);
			trackDrawInfo.setSpeedMaxKmh(Math.max(speedMin + SPEED_STEP, speedMax));
		}
		if (getTargetFragment() instanceof TrackAppearanceFragment fragment) {
			fragment.applyGradientScale();
		}
		dismiss();
	}

	@NonNull
	private String formatDistance(float meters) {
		return OsmAndFormatter.getFormattedDistance(meters, app, NO_TRAILING_ZEROS);
	}

	@NonNull
	private String formatConsumption(float whKm) {
		return String.valueOf((int) whKm);
	}

	@NonNull
	private String formatSpeed(float kmh) {
		return String.valueOf((int) kmh);
	}

	private static float snap(double value, float from, float to, float step) {
		float clamped = (float) Math.max(from, Math.min(to, value));
		return from + Math.round((clamped - from) / step) * step;
	}

	public static void showInstance(@NonNull FragmentManager manager, @Nullable Fragment target) {
		if (AndroidUtils.isFragmentCanBeAdded(manager, TAG)) {
			GradientScaleBottomSheet fragment = new GradientScaleBottomSheet();
			fragment.setTargetFragment(target, 0);
			fragment.show(manager, TAG);
		}
	}
}
