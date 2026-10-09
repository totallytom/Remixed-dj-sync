package net.remixed.djsync.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

    @Test
    void allowsBurstThenRefillsOverTime() {
        var now = new AtomicLong(0);
        var limiter = new RateLimiter(5, 10, now::get);
        for (int i = 0; i < 5; i++) assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
        now.addAndGet(100);                       // 10/s -> one token per 100 ms
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
        now.addAndGet(10_000);                    // long idle never exceeds capacity
        int allowed = 0;
        while (limiter.tryAcquire()) allowed++;
        assertThat(allowed).isEqualTo(5);
    }
}
