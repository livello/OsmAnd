package net.osmand.evbms;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

public class EvBmsProfileCatalogTest {

	@Test
	public void savedNameAppearsInTheList() {
		String store = EvBmsProfileCatalog.upsert("", "Default", "{}");
		store = EvBmsProfileCatalog.upsert(store, "Город", "{\"a\":\"1\"}");
		store = store.trim();
		List<String> names = EvBmsProfileCatalog.namesFromStore(store);
		Assert.assertTrue(names.contains("Default"));
		Assert.assertTrue(names.contains("Город"));
		Assert.assertEquals("Город", names.get(names.size() - 1));
	}

	@Test
	public void fileProfileAppearsWhenPreferenceJsonMissedIt() throws Exception {
		File dir = Files.createTempDirectory("ev-profiles").toFile();
		try {
			EvBmsProfileCatalog.writeProfile(dir, "Трасса", "{\"hud\":\"1\"}");
			String prefs = EvBmsProfileCatalog.upsert("", EvBmsProfileCatalog.DEFAULT_NAME, "{}");
			List<String> shown = EvBmsProfileCatalog.visibleNames(prefs, dir);
			Assert.assertTrue("saved profile must be in the general list", shown.contains("Трасса"));
			Assert.assertTrue(shown.contains("Default"));
			Assert.assertNotNull(EvBmsProfileCatalog.readSettings(dir, "Трасса"));
		} finally {
			File[] files = dir.listFiles();
			if (files != null) {
				for (File file : files) {
					file.delete();
				}
			}
			dir.delete();
		}
	}

	@Test
	public void corruptPreferenceJsonStillListsFiles() throws Exception {
		File dir = Files.createTempDirectory("ev-profiles-bad").toFile();
		try {
			EvBmsProfileCatalog.writeProfile(dir, "Зима", "{}");
			List<String> shown = EvBmsProfileCatalog.visibleNames("{not json", dir);
			Assert.assertEquals(1, shown.size());
			Assert.assertEquals("Зима", shown.get(0));
		} finally {
			File[] files = dir.listFiles();
			if (files != null) {
				for (File file : files) {
					file.delete();
				}
			}
			dir.delete();
		}
	}

	@Test
	public void multiDayChargeOverlappingTheLogIsDropped() {
		long winStart = 1_792_208_296_793L;
		long winEnd = winStart + 34L * 60L * 60L * 1000L;
		long chargeStart = winStart - 5L * 24L * 60L * 60L * 1000L;
		long chargeEnd = winStart + 30L * 60L * 1000L;
		Assert.assertEquals(-1L, EvJournalWindow.keptEnd(chargeStart, chargeEnd, winStart, winEnd, winEnd));
		long olderEnd = winStart - 60L * 60L * 1000L;
		long olderStart = olderEnd - 40L * 60L * 1000L;
		Assert.assertEquals(olderEnd, EvJournalWindow.keptEnd(olderStart, olderEnd, winStart, winEnd, winEnd));
	}
}
