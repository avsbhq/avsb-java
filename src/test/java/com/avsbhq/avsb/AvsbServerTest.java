package com.avsbhq.avsb;

import com.avsbhq.avsb.core.Datafile;
import com.avsbhq.avsb.sticky.InMemoryStickyBucketService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Client-layer behaviour: the pipeline above the evaluator (runtime overrides, sticky,
 * typed coercion, readiness) and the loud-failure contract.
 *
 * <p>Every server here is built with polling and tracking off so the tests never touch the
 * network.
 */
@DisplayName("AvsbServer — client layer")
class AvsbServerTest {

    private static final String SDK_KEY = "sdk_test_conformancefixture01";

    private static final String DATAFILE = """
            {
              "version": 2,
              "projectType": "FEATURE_FLAG",
              "sdkKey": "sdk_test_conformancefixture01",
              "environmentKey": "test",
              "publishedAt": "2026-05-29T00:00:00.000Z",
              "collectEndpoint": "https://ingest.avsb.cloud",
              "flags": [
                {
                  "id": "f1",
                  "key": "bool-flag",
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
                      "id": "rule_1",
                      "type": "targeted_delivery",
                      "enabled": true,
                      "audienceIds": [],
                      "hashAttribute": "user.key",
                      "trafficAllocation": null,
                      "variations": [{"variationId": "var_on", "percentage": 1}]
                    }
                  ]
                },
                {
                  "id": "f2",
                  "key": "string-flag",
                  "type": "string",
                  "enabled": true,
                  "defaultVariationId": "var_a",
                  "variations": [{"id": "var_a", "key": "variant_a", "value": "hello"}],
                  "overrides": [],
                  "rules": []
                },
                {
                  "id": "f3",
                  "key": "number-flag",
                  "type": "number",
                  "enabled": true,
                  "defaultVariationId": "var_n",
                  "variations": [{"id": "var_n", "key": "tuned", "value": 42}],
                  "overrides": [],
                  "rules": []
                },
                {
                  "id": "f4",
                  "key": "json-flag",
                  "type": "json",
                  "enabled": true,
                  "defaultVariationId": "var_j",
                  "variations": [
                    {"id": "var_j", "key": "doc", "value": {"plan": "pro", "amount": 19.5}}
                  ],
                  "overrides": [],
                  "rules": []
                },
                {
                  "id": "f5",
                  "key": "ab-flag",
                  "type": "string",
                  "enabled": true,
                  "defaultVariationId": "var_ctrl",
                  "variations": [
                    {"id": "var_ctrl", "key": "control",   "value": "old"},
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
                  "id": "f6",
                  "key": "rollout-flag",
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
                      "id": "rule_rollout",
                      "type": "targeted_delivery",
                      "enabled": true,
                      "audienceIds": [],
                      "hashAttribute": "user.key",
                      "trafficAllocation": null,
                      "variations": [{"variationId": "var_on", "percentage": 1}]
                    }
                  ]
                }
              ],
              "audiences": []
            }
            """;

    private static AvsbServer.ServerBuilder builder() {
        return AvsbServer.builder(SDK_KEY)
                .logger(Logger.noOp())
                .polling(false)
                .tracking(false);
    }

    private static AvsbServer loaded() {
        AvsbServer server = builder()
                .bootstrapDatafile(TestUtils.parseDatafile(DATAFILE))
                .build();
        return server;
    }

    // -------------------------------------------------------------------------
    // Readiness
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("before a datafile loads, every getter reports NOT_READY, not NOT_FOUND")
    void notReadyBeforeInit() {
        AvsbServer server = builder().build();
        assertFalse(server.isReady());

        Flag<Boolean> flag = server.getBoolFlag("any-flag", true, EvalContext.user("alice"));
        assertEquals(EvaluationSource.NOT_READY, flag.source(),
                "not_found means the flag does not exist; not_ready means the SDK never started");
        assertTrue(flag.value(), "the caller default survives, type intact");
        assertFalse(flag.isEnabled());
        assertFalse(flag.exists());
        assertEquals(1, flag.reasons().size());
        assertTrue(flag.reasons().get(0).contains("init()"),
                "the reason names what to call: " + flag.reasons().get(0));
    }

    @Test
    @DisplayName("a closed client reports NOT_READY rather than serving a stale datafile")
    void notReadyAfterClose() {
        AvsbServer server = loaded();
        assertTrue(server.getBoolFlag("bool-flag", false, EvalContext.user("alice")).value());

        server.close();

        Flag<Boolean> flag = server.getBoolFlag("bool-flag", false, EvalContext.user("alice"));
        assertEquals(EvaluationSource.NOT_READY, flag.source());
        assertTrue(flag.reasons().get(0).contains("closed"));
    }

