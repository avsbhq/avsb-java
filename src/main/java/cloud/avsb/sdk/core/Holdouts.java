package cloud.avsb.sdk.core;

import cloud.avsb.sdk.EvalContext;

import java.util.List;

/**
 * Holdout evaluation engine. Mirrors {@code evaluateHoldout} in
 * {@code @avsbhq/core/src/holdouts.ts}.
 *
 * <p>Spec §8.2: walks the datafile's holdout list; the first holdout that
 * participates in {@code flagId} and whose bucket is inside the traffic
 * window classifies the context as held out. Bucketing uses
 * {@code murmurhash3(holdoutKey + ':' + bucketingKey) % 10_000}.
 */
public final class Holdouts {

    private Holdouts() {}

    public record HoldoutEvaluation(
            boolean heldOut,
            Datafile.HoldoutEntry holdout,
            String variationId
    ) {}

    public static HoldoutEvaluation evaluateHoldout(
            EvalContext ctx,
            String flagId,
            List<Datafile.HoldoutEntry> holdouts
    ) {
        if (holdouts == null || holdouts.isEmpty()) {
            return new HoldoutEvaluation(false, null, null);
        }

        for (Datafile.HoldoutEntry holdout : holdouts) {
            if (!holdout.flagIds().contains(flagId)) continue;
            if (holdout.trafficAllocation() <= 0) continue;

            String bucketKey = resolveBucketKey(ctx, holdout.hashAttribute());
            int hash = Hash.bucket(holdout.key() + ":" + bucketKey);
            // Float comparison against the raw product, never a floor (spec section 1.4).
            // Flooring put holdouts one bucket out of step with the ab_test allocation
            // gate: a 0.12345 holdout has a threshold of 1234.5, so bucket 1234 is inside
            // the window, but the floored form compared against 1234 and let that context
            // through. Every port compares the raw product.
            double threshold = holdout.trafficAllocation() * 10_000;
            if (hash >= threshold) continue;

            String varId = holdout.defaultVariationByFlag() != null
                    ? holdout.defaultVariationByFlag().get(flagId)
                    : null;
            return new HoldoutEvaluation(true, holdout, varId);
        }

        return new HoldoutEvaluation(false, null, null);
    }

    private static String resolveBucketKey(EvalContext ctx, String hashAttribute) {
        Object value = Context.resolveDottedAttribute(ctx, hashAttribute);
        // JavaScript coercion, not Java's: a numeric hash attribute must stringify to
        // "42", not "42.0", or this JVM buckets the user differently from every other SDK.
        return value == null ? "" : JsNumber.stringOf(value);
    }
}
