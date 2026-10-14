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

/**
 * Targeted delivery rollouts (N1-1, production QA 2026-10-01).
 *
 * <p>A TARGETED_DELIVERY rule with trafficAllocation 0.3 used to serve 100 percent of its
 * audience in every SDK, because the evaluator only applied the allocation to ab_test rules.
 * These tests lock the fix: the same gate, hash input and float threshold as an ab_test rule.
 * They mirror packages/avsb-core/src/evaluate.rollout.test.ts, and the exact counts were
 * computed by the TypeScript reference (@avsbhq/core 1.6.0) over the same keys.
 */
@DisplayName("Evaluator — targeted delivery rollouts")
class EvaluatorRolloutTest {

    private static final String FLAG_KEY = "rollout_feature";
    private static final String RULE_ID = "rule_rollout";
    private static final int USERS = 10_000;

    /** One targeted_delivery rule; {@code allocation} is a JSON literal such as 0.3 or null. */
    private static String tdRule(String id, String allocation, String variationId) {
        return """
                {"id": "%s", "type": "targeted_delivery", "enabled": true, "audienceIds": [],
                 "hashAttribute": "user.key", "trafficAllocation": %s,
                 "variations": [{"variationId": "%s", "percentage": 1}]}
                """.formatted(id, allocation, variationId);
    }

    private static Datafile.FlagDatafileDoc datafile(String rules, String audiences) {
        return TestUtils.parseDatafile("""
                {
                  "version": 2,
                  "projectType": "FEATURE_FLAG",
                  "sdkKey": "sdk_test_rolloutfixture00001",
                  "environmentKey": "test",
                  "publishedAt": "2026-10-01T00:00:00.000Z",
                  "collectEndpoint": "https://collect.test.invalid",
                  "flags": [{
                    "id": "flag_rollout", "key": "rollout_feature", "type": "boolean", "enabled": true,
                    "defaultVariationId": "var_off",
                    "variations": [
                      {"id": "var_off", "key": "off", "value": false},
                      {"id": "var_on", "key": "on", "value": true},
                      {"id": "var_fallback", "key": "fallback", "value": true}
                    ],
                    "overrides": [],
                    "rules": [%s]
                  }],
                  "audiences": [%s]
                }
                """.formatted(rules, audiences));
    }

    private static Flag<Object> evaluate(Datafile.FlagDatafileDoc df, String key) {
        return Evaluator.evaluateFlag(FLAG_KEY, false, EvalContext.user(key), df);
    }

    private static int servedCount(Datafile.FlagDatafileDoc df, int count) {
        int served = 0;
        for (int i = 0; i < count; i++) {
            if (RULE_ID.equals(evaluate(df, "user-" + i).ruleId())) served++;
        }
        return served;
    }

    private static int allocationBucket(String key) {
        return Hash.bucket(key + FLAG_KEY + "__" + RULE_ID);
    }

    private static String keyForAllocationBucket(int bucket, String prefix) {
        for (int i = 0; i < 200_000; i++) {
            String candidate = prefix + "-" + i;
            if (allocationBucket(candidate) == bucket) return candidate;
        }
        throw new IllegalStateException("no key hashed to allocation bucket " + bucket);
    }

    // -------------------------------------------------------------------------
    // Rollout share
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a 30 percent rule serves the reference share of 10,000 users")
    void thirtyPercentServesTheReferenceShare() {
        // The TypeScript reference serves exactly 2,984 of these keys.
        assertEquals(2984, servedCount(datafile(tdRule(RULE_ID, "0.3", "var_on"), ""), USERS));
    }

    @Test
    @DisplayName("the 5 and 50 percent rollouts match the reference counts")
    void fiveAndFiftyPercentMatchTheReference() {
        assertEquals(536, servedCount(datafile(tdRule(RULE_ID, "0.05", "var_on"), ""), USERS));
        assertEquals(4939, servedCount(datafile(tdRule(RULE_ID, "0.5", "var_on"), ""), USERS));
    }

    @Test
    @DisplayName("a null allocation serves everyone, exactly as before the fix")
    void nullServesEveryone() {
        assertEquals(2000, servedCount(datafile(tdRule(RULE_ID, "null", "var_on"), ""), 2000));
    }

    @Test
    @DisplayName("a full allocation serves everyone and a zero allocation no one")
    void fullServesEveryoneAndZeroNoOne() {
        assertEquals(2000, servedCount(datafile(tdRule(RULE_ID, "1", "var_on"), ""), 2000));
        assertEquals(0, servedCount(datafile(tdRule(RULE_ID, "0", "var_on"), ""), 2000));
    }

    @Test
    @DisplayName("growing a rollout keeps everyone already inside it")
    void growingKeepsEveryoneInside() {
        Datafile.FlagDatafileDoc small = datafile(tdRule(RULE_ID, "0.05", "var_on"), "");
        Datafile.FlagDatafileDoc large = datafile(tdRule(RULE_ID, "0.3", "var_on"), "");
        for (int i = 0; i < 2000; i++) {
            if (!RULE_ID.equals(evaluate(small, "user-" + i).ruleId())) continue;
            assertEquals(RULE_ID, evaluate(large, "user-" + i).ruleId(), "user-" + i);
        }
    }

