package net.osmand.plus.plugins.monitoring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * The screen-off handoff. A ride with the phone in a pocket must keep storing
 * points after the map activity has paused, and a cold start in the background
 * must not ask for a location foreground service.
 */
public class ScreenOffTrackRecordingTest {

	@Test
	public void serviceIgnoresFixesWhileTheMapIsListening() {
		assertFalse(ScreenOffTrackRecording.serviceAcceptsFix(true));
	}

	@Test
	public void serviceRecordsFixesAfterTheScreenTurnsOff() {
		assertTrue(ScreenOffTrackRecording.serviceAcceptsFix(false));
	}

	@Test
	public void continuousGpsMatchesTheOriginalListener() {
		assertEquals(0L, ScreenOffTrackRecording.GPS_MIN_TIME_MS);
		assertEquals(0f, ScreenOffTrackRecording.GPS_MIN_DISTANCE_M, 0f);
	}

	@Test
	public void screenOffHandoffReleasesTheActivitySessionBeforeTheServiceListens() {
		List<ScreenOffTrackRecording.HandoffStep> steps = ScreenOffTrackRecording.screenOffHandoff();
		assertEquals(ScreenOffTrackRecording.HandoffStep.ENSURE_SERVICE_WHILE_FOREGROUND, steps.get(0));
		assertEquals(ScreenOffTrackRecording.HandoffStep.MARK_MAP_DISABLED, steps.get(1));
		assertEquals(ScreenOffTrackRecording.HandoffStep.RELEASE_ACTIVITY_LISTENER, steps.get(2));
		assertEquals(ScreenOffTrackRecording.HandoffStep.REGISTER_CONTINUOUS_GPS, steps.get(3));
		assertTrue(steps.indexOf(ScreenOffTrackRecording.HandoffStep.RELEASE_ACTIVITY_LISTENER)
				< steps.indexOf(ScreenOffTrackRecording.HandoffStep.REGISTER_CONTINUOUS_GPS));
		assertTrue(steps.indexOf(ScreenOffTrackRecording.HandoffStep.ENSURE_SERVICE_WHILE_FOREGROUND)
				< steps.indexOf(ScreenOffTrackRecording.HandoffStep.MARK_MAP_DISABLED));
	}

	@Test
	public void backgroundColdStartDoesNotStartAForegroundService() {
		assertFalse(ScreenOffTrackRecording.mayStartNewForegroundService(false, false));
	}

	@Test
	public void pausingMapCanStillStartTheServiceWhileTheActivityIsForeground() {
		assertTrue(ScreenOffTrackRecording.mayStartNewForegroundService(true, true));
		assertTrue(ScreenOffTrackRecording.mayStartNewForegroundService(false, true));
	}
}
