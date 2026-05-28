package com.avsbhq.avsb;

/**
 * Identifies why a particular flag value was produced. Mirrors §2.12 of the
 * SDK launch-readiness spec and the {@code EvaluationSource} union in
 * {@code @avsbhq/core/src/types.ts}.
 */
public enum EvaluationSource {
    /** Value overridden in the datafile for a specific user id. */
    DATAFILE_OVERRIDE,
    /** Value overridden at runtime via the server builder. */
    RUNTIME_OVERRIDE,
    /** Value pinned by a sticky-bucket assignment. */
    STICKY,
    /** Value served by a matching targeting or AB-test rule. */
    RULE,
    /** Context is inside a holdout window; served the holdout default. */
    HOLDOUT,
    /** Value selected by the bandit engine. */
    BANDIT,
    /** No rule matched; served the flag's default variation. */
    DEFAULT,
    /** Flag key not present in the datafile. */
    NOT_FOUND,
    /** Flag is disabled; served the flag's default variation. */
    DISABLED;

    /** Wire-format name used in conformance fixture JSON. */
    public String wireValue() {
        return switch (this) {
            case DATAFILE_OVERRIDE -> "datafileOverride";
            case RUNTIME_OVERRIDE  -> "runtimeOverride";
            case STICKY            -> "sticky";
            case RULE              -> "rule";
            case HOLDOUT           -> "holdout";
            case BANDIT            -> "bandit";
            case DEFAULT           -> "default";
            case NOT_FOUND         -> "not_found";
            case DISABLED          -> "disabled";
        };
    }

    /** Parse a wire-format string. Returns {@code NOT_FOUND} for unknown values. */
    public static EvaluationSource fromWire(String wire) {
        return switch (wire == null ? "" : wire) {
            case "datafileOverride" -> DATAFILE_OVERRIDE;
            case "runtimeOverride"  -> RUNTIME_OVERRIDE;
            case "sticky"           -> STICKY;
            case "rule"             -> RULE;
            case "holdout"          -> HOLDOUT;
            case "bandit"           -> BANDIT;
            case "default"          -> DEFAULT;
            case "not_found"        -> NOT_FOUND;
            case "disabled"         -> DISABLED;
            // legacy pre-CoW6 evaluator used "override" for datafile overrides
            case "override"         -> DATAFILE_OVERRIDE;
            default                 -> NOT_FOUND;
        };
    }
}
