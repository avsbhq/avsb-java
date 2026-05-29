package com.avsbhq.avsb.core;

import com.avsbhq.avsb.EvalContext;
import com.avsbhq.avsb.EvaluationSource;
import com.avsbhq.avsb.Flag;
import com.avsbhq.avsb.RuleType;
import com.avsbhq.avsb.TestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Evaluator — evaluateFlag")
class EvaluatorTest {

    // Minimal datafile with one enabled flag, one disabled flag, overrides, rules
    private static final String DATAFILE_JSON = """
            {
              "version": 2,
              "projectType": "FEATURE_FLAG",
              "sdkKey": "test-key",
              "environmentKey": "test",
              "publishedAt": "2026-05-29T00:00:00.000Z",
              "collectEndpoint": "https://ingest.avsb.cloud/v1/events",
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
    @DisplayName("flag not found returns source=NOT_FOUND with default value")
    void flagNotFound() {
        Flag<Boolean> flag = Evaluator.evaluateFlag("missing-flag", false, EvalContext.user("alice"), datafile());
        assertEquals(EvaluationSource.NOT_FOUND, flag.source());
        assertFalse(flag.value());
        assertFalse(flag.exists());
    }

    @Test
    @DisplayName("disabled flag returns source=DISABLED with default variation value")
    void disabledFlag() {
        Flag<Boolean> flag = Evaluator.evaluateFlag("disabled-flag", true, EvalContext.user("alice"), datafile());
        assertEquals(EvaluationSource.DISABLED, flag.source());
        assertFalse(flag.value(), "Should return the default variation value, not the call default");
    }

    @Test
    @DisplayName("user override wins over rules")
    void userOverride() {
        Flag<Boolean> flag = Evaluator.evaluateFlag("feature-flag", false, EvalContext.user("vip-user"), datafile());
        assertEquals(EvaluationSource.DATAFILE_OVERRIDE, flag.source());
        assertTrue(flag.value());
        assertEquals("on", flag.variationKey());
    }

    @Test
    @DisplayName("targeted_delivery rule matches paid user")
    void targetedDeliveryMatch() {
        EvalContext ctx = EvalContext.user("alice", Map.of("plan", "paid"));
        Flag<Boolean> flag = Evaluator.evaluateFlag("feature-flag", false, ctx, datafile());
        assertEquals(EvaluationSource.RULE, flag.source());
        assertEquals(RuleType.TARGETED_DELIVERY, flag.ruleType());
        assertTrue(flag.value());
    }

    @Test
    @DisplayName("targeted_delivery rule misses free user, returns default")
    void targetedDeliveryMiss() {
        EvalContext ctx = EvalContext.user("alice", Map.of("plan", "free"));
        Flag<Boolean> flag = Evaluator.evaluateFlag("feature-flag", false, ctx, datafile());
        assertEquals(EvaluationSource.DEFAULT, flag.source());
        assertFalse(flag.value());
    }

    @Test
    @DisplayName("ab_test rule assigns deterministic variation")
    void abTestDeterministic() {
        EvalContext ctx = EvalContext.user("alice");
        Flag<String> r1 = Evaluator.evaluateFlag("ab-experiment", "fallback", ctx, datafile());
        Flag<String> r2 = Evaluator.evaluateFlag("ab-experiment", "fallback", ctx, datafile());
        assertEquals(r1.variationKey(), r2.variationKey(), "AB test must be deterministic");
        assertEquals(EvaluationSource.RULE, r1.source());
        assertEquals(RuleType.AB_TEST, r1.ruleType());
    }

    @Test
    @DisplayName("reasons list is populated for every evaluation path")
    void reasonsPopulated() {
        Flag<Boolean> found = Evaluator.evaluateFlag("missing-flag", false, EvalContext.user("x"), datafile());
        assertFalse(found.reasons().isEmpty());

        Flag<Boolean> disabled = Evaluator.evaluateFlag("disabled-flag", false, EvalContext.user("x"), datafile());
        assertFalse(disabled.reasons().isEmpty());
    }

    @Test
    @DisplayName("evaluatedAt is set to a current ms epoch")
    void evaluatedAtSet() {
        long before = System.currentTimeMillis() - 1;
        Flag<Boolean> flag = Evaluator.evaluateFlag("feature-flag", false, EvalContext.user("x"), datafile());
        assertTrue(flag.evaluatedAt() >= before);
    }

    @Test
    @DisplayName("durationMicros is non-negative")
    void durationNonNegative() {
        Flag<Boolean> flag = Evaluator.evaluateFlag("feature-flag", false, EvalContext.user("x"), datafile());
        assertTrue(flag.durationMicros() >= 0);
    }

    @Test
    @DisplayName("exists() returns true for found flag and false for not_found")
    void existsHelper() {
        Flag<Boolean> found = Evaluator.evaluateFlag("feature-flag", false, EvalContext.user("x"), datafile());
        Flag<Boolean> missing = Evaluator.evaluateFlag("no-such-flag", false, EvalContext.user("x"), datafile());
        assertTrue(found.exists());
        assertFalse(missing.exists());
    }
}
