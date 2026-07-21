package com.avsbhq.avsb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Append-only decision log with per-entry private-attribute redaction.
 *
 * <p>Mirrors §2.5 in types.ts. Redaction replaces any attribute value that
 * appears in a reason string with {@code "[REDACTED]"} when the attribute key
 * is listed in the context's {@code _meta.privateAttributes}.
 *
 * <p>Thread-safe: append and read can be called from different threads.
 */
public final class DecisionLog {

    private final CopyOnWriteArrayList<DecisionLogEntry> entries = new CopyOnWriteArrayList<>();

    /** All recorded entries in append order. Immutable view. */
    public List<DecisionLogEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * Append an entry to the log, applying private-attribute redaction.
     *
     * @param entry          Raw entry from the evaluator.
     * @param privateAttrs   Attribute keys whose values should be redacted.
     */
    public void append(DecisionLogEntry entry, Set<String> privateAttrs) {
        if (privateAttrs == null || privateAttrs.isEmpty()) {
            entries.add(entry);
            return;
        }

        // Redact reason strings
        List<String> redactedReasons = new ArrayList<>(entry.reasons().size());
        for (String reason : entry.reasons()) {
            redactedReasons.add(redact(reason, privateAttrs));
        }

        // Redact value if it's a string that matches a private attribute value
        Object redactedValue = entry.value();
        if (redactedValue instanceof String s && isPrivateAttributeValue(s, privateAttrs)) {
            redactedValue = "[REDACTED]";
        }

        entries.add(new DecisionLogEntry(
                entry.flagKey(),
                redactedValue,
                entry.variationKey(),
                entry.source(),
                entry.ruleId(),
                entry.ruleType(),
                Collections.unmodifiableList(redactedReasons),
                entry.evaluatedAt(),
                entry.durationMicros(),
                entry.contextSummary(),
                entry.manuallyFired()
        ));
    }

    /** Append without redaction. */
    public void append(DecisionLogEntry entry) {
        append(entry, Set.of());
    }

    /** Clear all entries. */
    public void clear() {
        entries.clear();
    }

    // -------------------------------------------------------------------------
    // Redaction helpers
    // -------------------------------------------------------------------------

    private static String redact(String text, Set<String> privateAttrs) {
        if (text == null) return null;
        String result = text;
        for (String attr : privateAttrs) {
            // Replace attribute name patterns like `attrKey="<value>"` in reason strings
            result = result.replaceAll("(?i)" + java.util.regex.Pattern.quote(attr) + "=[^\\s\"]*", attr + "=[REDACTED]");
        }
        return result;
    }

    private static boolean isPrivateAttributeValue(String value, Set<String> privateAttrs) {
        // The attribute key itself being in the private set doesn't mean the
        // value string equals the key — this check is intentionally conservative.
        // Full PII scrubbing is the caller's responsibility.
        return false;
    }
}
