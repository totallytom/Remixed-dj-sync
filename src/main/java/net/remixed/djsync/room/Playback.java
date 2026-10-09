package net.remixed.djsync.room;

/**
 * Where playback is, expressed against the server clock.
 *
 * <p>Instead of streaming positions, the server sends an anchor: "at server time
 * {@code anchorMs} the position was {@code positionMs}". While playing, every
 * client computes {@code positionMs + (serverNow - anchorMs)} itself, so a state
 * message is only needed when something changes (play, pause, seek, next).
 */
public record Playback(String trackId, long positionMs, long anchorMs, boolean playing, long durationMs) {

    public static Playback idle(long now) {
        return new Playback(null, 0, now, false, 0);
    }

    public static Playback start(Track track, long now) {
        return new Playback(track.id(), 0, now, true, track.durationMs());
    }

    /** Position at server time {@code now}, never past the end of the track. */
    public long positionAt(long now) {
        long pos = playing ? positionMs + Math.max(0, now - anchorMs) : positionMs;
        return Math.min(pos, durationMs);
    }

    Playback pausedAt(long now) {
        return new Playback(trackId, positionAt(now), now, false, durationMs);
    }

    Playback resumedAt(long now) {
        return new Playback(trackId, positionMs, now, true, durationMs);
    }

    Playback seekedTo(long position, long now) {
        long clamped = Math.max(0, Math.min(position, durationMs));
        return new Playback(trackId, clamped, now, playing, durationMs);
    }
}
