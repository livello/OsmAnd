package net.osmand.evbms;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Named telemetry setting profiles. The preference blob and the files under
 * ev_telemetry/profiles are one catalog: a name saved to either place is part
 * of the list the settings dialog shows.
 */
public final class EvBmsProfileCatalog {

	public static final String TYPE = "ev_bms_profile";
	public static final String DEFAULT_NAME = "Default";
	public static final String NEW_PROFILE_VALUE = "__new__";
	private static final int MAX_NAME = 40;

	private EvBmsProfileCatalog() {
	}

	public static String sanitize(String raw) {
		if (raw == null) {
			return null;
		}
		String clean = raw.trim().replaceAll("[\\r\\n]+", " ");
		if (clean.isEmpty() || NEW_PROFILE_VALUE.equals(clean)) {
			return null;
		}
		if (clean.length() > MAX_NAME) {
			clean = clean.substring(0, MAX_NAME);
		}
		return clean;
	}

	public static List<String> namesFromStore(String raw) {
		List<String> names = new ArrayList<>();
		JSONArray arr = profiles(raw);
		for (int i = 0; i < arr.length(); i++) {
			JSONObject obj = arr.optJSONObject(i);
			if (obj == null) {
				continue;
			}
			String name = obj.optString("name", "");
			if (!name.isEmpty()) {
				names.add(name);
			}
		}
		return names;
	}

	/**
	 * Inserts or replaces one profile and returns the store JSON.
	 * The name is always present in {@link #namesFromStore(String)} of the result.
	 */
	public static String upsert(String raw, String name, String settingsJson) {
		String clean = sanitize(name);
		if (clean == null) {
			throw new IllegalArgumentException("profile name");
		}
		JSONObject settings = parseSettings(settingsJson);
		JSONObject root = root(raw);
		JSONArray arr = root.optJSONArray("profiles");
		if (arr == null) {
			arr = new JSONArray();
			put(root, "profiles", arr);
		}
		boolean found = false;
		for (int i = 0; i < arr.length(); i++) {
			JSONObject obj = arr.optJSONObject(i);
			if (obj != null && clean.equals(obj.optString("name"))) {
				put(obj, "settings", settings);
				found = true;
				break;
			}
		}
		if (!found) {
			JSONObject obj = new JSONObject();
			put(obj, "name", clean);
			put(obj, "settings", settings);
			add(arr, obj);
		}
		return root.toString();
	}

	public static String rename(String raw, String oldName, String newName, String settingsJson) {
		String clean = sanitize(newName);
		String previous = sanitize(oldName);
		if (clean == null) {
			throw new IllegalArgumentException("profile name");
		}
		JSONObject root = root(raw);
		JSONArray arr = root.optJSONArray("profiles");
		if (arr != null && previous != null) {
			for (int i = 0; i < arr.length(); i++) {
				JSONObject obj = arr.optJSONObject(i);
				if (obj != null && previous.equals(obj.optString("name"))) {
					put(obj, "name", clean);
					if (settingsJson != null) {
						put(obj, "settings", parseSettings(settingsJson));
					}
					return root.toString();
				}
			}
		}
		return upsert(raw, clean, settingsJson == null ? "{}" : settingsJson);
	}

	public static String fileName(String name) {
		String clean = sanitize(name);
		if (clean == null) {
			clean = DEFAULT_NAME;
		}
		return "p-" + (clean.hashCode() & 0x7fffffff) + ".json";
	}

