package net.remixed.djsync.ws;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import net.remixed.djsync.auth.RoomAccess;
import net.remixed.djsync.auth.TokenVerifier;
import net.remixed.djsync.room.Room;
import net.remixed.djsync.room.RoomException;
import net.remixed.djsync.room.RoomRegistry;
import net.remixed.djsync.room.RoomSnapshot;
import net.remixed.djsync.room.Track;

/**
 * The DJ Room protocol over one WebSocket per client. JSON text frames, field "t" = type.
 *
 * <pre>
 * client → server                       server → client
 * hello  {token, room}   (first frame)  welcome {you, s}  then state
 * ping   {c}                            pong    {c, s}
 * add    {track}                        state   {s, room}   after every change, to everyone
 * remove {index}                        error   {code, message}
 * play | pause | next | claim
 * seek   {positionMs}
 * ended  {trackId}
 * </pre>
 * {@code s} is always the server clock in ms; clients turn it into local time with
 * {@link net.remixed.djsync.clock.ClockSync}.
 */
public final class SyncSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SyncSocketHandler.class);

    static final CloseStatus UNAUTHORIZED = new CloseStatus(4401, "unauthorized");
    static final CloseStatus FORBIDDEN = new CloseStatus(4403, "not a member of this room");
    static final CloseStatus HELLO_TIMEOUT = new CloseStatus(4408, "hello not received");
    static final CloseStatus TOO_MANY = new CloseStatus(4429, "too many messages");
    static final int MAX_FRAME_CHARS = 16 * 1024;
    private static final long HELLO_TIMEOUT_MS = 10_000;
    private static final int MAX_VIOLATIONS = 20;

    /** One client connection. */
    private static final class Conn {
        final WebSocketSession session;
        final RateLimiter limiter;
        volatile String userId;
        volatile String roomId;
        int violations;

        Conn(WebSocketSession session, RateLimiter limiter) {
            this.session = session;
            this.limiter = limiter;
        }
    }

    private final ObjectMapper json;
    private final RoomRegistry rooms;
    private final TokenVerifier tokens;
    private final RoomAccess access;
    private final LongSupplier clock;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Conn> conns = new ConcurrentHashMap<>();
    private final Map<String, Set<Conn>> byRoom = new ConcurrentHashMap<>();

    public SyncSocketHandler(ObjectMapper json, RoomRegistry rooms, TokenVerifier tokens, RoomAccess access,
                             LongSupplier clock, ScheduledExecutorService scheduler) {
        this.json = json;
        this.rooms = rooms;
        this.tokens = tokens;
        this.access = access;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) {
        // Spring sessions aren't safe for concurrent sends; broadcasts come from many threads.
        var session = new ConcurrentWebSocketSessionDecorator(raw, 5_000, 256 * 1024);
        var conn = new Conn(session, new RateLimiter(20, 10, clock));
        conns.put(raw.getId(), conn);
        scheduler.schedule(() -> {
            if (conn.userId == null) close(conn, HELLO_TIMEOUT);
        }, HELLO_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
        Conn conn = conns.get(raw.getId());
        if (conn == null) return;
        if (message.getPayloadLength() > MAX_FRAME_CHARS) {
            close(conn, CloseStatus.TOO_BIG_TO_PROCESS);
            return;
        }
        if (!conn.limiter.tryAcquire()) {
            if (++conn.violations > MAX_VIOLATIONS) close(conn, TOO_MANY);
            else sendError(conn, "rate_limited", "slow down");
            return;
        }

        JsonNode msg;
        try {
            msg = json.readTree(message.getPayload());
        } catch (IOException e) {
            sendError(conn, "bad_message", "not valid JSON");
            return;
        }
        String type = msg.path("t").asText("");

        if (conn.userId == null) {
            if ("hello".equals(type)) hello(conn, msg);
            else close(conn, UNAUTHORIZED);
            return;
        }
        if ("ping".equals(type)) {
            ObjectNode pong = json.createObjectNode().put("t", "pong").put("s", clock.getAsLong());
            pong.set("c", msg.path("c"));
            send(conn, pong);
            return;
        }

        Room room = rooms.getOrCreate(conn.roomId);
        try {
            RoomSnapshot after = switch (type) {
                case "add" -> room.add(conn.userId, readTrack(msg.path("track")));
                case "remove" -> room.remove(conn.userId, msg.path("index").asInt(-1));
                case "play" -> room.play(conn.userId);
                case "pause" -> room.pause(conn.userId);
                case "seek" -> room.seek(conn.userId, msg.path("positionMs").asLong(0));
                case "next" -> room.next(conn.userId);
                case "ended" -> room.ended(conn.userId, msg.path("trackId").asText(""));
                case "claim" -> room.claimHost(conn.userId);
                default -> throw new RoomException("bad_message", "unknown type: " + type);
            };
            broadcast(after);
        } catch (RoomException e) {
            sendError(conn, e.code(), e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        Conn conn = conns.remove(raw.getId());
        if (conn == null || conn.roomId == null) return;
        Set<Conn> members = byRoom.get(conn.roomId);
        if (members != null) members.remove(conn);
        Room room = rooms.get(conn.roomId);
        if (room != null) broadcast(room.leave(conn.userId));
    }

    @Override
    public void handleTransportError(WebSocketSession raw, Throwable error) {
        log.debug("transport error on {}: {}", raw.getId(), error.toString());
    }

    // ── handshake ──────────────────────────────────────────────────────────

    private void hello(Conn conn, JsonNode msg) {
        String userId;
        try {
            userId = tokens.verify(msg.path("token").asText(""));
        } catch (TokenVerifier.InvalidTokenException e) {
            close(conn, UNAUTHORIZED);
            return;
        }
        String roomId = msg.path("room").asText("");
        if (roomId.isEmpty() || roomId.length() > 100 || !access.canJoin(userId, roomId)) {
            close(conn, FORBIDDEN);
            return;
        }
        conn.userId = userId;
        conn.roomId = roomId;
        byRoom.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(conn);
        send(conn, json.createObjectNode().put("t", "welcome").put("you", userId).put("s", clock.getAsLong()));
        broadcast(rooms.getOrCreate(roomId).join(userId));
    }

    // ── sending ────────────────────────────────────────────────────────────

    private void broadcast(RoomSnapshot snapshot) {
        ObjectNode state = json.createObjectNode().put("t", "state").put("s", clock.getAsLong());
        state.set("room", json.valueToTree(snapshot));
        for (Conn c : byRoom.getOrDefault(snapshot.roomId(), Set.of())) send(c, state);
    }

    private void sendError(Conn conn, String code, String message) {
        send(conn, json.createObjectNode().put("t", "error").put("code", code).put("message", message));
    }

    private void send(Conn conn, JsonNode node) {
        try {
            conn.session.sendMessage(new TextMessage(json.writeValueAsString(node)));
        } catch (IOException | IllegalStateException e) {
            log.debug("send failed to {}: {}", conn.session.getId(), e.toString());
        }
    }

    private void close(Conn conn, CloseStatus status) {
        try {
            conn.session.close(status);
        } catch (IOException ignored) {
            // already gone
        }
    }

    private static Track readTrack(JsonNode t) {
        if (!t.isObject()) throw new RoomException("bad_track", "track missing");
        return new Track(t.path("id").asText(""), t.path("title").asText(""), t.path("artist").asText(""),
                t.hasNonNull("cover") ? t.path("cover").asText() : null,
                t.path("audioUrl").asText(""), t.path("durationMs").asLong(0));
    }
}