    // -------------------------------------------------------------------------
    // Rollout membership
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("the ab_test hash input decides membership for both rule types")
    void abTestHashInputDecidesMembership() {
        Datafile.FlagDatafileDoc asTargeted = datafile(tdRule(RULE_ID, "0.3", "var_on"), "");
        Datafile.FlagDatafileDoc asAbTest = datafile(
                tdRule(RULE_ID, "0.3", "var_on").replace("targeted_delivery", "ab_test"), "");
        for (int i = 0; i < 2000; i++) {
            boolean inRollout = allocationBucket("user-" + i) < 3000;
            assertEquals(inRollout, RULE_ID.equals(evaluate(asTargeted, "user-" + i).ruleId()), "td user-" + i);
            assertEquals(inRollout, RULE_ID.equals(evaluate(asAbTest, "user-" + i).ruleId()), "ab user-" + i);
        }
    }

    @Test
    @DisplayName("the first users bucket exactly as the reference does")
    void firstUsersBucketAsTheReference() {
        List<Integer> buckets = new ArrayList<>();
        for (int i = 0; i < 5; i++) buckets.add(allocationBucket("user-" + i));
        assertEquals(List.of(5278, 8498, 4278, 4172, 1088), buckets);
    }

    @Test
    @DisplayName("the threshold is the raw float, so bucket 1234 is inside a 0.12345 rollout")
    void thresholdIsTheRawFloat() {
        Datafile.FlagDatafileDoc df = datafile(tdRule(RULE_ID, "0.12345", "var_on"), "");
        String inside = keyForAllocationBucket(1234, "td-in");
        String outside = keyForAllocationBucket(1235, "td-out");
        // The same keys the TypeScript search settles on.
        assertEquals("td-in-3679", inside);
        assertEquals("td-out-19194", outside);

        assertEquals(RULE_ID, evaluate(df, inside).ruleId());

        Flag<Object> excluded = evaluate(df, outside);
        assertEquals(EvaluationSource.DEFAULT, excluded.source());
        assertEquals(List.of(
                "rule \"rule_rollout\" excluded by traffic allocation (hash 1235 >= threshold 1234.5)",
                "no rules matched, returning default variation"), excluded.reasons());
    }

    @Test
    @DisplayName("a visitor outside the rollout falls through to the next rule")
    void outsideFallsThroughToTheNextRule() {
        Datafile.FlagDatafileDoc df = datafile(
                tdRule(RULE_ID, "0.3", "var_on") + "," + tdRule("rule_everyone", "null", "var_fallback"), "");
        Flag<Object> flag = evaluate(df, keyForAllocationBucket(9000, "td-next"));
        assertEquals("rule_everyone", flag.ruleId());
        assertEquals("fallback", flag.variationKey());
    }

    @Test
    @DisplayName("an audience miss still skips before the rollout is consulted")
    void audienceMissStillSkipsFirst() {
        String paid = """
                {"id": "aud_paid", "rules": {"id": "g_root", "logic": "AND", "conditions": [
                  {"id": "c_plan", "type": "CUSTOM_ATTRIBUTE", "attribute": "plan",
                   "operator": "equals", "value": "paid", "contextKind": "user"}]}}
                """;
        String rule = tdRule(RULE_ID, "1", "var_on").replace("\"audienceIds\": []", "\"audienceIds\": [\"aud_paid\"]");
        Datafile.FlagDatafileDoc df = datafile(rule, paid);

        Flag<Object> flag = Evaluator.evaluateFlag(
                FLAG_KEY, false, EvalContext.user("free-user", Map.of("plan", "free")), df);
        assertEquals(EvaluationSource.DEFAULT, flag.source());
        assertEquals("rule \"rule_rollout\" skipped (audience not matched)", flag.reasons().get(0));
    }

    @Test
    @DisplayName("a rule with no variations stays a silent skip, whatever its allocation")
    void noVariationsStaysSilent() {
        String rule = """
                {"id": "rule_rollout", "type": "targeted_delivery", "enabled": true, "audienceIds": [],
                 "hashAttribute": "user.key", "trafficAllocation": 0, "variations": []}
                """;
        Flag<Object> flag = evaluate(datafile(rule, ""), "u1");
        assertEquals(List.of("no rules matched, returning default variation"), flag.reasons());
    }

    @Test
    @DisplayName("a targeted delivery decision fires no exposure, rollout or not")
    void targetedDeliveryFiresNoExposure() {
        List<ExposureEvent> exposures = new ArrayList<>();
        String inside = keyForAllocationBucket(10, "td-expo");
        Flag<Object> flag = Evaluator.evaluateFlag(FLAG_KEY, false, EvalContext.user(inside),
                datafile(tdRule(RULE_ID, "0.3", "var_on"), ""), EvaluateOptions.withExposure(exposures::add));
        assertEquals(RuleType.TARGETED_DELIVERY, flag.ruleType());
        assertEquals(List.of(), exposures);
    }
}
