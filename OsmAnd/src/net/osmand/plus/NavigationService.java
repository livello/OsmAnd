package net.osmand.plus;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE;
import static net.osmand.plus.OsmAndLocationProvider.NOT_SWITCH_TO_NETWORK_WHEN_GPS_LOST_MS;
import static net.osmand.plus.OsmAndLocationProvider.isRunningOnEmulator;
import static net.osmand.plus.notifications.OsmandNotification.NotificationType.AIS;
import static net.osmand.plus.notifications.OsmandNotification.NotificationType.GPX;
import static net.osmand.plus.notifications.OsmandNotification.NotificationType.NAVIGATION;
import static net.osmand.plus.notifications.OsmandNotification.TOP_NOTIFICATION_SERVICE_ID;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.core.location.LocationListenerCompat;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.StateChangedListener;
import net.osmand.plus.auto.NavigationCarAppService;
import net.osmand.plus.auto.NavigationSession;
import net.osmand.plus.helpers.LocationCallback;
import net.osmand.plus.helpers.LocationServiceHelper;
import net.osmand.plus.notifications.NotificationHelper;
import net.osmand.plus.plugins.monitoring.ScreenOffTrackRecording;
import net.osmand.plus.notifications.OsmandNotification.NotificationType;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.settings.enums.LocationSource;

