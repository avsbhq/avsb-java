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
 * Bandit dispatch. A {@code bandit}-typed rule used to fall through every branch of this
 * evaluator with no reason appended, so a configured bandit behaved exactly like "no rules
 * matched" and nothing anywhere said otherwise.
 *
 * <p>Six outcomes are possible and all six are asserted: a dispatch, and the five skip paths.
 * Each skip appends a reason naming the rule, which is the spec section 1.2 silent-drop ban.
 */
@DisplayName("Evaluator — bandit dispatch")
class EvaluatorBanditTest {

    private static String datafile(String rules, String bandits) {
        return """
                {
                  "version": 2,
                  "projectType": "FEATURE_FLAG",
                  "sdkKey": "sdk_test_conformancefixture01",
                  "environmentKey": "test",
                  "publishedAt": "2026-05-29T00:00:00.000Z",
                  "collectEndpoint": "https://ingest.avsb.cloud",
                  "flags": [
                    {
                      "id": "flag_bandit_1",
                      "key": "bandit-feature",
                      "type": "string",
                      "enabled": true,
                      "defaultVariationId": "var_control",
                      "variations": [
                        {"id": "var_control", "key": "control", "value": "control-copy"},
                        {"id": "var_bold",    "key": "bold",    "value": "bold-copy"},
                        {"id": "var_plain",   "key": "plain",   "value": "plain-copy"}
                      ],
                      "overrides": [],
                      "rules": [RULES]
                    }
                  ],
                  "audiences": []BANDITS
                }
                """.replace("RULES", rules).replace("BANDITS", bandits);
    }

    private static final String INLINE_UCB1_RULE = """
            {
              "id": "rule_bandit_ucb1",
              "type": "bandit",
              "enabled": true,
              "audienceIds": [],
              "hashAttribute": "user.key",
              "trafficAllocation": null,
              "variations": [],
              "bandit": {
                "algorithm": "ucb1",
                "rewardMetric": "checkout_completed",
                "actions": [
                  {"id": "action_c_plain", "variationId": "var_plain"},
                  {"id": "action_a_bold",  "variationId": "var_bold"}
                ]
              }
            }
            """;

    private static Flag<Object> evaluate(String json) {
        return evaluate(json, EvaluateOptions.NONE);
    }

    private static Flag<Object> evaluate(String json, EvaluateOptions options) {
        return Evaluator.evaluateFlag("bandit-feature", "caller-default",
                EvalContext.user("u_bandit"), TestUtils.parseDatafile(json), options);
    }

    // -------------------------------------------------------------------------
    // Dispatch
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("an inline ucb1 bandit dispatches and cold-start serves the lowest action id")
    void inlineUcb1ColdStart() {
        Flag<Object> flag = evaluate(datafile(INLINE_UCB1_RULE, ""));

        assertEquals(EvaluationSource.BANDIT, flag.source());
        assertEquals(RuleType.BANDIT, flag.ruleType());
        assertEquals("rule_bandit_ucb1", flag.ruleId());
        // Actions are sorted by id, so action_a_bold wins over action_c_plain even though it
        // is declared second.
        assertEquals("bold-copy", flag.value());
        assertEquals("bold", flag.variationKey());
        assertEquals(List.of("rule \"rule_bandit_ucb1\" matched (bandit \"rule_bandit_ucb1\"),"
                + " served action \"action_a_bold\" into variation \"bold\""), flag.reasons());
    }

    @Test
    @DisplayName("an inline epsilon-greedy bandit with exploration off exploits deterministically")
    void inlineEpsilonGreedy() {
        String rule = """
                {
                  "id": "rule_bandit_eg",
                  "type": "bandit",
                  "enabled": true,
                  "audienceIds": [],
                  "hashAttribute": "user.key",
                  "trafficAllocation": null,
                  "variations": [],
                  "bandit": {
                    "algorithm": "epsilon-greedy",
                    "explorationRate": 0,
                    "rewardMetric": "checkout_completed",
                    "actions": [
                      {"id": "action_a_bold",  "variationId": "var_bold"},
                      {"id": "action_b_plain", "variationId": "var_plain"}
                    ]
                  }
                }
                """;
        Flag<Object> flag = evaluate(datafile(rule, ""));
        assertEquals(EvaluationSource.BANDIT, flag.source());
        assertEquals("bold-copy", flag.value());
    }

