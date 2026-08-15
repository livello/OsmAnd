package net.osmand.plus.settings.preferences;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;

import androidx.preference.EditTextPreference;

public class EditTextPreferenceEx extends EditTextPreference {

	private String description;
	private int inputType;

	public EditTextPreferenceEx(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
		super(context, attrs, defStyleAttr, defStyleRes);
		initAttrs(context, attrs);
	}

	public EditTextPreferenceEx(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		initAttrs(context, attrs);
	}

	public EditTextPreferenceEx(Context context, AttributeSet attrs) {
		super(context, attrs);
		initAttrs(context, attrs);
	}

	public EditTextPreferenceEx(Context context) {
		super(context);
	}

	private void initAttrs(Context context, AttributeSet attrs) {
		if (attrs == null) {
			return;
		}
		TypedArray typedArray = context.obtainStyledAttributes(attrs, new int[] {android.R.attr.inputType});
		inputType = typedArray.getInt(0, 0);
		typedArray.recycle();
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public void setDescription(int descriptionResId) {
		setDescription(getContext().getString(descriptionResId));
	}

	public int getInputType() {
		return inputType;
	}
}
