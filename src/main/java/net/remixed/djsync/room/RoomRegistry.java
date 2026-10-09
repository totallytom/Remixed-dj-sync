package net.remixed.djsync.room;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * All live rooms, in memory. A room outlives a short disconnect (EMPTY_GRACE) so a
 * listener whose phone briefly loses signal comes back to the same queue.
 *
 * <p>Single instance by design: at Re-Mixed's scale one server holds every room.
 * Scaling out would mean routing each room to one instance (consistent hashing on
 * the room id) rather than sharing state — see the README.
 */
public final class RoomRegistry {

    public static final long EMPTY_GRACE_MS = 2 * 60 * 1000;

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public RoomRegistry(LongSupplier clock) {
        this.clock = clock;
    }

    public Room getOrCreate(String roomId) {
        return rooms.computeIfAbsent(roomId, id -> new Room(id, clock));
    }

    public Room get(String roomId) {
        return rooms.get(roomId);
    }

    public int size() {
        return rooms.size();
    }

    /** Drop rooms nobody has been in for the grace period. Called on a timer. */
    public int evictIdle() {
        int before = rooms.size();
        rooms.values().removeIf(r -> r.isEmptyFor(EMPTY_GRACE_MS));
        return before - rooms.size();
    }
}
