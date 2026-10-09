package net.remixed.djsync.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RoomTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private Room room;

    static Track track(String id, long durationMs) {
        return new Track(id, "Title " + id, "Artist", null, "https://cdn.example/" + id + ".mp3", durationMs);
    }

    @BeforeEach
    void setUp() {
        room = new Room("a_b", now::get);
        room.join("alice");
        room.join("bob");
    }

    @Test
    void firstAdderBecomesDjAndTrackStarts() {
        RoomSnapshot s = room.add("alice", track("t1", 180_000));
        assertThat(s.hostId()).isEqualTo("alice");
        assertThat(s.index()).isZero();
        assertThat(s.playback().playing()).isTrue();
        assertThat(s.playback().trackId()).isEqualTo("t1");
    }

    @Test
    void laterTracksQueueWithoutInterrupting() {
        room.add("alice", track("t1", 180_000));
        RoomSnapshot s = room.add("bob", track("t2", 200_000));
        assertThat(s.queue()).hasSize(2);
        assertThat(s.playback().trackId()).isEqualTo("t1");
        assertThat(s.hostId()).isEqualTo("alice");        // bob added a track but isn't DJ
    }

    @Test
    void positionAdvancesWithServerTimeWhilePlaying() {
        room.add("alice", track("t1", 180_000));
        now.addAndGet(42_000);
        assertThat(room.snapshot().playback().positionAt(now.get())).isEqualTo(42_000);
    }

    @Test
    void pauseFreezesPositionAndPlayResumesFromIt() {
        room.add("alice", track("t1", 180_000));
        now.addAndGet(10_000);
        room.pause("alice");
        now.addAndGet(60_000);                              // paused for a minute
        assertThat(room.snapshot().playback().positionAt(now.get())).isEqualTo(10_000);
        room.play("alice");
        now.addAndGet(5_000);
        assertThat(room.snapshot().playback().positionAt(now.get())).isEqualTo(15_000);
    }

    @Test
    void seekIsClampedToTheTrack() {
        room.add("alice", track("t1", 180_000));
        assertThat(room.seek("alice", 999_999).playback().positionMs()).isEqualTo(180_000);
        assertThat(room.seek("alice", -5).playback().positionMs()).isZero();
    }

    @Test
    void positionNeverRunsPastTheEnd() {
        room.add("alice", track("t1", 10_000));
        now.addAndGet(60_000);
        assertThat(room.snapshot().playback().positionAt(now.get())).isEqualTo(10_000);
    }

    @Test
    void onlyTheDjControlsPlayback() {
        room.add("alice", track("t1", 180_000));
        assertThatThrownBy(() -> room.pause("bob")).isInstanceOf(RoomException.class).hasMessageContaining("DJ");
        assertThatThrownBy(() -> room.seek("bob", 1)).isInstanceOf(RoomException.class);
        assertThatThrownBy(() -> room.next("bob")).isInstanceOf(RoomException.class);
    }

    @Test
    void nonMembersCantDoAnything() {
        assertThatThrownBy(() -> room.add("mallory", track("x", 1000)))
                .isInstanceOf(RoomException.class).extracting("code").isEqualTo("not_member");
    }

    @Test
    void endedAdvancesOnceEvenIfReportedTwice() {
        room.add("alice", track("t1", 180_000));
        room.add("alice", track("t2", 180_000));
        room.add("alice", track("t3", 180_000));
        room.ended("alice", "t1");
        RoomSnapshot s = room.ended("alice", "t1");          // duplicate report for the old track
        assertThat(s.index()).isEqualTo(1);
        assertThat(s.playback().trackId()).isEqualTo("t2");
    }

    @Test
    void endOfQueueStopsOnTheLastTrack() {
        room.add("alice", track("t1", 5_000));
        now.addAndGet(5_000);
        RoomSnapshot s = room.ended("alice", "t1");
        assertThat(s.playback().playing()).isFalse();
        assertThat(s.playback().trackId()).isEqualTo("t1");
    }

    @Test
    void djLeavingHandsOverToLongestPresentMember() {
        room.join("carol");
        room.add("alice", track("t1", 180_000));
        assertThat(room.leave("alice").hostId()).isEqualTo("bob");
    }

    @Test
    void djWithTwoDevicesStaysDjUntilBothDisconnect() {
        room.join("alice");                                  // second device
        room.add("alice", track("t1", 180_000));
        assertThat(room.leave("alice").hostId()).isEqualTo("alice");
        assertThat(room.leave("alice").hostId()).isEqualTo("bob");
    }

    @Test
    void anyoneCanTakeOver() {
        room.add("alice", track("t1", 180_000));
        assertThat(room.claimHost("bob").hostId()).isEqualTo("bob");
        room.pause("bob");                                   // now allowed
        assertThatThrownBy(() -> room.pause("alice")).isInstanceOf(RoomException.class);
    }

    @Test
    void removingAnEarlierTrackKeepsTheCurrentOnePlaying() {
        room.add("alice", track("t1", 1000));
        room.add("alice", track("t2", 1000));
        room.add("alice", track("t3", 1000));
        room.next("alice");                                  // now on t2 (index 1)
        RoomSnapshot s = room.remove("alice", 0);
        assertThat(s.index()).isZero();
        assertThat(s.queue().get(s.index()).id()).isEqualTo("t2");
        assertThatThrownBy(() -> room.remove("alice", 0)).hasMessageContaining("skip");
    }

    @Test
    void versionIncreasesOnEveryChange() {
        long v = room.snapshot().version();
        room.add("alice", track("t1", 1000));
        room.pause("alice");
        assertThat(room.snapshot().version()).isEqualTo(v + 2);
    }

    @Test
    void queueIsBounded() {
        for (int i = 0; i < Room.MAX_QUEUE; i++) room.add("alice", track("t" + i, 1000));
        assertThatThrownBy(() -> room.add("alice", track("one-more", 1000))).hasMessageContaining("full");
    }

    @Test
    void rejectsBadTracks() {
        assertThatThrownBy(() -> track("x", 0)).isInstanceOf(RoomException.class);
        assertThatThrownBy(() -> new Track("x", "", "", null, "http://insecure", 1000))
                .isInstanceOf(RoomException.class);
    }

    @Test
    void emptyRoomIsEvictedOnlyAfterGracePeriod() {
        var registry = new RoomRegistry(now::get);
        Room r = registry.getOrCreate("x_y");
        r.join("u");
        r.leave("u");
        now.addAndGet(RoomRegistry.EMPTY_GRACE_MS - 1);
        assertThat(registry.evictIdle()).isZero();
        now.addAndGet(1);
        assertThat(registry.evictIdle()).isEqualTo(1);
    }
}
