package com.avsbhq.avsb;

import java.util.List;

/**
 * The unified evaluation return type. Mirrors {@code Flag<T>} (§2.2) in
 * {@code @avsbhq/core/src/types.ts}.
 *
 * <p>Use the static factory methods on {@link AvsbServer} or construct via
 * {@link Flag.Builder} directly when writing tests.
 *
 * @param <T> The type of the resolved flag value.
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
     * Convenience: {@code true} when the flag was served by an active rule
     * AND the value is truthy (non-null, non-false, non-zero, non-empty).
     */
    public boolean isEnabled() {
        if (source == EvaluationSource.NOT_FOUND || source == EvaluationSource.DEFAULT
                || source == EvaluationSource.DISABLED) {
            return false;
        }
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() != 0;
        if (value instanceof String s) return !s.isEmpty();
        return true;
    }

    /** Convenience: {@code true} when the flag key was found in the datafile. */
    public boolean exists() {
        return source != EvaluationSource.NOT_FOUND;
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static <T> Builder<T> builder(T value) {
        return new Builder<>(value);
    }

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
            return new Flag<>(value, variationKey, source, ruleId, ruleType, reasons, evaluatedAt, durationMicros);
        }
    }
}