    @Test
    @DisplayName("a shared banditId resolves through datafile.bandits and uses that entry's key")
    void sharedBanditId() {
        String rule = """
                {
                  "id": "rule_bandit_shared",
                  "type": "bandit",
                  "enabled": true,
                  "audienceIds": [],
                  "hashAttribute": "user.key",
                  "trafficAllocation": null,
                  "variations": [],
                  "banditId": "bandit_checkout"
                }
                """;
        String bandits = """
                ,
                  "bandits": [
                    {
                      "id": "bandit_checkout",
                      "key": "checkout-copy-bandit",
                      "config": {
                        "algorithm": "ucb1",
                        "rewardMetric": "checkout_completed",
                        "actions": [
                          {"id": "action_c_plain", "variationId": "var_plain"},
                          {"id": "action_a_bold",  "variationId": "var_bold"}
                        ]
                      }
                    }
                  ]
                """;
        Flag<Object> flag = evaluate(datafile(rule, bandits));
        assertEquals(EvaluationSource.BANDIT, flag.source());
        // The reason is attributed to the SHARED key, not the rule id: that is what addresses
        // the model store, so mixing them up would learn against the wrong model.
        assertEquals(List.of("rule \"rule_bandit_shared\" matched (bandit \"checkout-copy-bandit\"),"
                + " served action \"action_a_bold\" into variation \"bold\""), flag.reasons());
    }

    @Test
    @DisplayName("a bandit decision fires one exposure naming the bandit and the action")
    void banditFiresExposure() {
        List<ExposureEvent> fired = new ArrayList<>();
        evaluate(datafile(INLINE_UCB1_RULE, ""), EvaluateOptions.withExposure(fired::add));

        assertEquals(1, fired.size());
        ExposureEvent event = fired.get(0);
        assertEquals("flag_exposure", event.eventName());
        assertEquals("rule_bandit_ucb1", event.experimentId());
        assertEquals("var_bold", event.variationId());
        Map<String, Object> properties = event.properties();
        assertEquals("bandit-feature", properties.get("flagKey"));
        assertEquals("rule_bandit_ucb1", properties.get("banditKey"));
        assertEquals("action_a_bold", properties.get("actionId"));
    }

    // -------------------------------------------------------------------------
    // The five skip paths, each with a reason
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("skip 1: a bandit rule with neither an inline config nor a banditId")
    void skipNoConfig() {
        String rule = """
                {
                  "id": "rule_bandit_no_config",
                  "type": "bandit",
                  "enabled": true,
                  "audienceIds": [],
                  "hashAttribute": "user.key",
                  "trafficAllocation": null,
                  "variations": []
                }
                """;
        Flag<Object> flag = evaluate(datafile(rule, ""));
        assertEquals(EvaluationSource.DEFAULT, flag.source());
        assertEquals(List.of(
                "rule \"rule_bandit_no_config\" skipped (bandit rule carries no bandit config)",
                "no rules matched, returning default variation"), flag.reasons());
    }

    @Test
    @DisplayName("skip 2: a banditId that is not in datafile.bandits")
    void skipUnresolvableBanditId() {
        String rule = """
                {
                  "id": "rule_bandit_ghost_id",
                  "type": "bandit",
                  "enabled": true,
                  "audienceIds": [],
                  "hashAttribute": "user.key",
                  "trafficAllocation": null,
                  "variations": [],
                  "banditId": "bandit_does_not_exist"
                }
                """;
        Flag<Object> flag = evaluate(datafile(rule, ""));
        assertEquals(EvaluationSource.DEFAULT, flag.source());
        assertEquals(List.of(
                "rule \"rule_bandit_ghost_id\" skipped (banditId \"bandit_does_not_exist\" is not"
                        + " in datafile.bandits)",
                "no rules matched, returning default variation"), flag.reasons());
    }

