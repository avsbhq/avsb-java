package cloud.avsb.sdk;

import cloud.avsb.sdk.core.Bandits;
import cloud.avsb.sdk.core.Context;
import cloud.avsb.sdk.core.Datafile;
import cloud.avsb.sdk.core.EvaluateOptions;
import cloud.avsb.sdk.core.Evaluator;
import cloud.avsb.sdk.sticky.StickyBucketService;
import cloud.avsb.sdk.tracking.ServerTracker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Flag reads for {@link AvsbServer}: the client-layer pipeline (runtime override, sticky
 * lookup, then the evaluator), type coercion and the bulk read. Package-private; split out
 * of AvsbServer.java (FU-31).
 */
final class ServerEvaluation {

    private final Logger logger;
    private final Map<String, String> runtimeOverrides;
    private final StickyBucketService stickyBucketService;
    private final Bandits.ModelStore banditModelStore;
    private final AtomicReference<Datafile.FlagDatafileDoc> datafileRef;
    private final AtomicReference<ServerTracker> trackerRef;
    private final AtomicBoolean closed;
    private final ServerTracking tracking;
    private final AtomicBoolean emptyContextWarned = new AtomicBoolean(false);

    ServerEvaluation(
            Logger logger,
            Map<String, String> runtimeOverrides,
            StickyBucketService stickyBucketService,
            Bandits.ModelStore banditModelStore,
            AtomicReference<Datafile.FlagDatafileDoc> datafileRef,
            AtomicReference<ServerTracker> trackerRef,
            AtomicBoolean closed,
            ServerTracking tracking) {
        this.logger = logger;
        this.runtimeOverrides = runtimeOverrides;
        this.stickyBucketService = stickyBucketService;
        this.banditModelStore = banditModelStore;
        this.datafileRef = datafileRef;
        this.trackerRef = trackerRef;
        this.closed = closed;
        this.tracking = tracking;
    }

    /** The body of {@link AvsbServer#getFlag(String, Object, FlagValueType, EvalContext)}. */
    <T> Flag<T> getFlag(String key, T defaultValue, FlagValueType<T> type, EvalContext context) {
        Datafile.FlagDatafileDoc doc = datafileRef.get();
        if (doc == null) {
            String reason = closed.get()
                    ? "AvsB SDK is closed, so flag \"" + key + "\" cannot be evaluated. Build a"
                            + " new AvsbServer if you need flags again."
                    : "AvsB SDK is not ready: no datafile is loaded, so flag \"" + key + "\" fell"
                            + " back to your default. Call server.init() (or pass"
                            + " bootstrapDatafile) before evaluating.";
            logger.warn(reason);
            return Flag.notReady(defaultValue, reason);
        }

        EvalContext ctx = context != null ? context : EvalContext.user("");
        warnOnEmptyContextOnce(ctx);

        Flag<Object> raw = evaluateRaw(doc, key, defaultValue, ctx);
        Flag<T> typed = coerce(key, defaultValue, type, raw, doc);
        tracking.recordDecision(key, ctx, typed);
        return typed;
    }

    /**
     * The three client-layer steps plus the evaluator, with no type coercion. This is the
     * one path every read goes through.
     */
    private Flag<Object> evaluateRaw(
            Datafile.FlagDatafileDoc doc, String key, Object defaultValue, EvalContext ctx) {
        // 1. Runtime override, per flag key, resolved against the datafile's variations.
        Flag<Object> raw = resolveRuntimeOverride(doc, key);

        // 2. Sticky lookup, before the evaluator, so a stored assignment beats a rule and
        //    even beats a datafile override (the node pattern, locked by the corpus).
        if (raw == null) {
            raw = resolveSticky(doc, key, ctx);
        }

        // 3. The evaluator. The caller's default goes in, so a datafile that cannot answer
        //    (missing key, broken defaultVariationId) hands back the caller's own value
        //    with the real source rather than something invented.
        if (raw == null) {
            raw = Evaluator.evaluateFlag(key, defaultValue, ctx, doc, evaluateOptions());
            persistStickyAssignment(doc, key, ctx, raw);
        }
        return raw;
    }

    /** The body of {@link AvsbServer#getAllFlags(EvalContext)}. */
    Map<String, Object> getAllFlags(EvalContext context) {
        Datafile.FlagDatafileDoc doc = datafileRef.get();
        if (doc == null) {
            logger.warn("AvsB SDK is not ready: getAllFlags returned an empty map because no"
                    + " datafile is loaded. Call server.init() first.");
            return Map.of();
        }
        EvalContext ctx = context != null ? context : EvalContext.user("");
        warnOnEmptyContextOnce(ctx);
        Map<String, Object> out = new LinkedHashMap<>();
        List<Datafile.FlagEntry> flags = doc.flags() == null ? List.of() : doc.flags();
        for (Datafile.FlagEntry flag : flags) {
            Flag<Object> resolved = evaluateRaw(doc, flag.key(), null, ctx);
            tracking.recordDecision(flag.key(), ctx, resolved);
            out.put(flag.key(), resolved.value());
        }
        return out;
    }

