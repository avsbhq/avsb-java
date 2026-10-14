package cloud.avsb.sdk.core;

/**
 * The traffic-allocation gate shared by {@code ab_test} and {@code targeted_delivery} rules.
 * Mirrors {@code allocationExclusion} in packages/avsb-core/src/evaluate.ts.
 *
 * <p>One hash input serves both rule types, {@code hashValue + flagKey + "__" + ruleId}, so a
 * visitor's rollout membership is the same in every SDK and does not move when a rule changes
 * type.
 *
 * <p>The threshold is a FLOAT comparison against the raw product, never a truncated or rounded
 * integer (spec section 1.4). A 0.12345 rollout puts the threshold at 1234.5: bucket 1234 is
 * inside it, while a port that truncates to 1234 excludes that context and diverges by one
 * bucket in ten thousand, per rollout, undetectably. Boundary fixtures lock this at fractional
 * and at integer thresholds, for both rule types.
 *
 * <p>A null allocation means no gate, and so does a field missing from an old datafile, which
 * Jackson reads as null.
 */
final class AllocationGate {

    private AllocationGate() {
        // Static helper only.
    }

    /**
     * Decide whether a context is inside a rule's traffic allocation.
     *
     * @return the exclusion reason, character for character the reference's, when the context
     *     falls outside the allocation; {@code null} when it is inside or there is no gate.
     */
    static String exclusion(Datafile.RuleEntry rule, String hashValue, String flagKey) {
        Double allocation = rule.trafficAllocation();
        if (allocation == null) return null;
        int allocHash = Hash.bucket(hashValue + flagKey + "__" + rule.id());
        double threshold = allocation * 10_000;
        if (allocHash >= threshold) {
            return "rule \"" + rule.id() + "\" excluded by traffic allocation (hash " + allocHash
                    + " >= threshold " + JsNumber.stringify(threshold) + ")";
        }
        return null;
    }
}
