package net.osmand.plus.charts;

import static net.osmand.shared.gpx.PointAttributes.SENSOR_TAG_BIKE_POWER;
import static net.osmand.shared.gpx.PointAttributes.SENSOR_TAG_CADENCE;
import static net.osmand.shared.gpx.PointAttributes.SENSOR_TAG_HEART_RATE;
import static net.osmand.shared.gpx.PointAttributes.SENSOR_TAG_SPEED;
import static net.osmand.shared.gpx.PointAttributes.SENSOR_TAG_TEMPERATURE;

import android.content.Context;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.shared.gpx.PointAttributes;
import net.osmand.shared.obd.OBDCommand;

public enum GPXDataSetType {

	ALTITUDE(R.string.altitude, R.drawable.ic_action_altitude, PointAttributes.POINT_ELEVATION, R.color.gpx_chart_blue_label, R.color.gpx_chart_blue, GpxDataSetTypeGroup.GENERAL),
	SPEED(R.string.shared_string_speed, R.drawable.ic_action_speed_outlined, PointAttributes.POINT_SPEED, R.color.gpx_chart_orange_label, R.color.gpx_chart_orange, GpxDataSetTypeGroup.GENERAL),
	SLOPE(R.string.shared_string_slope, R.drawable.ic_action_slope, PointAttributes.POINT_ELEVATION, R.color.gpx_chart_green_label, R.color.gpx_chart_green, GpxDataSetTypeGroup.GENERAL),
	ALTITUDE_EXTRM(R.string.altitude, R.drawable.ic_action_altitude_average, PointAttributes.POINT_ELEVATION, R.color.gpx_chart_blue_label, R.color.gpx_chart_blue, GpxDataSetTypeGroup.GENERAL),

	SENSOR_SPEED(R.string.shared_string_speed, R.drawable.ic_action_sensor_speed_outlined, SENSOR_TAG_SPEED, R.color.gpx_chart_yellow_label, R.color.gpx_chart_yellow, GpxDataSetTypeGroup.EXTERNAL_SENSORS),
	SENSOR_HEART_RATE(R.string.map_widget_ant_heart_rate, R.drawable.ic_action_sensor_heart_rate_outlined, SENSOR_TAG_HEART_RATE, R.color.gpx_chart_pink_label, R.color.gpx_chart_pink, GpxDataSetTypeGroup.EXTERNAL_SENSORS),
	SENSOR_BIKE_POWER(R.string.map_widget_ant_bicycle_power, R.drawable.ic_action_sensor_bicycle_power_outlined, SENSOR_TAG_BIKE_POWER, R.color.gpx_chart_teal_label, R.color.gpx_chart_teal, GpxDataSetTypeGroup.EXTERNAL_SENSORS),
	SENSOR_BIKE_CADENCE(R.string.map_widget_ant_bicycle_cadence, R.drawable.ic_action_sensor_cadence_outlined, SENSOR_TAG_CADENCE, R.color.gpx_chart_indigo_label, R.color.gpx_chart_indigo, GpxDataSetTypeGroup.EXTERNAL_SENSORS),
	SENSOR_TEMPERATURE(R.string.map_settings_weather_temp, R.drawable.ic_action_thermometer, SENSOR_TAG_TEMPERATURE, R.color.gpx_chart_green_label, R.color.gpx_chart_green, GpxDataSetTypeGroup.EXTERNAL_SENSORS),

