package com.avsbhq.avsb;

import java.util.List;

/**
 * An append-only decision-log record. Mirrors {@code DecisionLogEntry}
 * (§2.5) in types.ts.
 *
 * <p>Values or reasons that contain private attributes are redacted to
 * {@code "[REDACTED]"} by {@link DecisionLog} before the entry is stored.
 *
 * @param flagKey        The evaluated flag key.
 * @param value          The resolved value (possibly {@code "[REDACTED]"}).
 * @param variationKey   The variation key, or {@code null}.
 * @param source         Why this value was produced.
 * @param ruleId         Matched rule id, or {@code null}.
 * @param ruleType       Matched rule type, or {@code null}.
 * @param reasons        Structured reason strings (private-attr values redacted).
 * @param evaluatedAt    ms-epoch when evaluated.
 * @param durationMicros µs elapsed in the evaluator.
 * @param contextSummary Kind+key pairs for each sub-context (no attribute values).
 * @param manuallyFired  {@code true} when the entry was from a manual-exposure call.
 */
public record DecisionLogEntry(
        String flagKey,
        Object value,
        String variationKey,
        EvaluationSource source,
        String ruleId,
        RuleType ruleType,
        List<String> reasons,
        long evaluatedAt,
        long durationMicros,
        List<ContextRef> contextSummary,
        boolean manuallyFired
) {

    /** Lightweight context reference stored in the log (no attribute values). */
    public record ContextRef(String kind, String key) {}
}
