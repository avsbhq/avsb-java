package com.avsbhq.avsb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AvsbServer — facade tests")
class AvsbServerTest {

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
                  "defaultVariationId": "var_off",
                  "variations": [
                    {"id": "var_off", "key": "off",  "value": false},
                    {"id": "var_on",  "key": "on",   "value": true}
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
                }
              ],
              "audiences": []
            }
            """;

    private AvsbServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = AvsbServer.builder("test-key").build();
        server.loadDatafile(DATAFILE);
    }

    @Test
    @DisplayName("getBoolFlag returns correct value for known flag")
    void getBoolFlagFound() {
        Flag<Boolean> flag = server.getBoolFlag("bool-flag", false, EvalContext.user("alice"));
        assertTrue(flag.value());
        assertTrue(flag.exists());
    }

    @Test
    @DisplayName("getBoolFlag returns defaultValue for unknown flag")
    void getBoolFlagNotFound() {
        Flag<Boolean> flag = server.getBoolFlag("no-such-flag", true, EvalContext.user("alice"));
        assertTrue(flag.value(), "Should return the caller default");
        assertEquals(EvaluationSource.NOT_FOUND, flag.source());
    }

    @Test
    @DisplayName("getStringFlag returns string variation value")
    void getStringFlag() {
        Flag<String> flag = server.getStringFlag("string-flag", "default", EvalContext.user("alice"));
        assertEquals("hello", flag.value());
    }

    @Test
    @DisplayName("getNumberFlag returns defaultValue for missing flag")
    void getNumberFlagMissing() {
        Flag<Double> flag = server.getNumberFlag("missing-num", 42.0, EvalContext.user("alice"));
        assertEquals(42.0, flag.value());
    }

    @Test
    @DisplayName("before init, evaluation returns NOT_FOUND with informative reason")
    void evaluateBeforeInit() {
        AvsbServer uninitialised = AvsbServer.builder("sdk-key").build();
        Flag<Boolean> flag = uninitialised.getBoolFlag("any-flag", false, EvalContext.user("alice"));
        assertEquals(EvaluationSource.NOT_FOUND, flag.source());
        assertFalse(flag.reasons().isEmpty());
    }

    @Test
    @DisplayName("track and identify do not throw")
    void trackIdentifyNoThrow() {
        assertDoesNotThrow(() -> {
            server.track("purchase", EvalContext.user("alice"), TrackPayload.of(99.0));
            server.identify(EvalContext.user("alice"));
        });
    }

    @Test
    @DisplayName("isEnabled() returns false for NOT_FOUND source")
    void isEnabledFalseForNotFound() {
        Flag<Boolean> flag = server.getBoolFlag("missing", false, EvalContext.user("alice"));
        assertFalse(flag.isEnabled());
    }
}
