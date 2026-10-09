package net.remixed.djsync.room;

import java.util.Objects;

/** A queued track. Only what listeners need to play it; the app fetches the rest itself. */
public record Track(String id, String title, String artist, String cover, String audioUrl, long durationMs) {

    static final long MAX_DURATION_MS = 60L * 60 * 1000;   // an hour; longer is almost certainly bad data
    private static final int MAX_TEXT = 300;
    private static final int MAX_URL = 1000;

    public Track {
        Objects.requireNonNull(id, "id");
        if (id.isBlank() || id.length() > 64) throw new RoomException("bad_track", "invalid track id");
        if (audioUrl == null || !audioUrl.startsWith("https://") || audioUrl.length() > MAX_URL) {
            throw new RoomException("bad_track", "audioUrl must be an https URL");
        }
        if (durationMs <= 0 || durationMs > MAX_DURATION_MS) {
            throw new RoomException("bad_track", "invalid duration");
        }
        title = clip(title);
        artist = clip(artist);
        cover = cover == null || cover.length() > MAX_URL ? null : cover;
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }
}
