package cloud.avsb.sdk.core;

import cloud.avsb.sdk.EvalContext;
import cloud.avsb.sdk.EvaluationSource;
import cloud.avsb.sdk.ExposureEvent;
import cloud.avsb.sdk.Flag;
import cloud.avsb.sdk.RuleType;
import cloud.avsb.sdk.core.Evaluator.VariationRef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cloud.avsb.sdk.core.Evaluator.build;
import static cloud.avsb.sdk.core.Evaluator.buildExposureAttributes;
import static cloud.avsb.sdk.core.Evaluator.getVariationValue;
import static cloud.avsb.sdk.core.Evaluator.keyOrNull;
import static cloud.avsb.sdk.core.Evaluator.keyText;
import static cloud.avsb.sdk.core.Evaluator.valueOr;

/**
 * Step 5 of {@link Evaluator#evaluateFlag}: one rule against one context, plus the bandit
 * resolution that step needs. Package-private; split out of Evaluator.java (FU-31).
 * {@code evaluateFlag} walks the rules in declared order and stops at the first one that
 * answers. The traffic-allocation gate stays in {@link AllocationGate}.
 */
final class RuleEvaluator {

    private RuleEvaluator() {
        // Static helpers only.
    }

    /**
     * Evaluate one rule. Returns the decision when the rule matches, or null when evaluation
     * falls through to the next rule (disabled, audience not matched, outside the
     * allocation, or nothing to serve). Every skip the reference names appends its reason.
     */
    static Flag<Object> evaluateRule(
            Datafile.RuleEntry rule,
            Datafile.FlagEntry flag,
            String flagKey,
            Object defaultValue,
            EvalContext ctx,
            Datafile.FlagDatafileDoc datafile,
            EvaluateOptions opts,
            List<String> reasons,
            Map<String, Datafile.AudienceEntry> audienceMap,
            int currencyExponent,
            long startedAt,
            long startNano) {
        if (!rule.enabled()) {
            reasons.add("rule \"" + rule.id() + "\" skipped (disabled)");
            return null;
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
                return null;
            }
        }

        // b. EVALUATE BY TYPE
        String ruleType = rule.type() == null ? "" : rule.type();
        switch (ruleType) {
            case "targeted_delivery" -> {
                // An empty variation list cannot serve anything. The reference
                // skips it without a reason, so this does too.
                if (rule.variations() == null || rule.variations().isEmpty()) return null;
                // A targeted delivery rule below 100 percent is a ROLLOUT: serve the
                // variation to that share of the audience and let everyone else fall
                // through to the next rule, exactly as an ab_test rule's allocation does
                // (N1-1). The gate sits after the empty-variations skip, so a rule with
                // nothing to serve stays a silent skip.
                String tdExclusion = AllocationGate.exclusion(rule, hashValue, flagKey);
                if (tdExclusion != null) {
                    reasons.add(tdExclusion);
                    return null;
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
                    return null;
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
                return null;
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
                    return null;
                }
                if (bandit.config().actions() == null || bandit.config().actions().isEmpty()) {
                    reasons.add("rule \"" + rule.id() + "\" skipped (bandit \""
                            + bandit.key() + "\" declares no actions)");
                    return null;
                }

                Bandits.ModelStore store = opts.banditModelStore() != null
                        ? opts.banditModelStore()
                        : Bandits.coldStartStore(bandit.config());
                Bandits.Decision decision =
                        Bandits.evaluateBandit(bandit.key(), bandit.config(), ctx, store);
                if (decision == null) {
                    reasons.add("rule \"" + rule.id() + "\" skipped (bandit \""
                            + bandit.key() + "\" has no model in the supplied store)");
                    return null;
                }

                VariationRef banditVar =
                        getVariationValue(flag, decision.action().variationId());
                if (banditVar == null) {
                    reasons.add("rule \"" + rule.id() + "\" skipped (bandit \""
                            + bandit.key() + "\" action \"" + decision.action().id()
                            + "\" targets unknown variation \""
                            + decision.action().variationId() + "\")");
                    return null;
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
        return null;
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
}
