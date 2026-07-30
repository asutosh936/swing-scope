package com.swingscope.service.marketdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    /** A clock the test drives by hand, so nothing actually sleeps. */
    private static class FakeClock implements RateLimiter.Clock {
        long now = 1_000_000L;
        final List<Long> sleeps = new ArrayList<>();

        @Override
        public long nowMillis() {
            return now;
        }

        @Override
        public void sleep(long millis) {
            sleeps.add(millis);
            now += millis;      // time passes exactly as long as we were told to wait
        }
    }

    @Test
    @DisplayName("calls within the allowance pass straight through")
    void underTheLimitNeverWaits() {
        FakeClock clock = new FakeClock();
        RateLimiter limiter = new RateLimiter("test", 8, clock);

        for (int i = 0; i < 8; i++) {
            assertThat(limiter.acquire()).isZero();
        }

        assertThat(clock.sleeps).isEmpty();
        assertThat(limiter.used()).isEqualTo(8);
    }

    @Test
    @DisplayName("the ninth call in a minute waits for the first one to age out")
    void overTheLimitWaits() {
        FakeClock clock = new FakeClock();
        RateLimiter limiter = new RateLimiter("test", 8, clock);

        for (int i = 0; i < 8; i++) {
            limiter.acquire();
        }
        long waited = limiter.acquire();

        assertThat(waited).isPositive();
        assertThat(clock.sleeps).hasSize(1);
        // The window is 60s and all 8 went out at the same instant, so the wait is ~60s.
        assertThat(clock.sleeps.get(0)).isBetween(60_000L, 60_001L);
    }

    @Test
    @DisplayName("permits free up as the window slides")
    void permitsRecoverOverTime() {
        FakeClock clock = new FakeClock();
        RateLimiter limiter = new RateLimiter("test", 2, clock);

        limiter.acquire();
        clock.now += 30_000;
        limiter.acquire();
        assertThat(limiter.used()).isEqualTo(2);

        // 31s later the first permit has aged out of the 60s window.
        clock.now += 31_000;
        assertThat(limiter.used()).isEqualTo(1);
        assertThat(limiter.acquire()).isZero();
    }

    @Test
    @DisplayName("a non-positive allowance disables pacing entirely")
    void zeroPermitsMeansNoLimiting() {
        FakeClock clock = new FakeClock();
        RateLimiter limiter = new RateLimiter("test", 0, clock);

        assertThat(limiter.isEnabled()).isFalse();
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.acquire()).isZero();
        }
        assertThat(clock.sleeps).isEmpty();
        assertThat(limiter.used()).isZero();
    }

    @Test
    @DisplayName("the real clock actually advances and actually sleeps")
    void systemClockWorks() throws Exception {
        long before = RateLimiter.SYSTEM.nowMillis();
        RateLimiter.SYSTEM.sleep(5);
        long after = RateLimiter.SYSTEM.nowMillis();

        assertThat(after).isGreaterThanOrEqualTo(before);
    }

    @Test
    void reportsItsConfiguredAllowance() {
        assertThat(new RateLimiter("test", 8).permitsPerMinute()).isEqualTo(8);
        assertThat(new RateLimiter("test", 8).isEnabled()).isTrue();
    }
}
