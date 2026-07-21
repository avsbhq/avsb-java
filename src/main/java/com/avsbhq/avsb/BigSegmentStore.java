package com.avsbhq.avsb;

import java.util.Map;

/**
 * External store for large-segment membership data. Mirrors
 * {@code BigSegmentStore} (§2.9) in types.ts.
 *
 * <p>Implementations must be thread-safe: the evaluator calls {@link #isMember}
 * on every flag evaluation in the hot path.
 */
public interface BigSegmentStore {

    /**
     * Synchronous membership check. Returns {@code true} when {@code contextKey}
     * is a member of {@code segmentKey}.
     *
     * <p>Must never throw. Return {@code false} on failure and let
     * {@link #isHealthy()} report the degraded state.
     */
    boolean isMember(String segmentKey, String contextKey);

    /**
     * Return all known segment memberships for a single context key, keyed by
     * segment key. Bulk reads let the evaluator avoid N separate
     * {@link #isMember} calls when a flag references multiple segments.
     */
    Map<String, Boolean> membership(String contextKey);

    /**
     * Health probe. Returns {@code false} when the backing store is stale,
     * unreachable, or degraded. The evaluator logs a warning and falls through
     * to {@code false} for every big-segment condition when unhealthy.
     */
    boolean isHealthy();
}