    @Test
    @DisplayName("skip 3: a bandit that declares no actions")
    void skipNoActions() {
        String rule = """
                {
                  "id": "rule_bandit_no_actions",
                  "type": "bandit",
                  "enabled": true,
                  "audienceIds": [],
                  "hashAttribute": "user.key",
                  "trafficAllocation": null,
                  "variations": [],
                  "bandit": {
                    "algorithm": "ucb1",
                    "rewardMetric": "checkout_completed",
                    "actions": []
                  }
                }
                """;
        Flag<Object> flag = evaluate(datafile(rule, ""));
        assertEquals(EvaluationSource.DEFAULT, flag.source());
        assertEquals(List.of(
                "rule \"rule_bandit_no_actions\" skipped (bandit \"rule_bandit_no_actions\""
                        + " declares no actions)",
                "no rules matched, returning default variation"), flag.reasons());
    }

    @Test
    @DisplayName("skip 4: a supplied store that has no model for this bandit")
    void skipNoModelInStore() {
        // The cold-start fallback only applies when NO store was configured. A caller who
        // supplied a store and has not trained this bandit yet must be told, not silently
        // given a cold-start decision against a store they are managing themselves.
        Bandits.ModelStore emptyStore = new Bandits.ModelStore() {
            @Override public Bandits.BanditModel getModel(String banditKey) { return null; }
            @Override public void updateModel(String banditKey, Bandits.BanditModel model) { }
        };

        Flag<Object> flag = evaluate(datafile(INLINE_UCB1_RULE, ""),
                EvaluateOptions.of(null, emptyStore));
        assertEquals(EvaluationSource.DEFAULT, flag.source());
        assertEquals(List.of(
                "rule \"rule_bandit_ucb1\" skipped (bandit \"rule_bandit_ucb1\" has no model in"
                        + " the supplied store)",
                "no rules matched, returning default variation"), flag.reasons());
    }

    @Test
    @DisplayName("skip 5: an action pointing at a variation the flag no longer has")
    void skipUnknownVariation() {
        String rule = """
                {
                  "id": "rule_bandit_ghost_variation",
                  "type": "bandit",
                  "enabled": true,
                  "audienceIds": [],
                  "hashAttribute": "user.key",
                  "trafficAllocation": null,
                  "variations": [],
                  "bandit": {
                    "algorithm": "ucb1",
                    "rewardMetric": "checkout_completed",
                    "actions": [
                      {"id": "action_ghost", "variationId": "var_deleted"}
                    ]
                  }
                }
                """;
        Flag<Object> flag = evaluate(datafile(rule, ""));
        assertEquals(EvaluationSource.DEFAULT, flag.source());
        assertEquals(List.of(
                "rule \"rule_bandit_ghost_variation\" skipped (bandit"
                        + " \"rule_bandit_ghost_variation\" action \"action_ghost\" targets unknown"
                        + " variation \"var_deleted\")",
                "no rules matched, returning default variation"), flag.reasons());
    }

    @Test
    @DisplayName("a disabled bandit rule is skipped before dispatch, with the disabled reason")
    void disabledRuleSkippedFirst() {
        Flag<Object> flag = evaluate(datafile(
                INLINE_UCB1_RULE.replace("\"enabled\": true", "\"enabled\": false"), ""));
        assertEquals(EvaluationSource.DEFAULT, flag.source());
        assertEquals(List.of(
                "rule \"rule_bandit_ucb1\" skipped (disabled)",
                "no rules matched, returning default variation"), flag.reasons());
    }

    @Test
    @DisplayName("a learned model beats cold start")
    void learnedModelWins() {
        Bandits.ModelStore store = Bandits.inMemoryStore(Map.of(
                "rule_bandit_ucb1", new Bandits.BanditModel("v1", "ucb1", Map.of(
                        "action_a_bold", new Bandits.BanditPerActionStats(0.1, 0.1, 50),
                        "action_c_plain", new Bandits.BanditPerActionStats(0.9, 0.1, 50)))));

        Flag<Object> flag = evaluate(datafile(INLINE_UCB1_RULE, ""),
                EvaluateOptions.of(null, store));
        assertEquals(EvaluationSource.BANDIT, flag.source());
        assertEquals("plain-copy", flag.value(),
                "with both actions sampled, the higher mean wins instead of the lowest id");
    }
}