    @Test
    @DisplayName("a missing flag reports NOT_FOUND with the caller default")
    void notFoundForUnknownKey() {
        AvsbServer server = loaded();
        Flag<Boolean> flag = server.getBoolFlag("no-such-flag", true, EvalContext.user("alice"));
        assertEquals(EvaluationSource.NOT_FOUND, flag.source());
        assertTrue(flag.value());
        assertFalse(flag.exists());
    }

    // -------------------------------------------------------------------------
    // Typed getters
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("each typed getter returns its own type")
    void typedGetters() {
        AvsbServer server = loaded();
        EvalContext ctx = EvalContext.user("alice");

        assertTrue(server.getBoolFlag("bool-flag", false, ctx).value());
        assertEquals("hello", server.getStringFlag("string-flag", "fallback", ctx).value());
        assertEquals(42.0, server.getNumberFlag("number-flag", 0, ctx).value(),
                "every number flag widens to Double, because the reference has one number type");

        Object json = server.getJsonFlag("json-flag", Map.of(), ctx).value();
        assertInstanceOf(Map.class, json);
        assertEquals("pro", ((Map<?, ?>) json).get("plan"));
    }

    @Test
    @DisplayName("a type mismatch falls back to the caller default instead of throwing later")
    void typeMismatchFallsBack() {
        AvsbServer server = loaded();
        // The old evaluator's (T) cast could not throw where it was written, so this call
        // used to hand back a Flag<Boolean> holding a String and blow up with a
        // ClassCastException at the caller's own flag.value().
        Flag<Boolean> asBool = server.getBoolFlag("string-flag", false, EvalContext.user("alice"));
        assertEquals(EvaluationSource.NOT_FOUND, asBool.source());
        assertEquals(Boolean.FALSE, asBool.value());
        assertFalse(asBool.isEnabled());
        assertTrue(asBool.reasons().get(0).contains("string-flag"),
                "the reason names the flag: " + asBool.reasons().get(0));

        // And reading it as a number does not coerce either.
        Flag<Double> asNumber = server.getNumberFlag("string-flag", -1, EvalContext.user("alice"));
        assertEquals(-1.0, asNumber.value());
        assertEquals(EvaluationSource.NOT_FOUND, asNumber.source());
    }

    @Test
    @DisplayName("a JSON flag maps into a caller-supplied record, or falls back with a reason")
    void jsonFlagIntoRecord() {
        AvsbServer server = loaded();
        EvalContext ctx = EvalContext.user("alice");

        Flag<Pricing> pricing = server.getJsonFlag(
                "pricing", new Pricing("standard", 9.99), FlagValueType.json(Pricing.class), ctx);
        assertEquals(EvaluationSource.NOT_FOUND, pricing.source(), "there is no pricing flag here");
        assertEquals("standard", pricing.value().plan());

        Flag<Pricing> mapped = server.getJsonFlag(
                "json-flag", new Pricing("standard", 9.99), FlagValueType.json(Pricing.class), ctx);
        assertEquals("pro", mapped.value().plan());
        assertEquals(19.5, mapped.value().amount());
    }

    /** Target for the JSON generic test. */
    record Pricing(String plan, double amount) {}

    // -------------------------------------------------------------------------
    // Runtime overrides
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a runtime override wins and reports source RUNTIME_OVERRIDE")
    void runtimeOverrideWins() {
        AvsbServer server = builder()
                .bootstrapDatafile(TestUtils.parseDatafile(DATAFILE))
                .runtimeOverrides(Map.of("bool-flag", "off"))
                .build();

        Flag<Boolean> flag = server.getBoolFlag("bool-flag", true, EvalContext.user("alice"));
        assertEquals(EvaluationSource.RUNTIME_OVERRIDE, flag.source());
        assertFalse(flag.value(), "the override's variation value, not the rule's");
        assertEquals("off", flag.variationKey());
        assertFalse(flag.isEnabled(), "a falsy override is not enabled");
    }

    @Test
    @DisplayName("a runtime override naming an unknown variation is ignored loudly")
    void runtimeOverrideUnknownVariation() {
        AvsbServer server = builder()
                .bootstrapDatafile(TestUtils.parseDatafile(DATAFILE))
                .runtimeOverrides(Map.of("bool-flag", "no-such-variation"))
                .build();

        Flag<Boolean> flag = server.getBoolFlag("bool-flag", false, EvalContext.user("alice"));
        assertEquals(EvaluationSource.RULE, flag.source(),
                "an unusable override falls through to the evaluator rather than inventing a value");
    }

