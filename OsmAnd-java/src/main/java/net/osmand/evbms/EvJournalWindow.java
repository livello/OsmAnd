package net.osmand.evbms;

/**
 * Decides which existing journal row survives when a telemetry file is rebuilt
 * over a time window. A charge that swallowed several days is dropped instead
 * of being kept beside the rides parsed from that file.
 */
public final class EvJournalWindow {

	public static final long MAX_CHARGE_MS = 16L * 60L * 60L * 1000L;
	public static final long MIN_PIECE_MS = 5L * 60L * 1000L;

	private EvJournalWindow() {
	}

	/**
	 * @return the end timestamp to keep, the original {@code endMs} when the row
	 * does not overlap the window, or {@code -1} when the row must be dropped
	 */
	public static long keptEnd(long startMs, long endMs, long winStart, long winEnd, long nowMs) {
		if (startMs <= 0L || winEnd <= winStart) {
			return endMs;
		}
		long end = endMs > startMs ? endMs : Math.max(nowMs, startMs + 1L);
		boolean overlaps = startMs < winEnd && end > winStart;
		if (!overlaps) {
			return endMs;
		}
		if (startMs >= winStart) {
			return -1L;
		}
		long clipped = winStart;
		long dur = clipped - startMs;
		if (dur < MIN_PIECE_MS || dur > MAX_CHARGE_MS) {
			return -1L;
		}
		return clipped;
	}
}
