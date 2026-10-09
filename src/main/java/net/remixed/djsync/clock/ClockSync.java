package net.remixed.djsync.clock;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;

/**
 * Client-side clock synchronisation (an NTP-style estimate), the algorithm each phone runs.
 *
 * <p>The client sends a ping at local time {@code t0}; the server answers with its own
 * time {@code s}; the client receives it at local time {@code t1}. Assuming the trip
 * there took as long as the trip back,
 * <pre>
 *   offset = s - (t0 + t1) / 2          serverTime ≈ localTime + offset
 *   error  ≤ (t1 - t0) / 2              half the round trip
 * </pre>
 * The error bound shrinks with the round-trip time, so of the recent samples the one
 * with the <em>smallest</em> round trip is trusted — slow samples (queueing, Wi-Fi
 * retries) are the ones with lopsided delays.
 *
 * <p>This lives in the server project so it can be tested against the server in a
 * simulation; the app ports the same few lines to TypeScript.
 */
public final class ClockSync {

    /** Keep this many recent samples; old ones age out so clock drift is followed. */
    public static final int WINDOW = 8;

    private record Sample(long rtt, long offset) {}

    private final Deque<Sample> samples = new ArrayDeque<>();

    /** Record one ping/pong exchange (all values in milliseconds). */
    public void addSample(long clientSent, long serverTime, long clientReceived) {
        long rtt = clientReceived - clientSent;
        if (rtt < 0) return;                       // local clock jumped mid-exchange; ignore
        long offset = serverTime - (clientSent + clientReceived) / 2;
        samples.addLast(new Sample(rtt, offset));
        if (samples.size() > WINDOW) samples.removeFirst();
    }

    public boolean ready() {
        return !samples.isEmpty();
    }

    /** Best estimate of serverTime - localTime. */
    public long offset() {
        return best().offset();
    }

    /** Upper bound on the error of {@link #offset()}: half the best round trip. */
    public long errorBound() {
        return best().rtt() / 2;
    }

    public long serverNow(long localNow) {
        return localNow + offset();
    }

    private Sample best() {
        return samples.stream().min(Comparator.comparingLong(Sample::rtt))
                .orElseThrow(() -> new IllegalStateException("no samples yet"));
    }
}
