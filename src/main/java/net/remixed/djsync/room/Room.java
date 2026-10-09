package net.remixed.djsync.room;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * One DJ Room: the queue, who the DJ is, and playback — all owned by the server.
 *
 * <p>Rules (matching the app):
 * <ul>
 *   <li>Anyone in the room can add tracks. The first person to add one becomes the DJ,
 *       and the first track starts playing.</li>
 *   <li>Only the DJ can play, pause, seek, skip or remove tracks.</li>
 *   <li>Anyone can take over as DJ. If the DJ leaves, the longest-present member takes over.</li>
 *   <li>When a track ends the queue advances; the DJ's "ended" report is idempotent so
 *       duplicates (or a late report for an old track) are ignored.</li>
 * </ul>
 *
 * <p>Methods are synchronized: a room is small and contended only by its own members,
 * so one lock per room is simpler and cheaper than anything finer-grained.
 */
public final class Room {

    public static final int MAX_QUEUE = 200;

    private final String id;
    private final LongSupplier clock;
    /** userId -> open connections (a user may be connected from two devices). Insertion order = arrival. */
    private final Map<String, Integer> members = new LinkedHashMap<>();
    private final List<Track> queue = new ArrayList<>();
    private int index = -1;
    private String hostId;
    private Playback playback;
    private long version;
    private long emptySince;

    public Room(String id, LongSupplier clock) {
        this.id = id;
        this.clock = clock;
        this.playback = Playback.idle(clock.getAsLong());
        this.emptySince = clock.getAsLong();
    }

    public String id() {
        return id;
    }

    // ── membership ──────────────────────────────────────────────────────────

    public synchronized RoomSnapshot join(String userId) {
        members.merge(userId, 1, Integer::sum);
        return changed();
    }

    public synchronized RoomSnapshot leave(String userId) {
        Integer n = members.get(userId);
        if (n == null) return snapshot();
        if (n > 1) {
            members.put(userId, n - 1);
            return snapshot();            // still connected elsewhere: nothing visible changed
        }
        members.remove(userId);
        if (userId.equals(hostId)) {
            hostId = members.isEmpty() ? null : members.keySet().iterator().next();
        }
        if (members.isEmpty()) emptySince = clock.getAsLong();
        return changed();
    }

    public synchronized boolean isEmptyFor(long millis) {
        return members.isEmpty() && clock.getAsLong() - emptySince >= millis;
    }

    // ── queue ───────────────────────────────────────────────────────────────

    public synchronized RoomSnapshot add(String userId, Track track) {
        requireMember(userId);
        if (queue.size() >= MAX_QUEUE) throw new RoomException("queue_full", "the queue is full");
        queue.add(track);
        if (hostId == null) hostId = userId;
        if (index < 0) {                   // first track: start playing it
            index = queue.size() - 1;
            playback = Playback.start(track, clock.getAsLong());
        }
        return changed();
    }

    public synchronized RoomSnapshot remove(String userId, int at) {
        requireHost(userId);
        if (at < 0 || at >= queue.size()) throw new RoomException("bad_index", "no track at that position");
        if (at == index) throw new RoomException("bad_index", "skip the playing track instead of removing it");
        queue.remove(at);
        if (at < index) index--;
        return changed();
    }

    // ── playback (DJ only) ─────────────────────────────────────────────────

    public synchronized RoomSnapshot play(String userId) {
        requireHost(userId);
        requireTrack();
        if (!playback.playing()) playback = playback.resumedAt(clock.getAsLong());
        return changed();
    }

    public synchronized RoomSnapshot pause(String userId) {
        requireHost(userId);
        requireTrack();
        if (playback.playing()) playback = playback.pausedAt(clock.getAsLong());
        return changed();
    }

    public synchronized RoomSnapshot seek(String userId, long positionMs) {
        requireHost(userId);
        requireTrack();
        playback = playback.seekedTo(positionMs, clock.getAsLong());
        return changed();
    }

    public synchronized RoomSnapshot next(String userId) {
        requireHost(userId);
        requireTrack();
        return advance();
    }

    /** The DJ's player finished {@code trackId}. Ignored unless it's the track still playing. */
    public synchronized RoomSnapshot ended(String userId, String trackId) {
        requireHost(userId);
        if (playback.trackId() == null || !playback.trackId().equals(trackId)) return snapshot();
        return advance();
    }

    public synchronized RoomSnapshot claimHost(String userId) {
        requireMember(userId);
        hostId = userId;
        return changed();
    }

    public synchronized RoomSnapshot snapshot() {
        return new RoomSnapshot(id, version, hostId, List.copyOf(queue), index, playback,
                List.copyOf(members.keySet()));
    }

    // ── internals ──────────────────────────────────────────────────────────

    private RoomSnapshot advance() {
        long now = clock.getAsLong();
        if (index < queue.size() - 1) {
            index++;
            playback = Playback.start(queue.get(index), now);
        } else {
            playback = playback.pausedAt(now);   // end of queue: stop on the last track
        }
        return changed();
    }

    private RoomSnapshot changed() {
        version++;
        return snapshot();
    }

    private void requireMember(String userId) {
        if (!members.containsKey(userId)) throw new RoomException("not_member", "join the room first");
    }

    private void requireHost(String userId) {
        requireMember(userId);
        if (!userId.equals(hostId)) throw new RoomException("not_host", "only the DJ can do that");
    }

    private void requireTrack() {
        if (index < 0) throw new RoomException("empty", "add a track first");
    }
}
