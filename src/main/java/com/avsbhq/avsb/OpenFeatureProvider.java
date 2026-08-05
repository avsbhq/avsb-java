package com.avsbhq.avsb;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Structure;
import dev.openfeature.sdk.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenFeature {@link FeatureProvider} backed by an {@link AvsbServer}.
 *
 * <pre>{@code
 * OpenFeatureAPI.getInstance().setProviderAndWait(new OpenFeatureProvider(server));
 * Client client = OpenFeatureAPI.getInstance().getClient();
 * boolean value = client.getBooleanValue("my-flag", false);
 * }</pre>
 *
 * <h2>Context mapping, including multi-kind</h2>
 *
 * OpenFeature has one flat evaluation context; A vs B buckets by KIND, which is how
 * organisation-keyed targeting works. The bridge follows the convention the OpenFeature
 * ecosystem settled on, so nothing is lost:
 *
 * <ul>
 *   <li>{@code kind: "multi"} plus one structure per kind builds a multi-context. Each
 *       structure's {@code key} (or {@code targetingKey}) is that kind's bucketing key and
 *       its remaining members become that kind's attributes.
 *       <pre>{@code
 *       EvaluationContext ctx = new MutableContext("alice")
 *         .add("kind", "multi")
 *         .add("user", new MutableStructure().add("key", "alice").add("plan", "pro"))
 *         .add("organization", new MutableStructure().add("key", "org_42").add("tier", "enterprise"));
 *       }</pre></li>
 *   <li>{@code kind: "organization"} with a targeting key builds a single context of that
 *       kind, so a flag bucketed by organisation buckets by organisation.</li>
 *   <li>No {@code kind} builds a {@code user} context from the targeting key, which is the
 *       behaviour every existing caller already gets.</li>
 * </ul>
 *
 * Collapsing everything to a user context, which this provider used to do, silently lost
 * organisation-keyed bucketing: A vs B's headline targeting feature, evaluated as if it had
 * never been configured.
 *
 * <h2>Error codes</h2>
 *
 * An SDK with no datafile yet reports {@link ErrorCode#PROVIDER_NOT_READY}, and a missing
 * flag reports {@link ErrorCode#FLAG_NOT_FOUND}, so an OpenFeature caller can tell "I never
 * started" from "you asked for a flag that does not exist". Those two used to be
 * indistinguishable.
 */
public final class OpenFeatureProvider implements FeatureProvider {

    private static final String NAME = "avsb";

    private final AvsbServer server;

    public OpenFeatureProvider(AvsbServer server) {
        if (server == null) throw new IllegalArgumentException("AvsbServer must not be null");
        this.server = server;
    }

    @Override
    public Metadata getMetadata() {
        return () -> NAME;
    }

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(
            String flagKey, Boolean defaultValue, EvaluationContext ctx) {
        Flag<Boolean> flag = server.getBoolFlag(
                flagKey, defaultValue != null && defaultValue, toAvsbContext(ctx));
        return toEvaluation(flag, flag.value());
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(
            String flagKey, String defaultValue, EvaluationContext ctx) {
        Flag<String> flag = server.getStringFlag(flagKey, defaultValue, toAvsbContext(ctx));
        return toEvaluation(flag, flag.value());
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(
            String flagKey, Integer defaultValue, EvaluationContext ctx) {
        double fallback = defaultValue != null ? defaultValue : 0;
        Flag<Double> flag = server.getNumberFlag(flagKey, fallback, toAvsbContext(ctx));
        int value = flag.value() != null ? (int) Math.round(flag.value()) : (int) fallback;
        return toEvaluation(flag, value);
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(
            String flagKey, Double defaultValue, EvaluationContext ctx) {
        double fallback = defaultValue != null ? defaultValue : 0;
        Flag<Double> flag = server.getNumberFlag(flagKey, fallback, toAvsbContext(ctx));
        return toEvaluation(flag, flag.value());
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(
            String flagKey, Value defaultValue, EvaluationContext ctx) {
        Flag<Object> flag = server.getJsonFlag(flagKey, null, toAvsbContext(ctx));
        Value value = flag.value() == null ? defaultValue : toValue(flag.value());
        return toEvaluation(flag, value);
    }

    // -------------------------------------------------------------------------
    // Result mapping
    // -------------------------------------------------------------------------

    private static <T> ProviderEvaluation<T> toEvaluation(Flag<?> flag, T value) {
        var builder = ProviderEvaluation.<T>builder()
                .value(value)
                .variant(flag.variationKey())
                .reason(mapReason(flag.source()));

        switch (flag.source()) {
            case NOT_READY -> builder
                    .errorCode(ErrorCode.PROVIDER_NOT_READY)
                    .errorMessage(firstReason(flag));
            case NOT_FOUND -> builder
                    .errorCode(ErrorCode.FLAG_NOT_FOUND)
                    .errorMessage(firstReason(flag));
            default -> {
                // A served decision carries no error code.
            }
        }
        return builder.build();
    }

    private static String firstReason(Flag<?> flag) {
        return flag.reasons().isEmpty() ? null : flag.reasons().get(0);
    }

    /**
     * Map an A vs B source onto an OpenFeature reason. A vs B carries more detail than the
     * OpenFeature vocabulary does, so several sources collapse; the precise source is still
     * available on {@link Flag#source()} for callers holding the SDK directly.
     */
    private static String mapReason(EvaluationSource source) {
        return switch (source) {
            case RULE, HOLDOUT, BANDIT -> Reason.TARGETING_MATCH.toString();
            case DATAFILE_OVERRIDE, RUNTIME_OVERRIDE -> Reason.TARGETING_MATCH.toString();
            case STICKY -> Reason.CACHED.toString();
            case DEFAULT -> Reason.DEFAULT.toString();
            case DISABLED -> Reason.DISABLED.toString();
            case NOT_FOUND, NOT_READY -> Reason.ERROR.toString();
        };
    }

    // -------------------------------------------------------------------------
    // Context mapping
    // -------------------------------------------------------------------------

    /** Build an A vs B context from an OpenFeature one, honouring multi-kind. */
    static EvalContext toAvsbContext(EvaluationContext ofCtx) {
        if (ofCtx == null) {
            return EvalContext.user("");
        }
        String targetingKey = ofCtx.getTargetingKey();
        String key = targetingKey != null ? targetingKey : "";
        Map<String, Value> members = ofCtx.asMap();
        String kind = stringMember(members.get("kind"));

        if ("multi".equals(kind)) {
            Map<String, EvalContext.SingleContext> subContexts = new LinkedHashMap<>();
            for (Map.Entry<String, Value> entry : members.entrySet()) {
                if ("kind".equals(entry.getKey()) || "targetingKey".equals(entry.getKey())) continue;
                Value value = entry.getValue();
                if (value == null || !value.isStructure()) continue;
                subContexts.put(entry.getKey(), toSubContext(entry.getKey(), value.asStructure()));
            }
            if (!subContexts.isEmpty()) {
                // Guarantee a user sub-context so a flag bucketed by user still buckets.
                if (!subContexts.containsKey("user") && !key.isEmpty()) {
                    subContexts.put("user", EvalContext.of("user", key));
                }
                return EvalContext.multi(subContexts);
            }
            // "kind": "multi" with no structures is a malformed context; fall through to a
            // single context rather than throwing inside an evaluation.
        }

        Map<String, Object> attributes = new LinkedHashMap<>();
        for (Map.Entry<String, Value> entry : members.entrySet()) {
            if ("kind".equals(entry.getKey()) || "targetingKey".equals(entry.getKey())) continue;
            attributes.put(entry.getKey(), toJavaObject(entry.getValue()));
        }
        String singleKind = kind != null && !kind.isEmpty() ? kind : "user";
        return EvalContext.of(singleKind, key, attributes);
    }

    private static EvalContext.SingleContext toSubContext(String kind, Structure structure) {
        Map<String, Value> members = structure.asMap();
        String key = stringMember(members.get("key"));
        if (key == null) {
            key = stringMember(members.get("targetingKey"));
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (Map.Entry<String, Value> entry : members.entrySet()) {
            if ("key".equals(entry.getKey()) || "targetingKey".equals(entry.getKey())
                    || "kind".equals(entry.getKey())) {
                continue;
            }
            attributes.put(entry.getKey(), toJavaObject(entry.getValue()));
        }
        return EvalContext.of(kind, key == null ? "" : key, attributes);
    }

    private static String stringMember(Value value) {
        if (value == null || value.isNull()) return null;
        return value.isString() ? value.asString() : null;
    }

    /** OpenFeature {@link Value} to the plain Java shapes the evaluator reads. */
    private static Object toJavaObject(Value value) {
        if (value == null || value.isNull()) return null;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isNumber()) return value.asDouble();
        if (value.isString()) return value.asString();
        if (value.isList()) {
            List<Object> out = new ArrayList<>();
            for (Value item : value.asList()) {
                out.add(toJavaObject(item));
            }
            return out;
        }
        if (value.isStructure()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Value> entry : value.asStructure().asMap().entrySet()) {
                out.put(entry.getKey(), toJavaObject(entry.getValue()));
            }
            return out;
        }
        return value.asObject();
    }

    /** Plain Java shapes back to an OpenFeature {@link Value}, for object evaluation. */
    private static Value toValue(Object raw) {
        if (raw == null) return new Value();
        if (raw instanceof Value v) return v;
        if (raw instanceof Boolean b) return new Value(b);
        if (raw instanceof Number n) return new Value(n.doubleValue());
        if (raw instanceof String s) return new Value(s);
        if (raw instanceof List<?> list) {
            List<Value> values = new ArrayList<>(list.size());
            for (Object item : list) {
                values.add(toValue(item));
            }
            return new Value(values);
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Value> members = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                members.put(String.valueOf(entry.getKey()), toValue(entry.getValue()));
            }
            return new Value(new ImmutableStructure(members));
        }
        return new Value(String.valueOf(raw));
    }
}
