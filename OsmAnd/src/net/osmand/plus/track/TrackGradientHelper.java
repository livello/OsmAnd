package net.osmand.plus.track;

import androidx.annotation.NonNull;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.track.helpers.GpxAppearanceHelper;
import net.osmand.shared.gpx.EvConsumptionScale;
import net.osmand.shared.gpx.TrackColorScale;

public final class TrackGradientHelper {

	private TrackGradientHelper() {
	}

	@NonNull
	public static TrackColorScale from(@NonNull OsmandApplication app) {
		TrackDrawInfo drawInfo = null;
		try {
			GpxAppearanceHelper appearanceHelper = app.getOsmandMap().getMapLayers().getGpxLayer()
					.getAppearanceHelper();
			drawInfo = appearanceHelper.getTrackDrawInfo();
		} catch (RuntimeException ignored) {
		}
		if (drawInfo != null) {
			return from(drawInfo);
		}
		return from(app.getSettings());
	}

	@NonNull
	public static TrackColorScale from(@NonNull TrackDrawInfo drawInfo) {
		return new TrackColorScale(
				drawInfo.getConsumptionWindowM(),
				drawInfo.getConsumptionMinWhKm(),
				drawInfo.getConsumptionMaxWhKm(),
				drawInfo.getSpeedMinKmh() / 3.6,
				drawInfo.getSpeedMaxKmh() / 3.6
		);
	}

	@NonNull
	public static TrackColorScale from(@NonNull OsmandSettings settings) {
		return new TrackColorScale(
				settings.TRACK_COLOR_CONSUMPTION_WINDOW_M.get(),
				settings.TRACK_COLOR_CONSUMPTION_MIN_WH_KM.get(),
				settings.TRACK_COLOR_CONSUMPTION_MAX_WH_KM.get(),
				settings.TRACK_COLOR_SPEED_MIN_KMH.get() / 3.6,
				settings.TRACK_COLOR_SPEED_MAX_KMH.get() / 3.6
		);
	}

	public static void applyToSettings(@NonNull OsmandSettings settings, @NonNull TrackDrawInfo drawInfo) {
		settings.TRACK_COLOR_CONSUMPTION_WINDOW_M.set((float) drawInfo.getConsumptionWindowM());
		settings.TRACK_COLOR_CONSUMPTION_MIN_WH_KM.set((float) drawInfo.getConsumptionMinWhKm());
		settings.TRACK_COLOR_CONSUMPTION_MAX_WH_KM.set((float) drawInfo.getConsumptionMaxWhKm());
		settings.TRACK_COLOR_SPEED_MIN_KMH.set((float) drawInfo.getSpeedMinKmh());
		settings.TRACK_COLOR_SPEED_MAX_KMH.set((float) drawInfo.getSpeedMaxKmh());
	}

	public static void copyFromSettings(@NonNull OsmandSettings settings, @NonNull TrackDrawInfo drawInfo) {
		drawInfo.setConsumptionWindowM(settings.TRACK_COLOR_CONSUMPTION_WINDOW_M.get());
		drawInfo.setConsumptionMinWhKm(settings.TRACK_COLOR_CONSUMPTION_MIN_WH_KM.get());
		drawInfo.setConsumptionMaxWhKm(settings.TRACK_COLOR_CONSUMPTION_MAX_WH_KM.get());
		drawInfo.setSpeedMinKmh(settings.TRACK_COLOR_SPEED_MIN_KMH.get());
		drawInfo.setSpeedMaxKmh(settings.TRACK_COLOR_SPEED_MAX_KMH.get());
	}

	public static final float[] WINDOW_METERS = {50f, 100f, 200f, 500f, 1000f};

	public static int windowIndex(double meters) {
		int best = 0;
		double bestDiff = Double.MAX_VALUE;
		for (int i = 0; i < WINDOW_METERS.length; i++) {
			double diff = Math.abs(WINDOW_METERS[i] - meters);
			if (diff < bestDiff) {
				bestDiff = diff;
				best = i;
			}
		}
		return best;
	}

	public static double defaultWindowM() {
		return EvConsumptionScale.DEFAULT_WINDOW_M;
	}
}
