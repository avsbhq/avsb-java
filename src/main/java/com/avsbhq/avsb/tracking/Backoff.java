package com.avsbhq.avsb.tracking;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with jitter, honouring a server-supplied {@code Retry-After}.
 *
 * <p>Port of {@code reconnectBackoff.ts} in {@code @avsbhq/node}, so every SDK retries on
 * the same curve: 1s, 2s, 4s, 8s, 16s, 32s, 60s (capped), with plus or minus 20 percent
 * jitter.
 *
 * <p>The jitter is the point. A fixed retry cadence means every connected SDK hits a
 * struggling worker again at the same instant, forever, which prevents it from recovering.
 * Jitter spreads clients that started together apart on each retry.
 *
 * <p>When the server sent {@code Retry-After} (in seconds), that wins for one attempt and
 * the attempt counter still advances, so the next miss climbs the curve rather than
 * restarting it.
 */
public final class Backoff {

    private static final long BASE_DELAY_MS = 1_000;
    private static final long MAX_DELAY_MS = 60_000;
    private static final long MIN_DELAY_MS = 500;
    private static final double JITTER = 0.2;

    /** How many attempts have been made, and how long to wait before the next one. */
    public record State(int attempt, long delayMs) {}

    private Backoff() {
        // Static helpers only.
    }

    /**
     * Compute the next wait.
     *
     * @param previous     the previous state, or null for the first retry.
     * @param retryAfterMs a server-supplied delay, or null to use the curve.
     */
    public static State next(State previous, Long retryAfterMs) {
        int attempt = (previous == null ? 0 : previous.attempt()) + 1;

        if (retryAfterMs != null && retryAfterMs > 0) {
            return new State(attempt, retryAfterMs);
        }

        long base = Math.min(MAX_DELAY_MS, BASE_DELAY_MS * (long) Math.pow(2, attempt - 1.0));
        double jitter = base * JITTER * (ThreadLocalRandom.current().nextDouble() * 2 - 1);
        long delay = Math.max(MIN_DELAY_MS, Math.round(base + jitter));
        return new State(attempt, delay);
    }

    /**
     * Parse an HTTP {@code Retry-After} header value.
     *
     * <p>Only the numeric-seconds form is supported, which is what the workers emit.
     * Returns null for the HTTP-date form and for anything unparseable.
     */
    public static Long parseRetryAfter(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        String trimmed = headerValue.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            double seconds = Double.parseDouble(trimmed);
            if (!Double.isFinite(seconds) || seconds < 0) {
                return null;
            }
            return Math.round(seconds * 1000);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
