package net.osmand.plus.configmap.routes;

import android.graphics.Paint;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.slider.Slider;

import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.routepreparationmenu.cards.MapBaseCard;
import net.osmand.plus.settings.backend.preferences.CommonPreference;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.plus.utils.ColorUtilities;
import net.osmand.plus.utils.UiUtilities;

public class MtbRoutesDisplayCard extends MapBaseCard {

	private final RouteLayersHelper routeLayersHelper;

	public MtbRoutesDisplayCard(@NonNull MapActivity mapActivity) {
		super(mapActivity);
		routeLayersHelper = app.getRouteLayersHelper();
	}

	@Override
	public int getCardLayoutId() {
		return R.layout.mtb_routes_display_card;
	}

	@Override
	protected void updateContent() {
		setupSlider(
				view.findViewById(R.id.mtb_min_zoom_row),
				R.string.mtb_routes_min_zoom,
				R.string.mtb_routes_min_zoom_desc,
				routeLayersHelper.getMtbRoutesMinZoomPref(),
				4f,
				20f,
				12f
		);
		setupSlider(
				view.findViewById(R.id.mtb_icon_size_row),
				R.string.mtb_routes_icon_size,
				R.string.mtb_routes_icon_size_desc,
				routeLayersHelper.getMtbRoutesIconSizePref(),
				4f,
				32f,
				12f
		);
		setupSlider(
				view.findViewById(R.id.mtb_name_text_size_row),
				R.string.mtb_routes_name_text_size,
				R.string.mtb_routes_name_text_size_desc,
				routeLayersHelper.getMtbRoutesNameTextSizePref(),
				6f,
				27f,
				12f
		);
	}

	private void setupSlider(@NonNull View row,
	                         int titleId,
	                         int summaryId,
	                         @NonNull CommonPreference<String> pref,
	                         float min,
	                         float max,
	                         float defaultValue) {
		TextView title = row.findViewById(R.id.slider_title);
		TextView valueTv = row.findViewById(R.id.slider_value);
		Slider slider = row.findViewById(R.id.slider);

		title.setText(titleId);
		int color = appMode.getProfileColor(nightMode);
		UiUtilities.setupSlider(slider, nightMode, color);

		slider.setValueFrom(min);
		slider.setValueTo(max);
		slider.setStepSize(1f);

		float current = parsePref(pref.get(), defaultValue);
		current = Math.max(min, Math.min(max, current));
		slider.setValue(current);
		valueTv.setText(formatValue(titleId, (int) current));

		slider.clearOnChangeListeners();
		slider.addOnChangeListener((s, value, fromUser) -> {
			if (!fromUser) {
				return;
			}
			applyValue(valueTv, titleId, pref, Math.round(value));
		});
		valueTv.setPaintFlags(valueTv.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
		valueTv.setTextColor(ColorUtilities.getActiveColor(valueTv.getContext(), nightMode));
		valueTv.setOnClickListener(v -> askExactValue(valueTv, slider, titleId, pref, min, max));
		row.setContentDescription(getString(summaryId));
	}

	private void askExactValue(@NonNull TextView valueTv,
	                           @NonNull Slider slider,
	                           int titleId,
	                           @NonNull CommonPreference<String> pref,
	                           float min,
	                           float max) {
		android.content.Context themed = UiUtilities.getThemedContext(mapActivity, nightMode);
		int pad = AndroidUtils.dpToPx(themed, 16f);
		EditText input = new EditText(themed);
		input.setInputType(InputType.TYPE_CLASS_NUMBER);
		input.setSingleLine();
		input.setText(String.valueOf(Math.round(slider.getValue())));
		input.setSelection(input.getText().length());
		FrameLayout wrap = new FrameLayout(themed);
		wrap.setPadding(pad, pad / 2, pad, 0);
		wrap.addView(input);
		String message = app.getString(
				R.string.ev_exact_value_hint,
				formatValue(titleId, (int) min),
				formatValue(titleId, (int) max)
		);
		new AlertDialog.Builder(themed)
				.setTitle(titleId)
				.setMessage(message)
				.setView(wrap)
				.setNegativeButton(R.string.shared_string_cancel, null)
				.setPositiveButton(R.string.shared_string_apply, (dialog, which) -> {
					int parsed;
					try {
						parsed = Integer.parseInt(input.getText().toString().trim());
					} catch (NumberFormatException e) {
						parsed = Integer.MIN_VALUE;
					}
					if (parsed < min || parsed > max) {
						app.showToastMessage(app.getString(
								R.string.ev_exact_value_invalid,
								formatValue(titleId, (int) min),
								formatValue(titleId, (int) max)
						));
						return;
					}
					slider.setValue(parsed);
					applyValue(valueTv, titleId, pref, parsed);
				})
				.show();
	}

	private void applyValue(@NonNull TextView valueTv,
	                        int titleId,
	                        @NonNull CommonPreference<String> pref,
	                        int value) {
		valueTv.setText(formatValue(titleId, value));
		pref.set(String.valueOf(value));
		routeLayersHelper.refreshMapAfterMtbDisplayChange();
	}

	private float parsePref(@NonNull String raw, float defaultValue) {
		try {
			return Float.parseFloat(raw);
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	@NonNull
	private String formatValue(int titleId, int value) {
		if (titleId == R.string.mtb_routes_min_zoom) {
			return getString(R.string.mtb_routes_zoom_level_fmt, value);
		}
		return String.valueOf(value);
	}
}