	INTAKE_TEMPERATURE(R.string.obd_air_intake_temp, R.drawable.ic_action_obd_temperature_intake, OBDCommand.OBD_AIR_INTAKE_TEMP_COMMAND.getGpxTag(), R.color.gpx_chart_intake_temperature, R.color.gpx_chart_intake_temperature, GpxDataSetTypeGroup.VEHICLE_METRICS),
	AMBIENT_TEMPERATURE(R.string.obd_ambient_air_temp, R.drawable.ic_action_obd_temperature_outside, OBDCommand.OBD_AMBIENT_AIR_TEMPERATURE_COMMAND.getGpxTag(), R.color.gpx_chart_ambient_temperature, R.color.gpx_chart_ambient_temperature, GpxDataSetTypeGroup.VEHICLE_METRICS),
	COOLANT_TEMPERATURE(R.string.obd_engine_coolant_temp, R.drawable.ic_action_obd_temperature_coolant, OBDCommand.OBD_ENGINE_COOLANT_TEMP_COMMAND.getGpxTag(), R.color.gpx_chart_coolant_temperature, R.color.gpx_chart_coolant_temperature, GpxDataSetTypeGroup.VEHICLE_METRICS),
	ENGINE_OIL_TEMPERATURE(R.string.obd_engine_oil_temperature, R.drawable.ic_action_obd_temperature_engine_oil, OBDCommand.OBD_ENGINE_OIL_TEMPERATURE_COMMAND.getGpxTag(), R.color.gpx_chart_engine_oil_temperature, R.color.gpx_chart_engine_oil_temperature, GpxDataSetTypeGroup.VEHICLE_METRICS),
	ENGINE_SPEED(R.string.obd_widget_engine_speed, R.drawable.ic_action_obd_engine_speed, OBDCommand.OBD_RPM_COMMAND.getGpxTag(), R.color.gpx_chart_engine_speed, R.color.gpx_chart_engine_speed, GpxDataSetTypeGroup.VEHICLE_METRICS),
	ENGINE_RUNTIME(R.string.obd_engine_runtime, R.drawable.ic_action_car_running_time, OBDCommand.OBD_ENGINE_RUNTIME_COMMAND.getGpxTag(), R.color.gpx_chart_engine_runtime, R.color.gpx_chart_engine_runtime, GpxDataSetTypeGroup.VEHICLE_METRICS),
	ENGINE_LOAD(R.string.obd_calculated_engine_load, R.drawable.ic_action_car_info, OBDCommand.OBD_CALCULATED_ENGINE_LOAD_COMMAND.getGpxTag(), R.color.gpx_chart_engine_load, R.color.gpx_chart_engine_load, GpxDataSetTypeGroup.VEHICLE_METRICS),
	FUEL_PRESSURE(R.string.obd_fuel_pressure, R.drawable.ic_action_obd_fuel_pressure, OBDCommand.OBD_FUEL_PRESSURE_COMMAND.getGpxTag(), R.color.gpx_chart_fuel_pressure, R.color.gpx_chart_fuel_pressure, GpxDataSetTypeGroup.VEHICLE_METRICS),
	FUEL_CONSUMPTION(R.string.obd_fuel_consumption, R.drawable.ic_action_obd_fuel_consumption, OBDCommand.OBD_FUEL_CONSUMPTION_RATE_COMMAND.getGpxTag(), R.color.gpx_chart_fuel_consumption, R.color.gpx_chart_fuel_consumption, GpxDataSetTypeGroup.VEHICLE_METRICS),
	REMAINING_FUEL(R.string.remaining_fuel, R.drawable.ic_action_obd_fuel_remaining, OBDCommand.OBD_FUEL_LEVEL_COMMAND.getGpxTag(), R.color.gpx_chart_remaining_fuel, R.color.gpx_chart_remaining_fuel, GpxDataSetTypeGroup.VEHICLE_METRICS),
	BATTERY_LEVEL(R.string.obd_battery_voltage, R.drawable.ic_action_obd_battery_voltage, OBDCommand.OBD_BATTERY_VOLTAGE_COMMAND.getGpxTag(), R.color.gpx_chart_battery_level, R.color.gpx_chart_battery_level, GpxDataSetTypeGroup.VEHICLE_METRICS),
	VEHICLE_SPEED(R.string.obd_widget_vehicle_speed, R.drawable.ic_action_obd_speed, OBDCommand.OBD_SPEED_COMMAND.getGpxTag(), R.color.gpx_chart_vehicle_speed, R.color.gpx_chart_vehicle_speed, GpxDataSetTypeGroup.VEHICLE_METRICS),
	THROTTLE_POSITION(R.string.obd_throttle_position, R.drawable.ic_action_obd_throttle_position, OBDCommand.OBD_THROTTLE_POSITION_COMMAND.getGpxTag(), R.color.gpx_chart_throttle_position, R.color.gpx_chart_throttle_position, GpxDataSetTypeGroup.VEHICLE_METRICS),

