package com.avsbhq.avsb.core;

import com.avsbhq.avsb.EvalContext;
import com.avsbhq.avsb.EvaluationSource;
import com.avsbhq.avsb.ExposureEvent;
import com.avsbhq.avsb.Flag;
import com.avsbhq.avsb.RuleType;
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

        // 5. EVALUATE RULES
        if (flag.rules() != null) {
            for (Datafile.RuleEntry rule : flag.rules()) {
                if (!rule.enabled()) {
                    reasons.add("rule \"" + rule.id() + "\" skipped (disabled)");
                    continue;
                }

                // v1 datafiles persist hashAttribute "userId" and expect the primary
                // context's key; v2 datafiles persist dotted paths like "user.key" or
                // "organization.tier".
                String hashPath = rule.hashAttribute() != null && !rule.hashAttribute().isEmpty()
                        ? rule.hashAttribute() : "userId";
                Object resolved = Context.resolveDottedAttribute(ctx, hashPath);
                String hashValue = resolved == null ? "" : JsNumber.stringOf(resolved);

                // a. AUDIENCE CHECK
                if (rule.audienceIds() != null && !rule.audienceIds().isEmpty()) {
                    boolean matched = false;
                    for (String audId : rule.audienceIds()) {
                        Datafile.AudienceEntry aud = audienceMap.get(audId);
                        if (aud != null && Audience.evaluateAudience(aud, ctx, currencyExponent)) {
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
                String ruleType = rule.type() == null ? "" : rule.type();
                switch (ruleType) {
                    case "targeted_delivery" -> {
                        // An empty variation list cannot serve anything. The reference
                        // skips it without a reason, so this does too.
                        if (rule.variations() == null || rule.variations().isEmpty()) continue;
                        // A targeted delivery rule below 100 percent is a ROLLOUT: serve the
                        // variation to that share of the audience and let everyone else fall
                        // through to the next rule, exactly as an ab_test rule's allocation does
                        // (N1-1). The gate sits after the empty-variations skip, so a rule with
                        // nothing to serve stays a silent skip.
                        String tdExclusion = AllocationGate.exclusion(rule, hashValue, flagKey);
                        if (tdExclusion != null) {
                            reasons.add(tdExclusion);
                            continue;
                        }
                        VariationRef targetVar =
                                getVariationValue(flag, rule.variations().get(0).variationId());
                        reasons.add("rule \"" + rule.id()
                                + "\" matched (targeted_delivery), serving variation \""
                                + keyText(targetVar) + "\"");
                        return build(valueOr(targetVar, defaultValue), keyOrNull(targetVar),
                                EvaluationSource.RULE, rule.id(), RuleType.TARGETED_DELIVERY,
                                reasons, startedAt, startNano);
                    }

                    case "ab_test" -> {
                        // Traffic allocation (see AllocationGate for the float-threshold rule).
                        String abExclusion = AllocationGate.exclusion(rule, hashValue, flagKey);
                        if (abExclusion != null) {
                            reasons.add(abExclusion);
                            continue;
                        }

                        int hash = Hash.bucket(hashValue + flagKey);
                        double cumulative = 0;
                        if (rule.variations() != null) {
                            for (Datafile.RuleVariation rv : rule.variations()) {
                                cumulative += rv.percentage() * 10_000;
                                if (hash < cumulative) {
                                    VariationRef expVar = getVariationValue(flag, rv.variationId());

                                    if (opts.onExposure() != null && expVar != null) {
                                        Map<String, Object> properties = new LinkedHashMap<>();
                                        properties.put("flagKey", flagKey);
                                        properties.put("flagId", flag.id());
                                        opts.onExposure().accept(new ExposureEvent(
                                                null,
                                                hashValue,
                                                rule.id(),
                                                rv.variationId(),
                                                ExposureEvent.FLAG_EXPOSURE,
                                                null,
                                                null,
                                                System.currentTimeMillis(),
                                                "",
                                                properties,
                                                buildExposureAttributes(ctx, datafile)));
                                    }

                                    reasons.add("rule \"" + rule.id()
                                            + "\" matched (ab_test), bucketed into variation \""
                                            + keyText(expVar) + "\" (hash " + hash + "/"
                                            + JsNumber.stringify(cumulative) + ")");
                                    return build(valueOr(expVar, defaultValue), keyOrNull(expVar),
                                            EvaluationSource.RULE, rule.id(), RuleType.AB_TEST,
                                            reasons, startedAt, startNano);
                                }
                            }
                        }
                        // Fell through every variation: the percentages do not sum to the
                        // allocated traffic. The reference continues silently.
                        continue;
                    }

                    case "bandit" -> {
                        // Every exit below appends a reason. A bandit rule must never
                        // disappear from the decision trail (spec section 1.2 silent-drop
                        // ban): before this, a configured bandit behaved exactly like "no
                        // rules matched" with nothing logged anywhere.
                        ResolvedBandit bandit = resolveRuleBandit(rule, datafile);
                        if (bandit == null) {
                            reasons.add(rule.banditId() != null
                                    ? "rule \"" + rule.id() + "\" skipped (banditId \""
                                            + rule.banditId() + "\" is not in datafile.bandits)"
                                    : "rule \"" + rule.id()
                                            + "\" skipped (bandit rule carries no bandit config)");
                            continue;
                        }
                        if (bandit.config().actions() == null || bandit.config().actions().isEmpty()) {
                            reasons.add("rule \"" + rule.id() + "\" skipped (bandit \""
                                    + bandit.key() + "\" declares no actions)");
                            continue;
                        }

                        Bandits.ModelStore store = opts.banditModelStore() != null
                                ? opts.banditModelStore()
                                : Bandits.coldStartStore(bandit.config());
                        Bandits.Decision decision =
                                Bandits.evaluateBandit(bandit.key(), bandit.config(), ctx, store);
                        if (decision == null) {
                            reasons.add("rule \"" + rule.id() + "\" skipped (bandit \""
                                    + bandit.key() + "\" has no model in the supplied store)");
                            continue;
                        }

                        VariationRef banditVar =
                                getVariationValue(flag, decision.action().variationId());
                        if (banditVar == null) {
                            reasons.add("rule \"" + rule.id() + "\" skipped (bandit \""
                                    + bandit.key() + "\" action \"" + decision.action().id()
                                    + "\" targets unknown variation \""
                                    + decision.action().variationId() + "\")");
                            continue;
                        }

                        if (opts.onExposure() != null) {
                            Map<String, Object> properties = new LinkedHashMap<>();
                            properties.put("flagKey", flagKey);
                            properties.put("flagId", flag.id());
                            properties.put("banditKey", bandit.key());
                            properties.put("actionId", decision.action().id());
                            opts.onExposure().accept(new ExposureEvent(
                                    null,
                                    hashValue,
                                    rule.id(),
                                    decision.action().variationId(),
                                    ExposureEvent.FLAG_EXPOSURE,
                                    null,
                                    null,
                                    System.currentTimeMillis(),
                                    "",
                                    properties,
                                    buildExposureAttributes(ctx, datafile)));
                        }

                        reasons.add("rule \"" + rule.id() + "\" matched (bandit \"" + bandit.key()
                                + "\"), served action \"" + decision.action().id()
                                + "\" into variation \"" + banditVar.key() + "\"");
                        return build(banditVar.value(), banditVar.key(), EvaluationSource.BANDIT,
                                rule.id(), RuleType.BANDIT, reasons, startedAt, startNano);
                    }

                    default -> {
                        // A rule type this SDK version does not know: name it rather than
                        // dropping it, so an old SDK against a new datafile is diagnosable.
                        reasons.add("rule \"" + rule.id() + "\" skipped (unsupported rule type \""
                                + ruleType + "\")");
                    }
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
    // Bandit resolution
    // -------------------------------------------------------------------------

    /** A bandit config plus the key that addresses its model store. */
    private record ResolvedBandit(String key, Datafile.BanditConfig config) {}

    /**
     * Resolve the bandit a {@code bandit}-typed rule points at. A shared {@code banditId}
     * joined against {@code datafile.bandits} wins over an inline {@code rule.bandit}, and
     * the resolved key is what addresses the model store: the shared entry's key, or the
     * rule's own id for an inline config.
     */
    private static ResolvedBandit resolveRuleBandit(
            Datafile.RuleEntry rule, Datafile.FlagDatafileDoc datafile) {
        if (rule.banditId() != null && !rule.banditId().isEmpty()) {
            List<Datafile.BanditEntry> shared =
                    datafile.bandits() == null ? List.of() : datafile.bandits();
            for (Datafile.BanditEntry entry : shared) {
                if (rule.banditId().equals(entry.id())) {
                    return new ResolvedBandit(entry.key(), entry.config());
                }
            }
            return null;
        }
        if (rule.bandit() != null) {
            return new ResolvedBandit(rule.id(), rule.bandit());
        }
        return null;
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
    private record VariationRef(Object value, String key) {}

    private static Flag<Object> build(
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
    private static VariationRef getVariationValue(Datafile.FlagEntry flag, String variationId) {
        if (flag.variations() == null || variationId == null) return null;
        for (Datafile.VariationEntry v : flag.variations()) {
            if (variationId.equals(v.id())) {
                return new VariationRef(v.value(), v.key());
            }
        }
        return null;
    }

    private static Object valueOr(VariationRef ref, Object defaultValue) {
        return ref != null ? ref.value() : defaultValue;
    }

    private static String keyOrNull(VariationRef ref) {
        return ref != null ? ref.key() : null;
    }

    /**
     * The variation key as the reference interpolates it into a reason string. A missing
     * variation reads "undefined" there, because JavaScript stringifies optional chaining
     * that way, and the corpus records it.
     */
    private static String keyText(VariationRef ref) {
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
