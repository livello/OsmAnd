package net.osmand.plus.plugins.monitoring;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.List;

/**
 * Trip recording with the screen off.
 *
 * <p>While the map is visible, the activity owns the GPS session. When the
 * screen turns off that session is released and {@code NavigationService}
 * listens to {@code GPS_PROVIDER} continuously: minimum time 0, minimum
 * distance 0. That is the listener OsmAnd kept for any recording interval
 * shorter than five minutes, before fused location replaced it. The service
 * writes a fix only after the map activity has stopped listening.
 */
public final class ScreenOffTrackRecording {

	/** Original continuous GPS request. Zero means "as often as the chip reports". */
	public static final long GPS_MIN_TIME_MS = 0L;
	public static final float GPS_MIN_DISTANCE_M = 0f;

	private ScreenOffTrackRecording() {
	}

	/**
	 * @return true when the foreground service, not the map, should store the fix
	 */
	public static boolean serviceAcceptsFix(boolean mapActivityEnabled) {
		return !mapActivityEnabled;
	}

	/**
	 * A new location foreground service may be started only while the process
	 * can still receive the location capability. Starting one from a cold
	 * background process is refused: Android accepts the call and then denies
	 * the location type.
	 */
	public static boolean mayStartNewForegroundService(boolean appInForeground, boolean mapActivityEnabled) {
		return appInForeground || mapActivityEnabled;
	}

	public enum HandoffStep {
		/** The location service must already be up before the process is background. */
		ENSURE_SERVICE_WHILE_FOREGROUND,
		/** From here the service, not the map, is allowed to store fixes. */
		MARK_MAP_DISABLED,
		/** Drop the activity listener. Leaving it registered suspends the GPS session. */
		RELEASE_ACTIVITY_LISTENER,
		/** Register the continuous GPS listener after the activity has let go. */
		REGISTER_CONTINUOUS_GPS
	}

	@NonNull
	public static List<HandoffStep> screenOffHandoff() {
		return Arrays.asList(
				HandoffStep.ENSURE_SERVICE_WHILE_FOREGROUND,
				HandoffStep.MARK_MAP_DISABLED,
				HandoffStep.RELEASE_ACTIVITY_LISTENER,
				HandoffStep.REGISTER_CONTINUOUS_GPS);
	}
}
