package cloud.avsb.sdk.core;

import cloud.avsb.sdk.EvalContext;
import cloud.avsb.sdk.EvaluationSource;
import cloud.avsb.sdk.ExposureEvent;
import cloud.avsb.sdk.Flag;
import cloud.avsb.sdk.RuleType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Core flag evaluation engine. Mirrors {@code evaluateFlag} in
 * {@code @avsbhq/core/src/evaluate.ts}.
 *
 * <p>Evaluation order, identical in every SDK:
 * <ol>
 *   <li>Flag not found, {@link EvaluationSource#NOT_FOUND}.</li>
 *   <li>Flag disabled, {@link EvaluationSource#DISABLED} serving the default variation.</li>
 *   <li>Datafile user override, {@link EvaluationSource#DATAFILE_OVERRIDE}.</li>
 *   <li>Holdout, {@link EvaluationSource#HOLDOUT} when the context is inside the window.</li>
 *   <li>Rules in declared order: first enabled, audience-matching, allocated rule wins.</li>
 *   <li>Flag default variation, {@link EvaluationSource#DEFAULT}.</li>
 * </ol>
 *
 * <p>Runtime overrides and sticky bucketing sit ABOVE this function, at the client layer
 * (see {@code AvsbServer}), exactly as {@code @avsbhq/node} does it. This function stays a
 * pure function of datafile plus context.
 *
 * <p>The value in the returned {@link Flag} is the datafile's JSON value as deserialised:
 * {@link Boolean}, {@link String}, {@link Double}, {@link Integer}, {@link java.util.Map},
 * {@link java.util.List}, or null. Type checking and the typed fallback live in
 * {@code AvsbServer} via {@code FlagValueType}, so this function never casts blindly.
 *
 * <p>Every reason string here is byte-compared against the reference by the conformance
 * corpus, in order. Numbers inside them are formatted through {@link JsNumber} because
 * they were produced by JavaScript string interpolation.
 */
public final class Evaluator {

    private Evaluator() {
        // Static entry point only.
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> RESERVED_KEYS = Set.of("kind", "key", "name", "_meta");

    /** Evaluate a flag with no exposure recording and cold-start bandits. */
    public static Flag<Object> evaluateFlag(
            String flagKey,
            Object defaultValue,
            EvalContext ctx,
            Datafile.FlagDatafileDoc datafile
    ) {
        return evaluateFlag(flagKey, defaultValue, ctx, datafile, EvaluateOptions.NONE);
    }

    /**
     * Evaluate a flag against the provided datafile and context.
     *
     * @param flagKey      The flag key to look up.
     * @param defaultValue Caller-supplied fallback, returned untouched when the datafile
     *                     cannot answer.
     * @param ctx          Evaluation context.
     * @param datafile     Parsed flag datafile.
     * @param options      Exposure sink and bandit model store; use
     *                     {@link EvaluateOptions#NONE} for neither.
     * @return A fully populated {@link Flag} carrying the raw datafile value.
     */
    public static Flag<Object> evaluateFlag(
            String flagKey,
            Object defaultValue,
            EvalContext ctx,
            Datafile.FlagDatafileDoc datafile,
            EvaluateOptions options
    ) {
        EvaluateOptions opts = options == null ? EvaluateOptions.NONE : options;
        long startedAt = System.currentTimeMillis();
        long startNano = System.nanoTime();
        List<String> reasons = new ArrayList<>();

        // 1. FIND FLAG
        Datafile.FlagEntry flag = findFlag(datafile, flagKey);
        if (flag == null) {
            reasons.add("flag \"" + flagKey + "\" not found in datafile");
            return build(defaultValue, null, EvaluationSource.NOT_FOUND, null, null,
                    reasons, startedAt, startNano);
        }

        // 2. CHECK ENABLED
        if (!flag.enabled()) {
            reasons.add("flag \"" + flagKey + "\" is disabled, returning default variation");
            VariationRef defVar = getVariationValue(flag, flag.defaultVariationId());
            return build(valueOr(defVar, defaultValue), keyOrNull(defVar),
                    EvaluationSource.DISABLED, null, null, reasons, startedAt, startNano);
        }

        // 3. CHECK OVERRIDES. Overrides match on the primary context's key (legacy
        //    "userId"). For a multi-context evaluation the primary kind is "user", or the
        //    first declared kind when no user sub-context is present.
        String primaryKey = resolvePrimaryKey(ctx);
        if (flag.overrides() != null) {
            for (Datafile.OverrideEntry override : flag.overrides()) {
                if (primaryKey.equals(override.userId())) {
                    reasons.add("matched user override for userId=\"" + primaryKey + "\"");
                    VariationRef ov = getVariationValue(flag, override.variationId());
                    return build(valueOr(ov, defaultValue), keyOrNull(ov),
                            EvaluationSource.DATAFILE_OVERRIDE, null, null,
                            reasons, startedAt, startNano);
                }
            }
        }

        // 4. HOLDOUT CHECK. Fires between overrides and the rules loop so an explicit
        //    operator override still wins, while every regular rule honours the holdout.
        Holdouts.HoldoutEvaluation holdout =
                Holdouts.evaluateHoldout(ctx, flag.id(), datafile.holdouts());
        if (holdout.heldOut()) {
            String targetVarId = holdout.variationId() != null
                    ? holdout.variationId()
                    : flag.defaultVariationId();
            VariationRef holdoutVar = getVariationValue(flag, targetVarId);

            if (opts.onExposure() != null) {
                Map<String, Object> properties = new LinkedHashMap<>();
                properties.put("flagKey", flagKey);
                properties.put("flagId", flag.id());
                properties.put("holdoutId", holdout.holdout().id());
                properties.put("holdoutKey", holdout.holdout().key());
                opts.onExposure().accept(new ExposureEvent(
                        null,
                        primaryKey,
                        holdout.holdout().id(),
                        targetVarId,
                        ExposureEvent.FLAG_HOLDOUT_EXPOSURE,
                        null,
                        null,
                        System.currentTimeMillis(),
                        "",
                        properties,
                        buildExposureAttributes(ctx, datafile)));
            }

            reasons.add("held out by holdout \"" + holdout.holdout().key()
                    + "\", serving default variation \""
                    + (holdoutVar != null ? holdoutVar.key() : targetVarId) + "\"");
            return build(valueOr(holdoutVar, defaultValue), keyOrNull(holdoutVar),
                    EvaluationSource.HOLDOUT, holdout.holdout().id(), RuleType.HOLDOUT,
                    reasons, startedAt, startNano);
        }

        Map<String, Datafile.AudienceEntry> audienceMap = buildAudienceMap(datafile);
        int currencyExponent = AudienceCommerce.currencyExponentOf(datafile.currencyExponent());

        // 5. EVALUATE RULES. Each rule's body lives in RuleEvaluator; null falls through to
        //    the next rule.
        if (flag.rules() != null) {
            for (Datafile.RuleEntry rule : flag.rules()) {
                Flag<Object> decision = RuleEvaluator.evaluateRule(
                        rule, flag, flagKey, defaultValue, ctx, datafile, opts, reasons,
                        audienceMap, currencyExponent, startedAt, startNano);
                if (decision != null) {
                    return decision;
                }
            }
        }

        // 6. DEFAULT
        reasons.add("no rules matched, returning default variation");
        VariationRef defVar = getVariationValue(flag, flag.defaultVariationId());
        return build(valueOr(defVar, defaultValue), keyOrNull(defVar), EvaluationSource.DEFAULT,
                null, null, reasons, startedAt, startNano);
    }

    // -------------------------------------------------------------------------
    // Exposure attributes
    // -------------------------------------------------------------------------

    /**
     * Flatten the context and filter it down to the project's registered attribute keys,
     * stringifying values so they fit the analytics store's string map.
     *
     * <p>Unregistered keys are dropped: that is the privacy and cardinality guard, and it
     * is why registering an attribute in the dashboard is what makes it queryable. Returns
     * null when nothing survives, so the field is omitted from the wire body.
     */
    static Map<String, String> buildExposureAttributes(
            EvalContext ctx, Datafile.FlagDatafileDoc datafile) {
        List<String> registered = datafile.registeredAttributeKeys();
        if (registered.isEmpty()) {
            return null;
        }
        Set<String> allowed = Set.copyOf(registered);

        Map<String, Object> flattened = new LinkedHashMap<>();
        EvalContext.SingleContext primary = Context.primaryContext(ctx);
        if (primary != null) {
            flattened.put("userId", primary.key());
            if (primary.name() != null) {
                flattened.put("name", primary.name());
            }
            for (Map.Entry<String, Object> entry : primary.attributes().entrySet()) {
                if (RESERVED_KEYS.contains(entry.getKey())) continue;
                flattened.put(entry.getKey(), entry.getValue());
            }
        }
        if (ctx instanceof EvalContext.MultiContext multi) {
            for (Map.Entry<String, EvalContext.SingleContext> sub : multi.contexts().entrySet()) {
                String kind = sub.getKey();
                EvalContext.SingleContext single = sub.getValue();
                flattened.put(kind + ".key", single.key());
                if (single.name() != null) {
                    flattened.put(kind + ".name", single.name());
                }
                for (Map.Entry<String, Object> entry : single.attributes().entrySet()) {
                    if (RESERVED_KEYS.contains(entry.getKey())) continue;
                    flattened.put(kind + "." + entry.getKey(), entry.getValue());
                }
            }
        }

        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : flattened.entrySet()) {
            if (!allowed.contains(entry.getKey())) continue;
            Object value = entry.getValue();
            if (value == null) continue;
            out.put(entry.getKey(), stringifyAttribute(value));
        }
        return out.isEmpty() ? null : out;
    }

    private static String stringifyAttribute(Object value) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            try {
                return MAPPER.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                // An attribute that cannot be serialised is dropped rather than crashing
                // an evaluation; the reference would have produced "[object Object]".
                return "";
            }
        }
        return JsNumber.stringOf(value);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** A resolved variation: its raw value and its key. */
    record VariationRef(Object value, String key) {}

    static Flag<Object> build(
            Object value, String variationKey, EvaluationSource source, String ruleId,
            RuleType ruleType, List<String> reasons, long startedAt, long startNano) {
        return Flag.builder(value)
                .variationKey(variationKey)
                .source(source)
                .ruleId(ruleId)
                .ruleType(ruleType)
                .reasons(reasons)
                .evaluatedAt(startedAt)
                .durationMicros(elapsedMicros(startNano))
                .build();
    }

    /**
     * Look up a variation by id. Returns null when the id matches nothing, which is
     * distinct from a variation whose value happens to be null: the caller falls back to
     * the caller's default only in the first case.
     */
    static VariationRef getVariationValue(Datafile.FlagEntry flag, String variationId) {
        if (flag.variations() == null || variationId == null) return null;
        for (Datafile.VariationEntry v : flag.variations()) {
            if (variationId.equals(v.id())) {
                return new VariationRef(v.value(), v.key());
            }
        }
        return null;
    }

    static Object valueOr(VariationRef ref, Object defaultValue) {
        return ref != null ? ref.value() : defaultValue;
    }

    static String keyOrNull(VariationRef ref) {
        return ref != null ? ref.key() : null;
    }

    /**
     * The variation key as the reference interpolates it into a reason string. A missing
     * variation reads "undefined" there, because JavaScript stringifies optional chaining
     * that way, and the corpus records it.
     */
    static String keyText(VariationRef ref) {
        return ref != null ? ref.key() : "undefined";
    }

    private static Datafile.FlagEntry findFlag(Datafile.FlagDatafileDoc datafile, String flagKey) {
        if (datafile.flags() == null) return null;
        for (Datafile.FlagEntry f : datafile.flags()) {
            if (flagKey.equals(f.key())) return f;
        }
        return null;
    }

    /**
     * The key overrides match on. Mirrors the reference exactly, including the fallback
     * when a multi-context carries a user sub-context with an empty key.
     */
    private static String resolvePrimaryKey(EvalContext ctx) {
        String userKey = Context.bucketingKey(ctx, "user");
        if (userKey != null && !userKey.isEmpty()) {
            return userKey;
        }
        EvalContext.SingleContext primary = Context.primaryContext(ctx);
        return primary != null && primary.key() != null ? primary.key() : "";
    }

    private static Map<String, Datafile.AudienceEntry> buildAudienceMap(
            Datafile.FlagDatafileDoc datafile) {
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
