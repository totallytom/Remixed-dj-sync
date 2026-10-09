package net.remixed.djsync.ws;

import java.util.function.LongSupplier;

/**
 * Token bucket per connection: bursts of {@code capacity} messages, refilled at
 * {@code perSecond}. A normal client sends a handful of pings and the odd command;
 * anything far above that is a bug or abuse.
 */
public final class RateLimiter {

    private final double capacity;
    private final double perMs;
    private final LongSupplier clock;
    private double tokens;
    private long last;

    public RateLimiter(int capacity, int perSecond, LongSupplier clock) {
        this.capacity = capacity;
        this.perMs = perSecond / 1000.0;
        this.clock = clock;
        this.tokens = capacity;
        this.last = clock.getAsLong();
    }

    public synchronized boolean tryAcquire() {
        long now = clock.getAsLong();
        tokens = Math.min(capacity, tokens + (now - last) * perMs);
        last = now;
        if (tokens < 1) return false;
        tokens -= 1;
        return true;
    }
}
