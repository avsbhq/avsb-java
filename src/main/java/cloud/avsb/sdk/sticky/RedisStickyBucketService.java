package cloud.avsb.sdk.sticky;

import cloud.avsb.sdk.RuleType;
import cloud.avsb.sdk.StickyAssignment;

// Jedis is declared as a compileOnly dependency — only loaded when present on
// the runtime classpath. Applications that do not use Redis can omit Jedis.
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Redis-backed sticky-bucket service using <a href="https://github.com/redis/jedis">Jedis</a>.
 *
 * <p>Jedis is declared as {@code compileOnly}; add it as a {@code runtimeOnly}
 * dependency in your application if you use this class.
 *
 * <p>Keys are stored as Redis hashes under {@code avsb:sticky:<userId>:<flagKey>}
 * with the fields {@code variationId}, {@code ruleId}, {@code ruleType}, and
 * {@code assignedAt}.
 */
public final class RedisStickyBucketService implements StickyBucketService {

    private static final String KEY_PREFIX = "avsb:sticky:";

    private final JedisPool pool;

    public RedisStickyBucketService(JedisPool pool) {
        if (pool == null) throw new IllegalArgumentException("JedisPool must not be null");
        this.pool = pool;
    }

    @Override
    public StickyAssignment lookup(String userId, String flagKey) {
        try (Jedis jedis = pool.getResource()) {
            String key = redisKey(userId, flagKey);
            String variationId = jedis.hget(key, "variationId");
            if (variationId == null) return null;
            String ruleId = jedis.hget(key, "ruleId");
            String ruleTypeStr = jedis.hget(key, "ruleType");
            String assignedAtStr = jedis.hget(key, "assignedAt");
            RuleType ruleType = RuleType.fromWire(ruleTypeStr);
            long assignedAt = assignedAtStr != null ? Long.parseLong(assignedAtStr) : 0;
            return new StickyAssignment(variationId, ruleId, ruleType, assignedAt);
        }
    }

    @Override
    public void save(String userId, String flagKey, StickyAssignment assignment) {
        try (Jedis jedis = pool.getResource()) {
            String key = redisKey(userId, flagKey);
            jedis.hset(key, "variationId", assignment.variationId());
            jedis.hset(key, "ruleId", assignment.ruleId() != null ? assignment.ruleId() : "");
            jedis.hset(key, "ruleType", assignment.ruleType() != null ? assignment.ruleType().wireValue() : "");
            jedis.hset(key, "assignedAt", String.valueOf(assignment.assignedAt()));
        }
    }

    private static String redisKey(String userId, String flagKey) {
        return KEY_PREFIX + userId + ":" + flagKey;
    }
}
