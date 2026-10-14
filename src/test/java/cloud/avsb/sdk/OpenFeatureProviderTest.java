package cloud.avsb.sdk;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.MutableStructure;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenFeatureProvider — provider contract and context mapping")
class OpenFeatureProviderTest {

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
                    {"id": "var_on",  "key": "on",  "value": true},
                    {"id": "var_off", "key": "off", "value": false}
                  ],
                  "overrides": [],
                  "rules": [
                    {
                      "id": "r1", "type": "targeted_delivery", "enabled": true,
                      "audienceIds": [], "hashAttribute": "user.key",
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
                  "defaultVariationId": "va",
                  "variations": [{"id": "va", "key": "greeting", "value": "hello"}],
                  "overrides": [],
                  "rules": []
                },
                {
                  "id": "f3",
                  "key": "json-flag",
                  "type": "json",
                  "enabled": true,
                  "defaultVariationId": "vj",
                  "variations": [{"id": "vj", "key": "doc", "value": {"mode": "on", "retries": 3}}],
                  "overrides": [],
                  "rules": []
                },
                {
                  "id": "f4",
                  "key": "enterprise-only",
                  "type": "boolean",
                  "enabled": true,
                  "defaultVariationId": "var_off",
                  "variations": [
                    {"id": "var_on",  "key": "on",  "value": true},
                    {"id": "var_off", "key": "off", "value": false}
                  ],
                  "overrides": [],
                  "rules": [
                    {
                      "id": "r_org", "type": "targeted_delivery", "enabled": true,
                      "audienceIds": ["aud_enterprise"], "hashAttribute": "organization.key",
                      "trafficAllocation": null,
                      "variations": [{"variationId": "var_on", "percentage": 1}]
                    }
                  ]
                }
              ],
              "audiences": [
                {
                  "id": "aud_enterprise",
                  "rules": {
                    "id": "g1",
                    "logic": "AND",
                    "conditions": [
                      {
                        "id": "c1", "type": "CUSTOM_ATTRIBUTE", "contextKind": "organization",
                        "attribute": "tier", "operator": "equals", "value": "enterprise"
                      }
                    ]
                  }
                }
              ]
            }
            """;

    private AvsbServer server;
    private OpenFeatureProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = AvsbServer.builder(SDK_KEY)
                .logger(Logger.noOp())
                .polling(false)
                .tracking(false)
                .build();
        server.loadDatafile(DATAFILE);
        provider = new OpenFeatureProvider(server);
    }

    @Test
    @DisplayName("metadata returns the provider name")
    void metadataName() {
        assertEquals("avsb", provider.getMetadata().getName());
    }

    @Test
    @DisplayName("a served boolean carries the value, the variant, and a targeting reason")
    void booleanServed() {
        EvaluationContext ctx = new ImmutableContext("alice");
        ProviderEvaluation<Boolean> eval = provider.getBooleanEvaluation("bool-flag", false, ctx);
        assertTrue(eval.getValue());
        assertEquals("on", eval.getVariant());
        assertEquals(Reason.TARGETING_MATCH.toString(), eval.getReason());
        assertNull(eval.getErrorCode());
    }

    @Test
    @DisplayName("a missing flag returns the default with FLAG_NOT_FOUND")
    void missingFlagErrorCode() {
        EvaluationContext ctx = new ImmutableContext("alice");
        ProviderEvaluation<Boolean> eval = provider.getBooleanEvaluation("no-such-flag", true, ctx);
        assertTrue(eval.getValue());
        assertEquals(ErrorCode.FLAG_NOT_FOUND, eval.getErrorCode());
        assertNotNull(eval.getErrorMessage());
    }

    @Test
    @DisplayName("an SDK with no datafile returns PROVIDER_NOT_READY, not FLAG_NOT_FOUND")
    void notReadyErrorCode() {
        AvsbServer empty = AvsbServer.builder(SDK_KEY)
                .logger(Logger.noOp())
                .polling(false)
                .tracking(false)
                .build();
        OpenFeatureProvider notReady = new OpenFeatureProvider(empty);

        ProviderEvaluation<Boolean> eval =
                notReady.getBooleanEvaluation("bool-flag", false, new ImmutableContext("alice"));
        assertEquals(ErrorCode.PROVIDER_NOT_READY, eval.getErrorCode(),
                "an OpenFeature caller must be able to tell 'I never started' from 'no such flag'");
        assertFalse(eval.getValue());
    }

    @Test
    @DisplayName("a string flag returns its variation value")
    void stringValue() {
        ProviderEvaluation<String> eval = provider.getStringEvaluation(
                "string-flag", "default", new ImmutableContext("alice"));
        assertEquals("hello", eval.getValue());
    }

    @Test
    @DisplayName("a missing double flag returns the caller default")
    void doubleDefault() {
        ProviderEvaluation<Double> eval = provider.getDoubleEvaluation(
                "missing", 3.14, new ImmutableContext("alice"));
        assertEquals(3.14, eval.getValue());
    }

    @Test
    @DisplayName("a JSON flag becomes an OpenFeature structure")
    void objectEvaluation() {
        ProviderEvaluation<Value> eval = provider.getObjectEvaluation(
                "json-flag", new Value(), new ImmutableContext("alice"));
        assertNotNull(eval.getValue());
        assertTrue(eval.getValue().isStructure());
        assertEquals("on", eval.getValue().asStructure().asMap().get("mode").asString());
    }

    // -------------------------------------------------------------------------
    // Context mapping
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a multi-kind context reaches organization-keyed targeting")
    void multiKindContextIsMapped() {
        // Collapsing this to a user context, which the provider used to do, silently lost
        // organization-keyed bucketing: the flag below could never match.
        EvaluationContext ctx = new MutableContext("alice")
                .add("kind", "multi")
                .add("user", new MutableStructure().add("key", "alice"))
                .add("organization", new MutableStructure()
                        .add("key", "org_42")
                        .add("tier", "enterprise"));

        ProviderEvaluation<Boolean> eval =
                provider.getBooleanEvaluation("enterprise-only", false, ctx);
        assertTrue(eval.getValue(), "the organization audience must be evaluable");
        assertEquals("on", eval.getVariant());
    }

    @Test
    @DisplayName("a multi-kind context that misses the audience serves the default")
    void multiKindContextCanMiss() {
        EvaluationContext ctx = new MutableContext("alice")
                .add("kind", "multi")
                .add("user", new MutableStructure().add("key", "alice"))
                .add("organization", new MutableStructure()
                        .add("key", "org_7")
                        .add("tier", "startup"));

        ProviderEvaluation<Boolean> eval =
                provider.getBooleanEvaluation("enterprise-only", false, ctx);
        assertFalse(eval.getValue(), "so the matching case above is not vacuous");
        assertEquals(Reason.DEFAULT.toString(), eval.getReason());
    }

    @Test
    @DisplayName("a single-kind context declares its kind and still buckets")
    void singleKindContext() {
        EvalContext mapped = OpenFeatureProvider.toAvsbContext(
                new MutableContext("org_42").add("kind", "organization").add("tier", "enterprise"));
        assertEquals("organization", mapped.kind());
        assertInstanceOf(EvalContext.SingleContext.class, mapped);
        assertEquals("org_42", ((EvalContext.SingleContext) mapped).key());
        assertEquals("enterprise", ((EvalContext.SingleContext) mapped).getAttribute("tier"));
    }

    @Test
    @DisplayName("no kind at all still produces a user context, as every caller expects")
    void defaultKindIsUser() {
        EvalContext mapped = OpenFeatureProvider.toAvsbContext(new ImmutableContext("alice"));
        assertEquals("user", mapped.kind());
        assertEquals("alice", ((EvalContext.SingleContext) mapped).key());
    }

    @Test
    @DisplayName("a null OpenFeature context does not throw")
    void nullContext() {
        EvalContext mapped = OpenFeatureProvider.toAvsbContext(null);
        assertEquals("user", mapped.kind());
        assertEquals("", ((EvalContext.SingleContext) mapped).key());
    }

    @Test
    @DisplayName("constructor refuses a null server")
    void nullServerThrows() {
        assertThrows(IllegalArgumentException.class, () -> new OpenFeatureProvider(null));
    }
}
