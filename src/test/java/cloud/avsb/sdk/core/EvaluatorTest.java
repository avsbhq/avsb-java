package cloud.avsb.sdk.core;

import cloud.avsb.sdk.EvalContext;
import cloud.avsb.sdk.EvaluationSource;
import cloud.avsb.sdk.ExposureEvent;
import cloud.avsb.sdk.Flag;
import cloud.avsb.sdk.RuleType;
import cloud.avsb.sdk.TestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Evaluator — evaluateFlag")
class EvaluatorTest {

    // Minimal datafile with one enabled flag, one disabled flag, overrides, rules
    private static final String DATAFILE_JSON = """
            {
              "version": 2,
              "projectType": "FEATURE_FLAG",
              "sdkKey": "sdk_test_conformancefixture01",
              "environmentKey": "test",
              "publishedAt": "2026-05-29T00:00:00.000Z",
              "collectEndpoint": "https://ingest.avsb.cloud",
              "flags": [
                {
                  "id": "flag_1",
                  "key": "feature-flag",
                  "type": "boolean",
                  "enabled": true,
                  "defaultVariationId": "var_off",
                  "variations": [
                    {"id": "var_off", "key": "off", "value": false},
                    {"id": "var_on",  "key": "on",  "value": true}
                  ],
                  "overrides": [{"userId": "vip-user", "variationId": "var_on"}],
                  "rules": [
                    {
                      "id": "rule_full",
                      "type": "targeted_delivery",
                      "enabled": true,
                      "audienceIds": ["aud_paid"],
                      "hashAttribute": "user.key",
                      "trafficAllocation": null,
                      "variations": [{"variationId": "var_on", "percentage": 1}]
                    }
                  ]
                },
                {
                  "id": "flag_2",
                  "key": "disabled-flag",
                  "type": "boolean",
                  "enabled": false,
                  "defaultVariationId": "var_off",
                  "variations": [
                    {"id": "var_off", "key": "off", "value": false}
                  ],
                  "overrides": [],
                  "rules": []
                },
                {
                  "id": "flag_ab",
                  "key": "ab-experiment",
                  "type": "string",
                  "enabled": true,
                  "defaultVariationId": "var_ctrl",
                  "variations": [
                    {"id": "var_ctrl", "key": "control", "value": "old"},
                    {"id": "var_trt",  "key": "treatment", "value": "new"}
                  ],
                  "overrides": [],
                  "rules": [
                    {
                      "id": "rule_ab",
                      "type": "ab_test",
                      "enabled": true,
                      "audienceIds": [],
                      "hashAttribute": "user.key",
                      "trafficAllocation": 1,
                      "variations": [
                        {"variationId": "var_ctrl", "percentage": 0.5},
                        {"variationId": "var_trt",  "percentage": 0.5}
                      ]
                    }
                  ]
                },
                {
                  "id": "flag_future",
                  "key": "future-rule-flag",
                  "type": "boolean",
                  "enabled": true,
                  "defaultVariationId": "var_off",
                  "variations": [
                    {"id": "var_off", "key": "off", "value": false},
                    {"id": "var_on",  "key": "on",  "value": true}
                  ],
                  "overrides": [],
                  "rules": [
                    {
                      "id": "rule_from_the_future",
                      "type": "multi_armed_rollout",
                      "enabled": true,
                      "audienceIds": [],
                      "hashAttribute": "user.key",
                      "trafficAllocation": null,
                      "variations": []
                    }
                  ]
                }
              ],
              "audiences": [
                {
                  "id": "aud_paid",
                  "rules": {
                    "id": "g1",
                    "logic": "AND",
                    "conditions": [
                      {"id": "c1", "type": "CUSTOM_ATTRIBUTE", "attribute": "plan", "operator": "equals", "value": "paid"}
                    ]
                  }
                }
              ]
            }
            """;

    private Datafile.FlagDatafileDoc datafile() {
        return TestUtils.parseDatafile(DATAFILE_JSON);
    }

    @Test
    @DisplayName("flag not found returns source=NOT_FOUND with the caller default")
    void flagNotFound() {
        Flag<Object> flag = Evaluator.evaluateFlag("missing-flag", false, EvalContext.user("alice"), datafile());
        assertEquals(EvaluationSource.NOT_FOUND, flag.source());
        assertEquals(Boolean.FALSE, flag.value());
        assertFalse(flag.exists());
    }

    @Test
    @DisplayName("disabled flag returns source=DISABLED with the default variation value")
    void disabledFlag() {
        Flag<Object> flag = Evaluator.evaluateFlag("disabled-flag", true, EvalContext.user("alice"), datafile());
        assertEquals(EvaluationSource.DISABLED, flag.source());
        assertEquals(Boolean.FALSE, flag.value(),
                "the default variation value wins over the caller default");
        assertTrue(flag.exists(), "a disabled flag still exists");
    }

