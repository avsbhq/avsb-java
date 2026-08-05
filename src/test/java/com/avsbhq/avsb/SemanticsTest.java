package com.avsbhq.avsb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The semantics the conformance corpus declares but cannot generate: what
 * {@code isEnabled()} and {@code exists()} answer, which source spellings are legal, and
 * what {@code not_ready} means.
 *
 * <p>Every case below is a row of
 * {@code packages/avsb-conformance/fixtures/semantics/manifest.json}. They live in a unit
 * test rather than the generated corpus because none of them can be expressed as a datafile
 * plus a context.
 */
@DisplayName("Semantics — isEnabled, exists, sources, not_ready")
class SemanticsTest {

    private static Flag<Object> flag(EvaluationSource source, Object value) {
        return Flag.builder(value).source(source).build();
    }

    // -------------------------------------------------------------------------
    // isEnabled: decided source AND truthy value
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a decided source with a truthy value is enabled")
    void decidedAndTruthy() {
        assertTrue(flag(EvaluationSource.RULE, true).isEnabled());
        assertTrue(flag(EvaluationSource.HOLDOUT, true).isEnabled());
        assertTrue(flag(EvaluationSource.BANDIT, "bold-copy").isEnabled());
        assertTrue(flag(EvaluationSource.RUNTIME_OVERRIDE, true).isEnabled());
        assertTrue(flag(EvaluationSource.DATAFILE_OVERRIDE, "forced-on").isEnabled());
        assertTrue(flag(EvaluationSource.STICKY, true).isEnabled());
    }

    @Test
    @DisplayName("a decided source with a falsy value is not enabled")
    void decidedAndFalsy() {
        assertFalse(flag(EvaluationSource.RULE, false).isEnabled());
        assertFalse(flag(EvaluationSource.HOLDOUT, false).isEnabled());
        assertFalse(flag(EvaluationSource.BANDIT, "").isEnabled());
        assertFalse(flag(EvaluationSource.RUNTIME_OVERRIDE, 0).isEnabled());
        assertFalse(flag(EvaluationSource.DATAFILE_OVERRIDE, null).isEnabled());
        assertFalse(flag(EvaluationSource.STICKY, false).isEnabled());
    }

    @Test
    @DisplayName("a fallback source is never enabled, however truthy the value")
    void fallbackSourcesAreNeverEnabled() {
        // The trap that made two ports disagree: a flag serving its DEFAULT variation is not
        // enabled even when that default is true.
        assertFalse(flag(EvaluationSource.DEFAULT, true).isEnabled());
        assertFalse(flag(EvaluationSource.NOT_FOUND, true).isEnabled());
        assertFalse(flag(EvaluationSource.DISABLED, true).isEnabled());
        assertFalse(flag(EvaluationSource.NOT_READY, true).isEnabled(),
                "an uninitialised SDK never reports a feature as enabled");
    }

    @Test
    @DisplayName("truthiness follows JavaScript, not Java, Ruby, or Python")
    void javascriptTruthiness() {
        assertTrue(flag(EvaluationSource.RULE, "false").isEnabled(), "the STRING false is truthy");
        assertTrue(flag(EvaluationSource.RULE, "0").isEnabled(), "the STRING zero is truthy");
        assertTrue(flag(EvaluationSource.RULE, List.of()).isEnabled(), "an empty array is truthy");
        assertTrue(flag(EvaluationSource.RULE, Map.of()).isEnabled(), "an empty object is truthy");
        assertTrue(flag(EvaluationSource.RULE, -1).isEnabled(), "negative numbers are truthy");
        assertFalse(flag(EvaluationSource.RULE, 0).isEnabled(), "zero is falsy");
        assertFalse(flag(EvaluationSource.RULE, "").isEnabled(), "the empty string is falsy");
        assertFalse(flag(EvaluationSource.RULE, Double.NaN).isEnabled(), "NaN is falsy");
    }

    // -------------------------------------------------------------------------
    // exists
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("exists is false only when the key is unknown or unknowable")
    void existsTable() {
        assertTrue(flag(EvaluationSource.RULE, true).exists());
        assertTrue(flag(EvaluationSource.DEFAULT, true).exists());
        assertTrue(flag(EvaluationSource.DISABLED, true).exists(),
                "a disabled flag exists; the customer turned it off");
        assertFalse(flag(EvaluationSource.NOT_FOUND, true).exists());
        assertFalse(flag(EvaluationSource.NOT_READY, true).exists(),
                "with no datafile, whether the key exists is unknowable");
    }

    // -------------------------------------------------------------------------
    // Source vocabulary
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("every public source has its wire spelling and round-trips")
    void wireSpellings() {
        Map<EvaluationSource, String> expected = Map.of(
                EvaluationSource.DATAFILE_OVERRIDE, "datafileOverride",
                EvaluationSource.RUNTIME_OVERRIDE, "runtimeOverride",
                EvaluationSource.STICKY, "sticky",
                EvaluationSource.RULE, "rule",
                EvaluationSource.HOLDOUT, "holdout",
                EvaluationSource.BANDIT, "bandit",
                EvaluationSource.DEFAULT, "default",
                EvaluationSource.NOT_FOUND, "not_found",
                EvaluationSource.DISABLED, "disabled",
                EvaluationSource.NOT_READY, "not_ready");

        assertEquals(EvaluationSource.values().length, expected.size(),
                "a new source needs a wire spelling and a row in this table");
        expected.forEach((source, wire) -> {
            assertEquals(wire, source.wireValue());
            assertEquals(source, EvaluationSource.fromWire(wire));
        });
    }

    @Test
    @DisplayName("the internal evaluator's legacy spellings map at the boundary")
    void legacySpellingsMap() {
        assertEquals(EvaluationSource.DATAFILE_OVERRIDE, EvaluationSource.fromWire("override"));
        assertEquals(EvaluationSource.RUNTIME_OVERRIDE, EvaluationSource.fromWire("runtime_override"));
    }

    @Test
    @DisplayName("an invented not-ready spelling is rejected rather than silently mapped")
    void bannedSpellingsThrow() {
        for (String banned : List.of("not_started", "not_initialized", "uninitialized", "error")) {
            assertThrows(IllegalArgumentException.class,
                    () -> EvaluationSource.fromWire(banned),
                    "\"" + banned + "\" must never resolve to a source");
        }
    }

    // -------------------------------------------------------------------------
    // not_ready shape
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a not-ready flag carries the caller default with nothing invented")
    void notReadyShape() {
        Flag<String> notReady = Flag.notReady("my-default", "SDK is not ready: call init()");
        assertEquals("my-default", notReady.value(), "the caller's value, type intact");
        assertEquals(EvaluationSource.NOT_READY, notReady.source());
        assertNull(notReady.variationKey());
        assertNull(notReady.ruleId());
        assertNull(notReady.ruleType());
        assertFalse(notReady.isEnabled());
        assertFalse(notReady.exists());
        assertEquals(1, notReady.reasons().size());
    }

    @Test
    @DisplayName("isDecided and isUnknownFlag describe the two source groups")
    void sourceGroups() {
        assertTrue(EvaluationSource.RULE.isDecided());
        assertTrue(EvaluationSource.STICKY.isDecided());
        assertFalse(EvaluationSource.DEFAULT.isDecided());
        assertFalse(EvaluationSource.NOT_READY.isDecided());

        assertTrue(EvaluationSource.NOT_FOUND.isUnknownFlag());
        assertTrue(EvaluationSource.NOT_READY.isUnknownFlag());
        assertFalse(EvaluationSource.DISABLED.isUnknownFlag());
    }
}