import org.apache.commons.logging.Log;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class NavigationService extends Service {

	public static final Log LOG = PlatformUtil.getLog(NavigationService.class);

	public static class NavigationServiceBinder extends Binder {
	}

	public static final String DEEP_LINK_ACTION_OPEN_ROOT_SCREEN = "net.osmand.plus.navigation.car.OpenRootScreen";

	// global id don't conflict with others
	public static int USED_BY_NAVIGATION = 1;
	public static int USED_BY_GPX = 2;
	public static int USED_BY_AIS = 4;
	/** Telemetry recording or an open charge. Keeps the process up when the track is not recording. */
	public static int USED_BY_EV = 8;
	public static final String USAGE_INTENT = "SERVICE_USED_BY";

	private final NavigationServiceBinder binder = new NavigationServiceBinder();

	private OsmandSettings settings;
	private RoutingHelper routingHelper;

	private final AtomicInteger usedBy = new AtomicInteger(0);
	private OsmAndLocationProvider locationProvider;
	private LocationServiceHelper locationServiceHelper;
	private StateChangedListener<LocationSource> locationSourceListener;
	private long lastTimeGPSLocationFixed;
	private PowerManager.WakeLock gpsWakeLock;

	private final LocationListenerCompat gpsListener = location -> {
		if (location == null || settings == null
				|| !ScreenOffTrackRecording.serviceAcceptsFix(settings.MAP_ACTIVITY_ENABLED)) {
			return;
		}
		if (getApp().getNavigationCarAppService() != null) {
			return;
		}
		if (locationProvider == null) {
			locationProvider = getApp().getLocationProvider();
		}
		net.osmand.Location converted = OsmAndLocationProvider.convertLocation(location, getApp());
		if (converted != null) {
			lastTimeGPSLocationFixed = System.currentTimeMillis();
			locationProvider.setLocationFromService(converted);
		}
	};

	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}

	private OsmandApplication getApp() {
		return (OsmandApplication) getApplication();
	}

	public int getUsedBy() {
		return usedBy.get();
	}

	public boolean isUsed() {
		return usedBy.get() != 0;
	}

	public boolean isUsedBy(int type) {
		return (usedBy.get() & type) == type;
	}

	private void onServiceChanged(boolean start) {
		OsmandApplication app = getApp();
		NavigationSession carSession = app.getCarNavigationSession();
		if (start) {
			if (carSession != null) {
				if (isUsedBy(USED_BY_NAVIGATION)) {
					carSession.startCarNavigation();
				}
				if (routingHelper.isRouteCalculated() && routingHelper.isPauseNavigation()) {
					routingHelper.resumeNavigation();
				}
			}
		} else {
			if (!isUsedBy(USED_BY_NAVIGATION)) {
				if (carSession != null) {
					carSession.stopCarNavigation();
				}
			}
		}
		app.getNotificationHelper().refreshNotifications();
	}

	public void addUsageIntent(int usageIntent) {
		usedBy.updateAndGet(value -> value | usageIntent);
		onServiceChanged(true);
	}

	public void stopIfNeeded(@NonNull Context context, int usageIntent) {
		LOG.info(">>>> NavigationService stopIfNeeded = " + usageIntent);
		OsmandApplication app = getApp();
		usedBy.updateAndGet(value -> (value & usageIntent) > 0 ? value & ~usageIntent : value);
		onServiceChanged(false);
		if (usedBy.get() == 0) {
			context.stopService(new Intent(context, NavigationService.class));
		} else {
			app.getNotificationHelper().updateTopNotification();
			app.runInUIThread(() -> app.getNotificationHelper().refreshNotifications(), 500);
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		LOG.info(">>>> NavigationService onStartCommand");
		int usageIntent = intent != null ? intent.getIntExtra(USAGE_INTENT, 0) : 0;
		OsmandApplication app = getApp();
		NotificationHelper notificationHelper = app.getNotificationHelper();

		// Meet the startForegroundService() deadline before any other work. A second start of
		// an already running service arms a new deadline; returning without startForeground()
		// kills the process with ForegroundServiceDidNotStartInTimeException.
		if (!promoteToForeground(notificationHelper.buildFallbackNotification())) {
			// A second start can be rejected once the screen is off. Stopping here
			// drops the location foreground service, so the track freezes while the
			// process (and telemetry polling) is still alive.
			if (isUsed()) {
				ensureLocationUpdates();
				return START_STICKY;
			}
			resetServiceState(app);
			stopSelf();
			return START_NOT_STICKY;
		}

		if (isUsed()) {
			LOG.info(">>>> NavigationService is used by = " + usedBy.get());
			// A running service of a "Restricted" app is taken out of the foreground in the background.
			// Before addUsageIntent(), so its refresh reposts what buildTopNotification() cancels.
			Notification notification = notificationHelper.buildTopNotification(this, getNotificationType());
			try {
				startForeground(notification != null ? notification : notificationHelper.buildFallbackNotification());
			} catch (Exception e) {
				// The promotion above already holds the location service. Stopping here
				// is what freezes the track for the rest of a screen-off ride.
				LOG.error("Failed to refresh NavigationService notification (usedBy=" + usedBy.get() + ")", e);
			}
			addUsageIntent(usageIntent);
			ensureLocationUpdates();
			return START_REDELIVER_INTENT;
		}

		settings = app.getSettings();
		routingHelper = app.getRoutingHelper();
		usedBy.set(usageIntent);

		locationProvider = app.getLocationProvider();
		locationServiceHelper = app.createLocationServiceHelper();
		app.setNavigationService(this);

		Notification notification = notificationHelper.buildTopNotification(this, getNotificationType());
		if (notification != null) {
			try {
				startForeground(notification);
			} catch (Exception e) {
				// The fallback promotion above already satisfied the deadline.
				LOG.error("Failed to replace fallback notification (usedBy=" + usedBy.get() + ")", e);
			}
			try {
				onServiceChanged(true);
			} catch (Exception e) {
				LOG.error(e.getMessage(), e);
			}
		} else {
			// The fallback notification above already holds the location foreground
			// service. Stopping here is what drops GPS for the rest of a ride.
			LOG.error("NavigationService notification is null. usedBy=" + usedBy.get() + ". Keeping fallback.");
		}
		ensureLocationUpdates();
		return START_REDELIVER_INTENT;
	}

	/**
	 * Re-register GPS and hold a wake lock so a screen-off ride keeps getting fixes.
	 */
	public void ensureLocationUpdates() {
		OsmandApplication app = getApp();
		if (settings == null) {
			settings = app.getSettings();
		}
		if (routingHelper == null) {
			routingHelper = app.getRoutingHelper();
		}
		if (locationProvider == null) {
			locationProvider = app.getLocationProvider();
		}
		if (locationServiceHelper == null) {
			locationServiceHelper = app.createLocationServiceHelper();
		}
		requestLocationUpdates();
		acquireGpsWakeLock();
	}

	/**
	 * @return true when the service is in the foreground with the location type
	 */
	private boolean promoteToForeground(@NonNull Notification notification) {
		try {
			startForeground(notification);
			return true;
		} catch (Exception e) {
			LOG.error("Failed to start NavigationService (usedBy=" + usedBy.get() + ")", e);
			if (isUsed()) {
				// Already a location foreground service. Do not demote it: GPS stops
				// with the screen as soon as this service is no longer in the foreground.
				LOG.error("Keeping the running location service after a rejected promotion");
				return true;
			}
			// The process is killed if startForegroundService() is not acknowledged.
			// shortService is allowed from the background and is dropped immediately.
			// It must not remain the running type: its timeout stops the track.
			acknowledgeForegroundStart(notification);
			return false;
		}
	}

	private void acknowledgeForegroundStart(@NonNull Notification notification) {
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
				startForeground(TOP_NOTIFICATION_SERVICE_ID, notification, FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
			} else {
				startForeground(TOP_NOTIFICATION_SERVICE_ID, notification);
			}
			stopForeground(STOP_FOREGROUND_REMOVE);
		} catch (Exception e) {
			LOG.error("Failed to acknowledge foreground service start", e);
		}
	}

	@Override
	public void onTimeout(int startId) {
		super.onTimeout(startId);
		LOG.info(">>>> NavigationService onTimeout " + startId + " usedBy=" + usedBy.get());
		// The successful path is a location service, so this timeout should not fire.
		// shortService is only the failed-start acknowledgement, which already stopped
		// itself. Stopping a service that is recording the track ends the ride.
		if (usedBy.get() == 0) {
			stopSelf();
		}
	}

	@NonNull
	private NotificationType getNotificationType() {
		boolean trackOrEv = isUsedBy(USED_BY_GPX) || isUsedBy(USED_BY_EV);
		return isUsedBy(USED_BY_NAVIGATION) ? NAVIGATION : trackOrEv ? GPX : AIS;
	}

	private void startForeground(@NonNull Notification notification) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			startForeground(TOP_NOTIFICATION_SERVICE_ID, notification, FOREGROUND_SERVICE_TYPE_LOCATION);
		} else {
			startForeground(TOP_NOTIFICATION_SERVICE_ID, notification);
		}
	}

	private void resetServiceState(@NonNull OsmandApplication app) {
		app.setNavigationService(null);
		usedBy.set(0);
	}

	@Override
	public void onCreate() {
		super.onCreate();
		addLocationSourceListener();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		stopForeground(STOP_FOREGROUND_REMOVE);

		OsmandApplication app = getApp();
		resetServiceState(app);
		removeLocationUpdates();
		releaseGpsWakeLock();
		removeLocationSourceListener();

		LOG.info(">>>> NavigationService onDestroy");
		// remove notification
		app.getNotificationHelper().resetTopNotification();
		app.runInUIThread(() -> app.getNotificationHelper().refreshNotifications(), 500);
	}

	@Override
	public void onTaskRemoved(Intent rootIntent) {
		LOG.info(">>>> NavigationService onTaskRemoved");
		OsmandApplication app = getApp();
		app.getNotificationHelper().removeNotifications(false);
		if (app.getNavigationService() != null && app.getSettings().DISABLE_RECORDING_ONCE_APP_KILLED.get()) {
			stopSelf();
		}
	}

	private void addLocationSourceListener() {
		OsmandApplication app = getApp();
		locationSourceListener = change -> {
			removeLocationUpdates();
			locationServiceHelper = app.createLocationServiceHelper();
			requestLocationUpdates();
		};
		app.getSettings().LOCATION_SOURCE.addListener(locationSourceListener);
	}

	private void removeLocationSourceListener() {
		getApp().getSettings().LOCATION_SOURCE.removeListener(locationSourceListener);
	}

	private void requestLocationUpdates() {
		try {
			LOG.info(">>>> requestLocationUpdates from NavigationService");
			registerGpsProvider();

			locationServiceHelper.requestLocationUpdates(new LocationCallback() {
				@Override
				public void onLocationResult(@NonNull List<net.osmand.Location> locations) {
					if (!locations.isEmpty()) {
						Location location = locations.get(locations.size() - 1);
						NavigationCarAppService navigationCarAppService = getApp().getNavigationCarAppService();
						lastTimeGPSLocationFixed = System.currentTimeMillis();
						if (ScreenOffTrackRecording.serviceAcceptsFix(settings.MAP_ACTIVITY_ENABLED)
								&& navigationCarAppService == null) {
							locationProvider.setLocationFromService(location);
						}
					}
				}
			});
			// try to always ask for network provide : it is faster way to find location
			if (locationServiceHelper.isNetworkLocationUpdatesSupported()) {
				locationServiceHelper.requestNetworkLocationUpdates(new LocationCallback() {
					@Override
					public void onLocationResult(@NonNull List<net.osmand.Location> locations) {
						NavigationCarAppService navigationCarAppService = getApp().getNavigationCarAppService();
						if (ScreenOffTrackRecording.serviceAcceptsFix(settings.MAP_ACTIVITY_ENABLED)
								&& navigationCarAppService == null && !locations.isEmpty() && !useOnlyGPS()) {
							locationProvider.setLocationFromService(locations.get(locations.size() - 1));
						}
					}
				});
			}
		} catch (SecurityException e) {
			getApp().showToastMessage(R.string.no_location_permission);
		} catch (IllegalArgumentException e) {
			getApp().showToastMessage(R.string.gps_not_available);
		}
	}

	private void removeLocationUpdates() {
		LOG.info(">>>> removeLocationUpdates from NavigationService");
		unregisterGpsProvider();
		if (locationServiceHelper != null) {
			try {
				locationServiceHelper.removeLocationUpdates();
			} catch (SecurityException e) {
				// Location service permission not granted
			} finally {
				lastTimeGPSLocationFixed = 0;
			}
		}
	}

	@SuppressLint("MissingPermission")
	private void registerGpsProvider() {
		LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
		if (locationManager == null) {
			return;
		}
		try {
			locationManager.removeUpdates(gpsListener);
			locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
					ScreenOffTrackRecording.GPS_MIN_TIME_MS, ScreenOffTrackRecording.GPS_MIN_DISTANCE_M,
					gpsListener, Looper.getMainLooper());
		} catch (SecurityException e) {
			LOG.error("GPS provider permission not granted", e);
		} catch (IllegalArgumentException e) {
			LOG.error("GPS provider not available", e);
		}
	}

	private void unregisterGpsProvider() {
		LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
		if (locationManager == null) {
			return;
		}
		try {
			locationManager.removeUpdates(gpsListener);
		} catch (SecurityException e) {
			// Location service permission not granted
		}
	}

	private void acquireGpsWakeLock() {
		if (gpsWakeLock == null) {
			PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
			if (powerManager == null) {
				return;
			}
			gpsWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OsmAnd:NavigationService");
			gpsWakeLock.setReferenceCounted(false);
		}
		if (!gpsWakeLock.isHeld()) {
			gpsWakeLock.acquire();
		}
	}

	private void releaseGpsWakeLock() {
		if (gpsWakeLock != null && gpsWakeLock.isHeld()) {
			gpsWakeLock.release();
		}
	}

	private boolean useOnlyGPS() {
		if (routingHelper.isFollowingMode()) {
			return true;
		}
		if (lastTimeGPSLocationFixed > 0 && (System.currentTimeMillis() - lastTimeGPSLocationFixed) < NOT_SWITCH_TO_NETWORK_WHEN_GPS_LOST_MS) {
			return true;
		}
		return isRunningOnEmulator();
	}
}
