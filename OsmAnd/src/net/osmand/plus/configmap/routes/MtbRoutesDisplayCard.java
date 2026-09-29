package net.osmand.plus.configmap.routes;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.slider.Slider;

import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.routepreparationmenu.cards.MapBaseCard;
import net.osmand.plus.settings.backend.preferences.CommonPreference;
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
				8f,
				16f,
				12f
		);
		setupSlider(
				view.findViewById(R.id.mtb_icon_size_row),
				R.string.mtb_routes_icon_size,
				R.string.mtb_routes_icon_size_desc,
				routeLayersHelper.getMtbRoutesIconSizePref(),
				8f,
				24f,
				12f
		);
		setupSlider(
				view.findViewById(R.id.mtb_name_text_size_row),
				R.string.mtb_routes_name_text_size,
				R.string.mtb_routes_name_text_size_desc,
				routeLayersHelper.getMtbRoutesNameTextSizePref(),
				9f,
				21f,
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
			int intVal = Math.round(value);
			valueTv.setText(formatValue(titleId, intVal));
			pref.set(String.valueOf(intVal));
			routeLayersHelper.refreshMapAfterMtbDisplayChange();
		});
		row.setContentDescription(getString(summaryId));
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