    private EvaluateOptions evaluateOptions() {
        ServerTracker tracker = trackerRef.get();
        return new EvaluateOptions(tracker == null ? null : tracker::queue, banditModelStore);
    }

    /**
     * A runtime override is a flag key mapped to a VARIATION KEY, resolved against the
     * datafile so the served value and its key are real. A key that names no variation is
     * logged and ignored rather than serving something invented.
     */
    private Flag<Object> resolveRuntimeOverride(Datafile.FlagDatafileDoc doc, String flagKey) {
        String variationKey = runtimeOverrides.get(flagKey);
        if (variationKey == null) {
            return null;
        }
        Datafile.FlagEntry flag = findFlag(doc, flagKey);
        if (flag == null || flag.variations() == null) {
            logger.warn("Runtime override for flag \"" + flagKey + "\" was ignored: that flag is"
                    + " not in the datafile for this environment.");
            return null;
        }
        for (Datafile.VariationEntry variation : flag.variations()) {
            if (variationKey.equals(variation.key())) {
                return Flag.builder(variation.value())
                        .variationKey(variation.key())
                        .source(EvaluationSource.RUNTIME_OVERRIDE)
                        .reasons(List.of("matched runtime override for flagKey=\"" + flagKey + "\""))
                        .build();
            }
        }
        logger.warn("Runtime override for flag \"" + flagKey + "\" was ignored: variation \""
                + variationKey + "\" is not one of that flag's variations ("
                + variationKeys(flag) + ").");
        return null;
    }

    /**
     * Sticky lookup. Runs BEFORE the evaluator, so a stored assignment wins over rules and
     * over a datafile override: a user who was already bucketed stays bucketed.
     *
     * <p>Two cases the contract calls out:
     * <ul>
     *   <li>Stored variation no longer on the flag: treat the lookup as a MISS and fall
     *       through. The variation was deleted; serving it is impossible.</li>
     *   <li>Stored variation still exists but its rule is gone: still serve the sticky
     *       value, warn, and SUPPRESS the exposure, because the rule it would be attributed
     *       to no longer exists.</li>
     * </ul>
     */
    private Flag<Object> resolveSticky(
            Datafile.FlagDatafileDoc doc, String flagKey, EvalContext ctx) {
        if (stickyBucketService == null) {
            return null;
        }
        String userId = Context.bucketingKey(ctx);
        if (userId == null || userId.isEmpty()) {
            return null;
        }
        StickyAssignment assignment = stickyBucketService.lookup(userId, flagKey);
        if (assignment == null) {
            return null;
        }
        Datafile.FlagEntry flag = findFlag(doc, flagKey);
        if (flag == null || flag.variations() == null) {
            return null;
        }
        Datafile.VariationEntry stored = null;
        for (Datafile.VariationEntry variation : flag.variations()) {
            if (variation.id().equals(assignment.variationId())) {
                stored = variation;
                break;
            }
        }
        if (stored == null) {
            // Deleted variation: a miss, not an error.
            return null;
        }

        boolean ruleStillPresent = false;
        if (flag.rules() != null) {
            for (Datafile.RuleEntry rule : flag.rules()) {
                if (!rule.id().equals(assignment.ruleId())) continue;
                if (rule.variations() == null) continue;
                for (Datafile.RuleVariation rv : rule.variations()) {
                    if (stored.id().equals(rv.variationId())) {
                        ruleStillPresent = true;
                        break;
                    }
                }
                break;
            }
        }
        if (!ruleStillPresent) {
            logger.warn("Sticky assignment for flag \"" + flagKey + "\" references a rule that no"
                    + " longer serves that variation, so the stored value is being served without"
                    + " an exposure event.", Map.of(
                    "flagKey", flagKey,
                    "userId", userId,
                    "assignedRuleId", String.valueOf(assignment.ruleId()),
                    "assignedVariationId", String.valueOf(assignment.variationId())));
        }

        return Flag.builder(stored.value())
                .variationKey(stored.key())
                .source(EvaluationSource.STICKY)
                .ruleId(assignment.ruleId())
                .ruleType(assignment.ruleType())
                .reasons(List.of("matched sticky bucket assignment for userId=\"" + userId + "\""))
                .build();
    }

