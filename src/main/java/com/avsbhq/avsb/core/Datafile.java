package com.avsbhq.avsb.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Deserialised representation of a v1/v2 flag datafile. Mirrors
 * {@code FlagDatafile} and its sub-types in {@code @avsbhq/core/src/types.ts}.
 *
 * <p>All classes are annotated {@code @JsonIgnoreProperties(ignoreUnknown = true)}
 * so forward-compatibility is maintained as the wire format evolves.
 */
public final class Datafile {

    private Datafile() {}

    // -------------------------------------------------------------------------
    // Root document
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FlagDatafileDoc(
            int version,
            String projectType,
            String sdkKey,
            String environmentKey,
            String publishedAt,
            String collectEndpoint,
            String heartbeatEndpoint,
            List<FlagEntry> flags,
            List<AudienceEntry> audiences,
            List<HoldoutEntry> holdouts,
            List<Object> bandits,
            List<Object> bigSegments,
            List<String> contextKinds,
            Double exposureSampling
    ) {}

    // -------------------------------------------------------------------------
    // Flag entry
    // -------------------------------------------------------------------------

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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RuleEntry(
            String id,
            String type,
            boolean enabled,
            List<String> audienceIds,
            String hashAttribute,
            String holdoutId,
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConditionNode(
            String id,
            String type,
            String attribute,
            String operator,
            Object value,             // string or List<String>
            String contextKind
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
}
