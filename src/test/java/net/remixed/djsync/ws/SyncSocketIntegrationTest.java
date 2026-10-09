package net.remixed.djsync.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import net.remixed.djsync.auth.RoomAccess;
import net.remixed.djsync.auth.TokenVerifier;

/** Real server on a random port, real WebSocket clients; only auth is faked. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dj-sync.supabase-url=https://example.supabase.co")
class SyncSocketIntegrationTest {

    static final String ROOM = "11111111-1111-1111-1111-111111111111_22222222-2222-2222-2222-222222222222";

    @TestConfiguration
    static class FakeAuth {
        /** Token "user:<id>" is valid for <id>; anything else is rejected. */
        @Bean @Primary
        TokenVerifier fakeTokens() {
            return token -> {
                if (!token.startsWith("user:")) throw new TokenVerifier.InvalidTokenException("bad", null);
                return token.substring(5);
            };
        }

        @Bean @Primary
        RoomAccess fakeAccess() {
            return (user, room) -> !user.equals("outsider");
        }
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;

    /** A connected test client that records everything it receives. */
    final class Client extends TextWebSocketHandler {
        final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
        final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        WebSocketSession session;

        Client connect() throws Exception {
            session = new StandardWebSocketClient()
                    .execute(this, new WebSocketHttpHeaders(), URI.create("ws://localhost:" + port + "/rooms"))
                    .get(5, TimeUnit.SECONDS);
            return this;
        }

        void send(String text) throws Exception {
            session.sendMessage(new TextMessage(text));
        }

        Client hello(String user) throws Exception {
            send("{\"t\":\"hello\",\"token\":\"user:" + user + "\",\"room\":\"" + ROOM + "\"}");
            return this;
        }

        /** Next message of the given type (skipping others). */
        JsonNode next(String type) throws InterruptedException {
            while (true) {
                JsonNode m = inbox.poll(5, TimeUnit.SECONDS);
                assertThat(m).as("expected a '%s' message", type).isNotNull();
                if (type.equals(m.path("t").asText())) return m;
            }
        }

        @Override
        protected void handleTextMessage(WebSocketSession s, TextMessage message) throws Exception {
            inbox.add(json.readTree(message.getPayload()));
        }

        @Override
        public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
            closed.complete(status);
        }
    }

    static String addTrack(String id) {
        return "{\"t\":\"add\",\"track\":{\"id\":\"" + id + "\",\"title\":\"Song\",\"artist\":\"A\","
                + "\"audioUrl\":\"https://cdn.example/" + id + ".mp3\",\"durationMs\":180000}}";
    }

    @Test
    void twoListenersSeeTheSamePlaybackAndOnlyTheDjControlsIt() throws Exception {
        Client dj = new Client().connect().hello("dj-user");
        assertThat(dj.next("welcome").path("you").asText()).isEqualTo("dj-user");
        Client fan = new Client().connect().hello("fan-user");
        fan.next("welcome");

        dj.send(addTrack("t1"));
        JsonNode djState = dj.next("state");
        while (djState.at("/room/queue").size() == 0) djState = dj.next("state");
        JsonNode fanState = fan.next("state");
        while (fanState.at("/room/queue").size() == 0) fanState = fan.next("state");

        assertThat(fanState.at("/room/hostId").asText()).isEqualTo("dj-user");
        assertThat(fanState.at("/room/playback/trackId").asText()).isEqualTo("t1");
        assertThat(fanState.at("/room/playback/playing").asBoolean()).isTrue();
        assertThat(fanState.at("/room/playback/anchorMs").asLong())
                .isEqualTo(djState.at("/room/playback/anchorMs").asLong());

        fan.send("{\"t\":\"pause\"}");
        assertThat(fan.next("error").path("code").asText()).isEqualTo("not_host");

        dj.send("{\"t\":\"pause\"}");
        assertThat(fan.next("state").at("/room/playback/playing").asBoolean()).isFalse();
    }

    @Test
    void pongCarriesServerTimeAndEchoesClientTime() throws Exception {
        Client c = new Client().connect().hello("pinger");
        c.next("welcome");
        long before = System.currentTimeMillis();
        c.send("{\"t\":\"ping\",\"c\":12345}");
        JsonNode pong = c.next("pong");
        assertThat(pong.path("c").asLong()).isEqualTo(12345);
        assertThat(pong.path("s").asLong()).isBetween(before - 1000, System.currentTimeMillis() + 1000);
    }

    @Test
    void badTokenIsDisconnected() throws Exception {
        Client c = new Client().connect();
        c.send("{\"t\":\"hello\",\"token\":\"forged\",\"room\":\"" + ROOM + "\"}");
        assertThat(c.closed.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(4401);
    }

    @Test
    void nonMemberIsDisconnected() throws Exception {
        Client c = new Client().connect().hello("outsider");
        assertThat(c.closed.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(4403);
    }

    @Test
    void commandsBeforeHelloAreRefused() throws Exception {
        Client c = new Client().connect();
        c.send("{\"t\":\"pause\"}");
        assertThat(c.closed.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(4401);
    }

    @Test
    void floodingIsThrottled() throws Exception {
        Client c = new Client().connect().hello("spammer");
        c.next("welcome");
        for (int i = 0; i < 30; i++) c.send("{\"t\":\"ping\",\"c\":" + i + "}");
        assertThat(c.next("error").path("code").asText()).isEqualTo("rate_limited");
    }

    @Test
    void healthEndpoint() throws Exception {
        var res = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(json.readTree(res.body()).path("status").asText()).isEqualTo("ok");
    }
}
