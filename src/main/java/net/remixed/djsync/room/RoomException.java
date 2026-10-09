package net.remixed.djsync.room;

/** A rejected room action; `code` is sent to the client (e.g. "not_host"). */
public class RoomException extends RuntimeException {
    private final String code;

    public RoomException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
