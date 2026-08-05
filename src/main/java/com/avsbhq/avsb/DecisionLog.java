package com.avsbhq.avsb;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bounded, append-only decision log with private-attribute redaction.
 *
 * <p>Every evaluation appends one entry, so this is the audit trail for "why did that user
 * see that variation". {@code AvsbServer} writes to it; before this program nothing did, and
 * the type existed as decoration.
 *
 * <p>BOUNDED is the important word. The Python port shipped an unbounded collector that grew
 * for the life of the process unless the caller discovered a flush method, which is a memory
 * leak installed by default in a long-running server. This keeps the most recent
 * {@link #capacity()} entries and drops the oldest, so a service that never reads the log
 * pays a fixed cost.
 *
 * <p>Thread-safe: appends and reads may come from different threads.
 */
public final class DecisionLog {

    /** Entries retained when the caller does not choose. */
    public static final int DEFAULT_CAPACITY = 1_000;

    private final ArrayDeque<DecisionLogEntry> entries;
    private final int capacity;
    private final Object lock = new Object();

    /** A log holding the most recent {@link #DEFAULT_CAPACITY} entries. */
    public DecisionLog() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * @param capacity how many entries to retain. Zero disables recording entirely, which
     *                 is what a caller who never reads the log should pick.
     */
    public DecisionLog(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("DecisionLog capacity must not be negative");
        }
        this.capacity = capacity;
        this.entries = new ArrayDeque<>(Math.min(Math.max(capacity, 1), 256));
    }

    /** How many entries this log retains before dropping the oldest. */
    public int capacity() {
        return capacity;
    }

    /** All retained entries in append order. Immutable snapshot. */
    public List<DecisionLogEntry> entries() {
        synchronized (lock) {
            return List.copyOf(entries);
        }
    }

    /** How many entries are currently retained. */
    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    /**
     * Append an entry, applying private-attribute redaction first.
     *
     * @param entry        Raw entry from the evaluator.
     * @param privateAttrs Attribute keys whose values must not be retained.
     */
    public void append(DecisionLogEntry entry, Set<String> privateAttrs) {
        DecisionLogEntry stored = privateAttrs == null || privateAttrs.isEmpty()
                ? entry
                : redactEntry(entry, privateAttrs);
        synchronized (lock) {
            if (capacity == 0) {
                return;
            }
            if (entries.size() >= capacity) {
                entries.pollFirst();
            }
            entries.addLast(stored);
        }
    }

    /** Append without redaction. */
    public void append(DecisionLogEntry entry) {
        append(entry, Set.of());
    }

    /** Drop every retained entry. */
    public void clear() {
        synchronized (lock) {
            entries.clear();
        }
    }

    /**
     * Take every retained entry and clear the log, for callers that ship the audit trail
     * somewhere on a timer.
     */
    public List<DecisionLogEntry> drain() {
        synchronized (lock) {
            List<DecisionLogEntry> snapshot = List.copyOf(entries);
            entries.clear();
            return snapshot;
        }
    }

    // -------------------------------------------------------------------------
    // Redaction
    // -------------------------------------------------------------------------

    private static DecisionLogEntry redactEntry(DecisionLogEntry entry, Set<String> privateAttrs) {
        List<String> redactedReasons = new ArrayList<>(entry.reasons().size());
        for (String reason : entry.reasons()) {
            redactedReasons.add(redact(reason, privateAttrs));
        }
        return new DecisionLogEntry(
                entry.flagKey(),
                entry.value(),
                entry.variationKey(),
                entry.source(),
                entry.ruleId(),
                entry.ruleType(),
                Collections.unmodifiableList(redactedReasons),
                entry.evaluatedAt(),
                entry.durationMicros(),
                entry.contextSummary(),
                entry.manuallyFired());
    }

    /**
     * Replace {@code attrKey="<value>"} and {@code attrKey=<value>} occurrences in a reason
     * string. Reason strings are the only place the evaluator names attribute values, which
     * is why this operates on them rather than on the whole entry.
     */
    private static String redact(String text, Set<String> privateAttrs) {
        if (text == null) return null;
        String result = text;
        for (String attr : privateAttrs) {
            result = result.replaceAll(
                    "(?i)" + Pattern.quote(attr) + "=\"[^\"]*\"", attr + "=\"[REDACTED]\"");
            result = result.replaceAll(
                    "(?i)" + Pattern.quote(attr) + "=[^\\s\"]*", attr + "=[REDACTED]");
        }
        return result;
    }
}
