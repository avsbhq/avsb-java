package cloud.avsb.sdk;

import java.util.Set;

/**
 * Why a particular flag value was produced. Mirrors the {@code EvaluationSource} union in
 * {@code @avsbhq/core/src/types.ts}, and the {@code sources.public} list in the
 * conformance corpus' {@code semantics} fixture family.
 *
 * <p>The ten members below are the complete public set. Two spellings the internal
 * evaluator once used, {@code override} and {@code runtime_override}, are mapped at the SDK
 * boundary and never reach a caller.
 */
public enum EvaluationSource {
    /** Value overridden in the datafile for a specific user id. */
    DATAFILE_OVERRIDE,
    /** Value overridden at runtime through the server builder. */
    RUNTIME_OVERRIDE,
    /** Value pinned by a stored sticky-bucket assignment. */
    STICKY,
    /** Value served by a matching targeted-delivery or AB-test rule. */
    RULE,
    /** Context is inside a holdout window; served the holdout's variation. */
    HOLDOUT,
    /** Value selected by the bandit engine. */
    BANDIT,
    /** No rule matched; served the flag's default variation. */
    DEFAULT,
    /** Flag key is not present in the datafile. */
    NOT_FOUND,
    /** Flag is disabled; served the flag's default variation. */
    DISABLED,
    /**
     * There was no datafile to look in: init has not completed, the fetch failed with no
     * bootstrap, or the client was closed.
     *
     * <p>Distinct from {@link #NOT_FOUND}, and the distinction is the point. Reporting
     * "flag does not exist" for an SDK that never started sent people hunting for a missing
     * flag in the dashboard instead of looking at their init call. Every language port used
     * to do it.
     */
    NOT_READY;

    /**
     * The six sources that mean "a real decision produced this value". Everything else is a
     * fallback, and {@link Flag#isEnabled()} is false for all of them however truthy the
     * value looks.
     */
    private static final Set<EvaluationSource> DECIDED = Set.of(
            RULE, HOLDOUT, BANDIT, RUNTIME_OVERRIDE, DATAFILE_OVERRIDE, STICKY);

    /** Sources that mean "no datafile answer for this key". */
    private static final Set<EvaluationSource> UNKNOWN_FLAG = Set.of(NOT_FOUND, NOT_READY);

    /** True when this source means a real decision was made. */
    public boolean isDecided() {
        return DECIDED.contains(this);
    }

    /** True when this source means the key's existence is unknown or denied. */
    public boolean isUnknownFlag() {
        return UNKNOWN_FLAG.contains(this);
    }

    /** Wire-format name, as it appears in fixtures, API responses, and decision logs. */
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
            case NOT_READY         -> "not_ready";
        };
    }

    /**
     * Parse a wire-format string.
     *
     * @throws IllegalArgumentException for a spelling this SDK does not know. Falling back
     *     to {@code NOT_FOUND} turned "this SDK has never heard of that source" into a
     *     silently wrong answer, including inside the conformance harness.
     */
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
            case "not_ready"        -> NOT_READY;
            // The internal evaluator's legacy spellings, mapped at the boundary.
            case "override"         -> DATAFILE_OVERRIDE;
            case "runtime_override" -> RUNTIME_OVERRIDE;
            default -> throw new IllegalArgumentException(
                    "Unknown evaluation source \"" + wire + "\". The public set is listed in the"
                            + " conformance corpus at fixtures/semantics/manifest.json.");
        };
    }
}