    @Test
    @DisplayName("user override wins over rules")
    void userOverride() {
        Flag<Object> flag = Evaluator.evaluateFlag("feature-flag", false, EvalContext.user("vip-user"), datafile());
        assertEquals(EvaluationSource.DATAFILE_OVERRIDE, flag.source());
        assertEquals(Boolean.TRUE, flag.value());
        assertEquals("on", flag.variationKey());
    }

    @Test
    @DisplayName("targeted_delivery rule matches a paid user")
    void targetedDeliveryMatch() {
        EvalContext ctx = EvalContext.user("alice", Map.of("plan", "paid"));
        Flag<Object> flag = Evaluator.evaluateFlag("feature-flag", false, ctx, datafile());
        assertEquals(EvaluationSource.RULE, flag.source());
        assertEquals(RuleType.TARGETED_DELIVERY, flag.ruleType());
        assertEquals(Boolean.TRUE, flag.value());
    }

    @Test
    @DisplayName("targeted_delivery rule misses a free user and returns the default variation")
    void targetedDeliveryMiss() {
        EvalContext ctx = EvalContext.user("alice", Map.of("plan", "free"));
        Flag<Object> flag = Evaluator.evaluateFlag("feature-flag", false, ctx, datafile());
        assertEquals(EvaluationSource.DEFAULT, flag.source());
        assertEquals(Boolean.FALSE, flag.value());
        assertEquals(List.of(
                "rule \"rule_full\" skipped (audience not matched)",
                "no rules matched, returning default variation"), flag.reasons());
    }

    @Test
    @DisplayName("ab_test rule assigns a deterministic variation")
    void abTestDeterministic() {
        EvalContext ctx = EvalContext.user("alice");
        Flag<Object> r1 = Evaluator.evaluateFlag("ab-experiment", "fallback", ctx, datafile());
        Flag<Object> r2 = Evaluator.evaluateFlag("ab-experiment", "fallback", ctx, datafile());
        assertEquals(r1.variationKey(), r2.variationKey(), "AB test must be deterministic");
        assertEquals(EvaluationSource.RULE, r1.source());
        assertEquals(RuleType.AB_TEST, r1.ruleType());
    }

    @Test
    @DisplayName("an unsupported rule type is skipped with a reason naming it")
    void unknownRuleTypeIsNamed() {
        Flag<Object> flag = Evaluator.evaluateFlag(
                "future-rule-flag", false, EvalContext.user("alice"), datafile());
        assertEquals(EvaluationSource.DEFAULT, flag.source());
        assertEquals(List.of(
                "rule \"rule_from_the_future\" skipped (unsupported rule type"
                        + " \"multi_armed_rollout\")",
                "no rules matched, returning default variation"), flag.reasons());
    }

    @Test
    @DisplayName("an ab_test decision fires one exposure with the locked event name and properties")
    void abTestFiresExposure() {
        List<ExposureEvent> fired = new ArrayList<>();
        Evaluator.evaluateFlag("ab-experiment", "fallback", EvalContext.user("alice"),
                datafile(), EvaluateOptions.withExposure(fired::add));

        assertEquals(1, fired.size());
        ExposureEvent event = fired.get(0);
        assertEquals("flag_exposure", event.eventName());
        assertEquals("alice", event.visitorId());
        assertEquals("rule_ab", event.experimentId());
        assertNull(event.revenue(), "an exposure never carries money");
        assertEquals("", event.pageUrl(), "there is no page on a server");
        assertEquals("ab-experiment", event.properties().get("flagKey"));
        assertEquals("flag_ab", event.properties().get("flagId"));
    }

    @Test
    @DisplayName("no exposure fires when the flag falls through to its default")
    void defaultFiresNoExposure() {
        List<ExposureEvent> fired = new ArrayList<>();
        Evaluator.evaluateFlag("feature-flag", false, EvalContext.user("alice", Map.of("plan", "free")),
                datafile(), EvaluateOptions.withExposure(fired::add));
        assertEquals(List.of(), fired);
    }

    @Test
    @DisplayName("evaluatedAt is a current ms epoch and durationMicros is non-negative")
    void timingFields() {
        long before = System.currentTimeMillis() - 1;
        Flag<Object> flag = Evaluator.evaluateFlag("feature-flag", false, EvalContext.user("x"), datafile());
        assertTrue(flag.evaluatedAt() >= before);
        assertTrue(flag.durationMicros() >= 0);
    }
}
