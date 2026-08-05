package com.avsbhq.avsb;

import com.avsbhq.avsb.core.JsNumber;

import java.util.List;

/**
 * The unified evaluation result. Mirrors {@code Flag<T>} in
 * {@code @avsbhq/core/src/types.ts}.
 *
 * <p>Full shape, so nothing here is a surprise:
 *
 * <pre>{@code
 * public record Flag<T>(
 *         T value,                  // never null unless the caller's default was null
 *         String variationKey,      // nullable: null when no variation was served
 *         EvaluationSource source,  // never null
 *         String ruleId,            // nullable: null unless a rule or holdout decided
 *         RuleType ruleType,        // nullable: null unless a rule or holdout decided
 *         List<String> reasons,     // never null, may be empty, immutable
 *         long evaluatedAt,         // ms since the Unix epoch
 *         long durationMicros       // microseconds spent inside the evaluator
 * )
 * }</pre>
 *
 * @param <T> The resolved value type: {@link Boolean}, {@link String}, {@link Double}, or
 *            whatever a JSON flag was asked to deserialise into.
 */
public record Flag<T>(
        T value,
        String variationKey,
        EvaluationSource source,
        String ruleId,
        RuleType ruleType,
        List<String> reasons,
        long evaluatedAt,
        long durationMicros
) {

    /**
     * True when a real decision produced this value AND the value is truthy.
     *
     * <p>Two halves, both fixture-locked (spec section 1.4):
     *
     * <ol>
     *   <li>The source must be one of {@code rule}, {@code holdout}, {@code bandit},
     *       {@code runtimeOverride}, {@code datafileOverride}, or {@code sticky}. A flag
     *       serving its DEFAULT variation is not enabled, however truthy that default is.
     *       Neither is a disabled flag, a missing flag, or an SDK that has not
     *       initialised.</li>
     *   <li>Truthiness follows JAVASCRIPT rules, not Java's, because the reference engine
     *       is TypeScript and the answer has to be identical in all eight
     *       implementations: {@code false}, {@code 0}, {@code -0}, {@code NaN}, the empty
     *       string, and null are falsy; everything else is truthy, INCLUDING the string
     *       {@code "false"}, the string {@code "0"}, an empty list, and an empty map.</li>
     * </ol>
     */
    public boolean isEnabled() {
        return source.isDecided() && JsNumber.isTruthy(value);
    }

    /**
     * True when the datafile has an answer about this key.
     *
     * <p>False for {@code not_found} and for {@code not_ready}: with no datafile loaded,
     * whether the key exists is unknowable, so the honest answer is false rather than a
     * guess. A DISABLED flag exists: the customer turned it off, which is not the same as
     * it being absent.
     */
    public boolean exists() {
        return !source.isUnknownFlag();
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static <T> Builder<T> builder(T value) {
        return new Builder<>(value);
    }

    /** Mutable builder for a {@link Flag}. Used by the evaluator and by tests. */
    public static final class Builder<T> {
        private final T value;
        private String variationKey;
        private EvaluationSource source = EvaluationSource.NOT_FOUND;
        private String ruleId;
        private RuleType ruleType;
        private List<String> reasons = List.of();
        private long evaluatedAt = System.currentTimeMillis();
        private long durationMicros = 0;

        private Builder(T value) {
            this.value = value;
        }

        public Builder<T> variationKey(String variationKey) { this.variationKey = variationKey; return this; }
        public Builder<T> source(EvaluationSource source) { this.source = source; return this; }
        public Builder<T> ruleId(String ruleId) { this.ruleId = ruleId; return this; }
        public Builder<T> ruleType(RuleType ruleType) { this.ruleType = ruleType; return this; }
        public Builder<T> reasons(List<String> reasons) { this.reasons = List.copyOf(reasons); return this; }
        public Builder<T> evaluatedAt(long evaluatedAt) { this.evaluatedAt = evaluatedAt; return this; }
        public Builder<T> durationMicros(long durationMicros) { this.durationMicros = durationMicros; return this; }

        public Flag<T> build() {
            return new Flag<>(value, variationKey, source, ruleId, ruleType, reasons,
                    evaluatedAt, durationMicros);
        }
    }

    /**
     * The result of evaluating before a datafile existed: the caller's default value with
     * source {@link EvaluationSource#NOT_READY}, no variation, and one reason naming the
     * state (spec section 1.2).
     */
    public static <T> Flag<T> notReady(T defaultValue, String reason) {
        return Flag.builder(defaultValue)
                .source(EvaluationSource.NOT_READY)
                .reasons(List.of(reason))
                .build();
    }

    /**
     * The result of asking for a key the loaded datafile does not carry, or of a typed
     * getter whose declared type disagreed with the resolved value.
     */
    public static <T> Flag<T> notFound(T defaultValue, String reason) {
        return Flag.builder(defaultValue)
                .source(EvaluationSource.NOT_FOUND)
                .reasons(List.of(reason))
                .build();
    }
}
