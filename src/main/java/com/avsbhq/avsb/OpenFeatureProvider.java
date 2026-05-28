package com.avsbhq.avsb;

import dev.openfeature.sdk.*;

import java.util.Map;

/**
 * OpenFeature {@link FeatureProvider} backed by an {@link AvsbServer}.
 *
 * <p>Register this provider with the OpenFeature API:
 * <pre>{@code
 * OpenFeatureAPI.getInstance().setProvider(new OpenFeatureProvider(server));
 * Client client = OpenFeatureAPI.getInstance().getClient();
 * boolean value = client.getBooleanValue("my-flag", false);
 * }</pre>
 *
 * <p>Context mapping: OpenFeature's {@link EvaluationContext} {@code targetingKey}
 * is used as the A vs B user key. Additional attributes are forwarded as-is
 * to the user context's attribute map.
 */
public final class OpenFeatureProvider implements FeatureProvider {

    private final AvsbServer server;

    public OpenFeatureProvider(AvsbServer server) {
        if (server == null) throw new IllegalArgumentException("AvsbServer must not be null");
        this.server = server;
    }

    @Override
    public Metadata getMetadata() {
        return () -> "avsb";
    }

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(
            String flagKey, Boolean defaultValue, EvaluationContext ctx) {
        Flag<Boolean> flag = server.getBoolFlag(flagKey, defaultValue, toAvsbContext(ctx));
        return ProviderEvaluation.<Boolean>builder()
                .value(flag.value())
                .variant(flag.variationKey())
                .reason(flag.source().wireValue())
                .build();
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(
            String flagKey, String defaultValue, EvaluationContext ctx) {
        Flag<String> flag = server.getStringFlag(flagKey, defaultValue, toAvsbContext(ctx));
        return ProviderEvaluation.<String>builder()
                .value(flag.value())
                .variant(flag.variationKey())
                .reason(flag.source().wireValue())
                .build();
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(
            String flagKey, Integer defaultValue, EvaluationContext ctx) {
        Flag<Double> flag = server.getNumberFlag(flagKey, defaultValue.doubleValue(), toAvsbContext(ctx));
        int intValue = flag.value() != null ? flag.value().intValue() : defaultValue;
        return ProviderEvaluation.<Integer>builder()
                .value(intValue)
                .variant(flag.variationKey())
                .reason(flag.source().wireValue())
                .build();
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(
            String flagKey, Double defaultValue, EvaluationContext ctx) {
        Flag<Double> flag = server.getNumberFlag(flagKey, defaultValue, toAvsbContext(ctx));
        return ProviderEvaluation.<Double>builder()
                .value(flag.value())
                .variant(flag.variationKey())
                .reason(flag.source().wireValue())
                .build();
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(
            String flagKey, Value defaultValue, EvaluationContext ctx) {
        Flag<Object> flag = server.getJsonFlag(flagKey, defaultValue, toAvsbContext(ctx));
        Value wrapped = flag.value() instanceof Value v ? v : new Value(flag.value());
        return ProviderEvaluation.<Value>builder()
                .value(wrapped)
                .variant(flag.variationKey())
                .reason(flag.source().wireValue())
                .build();
    }

    // -------------------------------------------------------------------------
    // Context adapter
    // -------------------------------------------------------------------------

    private static EvalContext toAvsbContext(EvaluationContext ofCtx) {
        if (ofCtx == null) return EvalContext.user("anonymous");
        String targetingKey = ofCtx.getTargetingKey();
        String key = (targetingKey != null && !targetingKey.isBlank()) ? targetingKey : "anonymous";
        // Forward all OpenFeature attributes as user context attributes.
        // Value → Object conversion via toString for string/bool/int/double primitives.
        Map<String, Object> attrs = new java.util.HashMap<>();
        for (var entry : ofCtx.asMap().entrySet()) {
            attrs.put(entry.getKey(), entry.getValue().asObject());
        }
        return EvalContext.user(key, attrs);
    }
}
