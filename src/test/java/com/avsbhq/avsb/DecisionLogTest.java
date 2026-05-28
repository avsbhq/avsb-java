package com.avsbhq.avsb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DecisionLog — append and redaction")
class DecisionLogTest {

    private static DecisionLogEntry entry(String flagKey, String reason) {
        return new DecisionLogEntry(
                flagKey, "some-value", "control",
                EvaluationSource.RULE, "rule_1", RuleType.AB_TEST,
                List.of(reason), System.currentTimeMillis(), 100,
                List.of(new DecisionLogEntry.ContextRef("user", "alice")), false);
    }

    @Test
    @DisplayName("append stores entry and entries() returns it in order")
    void appendAndRead() {
        DecisionLog log = new DecisionLog();
        log.append(entry("flag-a", "matched rule"), Set.of());
        log.append(entry("flag-b", "no rules"), Set.of());
        assertEquals(2, log.entries().size());
        assertEquals("flag-a", log.entries().get(0).flagKey());
        assertEquals("flag-b", log.entries().get(1).flagKey());
    }

    @Test
    @DisplayName("entries() view is immutable")
    void entriesImmutable() {
        DecisionLog log = new DecisionLog();
        log.append(entry("flag-a", "reason"), Set.of());
        assertThrows(UnsupportedOperationException.class,
                () -> log.entries().add(entry("flag-x", "extra")));
    }

    @Test
    @DisplayName("redaction replaces private attribute key in reason strings")
    void redactionInReasons() {
        DecisionLog log = new DecisionLog();
        DecisionLogEntry e = new DecisionLogEntry(
                "my-flag", "some-value", "control",
                EvaluationSource.RULE, "r1", RuleType.TARGETED_DELIVERY,
                List.of("matched attribute email=secret@example.com"), System.currentTimeMillis(), 0,
                List.of(), false);
        log.append(e, Set.of("email"));
        String reason = log.entries().get(0).reasons().get(0);
        // The redacted reason should contain [REDACTED] where the value was
        assertTrue(reason.contains("[REDACTED]"), "Reason should be redacted: " + reason);
    }

    @Test
    @DisplayName("clear empties the log")
    void clearLog() {
        DecisionLog log = new DecisionLog();
        log.append(entry("flag-a", "reason"), Set.of());
        log.clear();
        assertTrue(log.entries().isEmpty());
    }

    @Test
    @DisplayName("no redaction when privateAttrs is empty")
    void noRedaction() {
        DecisionLog log = new DecisionLog();
        String originalReason = "matched attribute plan=pro";
        log.append(entry("flag-a", originalReason), Set.of());
        assertEquals(originalReason, log.entries().get(0).reasons().get(0));
    }
}