    /**
     * Persist a sticky assignment. ONLY for an ab_test rule that served a variation: a
     * targeted delivery is a rollout, not an assignment, and a holdout or an override is
     * not the user's bucket.
     *
     * <p>The stored field is the variation's ID, not its key. Saving the key corrupts the
     * store, because lookup resolves by id and never matches again.
     */
    private void persistStickyAssignment(
            Datafile.FlagDatafileDoc doc, String flagKey, EvalContext ctx, Flag<Object> result) {
        if (stickyBucketService == null) {
            return;
        }
        if (result.source() != EvaluationSource.RULE
                || result.ruleType() != RuleType.AB_TEST
                || result.ruleId() == null
                || result.variationKey() == null) {
            return;
        }
        String userId = Context.bucketingKey(ctx);
        if (userId == null || userId.isEmpty()) {
            return;
        }
        Datafile.FlagEntry flag = findFlag(doc, flagKey);
        if (flag == null || flag.variations() == null) {
            return;
        }
        for (Datafile.VariationEntry variation : flag.variations()) {
            if (result.variationKey().equals(variation.key())) {
                stickyBucketService.save(userId, flagKey, new StickyAssignment(
                        variation.id(),
                        result.ruleId(),
                        result.ruleType(),
                        System.currentTimeMillis()));
                return;
            }
        }
    }

    /**
     * Apply the type token, then the datafile's declared type. A mismatch on either returns
     * the caller's default with a reason, and warns once per evaluation.
     */
    private <T> Flag<T> coerce(
            String key, T defaultValue, FlagValueType<T> type, Flag<Object> raw,
            Datafile.FlagDatafileDoc doc) {
        // findFlag returns null for an absent key, which is also the only case with no
        // declared type to check. The value in hand is then the caller's own default, so
        // the coercion below passes and the evaluator's source and reason survive.
        Datafile.FlagEntry entry = findFlag(doc, key);
        if (entry != null && !type.matchesDeclaredType(entry.type())) {
            String reason = "flag \"" + key + "\" is declared as \"" + entry.type()
                    + "\" in the datafile but a \"" + type.declaredType()
                    + "\" value was requested; returning your default. Use get"
                    + getterName(entry.type()) + "Flag instead.";
            logger.warn(reason);
            return Flag.notFound(defaultValue, reason);
        }

        FlagValueType.Coerced<T> coerced = type.coerce(raw.value());
        if (!coerced.matched()) {
            String reason = "flag \"" + key + "\" " + coerced.mismatch()
                    + "; returning your default.";
            logger.warn(reason);
            return Flag.notFound(defaultValue, reason);
        }

        T value = coerced.value() != null ? coerced.value() : defaultValue;
        return Flag.builder(value)
                .variationKey(raw.variationKey())
                .source(raw.source())
                .ruleId(raw.ruleId())
                .ruleType(raw.ruleType())
                .reasons(raw.reasons())
                .evaluatedAt(raw.evaluatedAt())
                .durationMicros(raw.durationMicros())
                .build();
    }

    /**
     * Warn once per client about an empty bucketing key. Every caller who forgets the
     * context lands the whole fleet in one bucket, which corrupts an experiment invisibly,
     * so the message names the exact call to add.
     */
    private void warnOnEmptyContextOnce(EvalContext ctx) {
        String key = Context.bucketingKey(ctx);
        if (key != null && !key.isEmpty()) {
            return;
        }
        if (emptyContextWarned.compareAndSet(false, true)) {
            logger.warn("AvsB evaluated a flag with an empty bucketing key, so every request will"
                    + " land in the same bucket and experiment results will be wrong. Pass a real"
                    + " identity: EvalContext.user(\"<your user id>\").");
        }
    }

    private static Datafile.FlagEntry findFlag(Datafile.FlagDatafileDoc doc, String flagKey) {
        if (doc.flags() == null) return null;
        for (Datafile.FlagEntry flag : doc.flags()) {
            if (flagKey.equals(flag.key())) return flag;
        }
        return null;
    }

    private static String variationKeys(Datafile.FlagEntry flag) {
        List<String> keys = new ArrayList<>();
        for (Datafile.VariationEntry variation : flag.variations()) {
            keys.add(variation.key());
        }
        return String.join(", ", keys);
    }

    private static String getterName(String declaredType) {
        return switch (declaredType == null ? "" : declaredType) {
            case "boolean" -> "Bool";
            case "string" -> "String";
            case "number" -> "Number";
            case "json" -> "Json";
            default -> "Json";
        };
    }
}
