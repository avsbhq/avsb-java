package com.avsbhq.avsb;

import dev.openfeature.sdk.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenFeatureProvider — provider contract")
class OpenFeatureProviderTest {

    private static final String DATAFILE = """
            {
              "version": 2,
              "projectType": "FEATURE_FLAG",
              "sdkKey": "test-key",
              "environmentKey": "test",
              "publishedAt": "2026-05-29T00:00:00.000Z",
              "collectEndpoint": "https://collect.avsb.cloud/v1/events",
              "flags": [
                {
                  "id": "f1",
                  "key": "bool-flag",
                  "type": "boolean",
                  "enabled": true,
                  "defaultVariationId": "var_on",
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
                }
              ],
              "audiences": []
            }
            """;

    private OpenFeatureProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        AvsbServer server = AvsbServer.builder("test-key").build();
        server.loadDatafile(DATAFILE);
        provider = new OpenFeatureProvider(server);
    }

    @Test
    @DisplayName("metadata returns name 'avsb'")
    void metadataName() {
        assertEquals("avsb", provider.getMetadata().getName());
    }

    @Test
    @DisplayName("getBooleanEvaluation returns true for enabled flag")
    void booleanTrue() {
        EvaluationContext ctx = new ImmutableContext("alice");
        ProviderEvaluation<Boolean> eval = provider.getBooleanEvaluation("bool-flag", false, ctx);
        assertTrue(eval.getValue());
    }

    @Test
    @DisplayName("getBooleanEvaluation returns default for missing flag")
    void booleanDefault() {
        EvaluationContext ctx = new ImmutableContext("alice");
        ProviderEvaluation<Boolean> eval = provider.getBooleanEvaluation("no-such-flag", true, ctx);
        assertTrue(eval.getValue());
    }

    @Test
    @DisplayName("getStringEvaluation returns variation value")
    void stringValue() {
        EvaluationContext ctx = new ImmutableContext("alice");
        ProviderEvaluation<String> eval = provider.getStringEvaluation("string-flag", "default", ctx);
        assertEquals("hello", eval.getValue());
    }

    @Test
    @DisplayName("getDoubleEvaluation returns default for missing flag")
    void doubleDefault() {
        EvaluationContext ctx = new ImmutableContext("alice");
        ProviderEvaluation<Double> eval = provider.getDoubleEvaluation("missing", 3.14, ctx);
        assertEquals(3.14, eval.getValue());
    }

    @Test
    @DisplayName("constructor throws on null server")
    void nullServerThrows() {
        assertThrows(IllegalArgumentException.class, () -> new OpenFeatureProvider(null));
    }
}
