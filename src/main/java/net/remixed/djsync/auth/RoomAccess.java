package net.remixed.djsync.auth;

/** May this user join this room? Rooms are conversations: a DM or a group chat. */
public interface RoomAccess {
    boolean canJoin(String userId, String roomId);
}
