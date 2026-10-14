package cloud.avsb.sdk.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deserialised representation of a v1/v2 flag datafile. Mirrors {@code FlagDatafile} and
 * its sub-types in {@code @avsbhq/core/src/types.ts}.
 *
 * <p>Every record is annotated {@code @JsonIgnoreProperties(ignoreUnknown = true)} so a
 * datafile published by a newer platform version still parses.
 *
 * <p>Field names are the wire contract. Renaming one here silently stops reading the
 * field: Jackson has nothing to bind and the value becomes null, which is how
 * {@code banditId} rules and commerce audiences used to evaluate as "no match" with no
 * error anywhere.
 */
public final class Datafile {

    private Datafile() {
        // Namespace for the wire records.
    }

    // -------------------------------------------------------------------------
    // Root document
    // -------------------------------------------------------------------------

    /**
     * @param collectEndpoint   Ingest origin for this environment. Normalise it through
     *                          {@code Endpoints.collectUrl} rather than concatenating.
     * @param heartbeatEndpoint Where liveness pings go. Absent means do not send them.
     * @param streamEndpoint    Server-controlled streaming ticket endpoint. Absent means
     *                          streaming is unavailable; never guess a host.
     * @param bandits           Shared bandit definitions a rule can join through
     *                          {@code banditId}.
     * @param registeredAttributes Attribute keys the project registered. Exposure events
     *                          carry only these; see {@link #registeredAttributeKeys()}.
     * @param currencyExponent  Minor-unit exponent for commerce money conditions.
     *                          Absent means 2.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FlagDatafileDoc(
            int version,
            String projectType,
            String sdkKey,
            String environmentKey,
            String publishedAt,
            String collectEndpoint,
            String heartbeatEndpoint,
            String streamEndpoint,
            List<FlagEntry> flags,
            List<AudienceEntry> audiences,
            List<HoldoutEntry> holdouts,
            List<BanditEntry> bandits,
            List<String> contextKinds,
            List<Object> registeredAttributes,
            Double exposureSampling,
            Integer currencyExponent
    ) {

        /**
         * Registered attribute keys, accepting both wire shapes: v1 publishes a list of
         * strings, v2 a list of {@code {key, type}} objects.
         */
        public List<String> registeredAttributeKeys() {
            if (registeredAttributes == null || registeredAttributes.isEmpty()) {
                return List.of();
            }
            List<String> keys = new ArrayList<>(registeredAttributes.size());
            for (Object entry : registeredAttributes) {
                if (entry instanceof String s) {
                    keys.add(s);
                } else if (entry instanceof Map<?, ?> map) {
                    Object key = map.get("key");
                    if (key instanceof String s) {
                        keys.add(s);
                    }
                }
            }
            return List.copyOf(keys);
        }
    }

    // -------------------------------------------------------------------------
    // Flag entry
    // -------------------------------------------------------------------------

    /**
     * @param type The declared value type: {@code boolean}, {@code string},
     *             {@code number}, or {@code json}. The typed getters check the resolved
     *             value against it.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FlagEntry(
            String id,
            String key,
            String type,
            boolean enabled,
            String defaultVariationId,
            List<VariationEntry> variations,
            List<OverrideEntry> overrides,
            List<RuleEntry> rules
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VariationEntry(String id, String key, Object value) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OverrideEntry(String userId, String variationId) {}

    // -------------------------------------------------------------------------
    // Rule entry
    // -------------------------------------------------------------------------

    /**
     * @param type     {@code targeted_delivery}, {@code ab_test}, or {@code bandit}. An
     *                 unrecognised value is skipped with a reason naming it, never
     *                 dropped silently.
     * @param banditId Joins {@code datafile.bandits}. Wins over {@link #bandit()}.
     * @param bandit   Inline bandit config, addressed in the model store by the rule id.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RuleEntry(
            String id,
            String type,
            boolean enabled,
            List<String> audienceIds,
            String hashAttribute,
            String holdoutId,
            String banditId,
            BanditConfig bandit,
            @JsonProperty("trafficAllocation") Double trafficAllocation,
            List<RuleVariation> variations
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RuleVariation(String variationId, double percentage) {}

    // -------------------------------------------------------------------------
    // Audience entry
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AudienceEntry(String id, ConditionGroupNode rules) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConditionGroupNode(
            String id,
            String logic,
            List<Object> conditions   // mixed: ConditionGroupNode or ConditionNode
    ) {}

    /**
     * One audience condition.
     *
     * @param type        Condition family: {@code CUSTOM_ATTRIBUTE}, the three commerce
     *                    families, or a browser-only family the server cannot answer.
     * @param value       A string or a list of strings, depending on the operator.
     * @param match       Commerce passthrough: {@code sku} (default) or {@code category},
     *                    picking which viewed-list attribute a PRODUCTS_VIEWED condition
     *                    reads.
     * @param datasetSlug Commerce passthrough: dataset-backed membership lives in the
     *                    browser, so a condition carrying one evaluates false server-side
     *                    for both operators.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConditionNode(
            String id,
            String type,
            String attribute,
            String operator,
            Object value,
            String contextKind,
            String match,
            String datasetSlug
    ) {}

    // -------------------------------------------------------------------------
    // Holdout entry
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HoldoutEntry(
            String id,
            String key,
            double trafficAllocation,
            List<String> flagIds,
            String hashAttribute,
            Map<String, String> defaultVariationByFlag
    ) {}

    // -------------------------------------------------------------------------
    // Bandit entries
    // -------------------------------------------------------------------------

    /**
     * A shared bandit. {@link #key()} (not the id, and not the rule id) is what addresses
     * the model store and what the decision is attributed to.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BanditEntry(String id, String key, BanditConfig config) {}

    /**
     * @param algorithm       {@code epsilon-greedy}, {@code thompson-sampling}, or
     *                        {@code ucb1}.
     * @param explorationRate Epsilon-greedy exploration fraction. Null means 0.1; an
     *                        explicit 0 means exploration off.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BanditConfig(
            String algorithm,
            Double explorationRate,
            String rewardMetric,
            List<BanditAction> actions
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BanditAction(
            String id,
            String variationId,
            Map<String, Object> contextAttributes
    ) {}
}
