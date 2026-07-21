package com.avsbhq.avsb.core;

import com.avsbhq.avsb.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Core flag evaluation engine. Mirrors {@code evaluateFlag} in
 * {@code @avsbhq/core/src/evaluate.ts}.
 *
 * <p>Evaluation order (matches spec §4 and the TS source):
 * <ol>
 *   <li>Flag not found → {@link EvaluationSource#NOT_FOUND}.</li>
 *   <li>Flag disabled → {@link EvaluationSource#DISABLED} (default variation).</li>
 *   <li>User override → {@link EvaluationSource#DATAFILE_OVERRIDE}.</li>
 *   <li>Holdout check → {@link EvaluationSource#HOLDOUT} if held out.</li>
 *   <li>Rules loop (in order): first enabled matching rule wins.</li>
 *   <li>Default variation → {@link EvaluationSource#DEFAULT}.</li>
 * </ol>
 */
public final class Evaluator {

    private Evaluator() {}

    /**
     * Evaluate a flag against the provided datafile and context.
     *
     * @param flagKey      The flag key to look up.
     * @param defaultValue Caller-supplied fallback value.
     * @param ctx          Evaluation context.
     * @param datafile     Parsed flag datafile.
     * @param <T>          Type of the default value.
     * @return A fully-populated {@link Flag} record.
     */
    public static <T> Flag<T> evaluateFlag(
            String flagKey,
            T defaultValue,
            EvalContext ctx,
            Datafile.FlagDatafileDoc datafile
    ) {
        long startedAt = System.currentTimeMillis();
        long startNano = System.nanoTime();
        List<String> reasons = new ArrayList<>();

        // 1. FIND FLAG
        Datafile.FlagEntry flag = findFlag(datafile, flagKey);
        if (flag == null) {
            reasons.add("flag \"" + flagKey + "\" not found in datafile");
            return Flag.<T>builder(defaultValue)
                    .variationKey(null)
                    .source(EvaluationSource.NOT_FOUND)
                    .reasons(reasons)
                    .evaluatedAt(startedAt)
                    .durationMicros(elapsedMicros(startNano))
                    .build();
        }

        // 2. CHECK ENABLED
        if (!flag.enabled()) {
            reasons.add("flag \"" + flagKey + "\" is disabled, returning default variation");
            VariationResult<T> defVar = getVariationValue(flag, flag.defaultVariationId(), defaultValue);
            return Flag.<T>builder(defVar.value())
                    .variationKey(defVar.key())
                    .source(EvaluationSource.DISABLED)
                    .reasons(reasons)
                    .evaluatedAt(startedAt)
                    .durationMicros(elapsedMicros(startNano))
                    .build();
        }

        // 3. CHECK OVERRIDES
        String primaryKey = resolvePrimaryKey(ctx);
        if (flag.overrides() != null) {
            for (Datafile.OverrideEntry override : flag.overrides()) {
                if (primaryKey.equals(override.userId())) {
                    reasons.add("matched user override for userId=\"" + primaryKey + "\"");
                    VariationResult<T> ov = getVariationValue(flag, override.variationId(), defaultValue);
                    return Flag.<T>builder(ov.value())
                            .variationKey(ov.key())
                            .source(EvaluationSource.DATAFILE_OVERRIDE)
                            .reasons(reasons)
                            .evaluatedAt(startedAt)
                            .durationMicros(elapsedMicros(startNano))
                            .build();
                }
            }
        }

        // 4. HOLDOUT CHECK
        List<Datafile.HoldoutEntry> holdouts = datafile.holdouts();
        Holdouts.HoldoutEvaluation holdout = Holdouts.evaluateHoldout(ctx, flag.id(), holdouts);
        if (holdout.heldOut()) {
            String targetVarId = holdout.variationId() != null ? holdout.variationId() : flag.defaultVariationId();
            VariationResult<T> holdoutVar = getVariationValue(flag, targetVarId, defaultValue);
            reasons.add("held out by holdout \"" + holdout.holdout().key()
                    + "\", serving default variation \"" + (holdoutVar.key() != null ? holdoutVar.key() : targetVarId) + "\"");
            return Flag.<T>builder(holdoutVar.value())
                    .variationKey(holdoutVar.key())
                    .source(EvaluationSource.HOLDOUT)
                    .ruleId(holdout.holdout().id())
                    .ruleType(RuleType.HOLDOUT)
                    .reasons(reasons)
                    .evaluatedAt(startedAt)
                    .durationMicros(elapsedMicros(startNano))
                    .build();
        }

        // Build audience lookup map
        Map<String, Datafile.AudienceEntry> audienceMap = buildAudienceMap(datafile);

        // 5. EVALUATE RULES
        if (flag.rules() != null) {
            for (Datafile.RuleEntry rule : flag.rules()) {
                if (!rule.enabled()) {
                    reasons.add("rule \"" + rule.id() + "\" skipped (disabled)");
                    continue;
                }

                String hashPath = rule.hashAttribute() != null && !rule.hashAttribute().isEmpty()
                        ? rule.hashAttribute() : "userId";
                Object resolved = Context.resolveDottedAttribute(ctx, hashPath);
                String hashValue = resolved == null ? "" : String.valueOf(resolved);

                // a. AUDIENCE CHECK
                if (rule.audienceIds() != null && !rule.audienceIds().isEmpty()) {
                    boolean matched = false;
                    for (String audId : rule.audienceIds()) {
                        Datafile.AudienceEntry aud = audienceMap.get(audId);
                        if (aud != null && Audience.evaluateAudience(aud, ctx)) {
                            matched = true;
                            break;
                        }
                    }
                    if (!matched) {
                        reasons.add("rule \"" + rule.id() + "\" skipped (audience not matched)");
                        continue;
                    }
                }

                // b. EVALUATE BY TYPE
                switch (rule.type()) {
                    case "targeted_delivery" -> {
                        if (rule.variations() == null || rule.variations().isEmpty()) continue;
                        VariationResult<T> targetVar = getVariationValue(flag, rule.variations().get(0).variationId(), defaultValue);
                        reasons.add("rule \"" + rule.id() + "\" matched (targeted_delivery), serving variation \"" + targetVar.key() + "\"");
                        return Flag.<T>builder(targetVar.value())
                                .variationKey(targetVar.key())
                                .source(EvaluationSource.RULE)
                                .ruleId(rule.id())
                                .ruleType(RuleType.TARGETED_DELIVERY)
                                .reasons(reasons)
                                .evaluatedAt(startedAt)
                                .durationMicros(elapsedMicros(startNano))
                                .build();
                    }

                    case "ab_test" -> {
                        // Traffic allocation gate
                        int allocHash = Hash.bucket(hashValue + flagKey + "__" + rule.id());
                        if (rule.trafficAllocation() != null && allocHash >= rule.trafficAllocation() * 10_000) {
                            reasons.add("rule \"" + rule.id() + "\" excluded by traffic allocation (hash " + allocHash
                                    + " >= threshold " + (long)(rule.trafficAllocation() * 10_000) + ")");
                            continue;
                        }
                        // Variation assignment
                        int hash = Hash.bucket(hashValue + flagKey);
                        double cumulative = 0;
                        if (rule.variations() != null) {
                            for (Datafile.RuleVariation rv : rule.variations()) {
                                cumulative += rv.percentage() * 10_000;
                                if (hash < cumulative) {
                                    VariationResult<T> expVar = getVariationValue(flag, rv.variationId(), defaultValue);
                                    reasons.add("rule \"" + rule.id() + "\" matched (ab_test), bucketed into variation \""
                                            + expVar.key() + "\" (hash " + hash + "/" + (long) cumulative + ")");
                                    return Flag.<T>builder(expVar.value())
                                            .variationKey(expVar.key())
                                            .source(EvaluationSource.RULE)
                                            .ruleId(rule.id())
                                            .ruleType(RuleType.AB_TEST)
                                            .reasons(reasons)
                                            .evaluatedAt(startedAt)
                                            .durationMicros(elapsedMicros(startNano))
                                            .build();
                                }
                            }
                        }
                        // Fell through all variations (under-allocated traffic)
                        continue;
                    }

                    default -> { /* unknown rule type — skip */ }
                }
            }
        }

        // 6. DEFAULT
        reasons.add("no rules matched, returning default variation");
        VariationResult<T> defVar = getVariationValue(flag, flag.defaultVariationId(), defaultValue);
        return Flag.<T>builder(defVar.value())
                .variationKey(defVar.key())
                .source(EvaluationSource.DEFAULT)
                .reasons(reasons)
                .evaluatedAt(startedAt)
                .durationMicros(elapsedMicros(startNano))
                .build();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private record VariationResult<T>(T value, String key) {}

    @SuppressWarnings("unchecked")
    private static <T> VariationResult<T> getVariationValue(
            Datafile.FlagEntry flag, String variationId, T defaultValue) {
        if (flag.variations() == null) return new VariationResult<>(defaultValue, null);
        for (Datafile.VariationEntry v : flag.variations()) {
            if (v.id().equals(variationId)) {
                Object raw = v.value();
                T typed;
                try {
                    typed = (T) raw;
                } catch (ClassCastException e) {
                    typed = defaultValue;
                }
                return new VariationResult<>(typed, v.key());
            }
        }
        return new VariationResult<>(defaultValue, null);
    }

    private static Datafile.FlagEntry findFlag(Datafile.FlagDatafileDoc datafile, String flagKey) {
        if (datafile.flags() == null) return null;
        for (Datafile.FlagEntry f : datafile.flags()) {
            if (f.key().equals(flagKey)) return f;
        }
        return null;
    }

    private static String resolvePrimaryKey(EvalContext ctx) {
        EvalContext.SingleContext primary = Context.primaryContext(ctx);
        if (primary != null) return primary.key();
        return "";
    }

    private static Map<String, Datafile.AudienceEntry> buildAudienceMap(Datafile.FlagDatafileDoc datafile) {
        Map<String, Datafile.AudienceEntry> map = new HashMap<>();
        if (datafile.audiences() != null) {
            for (Datafile.AudienceEntry a : datafile.audiences()) {
                map.put(a.id(), a);
            }
        }
        return map;
    }

    private static long elapsedMicros(long startNano) {
        return Math.max(0, (System.nanoTime() - startNano) / 1_000);
    }
}
