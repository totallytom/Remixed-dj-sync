package net.remixed.djsync;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import net.remixed.djsync.room.RoomRegistry;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DjSyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(DjSyncApplication.class, args);
    }

    @RestController
    static class Health {
        private final RoomRegistry rooms;

        Health(RoomRegistry rooms) {
            this.rooms = rooms;
        }

        @GetMapping("/health")
        Map<String, Object> health() {
            return Map.of("status", "ok", "rooms", rooms.size());
        }
    }
}
