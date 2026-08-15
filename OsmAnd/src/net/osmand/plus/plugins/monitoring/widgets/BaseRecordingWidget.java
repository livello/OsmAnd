package net.osmand.plus.plugins.monitoring.widgets;

import android.view.Gravity;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.evbms.EvWidgetChrome;
import net.osmand.plus.plugins.monitoring.SavingTrackHelper;
import net.osmand.plus.views.layers.base.OsmandMapLayer.DrawSettings;
import net.osmand.plus.views.mapwidgets.WidgetType;
import net.osmand.plus.views.mapwidgets.WidgetsPanel;
import net.osmand.plus.views.mapwidgets.widgets.MapWidget;
import net.osmand.plus.views.mapwidgets.widgets.SimpleWidget;
import net.osmand.shared.gpx.ElevationDiffsCalculator.SlopeInfo;
import net.osmand.shared.gpx.GpxTrackAnalysis;

public class BaseRecordingWidget extends SimpleWidget {

	protected int currentTrackIndex;
	protected final SavingTrackHelper savingTrackHelper;

	protected SlopeInfo slopeUphillInfo;
	protected SlopeInfo slopeDownhillInfo;


	public BaseRecordingWidget(@NonNull MapActivity mapActivity, @NonNull WidgetType widgetType, @Nullable String customId, @Nullable WidgetsPanel panel) {
		super(mapActivity, widgetType, customId, panel);
		this.savingTrackHelper = app.getSavingTrackHelper();
		EvWidgetChrome.applySideLayout(getView(), this.panel);
	}

	@Override
	public void attachView(@NonNull ViewGroup container, @NonNull WidgetsPanel panel,
			@NonNull List<MapWidget> followingWidgets) {
		EvWidgetChrome.attach(this, container, panel);
	}

	@Override
	protected void recreateViewInternal() {
		super.recreateViewInternal();
		EvWidgetChrome.applySideLayout(getView(), this.panel);
	}

	@Override
	public void updateValueAlign(boolean fullRow) {
		if (isVerticalWidget()) {
			super.updateValueAlign(fullRow);
			return;
		}
		int gravity = EvWidgetChrome.sideGravity(panel) | Gravity.CENTER_VERTICAL;
		textView.setGravity(gravity);
		if (smallTextView != null) {
			smallTextView.setGravity(gravity);
		}
		EvWidgetChrome.applySideLayout(getView(), panel);
	}

	@Override
	protected void updateSimpleWidgetInfo(@Nullable DrawSettings drawSettings) {
		int currentTrackIndex = savingTrackHelper.getCurrentTrackIndex();
		if (this.currentTrackIndex != currentTrackIndex) {
			resetCachedValue();
		}
		this.currentTrackIndex = currentTrackIndex;

		slopeUphillInfo = updateSlopeInfo(slopeUphillInfo, getAnalysis().getLastUphill());
		slopeDownhillInfo = updateSlopeInfo(slopeDownhillInfo, getAnalysis().getLastDownhill());
		EvWidgetChrome.applySideLayout(getView(), panel);
	}

	private SlopeInfo updateSlopeInfo(@Nullable SlopeInfo oldInfo, @Nullable SlopeInfo newInfo) {
		if (oldInfo == null) {
			return newInfo;
		}
		if (newInfo == null) {
			return oldInfo;
		}

		boolean isSameSlope = oldInfo.getStartPointIndex() == newInfo.getStartPointIndex();
		boolean isNextSlope = oldInfo.getStartPointIndex() < newInfo.getStartPointIndex();

		if (isSameSlope) {
			oldInfo.setElevDiff(Math.max(oldInfo.getElevDiff(), newInfo.getElevDiff()));
			oldInfo.setDistance(Math.max(oldInfo.getDistance(), newInfo.getDistance()));
			oldInfo.setMaxSpeed(Math.max(oldInfo.getMaxSpeed(), newInfo.getMaxSpeed()));
			oldInfo.setMovingTime(Math.max(oldInfo.getMovingTime(), newInfo.getMovingTime()));
			return oldInfo;
		} else if (isNextSlope) {
			return newInfo;
		} else {
			return oldInfo;
		}
	}

	protected void resetCachedValue(){
		slopeUphillInfo = null;
		slopeDownhillInfo = null;
	}

	protected SlopeInfo getLastSlope(boolean isUphill) {
		return isUphill ? slopeUphillInfo : slopeDownhillInfo;
	}

	@NonNull
	protected GpxTrackAnalysis getAnalysis() {
		return savingTrackHelper.getCurrentTrack().getTrackAnalysis(app);
	}
}
