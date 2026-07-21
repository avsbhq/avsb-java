package com.avsbhq.avsb.sticky;

import com.avsbhq.avsb.StickyAssignment;

/**
 * Contract for sticky-bucket persistence. Mirrors {@code StickyBucketService}
 * (§2.4) in types.ts.
 *
 * <p>Implementations must be thread-safe. Both methods are called from the
 * evaluation hot path.
 */
public interface StickyBucketService {

    /**
     * Synchronous lookup. Returns the stored assignment for {@code (userId,
     * flagKey)}, or {@code null} when no assignment exists.
     */
    StickyAssignment lookup(String userId, String flagKey);

    /**
     * Persist an assignment. Fire-and-forget: implementations may write
     * asynchronously.
     */
    void save(String userId, String flagKey, StickyAssignment assignment);
}