    // -------------------------------------------------------------------------
    // Sticky bucketing
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("an ab_test decision saves the variation ID, never the key")
    void stickySavesVariationId() {
        InMemoryStickyBucketService sticky = new InMemoryStickyBucketService();
        AvsbServer server = builder()
                .bootstrapDatafile(TestUtils.parseDatafile(DATAFILE))
                .stickyBucketService(sticky)
                .build();

        Flag<String> first = server.getStringFlag("ab-flag", "fallback", EvalContext.user("alice"));
        assertEquals(EvaluationSource.RULE, first.source());

        StickyAssignment saved = sticky.lookup("alice", "ab-flag");
        assertNotNull(saved, "an ab_test decision must be persisted");
        // Saving the key corrupts the store: lookup resolves by id and never matches again.
        assertTrue(saved.variationId().startsWith("var_"),
                "the stored value must be the variation ID, got " + saved.variationId());
        assertEquals("rule_ab", saved.ruleId());
        assertEquals(RuleType.AB_TEST, saved.ruleType());
    }

    @Test
    @DisplayName("a stored assignment is served on the next evaluation with source STICKY")
    void stickyLookupBeatsRules() {
        InMemoryStickyBucketService sticky = new InMemoryStickyBucketService();
        sticky.save("alice", "ab-flag",
                new StickyAssignment("var_trt", "rule_ab", RuleType.AB_TEST, 1L));
        AvsbServer server = builder()
                .bootstrapDatafile(TestUtils.parseDatafile(DATAFILE))
                .stickyBucketService(sticky)
                .build();

        Flag<String> flag = server.getStringFlag("ab-flag", "fallback", EvalContext.user("alice"));
        assertEquals(EvaluationSource.STICKY, flag.source());
        assertEquals("new", flag.value());
        assertEquals("treatment", flag.variationKey());
        assertEquals("rule_ab", flag.ruleId());
        assertEquals(RuleType.AB_TEST, flag.ruleType());
    }

    @Test
    @DisplayName("a stored assignment for a deleted variation is a miss, not an error")
    void stickyUnknownVariationFallsThrough() {
        InMemoryStickyBucketService sticky = new InMemoryStickyBucketService();
        sticky.save("alice", "ab-flag",
                new StickyAssignment("var_deleted", "rule_ab", RuleType.AB_TEST, 1L));
        AvsbServer server = builder()
                .bootstrapDatafile(TestUtils.parseDatafile(DATAFILE))
                .stickyBucketService(sticky)
                .build();

        Flag<String> flag = server.getStringFlag("ab-flag", "fallback", EvalContext.user("alice"));
        assertEquals(EvaluationSource.RULE, flag.source(),
                "serving a deleted variation is impossible, so the evaluator answers instead");
    }

    @Test
    @DisplayName("a targeted_delivery rollout is never persisted as an assignment")
    void stickyDoesNotSaveOnRollout() {
        InMemoryStickyBucketService sticky = new InMemoryStickyBucketService();
        AvsbServer server = builder()
                .bootstrapDatafile(TestUtils.parseDatafile(DATAFILE))
                .stickyBucketService(sticky)
                .build();

        Flag<Boolean> flag = server.getBoolFlag("rollout-flag", false, EvalContext.user("alice"));
        assertEquals(RuleType.TARGETED_DELIVERY, flag.ruleType());
        assertNull(sticky.lookup("alice", "rollout-flag"),
                "a rollout is not an assignment, so nothing is stored");
    }

    @Test
    @DisplayName("a default decision is never persisted as an assignment")
    void stickyDoesNotSaveOnDefault() {
        InMemoryStickyBucketService sticky = new InMemoryStickyBucketService();
        AvsbServer server = builder()
                .bootstrapDatafile(TestUtils.parseDatafile(DATAFILE))
                .stickyBucketService(sticky)
                .build();

        server.getStringFlag("string-flag", "fallback", EvalContext.user("alice"));
        assertNull(sticky.lookup("alice", "string-flag"));
    }

    // -------------------------------------------------------------------------
    // Decision log and tracking
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("every evaluation is recorded in the bounded decision log")
    void decisionLogRecords() {
        AvsbServer server = loaded();
        server.getBoolFlag("bool-flag", false, EvalContext.user("alice"));
        server.getStringFlag("string-flag", "fallback", EvalContext.user("alice"));

        List<DecisionLogEntry> entries = server.decisionLog().entries();
        assertEquals(2, entries.size(), "the decision log used to exist and never be written to");
        assertEquals("bool-flag", entries.get(0).flagKey());
        assertEquals(EvaluationSource.RULE, entries.get(0).source());
        assertEquals("string-flag", entries.get(1).flagKey());
    }

