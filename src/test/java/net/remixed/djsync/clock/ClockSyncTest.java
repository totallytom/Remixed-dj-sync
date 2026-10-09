package net.remixed.djsync.clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ClockSyncTest {

    @Test
    void symmetricDelayGivesTheExactOffset() {
        var sync = new ClockSync();
        // Client clock is 5 minutes behind the server; 40 ms each way.
        long clientSent = 1_000, trueServerAtArrival = clientSent + 300_000 + 40;
        sync.addSample(clientSent, trueServerAtArrival, clientSent + 80);
        assertThat(sync.offset()).isEqualTo(300_000);
        assertThat(sync.errorBound()).isEqualTo(40);
    }

    @Test
    void lopsidedDelayErrorStaysWithinHalfTheRoundTrip() {
        var sync = new ClockSync();
        long skew = -120_000;
        // 10 ms there, 190 ms back: the worst case for the symmetric assumption.
        long sent = 50_000;
        long server = sent - skew + 10;
        sync.addSample(sent, server, sent + 200);
        long error = Math.abs(sync.offset() - (-skew));
        assertThat(error).isLessThanOrEqualTo(sync.errorBound()).isEqualTo(90);
    }

    @Test
    void trustsTheFastestRecentSample() {
        var sync = new ClockSync();
        sync.addSample(0, 1_000 + 300, 600);     // slow, lopsided sample
        sync.addSample(1_000, 2_000 + 20, 1_040); // fast sample: offset 1000
        sync.addSample(2_000, 3_000 + 250, 2_400);
        assertThat(sync.offset()).isEqualTo(1_000);
        assertThat(sync.errorBound()).isEqualTo(20);
    }

    @Test
    void oldSamplesAgeOut() {
        var sync = new ClockSync();
        sync.addSample(0, 500 + 1, 2);            // very fast, but stale once the window rolls
        for (int i = 1; i <= ClockSync.WINDOW; i++) {
            long t = i * 1_000L;
            sync.addSample(t, t + 700 + 30, t + 60);
        }
        assertThat(sync.offset()).isEqualTo(700);
    }

    @Test
    void ignoresSamplesWhereTheLocalClockJumpedBackwards() {
        var sync = new ClockSync();
        sync.addSample(10_000, 5_000, 9_000);
        assertThat(sync.ready()).isFalse();
        assertThatThrownBy(sync::offset).isInstanceOf(IllegalStateException.class);
    }
}
