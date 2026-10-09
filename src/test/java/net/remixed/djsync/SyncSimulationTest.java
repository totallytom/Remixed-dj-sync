package net.remixed.djsync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import net.remixed.djsync.clock.ClockSync;
import net.remixed.djsync.room.Playback;
import net.remixed.djsync.room.Room;
import net.remixed.djsync.room.Track;

/**
 * The reason this server exists, as a test.
 *
 * <p>Simulates a DJ Room with phones whose clocks are wrong by up to ±5 minutes and whose
 * network delay is random and lopsided (10–150 ms each way). Each phone syncs its clock
 * against the server, then computes where the song should be at random moments.
 *
 * <p>Compares against the approach the app used before: trusting each phone's own clock
 * for timestamps. With that, playback is off by exactly the clock error.
 */
class SyncSimulationTest {

    private static final long SKEW_LIMIT_MS = 5 * 60 * 1000;

    /** A phone: its clock is server time + skew; network delays are random each way. */
    private record Phone(long skew, Random net) {
        long localTime(long trueTime) {
            return trueTime + skew;
        }

        long delay() {
            return 10 + net.nextInt(141);         // 10..150 ms
        }
    }

    @Test
    void phonesWithWrongClocksStayWithinTensOfMilliseconds() {
        var rnd = new Random(2026);
        var trueTime = new AtomicLong(1_700_000_000_000L);
        var room = new Room("a_b", trueTime::get);
        room.join("dj");

        List<Phone> phones = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long skew = (long) ((rnd.nextDouble() * 2 - 1) * SKEW_LIMIT_MS);
            phones.add(new Phone(skew, new Random(rnd.nextLong())));
        }

        // 1. Each phone does 8 ping/pong exchanges, a few seconds apart.
        List<ClockSync> syncs = new ArrayList<>();
        for (Phone p : phones) {
            var sync = new ClockSync();
            for (int k = 0; k < 8; k++) {
                long t0True = trueTime.get();
                long serverStamp = t0True + p.delay();                 // ping arrives
                long t1True = serverStamp + p.delay();                 // pong arrives
                sync.addSample(p.localTime(t0True), serverStamp, p.localTime(t1True));
                trueTime.addAndGet(3_000);
            }
            syncs.add(sync);
        }

        // 2. The DJ starts a 4-minute track; it plays for a while, gets paused, seeked, resumed.
        room.add("dj", new Track("song", "Song", "Artist", null, "https://cdn.example/song.mp3", 240_000));
        trueTime.addAndGet(37_000);
        room.pause("dj");
        trueTime.addAndGet(5_000);
        room.seek("dj", 90_000);
        room.play("dj");
        Playback playback = room.snapshot().playback();

        // 3. At random later moments, every phone works out the position on its own.
        long worstSynced = 0, worstNaive = 0;
        for (int check = 0; check < 50; check++) {
            trueTime.addAndGet(rnd.nextInt(2_000));
            long truth = playback.positionAt(trueTime.get());
            for (int i = 0; i < phones.size(); i++) {
                long local = phones.get(i).localTime(trueTime.get());
                long synced = playback.positionAt(syncs.get(i).serverNow(local));
                long naive = playback.positionAt(local);              // trusts the phone's clock
                worstSynced = Math.max(worstSynced, Math.abs(synced - truth));
                worstNaive = Math.max(worstNaive, Math.abs(naive - truth));
            }
        }

        // With clock sync: bounded by half the network round trip (here at most ~70 ms).
        assertThat(worstSynced).isLessThanOrEqualTo(75);
        // Without it: off by the clock error itself — tens of seconds or more, i.e. a different part of the song.
        assertThat(worstNaive).isGreaterThan(30_000);
        System.out.printf("worst error: synced %d ms, phone-clock %d ms%n", worstSynced, worstNaive);
    }
}
