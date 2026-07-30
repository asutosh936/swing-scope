package com.swingscope.service.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A small sliding-window limiter that paces outbound calls to a provider's per-minute allowance.
 *
 * <p>Twelve Data's free tier permits 8 requests a minute, so a cold 20-ticker scan would otherwise
 * hammer straight into HTTP 429. Blocking here — before the request goes out — is cheaper than
 * retrying after being rejected, and it keeps the daily 800-call budget intact.
 *
 * <p>{@code permitsPerMinute <= 0} disables limiting entirely, which is what the tests use.
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final String name;
    private final int permitsPerMinute;
    private final Deque<Long> recent = new ArrayDeque<>();

    /** Injectable clock and sleep so tests never actually wait a minute. */
    private final Clock clock;

    public interface Clock {
        long nowMillis();

        void sleep(long millis) throws InterruptedException;
    }

    static final Clock SYSTEM = new Clock() {
        @Override
        public long nowMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public void sleep(long millis) throws InterruptedException {
            Thread.sleep(millis);
        }
    };

    public RateLimiter(String name, int permitsPerMinute) {
        this(name, permitsPerMinute, SYSTEM);
    }

    RateLimiter(String name, int permitsPerMinute, Clock clock) {
        this.name = name;
        this.permitsPerMinute = permitsPerMinute;
        this.clock = clock;
    }

    public boolean isEnabled() {
        return permitsPerMinute > 0;
    }

    /**
     * Blocks until a permit is free, then records it.
     *
     * @return how many milliseconds the caller was made to wait
     */
    public synchronized long acquire() {
        if (!isEnabled()) {
            return 0;
        }

        long waited = 0;
        while (true) {
            long now = clock.nowMillis();
            long windowStart = now - 60_000;
            while (!recent.isEmpty() && recent.peekFirst() <= windowStart) {
                recent.pollFirst();
            }

            if (recent.size() < permitsPerMinute) {
                recent.addLast(now);
                return waited;
            }

            // The oldest call in the window decides when a permit frees up.
            long sleepFor = recent.peekFirst() + 60_000 - now + 1;
            log.info("[{}] rate limit reached ({}/min) — pausing {}ms for the next permit",
                    name, permitsPerMinute, sleepFor);
            try {
                clock.sleep(sleepFor);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MarketDataException(name, "interrupted while waiting for a rate-limit permit", e);
            }
            waited += sleepFor;
        }
    }

    /** Permits used inside the current 60-second window. */
    public synchronized int used() {
        long windowStart = clock.nowMillis() - 60_000;
        while (!recent.isEmpty() && recent.peekFirst() <= windowStart) {
            recent.pollFirst();
        }
        return recent.size();
    }

    public int permitsPerMinute() {
        return permitsPerMinute;
    }
}