	public static void writeProfile(File dir, String name, String settingsJson) throws IOException {
		String clean = sanitize(name);
		if (clean == null) {
			throw new IOException("profile name");
		}
		if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
			throw new IOException("profiles dir");
		}
		JSONObject payload = new JSONObject();
		put(payload, "type", TYPE);
		put(payload, "version", 1);
		put(payload, "name", clean);
		put(payload, "settings", parseSettings(settingsJson));
		byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
		File dest = new File(dir, fileName(clean));
		File tmp = new File(dir, fileName(clean) + ".tmp");
		FileOutputStream out = new FileOutputStream(tmp);
		try {
			out.write(bytes);
			out.flush();
		} finally {
			out.close();
		}
		if (dest.exists() && !dest.delete()) {
			tmp.delete();
			throw new IOException("replace " + dest.getName());
		}
		if (!tmp.renameTo(dest)) {
			FileOutputStream direct = new FileOutputStream(dest);
			try {
				direct.write(bytes);
			} finally {
				direct.close();
			}
			tmp.delete();
		}
	}

	public static void deleteProfile(File dir, String name) {
		String clean = sanitize(name);
		if (clean == null || dir == null) {
			return;
		}
		File dest = new File(dir, fileName(clean));
		if (dest.exists()) {
			dest.delete();
		}
	}

	public static List<String> namesFromDir(File dir) {
		List<String> names = new ArrayList<>();
		if (dir == null || !dir.isDirectory()) {
			return names;
		}
		File[] files = dir.listFiles();
		if (files == null) {
			return names;
		}
		for (File file : files) {
			if (!file.isFile() || !file.getName().endsWith(".json") || file.getName().endsWith(".tmp")) {
				continue;
			}
			String name = readName(file);
			if (name != null && !name.isEmpty()) {
				names.add(name);
			}
		}
		return names;
	}

	public static String readSettings(File dir, String name) {
		String clean = sanitize(name);
		if (clean == null || dir == null) {
			return null;
		}
		File file = new File(dir, fileName(clean));
		if (!file.isFile()) {
			return null;
		}
		try {
			JSONObject json = new JSONObject(readText(file));
			JSONObject settings = json.optJSONObject("settings");
			return settings == null ? null : settings.toString();
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Names shown in the profile list. A profile that was written to disk still
	 * appears when the preference JSON does not contain it.
	 */
	public static List<String> visibleNames(String storeJson, File dir) {
		LinkedHashSet<String> names = new LinkedHashSet<>();
		names.addAll(namesFromStore(storeJson));
		names.addAll(namesFromDir(dir));
		return new ArrayList<>(names);
	}

	private static String readName(File file) {
		try {
			JSONObject json = new JSONObject(readText(file));
			if (!TYPE.equals(json.optString("type"))) {
				return null;
			}
			String name = sanitize(json.optString("name", ""));
			return name;
		} catch (Exception e) {
			return null;
		}
	}

	private static String readText(File file) throws IOException {
		FileInputStream in = new FileInputStream(file);
		try {
			byte[] buf = new byte[(int) file.length()];
			int off = 0;
			while (off < buf.length) {
				int n = in.read(buf, off, buf.length - off);
				if (n < 0) {
					break;
				}
				off += n;
			}
			return new String(buf, 0, off, StandardCharsets.UTF_8);
		} finally {
			in.close();
		}
	}

	private static JSONObject root(String raw) {
		if (raw == null || raw.trim().isEmpty()) {
			JSONObject root = new JSONObject();
			put(root, "profiles", new JSONArray());
			return root;
		}
		try {
			return new JSONObject(raw);
		} catch (Exception e) {
			JSONObject root = new JSONObject();
			put(root, "profiles", new JSONArray());
			return root;
		}
	}

	private static JSONArray profiles(String raw) {
		JSONArray arr = root(raw).optJSONArray("profiles");
		return arr == null ? new JSONArray() : arr;
	}

	private static void add(JSONArray arr, Object value) {
		try {
			arr.put(value);
		} catch (JSONException e) {
			throw new IllegalStateException(e);
		}
	}

	private static JSONObject put(JSONObject obj, String key, Object value) {
		try {
			return obj.put(key, value);
		} catch (JSONException e) {
			throw new IllegalStateException(e);
		}
	}

	private static JSONObject parseSettings(String settingsJson) {
		if (settingsJson == null || settingsJson.trim().isEmpty()) {
			return new JSONObject();
		}
		try {
			return new JSONObject(settingsJson);
		} catch (Exception e) {
			return new JSONObject();
		}
	}
}
