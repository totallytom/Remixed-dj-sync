package net.remixed.djsync.config;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.LongSupplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import net.remixed.djsync.auth.RoomAccess;
import net.remixed.djsync.auth.SupabaseRoomAccess;
import net.remixed.djsync.auth.SupabaseTokenVerifier;
import net.remixed.djsync.auth.TokenVerifier;
import net.remixed.djsync.room.RoomRegistry;
import net.remixed.djsync.ws.SyncSocketHandler;

@Configuration
@EnableWebSocket
@EnableScheduling
public class SyncConfig implements WebSocketConfigurer {

    private final SyncSocketHandler handler;
    private final RoomRegistry rooms;

    public SyncConfig(SyncSocketHandler handler, RoomRegistry rooms) {
        this.handler = handler;
        this.rooms = rooms;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Auth is a token inside the first frame, not a cookie, so cross-site WebSocket
        // hijacking doesn't apply and any origin (the app sends none) is fine.
        registry.addHandler(handler, "/rooms").setAllowedOriginPatterns("*");
    }

    /** Evict rooms that have been empty past the grace period. */
    @Scheduled(fixedDelay = 30_000)
    void evictIdleRooms() {
        rooms.evictIdle();
    }

    @Configuration
    static class Beans {

        @Bean
        LongSupplier clock() {
            return System::currentTimeMillis;
        }

        @Bean
        RoomRegistry roomRegistry(LongSupplier clock) {
            return new RoomRegistry(clock);
        }

        @Bean
        TokenVerifier tokenVerifier(SyncProperties props) {
            return new SupabaseTokenVerifier(props.supabaseUrl(), props.supabaseJwtSecret());
        }

        @Bean
        RoomAccess roomAccess(SyncProperties props, LongSupplier clock) {
            return new SupabaseRoomAccess(props.supabaseUrl(), props.supabaseServiceKey(), clock);
        }

        @Bean(destroyMethod = "shutdownNow")
        ScheduledExecutorService syncScheduler() {
            return Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "dj-sync-timer");
                t.setDaemon(true);
                return t;
            });
        }

        @Bean
        SyncSocketHandler syncSocketHandler(ObjectMapper json, RoomRegistry rooms, TokenVerifier tokens,
                                            RoomAccess access, LongSupplier clock, ScheduledExecutorService syncScheduler) {
            return new SyncSocketHandler(json, rooms, tokens, access, clock, syncScheduler);
        }

        /** Small frames only, and drop connections that go quiet (clients ping every ~20 s). */
        @Bean
        ServletServerContainerFactoryBean webSocketContainer() {
            var container = new ServletServerContainerFactoryBean();
            container.setMaxTextMessageBufferSize(32 * 1024);
            container.setMaxSessionIdleTimeout(120_000L);
            return container;
        }
    }
}
