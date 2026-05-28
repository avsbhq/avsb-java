package com.avsbhq.avsb.sticky;

import com.avsbhq.avsb.RuleType;
import com.avsbhq.avsb.StickyAssignment;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory sticky-bucket service. Assignments survive for the
 * lifetime of the JVM process. Suitable for development and testing.
 *
 * <p>For production deployments with multiple JVM instances, use
 * {@link RedisStickyBucketService} to share assignments across the cluster.
 */
public final class InMemoryStickyBucketService implements StickyBucketService {

    private final ConcurrentHashMap<String, StickyAssignment> store = new ConcurrentHashMap<>();

    @Override
    public StickyAssignment lookup(String userId, String flagKey) {
        return store.get(storeKey(userId, flagKey));
    }

    @Override
    public void save(String userId, String flagKey, StickyAssignment assignment) {
        store.put(storeKey(userId, flagKey), assignment);
    }

    /** Return a defensive snapshot of all stored assignments. */
    public Map<String, StickyAssignment> snapshot() {
        return Map.copyOf(store);
    }

    /** Remove all stored assignments. */
    public void clear() {
        store.clear();
    }

    private static String storeKey(String userId, String flagKey) {
        return userId + "::" + flagKey;
    }
}
