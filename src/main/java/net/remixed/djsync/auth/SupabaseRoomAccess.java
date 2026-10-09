package net.remixed.djsync.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Room ids are the app's conversation ids:
 * <ul>
 *   <li>DM: {@code "<userA>_<userB>"}, the two user ids sorted. Membership is in the id
 *       itself, so no database call is needed.</li>
 *   <li>Group: the group's UUID. Checked against {@code group_chat_members} through
 *       Supabase's REST API with the service-role key; answers are cached briefly.</li>
 * </ul>
 */
public final class SupabaseRoomAccess implements RoomAccess {

    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern UUID_RE = Pattern.compile(UUID);
    private static final Pattern DM_RE = Pattern.compile("(" + UUID + ")_(" + UUID + ")");
    private static final long CACHE_MS = 60_000;

    private record Cached(boolean allowed, long at) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final String restUrl;
    private final String serviceKey;
    private final LongSupplier clock;

    public SupabaseRoomAccess(String supabaseUrl, String serviceKey, LongSupplier clock) {
        this.restUrl = supabaseUrl.replaceAll("/+$", "") + "/rest/v1";
        this.serviceKey = serviceKey;
        this.clock = clock;
    }

    @Override
    public boolean canJoin(String userId, String roomId) {
        var dm = DM_RE.matcher(roomId);
        if (dm.matches()) {
            String a = dm.group(1), b = dm.group(2);
            // The app always sorts the pair; an unsorted id isn't a real conversation.
            return a.compareTo(b) < 0 && (userId.equals(a) || userId.equals(b));
        }
        if (UUID_RE.matcher(roomId).matches()) return isGroupMember(userId, roomId);
        return false;
    }

    private boolean isGroupMember(String userId, String groupId) {
        String key = groupId + ":" + userId;
        Cached hit = cache.get(key);
        long now = clock.getAsLong();
        if (hit != null && now - hit.at() < CACHE_MS) return hit.allowed();

        String query = "group_id=eq." + enc(groupId) + "&user_id=eq." + enc(userId) + "&select=user_id&limit=1";
        HttpRequest req = HttpRequest.newBuilder(URI.create(restUrl + "/group_chat_members?" + query))
                .timeout(Duration.ofSeconds(5))
                .header("apikey", serviceKey)
                .header("Authorization", "Bearer " + serviceKey)
                .GET().build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            boolean allowed = res.statusCode() == 200 && res.body().contains(userId);
            cache.put(key, new Cached(allowed, now));
            return allowed;
        } catch (Exception e) {
            return false;                          // fail closed; the client retries
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
