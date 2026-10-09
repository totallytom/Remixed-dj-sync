package net.remixed.djsync.room;

import java.util.List;

/** Immutable copy of a room, sent to every member after each change. */
public record RoomSnapshot(
        String roomId,
        long version,
        String hostId,
        List<Track> queue,
        int index,
        Playback playback,
        List<String> members
) {}