	EV_CONSUMPTION(R.string.ev_bms_field_coverage, R.drawable.ic_action_obd_fuel_consumption, "coverage_wh_km", R.color.gpx_chart_orange_label, R.color.gpx_chart_orange, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_ENERGY(R.string.ev_bms_widget_consumption, R.drawable.ic_action_obd_fuel_consumption, "consumption_wh_km", R.color.gpx_chart_yellow_label, R.color.gpx_chart_yellow, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_VOLTAGE(R.string.ev_bms_widget_voltage, R.drawable.ic_action_obd_battery_voltage, "voltage_v", R.color.gpx_chart_teal_label, R.color.gpx_chart_teal, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_CURRENT(R.string.ev_bms_widget_current, R.drawable.ic_action_battery, "current_a", R.color.gpx_chart_green_label, R.color.gpx_chart_green, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_SOC(R.string.ev_bms_widget_soc, R.drawable.ic_action_battery, "soc_percent", R.color.gpx_chart_blue_label, R.color.gpx_chart_blue, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_SOC_OCV(R.string.ev_bms_field_soc_ocv, R.drawable.ic_action_battery, "soc_ocv_percent", R.color.gpx_chart_indigo_label, R.color.gpx_chart_indigo, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_CHARGE_TRIP(R.string.ev_bms_widget_charge_trip, R.drawable.ic_action_distance, "charge_trip_km", R.color.gpx_chart_indigo_label, R.color.gpx_chart_indigo, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_GPS_SPEED(R.string.ev_bms_field_gps_speed, R.drawable.ic_action_speed_outlined, "gps_speed_kmh", R.color.gpx_chart_orange_label, R.color.gpx_chart_orange, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_REMAINING_AH(R.string.ev_bms_field_remaining_ah, R.drawable.ic_action_obd_fuel_remaining, "remaining_ah", R.color.gpx_chart_green_label, R.color.gpx_chart_green, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_FULL_AH(R.string.ev_bms_field_full_ah, R.drawable.ic_action_obd_fuel_remaining, "full_ah", R.color.gpx_chart_teal_label, R.color.gpx_chart_teal, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_BMS_TEMP(R.string.ev_bms_widget_battery_temp, R.drawable.ic_action_thermometer, "bms_temp_c", R.color.gpx_chart_pink_label, R.color.gpx_chart_pink, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_CYCLES(R.string.ev_bms_field_cycles, R.drawable.ic_action_time_span, "cycles", R.color.gpx_chart_indigo_label, R.color.gpx_chart_indigo, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_MIN_CELL(R.string.ev_bms_field_min_cell, R.drawable.ic_action_obd_battery_voltage, "min_cell_v", R.color.gpx_chart_green_label, R.color.gpx_chart_green, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_MAX_CELL(R.string.ev_bms_field_max_cell, R.drawable.ic_action_obd_battery_voltage, "max_cell_v", R.color.gpx_chart_orange_label, R.color.gpx_chart_orange, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_CELL_IMBALANCE(R.string.ev_bms_field_cell_imbalance, R.drawable.ic_action_obd_battery_voltage, "cell_imbalance_v", R.color.gpx_chart_pink_label, R.color.gpx_chart_pink, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_RANGE(R.string.ev_bms_field_range, R.drawable.ic_action_distance, "range_km", R.color.gpx_chart_blue_label, R.color.gpx_chart_blue, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_RANGE_WINDOW(R.string.ev_bms_field_range_window, R.drawable.ic_action_distance, "range_window_km", R.color.gpx_chart_teal_label, R.color.gpx_chart_teal, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_RANGE_PNZ(R.string.ev_bms_field_range_pnz, R.drawable.ic_action_distance, "range_pnz_km", R.color.gpx_chart_indigo_label, R.color.gpx_chart_indigo, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_CTRL_VOLTAGE(R.string.ev_bms_field_ctrl_voltage, R.drawable.ic_action_obd_battery_voltage, "controller_voltage_v", R.color.gpx_chart_teal_label, R.color.gpx_chart_teal, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_CTRL_CURRENT(R.string.ev_bms_field_ctrl_current, R.drawable.ic_action_battery, "controller_current_a", R.color.gpx_chart_green_label, R.color.gpx_chart_green, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_POWER(R.string.ev_bms_widget_power, R.drawable.ic_action_obd_fuel_consumption, "power_w", R.color.gpx_chart_orange_label, R.color.gpx_chart_orange, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_RPM(R.string.ev_bms_field_rpm, R.drawable.ic_action_obd_engine_speed, "rpm", R.color.gpx_chart_yellow_label, R.color.gpx_chart_yellow, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_GEAR(R.string.ev_bms_field_gear, R.drawable.ic_action_obd_speed, "gear", R.color.gpx_chart_indigo_label, R.color.gpx_chart_indigo, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_MOTOR_TEMP(R.string.ev_bms_widget_motor_temp, R.drawable.ic_action_thermometer, "motor_temp_c", R.color.gpx_chart_pink_label, R.color.gpx_chart_pink, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_CTRL_TEMP(R.string.ev_bms_widget_controller_temp, R.drawable.ic_action_thermometer, "controller_temp_c", R.color.gpx_chart_green_label, R.color.gpx_chart_green, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_ODOMETER(R.string.ev_bms_field_odometer, R.drawable.ic_action_distance, "odometer_km", R.color.gpx_chart_blue_label, R.color.gpx_chart_blue, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_FAR_TRIP(R.string.ev_bms_widget_far_trip, R.drawable.ic_action_distance, "controller_trip_km", R.color.gpx_chart_teal_label, R.color.gpx_chart_teal, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_CTRL_SPEED(R.string.ev_bms_field_ctrl_speed, R.drawable.ic_action_speed_outlined, "controller_speed_kmh", R.color.gpx_chart_orange_label, R.color.gpx_chart_orange, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_WHEEL_SPEED(R.string.ev_bms_field_wheel_speed, R.drawable.ic_action_sensor_speed_outlined, "wheel_speed_kmh", R.color.gpx_chart_yellow_label, R.color.gpx_chart_yellow, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_WHEEL_ODOMETER(R.string.ev_bms_field_wheel_odometer, R.drawable.ic_action_distance, "wheel_odometer_km", R.color.gpx_chart_indigo_label, R.color.gpx_chart_indigo, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_CADENCE(R.string.ev_bms_field_cadence, R.drawable.ic_action_sensor_cadence_outlined, "cadence_rpm", R.color.gpx_chart_pink_label, R.color.gpx_chart_pink, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_USED_AH(R.string.ev_bms_field_used_ah, R.drawable.ic_action_obd_fuel_remaining, "used_ah", R.color.gpx_chart_green_label, R.color.gpx_chart_green, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_RANGE_RESERVE(R.string.ev_bms_widget_range_reserve, R.drawable.ic_action_time_to_distance, "range_reserve_km", R.color.gpx_chart_blue_label, R.color.gpx_chart_blue, GpxDataSetTypeGroup.EV_TELEMETRY),
	EV_STOP_TIME(R.string.ev_bms_field_stop_time, R.drawable.ic_action_time_span, "stop_time_ms", R.color.gpx_chart_indigo_label, R.color.gpx_chart_indigo, GpxDataSetTypeGroup.EV_TELEMETRY),

	ZOOM_ANIMATED(R.string.zoom_animated, R.drawable.ic_action_map_zoom, PointAttributes.DEV_ANIMATED_ZOOM, R.color.gpx_chart_teal_label, R.color.gpx_chart_teal, GpxDataSetTypeGroup.GENERAL),
	ZOOM_NON_ANIMATED(R.string.zoom_non_animated, R.drawable.ic_action_map_zoom, PointAttributes.DEV_RAW_ZOOM, R.color.gpx_chart_indigo_label, R.color.gpx_chart_indigo, GpxDataSetTypeGroup.GENERAL);

	@StringRes
	private final int titleId;
	@DrawableRes
	private final int iconId;

	private final String dataKey;
	@ColorRes
	private final int textColorId;
	@ColorRes
	private final int fillColorId;
	final GpxDataSetTypeGroup typeGroup;

	GPXDataSetType(@StringRes int titleId, @DrawableRes int iconId, @NonNull String dataKey, @ColorRes int textColorId, @ColorRes int fillColorId, @NonNull GpxDataSetTypeGroup typeGroup) {
		this.titleId = titleId;
		this.iconId = iconId;
		this.dataKey = dataKey;
		this.textColorId = textColorId;
		this.fillColorId = fillColorId;
		this.typeGroup = typeGroup;
	}

	public String getName(@NonNull Context ctx) {
		return ctx.getString(titleId);
	}

	@StringRes
	public int getTitleId() {
		return titleId;
	}

	@DrawableRes
	public int getIconId() {
		return iconId;
	}

	@NonNull
	public String getDataKey() {
		return dataKey;
	}

	@NonNull
	public GpxDataSetTypeGroup getTypeGroup() {
		return typeGroup;
	}

	@ColorRes
	public int getTextColorId(boolean additional) {
		if (this == SPEED) {
			return additional ? R.color.gpx_chart_red_label : textColorId;
		}
		return textColorId;
	}

	@ColorRes
	public int getFillColorId(boolean additional) {
		if (this == SPEED) {
			return additional ? R.color.gpx_chart_red : fillColorId;
		}
		return fillColorId;
	}

	@NonNull
	public String getMainUnitY(@NonNull OsmandApplication app) {
		OsmandSettings settings = app.getSettings();
		return switch (this) {
			case ALTITUDE -> {
				boolean shouldUseFeet = settings.METRIC_SYSTEM.get().shouldUseFeet();
				yield app.getString(shouldUseFeet ? R.string.foot : R.string.m);
			}
			case SLOPE -> "%";
			case SPEED, SENSOR_SPEED -> settings.SPEED_SYSTEM.get().toShortString();
			case SENSOR_HEART_RATE -> app.getString(R.string.beats_per_minute_short);
			case SENSOR_BIKE_POWER -> app.getString(R.string.power_watts_unit);
			case SENSOR_BIKE_CADENCE -> app.getString(R.string.revolutions_per_minute_unit);
			case SENSOR_TEMPERATURE -> app.getString(R.string.degree_celsius);
			default -> "";
		};
	}
}