    @Test
    @DisplayName("decision-log recording can be turned off entirely")
    void decisionLogCanBeDisabled() {
        AvsbServer server = builder()
                .bootstrapDatafile(TestUtils.parseDatafile(DATAFILE))
                .decisionLogCapacity(0)
                .build();
        server.getBoolFlag("bool-flag", false, EvalContext.user("alice"));
        assertTrue(server.decisionLog().entries().isEmpty());
    }

    @Test
    @DisplayName("track says so when event sending is off instead of dropping silently")
    void trackWithoutTrackerIsLoud() {
        List<String> warnings = new java.util.ArrayList<>();
        AvsbServer server = AvsbServer.builder(SDK_KEY)
                .logger(new CollectingLogger(warnings))
                .polling(false)
                .tracking(false)
                .bootstrapDatafile(TestUtils.parseDatafile(DATAFILE))
                .build();

        server.track("signup_completed", EvalContext.user("alice"));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("signup_completed")),
                "a dropped event must name itself: " + warnings);
    }

    @Test
    @DisplayName("an empty bucketing key warns once per client, naming the call to add")
    void emptyContextWarnsOnce() {
        List<String> warnings = new java.util.ArrayList<>();
        AvsbServer server = AvsbServer.builder(SDK_KEY)
                .logger(new CollectingLogger(warnings))
                .polling(false)
                .tracking(false)
                .bootstrapDatafile(TestUtils.parseDatafile(DATAFILE))
                .build();

        server.getBoolFlag("bool-flag", false, EvalContext.user(""));
        server.getBoolFlag("bool-flag", false, EvalContext.user(""));
        server.getBoolFlag("bool-flag", false, EvalContext.user(""));

        long emptyContextWarnings = warnings.stream()
                .filter(w -> w.contains("empty bucketing key"))
                .count();
        assertEquals(1, emptyContextWarnings, "once per process, not once per evaluation");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("EvalContext.user(")),
                "the warning names the exact factory call to add");
    }

    @Test
    @DisplayName("a key that is not shaped like an SDK key is called out at construction")
    void badKeyShapeIsLoud() {
        List<String> messages = new java.util.ArrayList<>();
        AvsbServer.builder("https://app.avsb.cloud/projects/p_1/settings")
                .logger(new CollectingLogger(messages))
                .polling(false)
                .tracking(false)
                .build();

        assertTrue(messages.stream().anyMatch(m -> m.contains("does not look like an SDK key")),
                "construction must say so: " + messages);
        assertTrue(messages.stream().anyMatch(m -> m.contains("URL")),
                "and name what it looks like instead");
    }

    @Test
    @DisplayName("a blank SDK key refuses construction with an actionable message")
    void blankKeyThrows() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AvsbServer.builder("  "));
        assertTrue(error.getMessage().contains("Environments"),
                "the message says where to find the key: " + error.getMessage());
    }

    @Test
    @DisplayName("getAllFlags returns every flag's value for server-rendered bootstrapping")
    void getAllFlags() {
        AvsbServer server = loaded();
        Map<String, Object> all = server.getAllFlags(EvalContext.user("alice"));
        assertEquals(6, all.size());
        assertEquals(Boolean.TRUE, all.get("bool-flag"));
        assertEquals("hello", all.get("string-flag"));
    }

    @Test
    @DisplayName("the datafile URL comes from the endpoint contract, not string surgery")
    void datafileUrlShape() {
        // Asserted through Endpoints because the URL is not otherwise observable; the point
        // is that /datafiles/{key}.json, the old shape, 404s.
        assertEquals("https://cdn.avsb.cloud/" + SDK_KEY + "/datafile.json",
                Endpoints.datafileUrl(SDK_KEY));
    }

    @Test
    @DisplayName("loadDatafile accepts a parsed document for bootstrap and tests")
    void loadDatafileFromDocument() {
        Datafile.FlagDatafileDoc doc = TestUtils.parseDatafile(DATAFILE);
        AvsbServer server = builder().build();
        assertFalse(server.isReady());
        server.loadDatafile(doc);
        assertTrue(server.isReady());
        assertEquals(2, server.datafile().version());
    }

    /** Collects every message the SDK logs, so loud-failure contracts can be asserted. */
    private static final class CollectingLogger implements Logger {
        private final List<String> messages;

        CollectingLogger(List<String> messages) {
            this.messages = messages;
        }

        @Override public void debug(String m, Map<String, Object> d) { messages.add(m); }
        @Override public void info(String m, Map<String, Object> d) { messages.add(m); }
        @Override public void warn(String m, Map<String, Object> d) { messages.add(m); }
        @Override public void error(String m, Map<String, Object> d) { messages.add(m); }
    }
}
