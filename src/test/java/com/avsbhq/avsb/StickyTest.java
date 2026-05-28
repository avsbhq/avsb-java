package com.avsbhq.avsb;

import com.avsbhq.avsb.sticky.InMemoryStickyBucketService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InMemoryStickyBucketService — round-trip")
class StickyTest {

    @Test
    @DisplayName("lookup returns null when no assignment exists")
    void lookupMiss() {
        InMemoryStickyBucketService svc = new InMemoryStickyBucketService();
        assertNull(svc.lookup("alice", "my-flag"));
    }

    @Test
    @DisplayName("save + lookup round-trip preserves all fields")
    void saveAndLookup() {
        InMemoryStickyBucketService svc = new InMemoryStickyBucketService();
        StickyAssignment assignment = new StickyAssignment("var_treatment", "rule_ab", RuleType.AB_TEST, 1234567890L);
        svc.save("alice", "my-flag", assignment);
        StickyAssignment found = svc.lookup("alice", "my-flag");
        assertNotNull(found);
        assertEquals("var_treatment", found.variationId());
        assertEquals("rule_ab", found.ruleId());
        assertEquals(RuleType.AB_TEST, found.ruleType());
        assertEquals(1234567890L, found.assignedAt());
    }

    @Test
    @DisplayName("different flag keys are stored independently")
    void independentFlagKeys() {
        InMemoryStickyBucketService svc = new InMemoryStickyBucketService();
        svc.save("alice", "flag-a", new StickyAssignment("var_a", "r1", RuleType.AB_TEST, 1L));
        svc.save("alice", "flag-b", new StickyAssignment("var_b", "r2", RuleType.TARGETED_DELIVERY, 2L));
        assertEquals("var_a", svc.lookup("alice", "flag-a").variationId());
        assertEquals("var_b", svc.lookup("alice", "flag-b").variationId());
    }

    @Test
    @DisplayName("clear removes all stored assignments")
    void clearAll() {
        InMemoryStickyBucketService svc = new InMemoryStickyBucketService();
        svc.save("alice", "flag-a", new StickyAssignment("var_a", "r1", RuleType.AB_TEST, 1L));
        svc.clear();
        assertNull(svc.lookup("alice", "flag-a"));
    }

    @Test
    @DisplayName("snapshot returns a defensive copy")
    void snapshot() {
        InMemoryStickyBucketService svc = new InMemoryStickyBucketService();
        svc.save("alice", "flag-a", new StickyAssignment("var_a", "r1", RuleType.AB_TEST, 1L));
        var snap = svc.snapshot();
        assertEquals(1, snap.size());
        // Snapshot should not be modifiable
        assertThrows(UnsupportedOperationException.class, () -> snap.put("key", null));
    }
}
