package com.avsbhq.avsb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Key-shape validation (spec section 1.2 client-layer contract).
 *
 * <p>A bad key used to be completely invisible: the CDN 404s, the fetch returns nothing, and
 * every flag serves its default. Each case below is one of the four bad values people
 * actually paste.
 */
@DisplayName("SdkKeyShape — construction-time validation")
class SdkKeyShapeTest {

    @Test
    @DisplayName("a real key validates, including an environment name with an underscore")
    void realKeysValidate() {
        assertTrue(SdkKeyShape.validate("sdk_production_ttqm0eaj4vth1krcb2xn").valid());
        assertTrue(SdkKeyShape.validate("sdk_development_local1abcdefgh").valid());
        assertTrue(SdkKeyShape.validate("sdk_pre_prod_ttqm0eaj4vth1krcb2xn").valid(),
                "the environment segment may itself contain underscores");
        assertNull(SdkKeyShape.validate("sdk_production_ttqm0eaj4vth1krcb2xn").message());
    }

    @Test
    @DisplayName("a pasted dashboard URL is recognised as a URL")
    void pastedUrl() {
        SdkKeyShape.Result result =
                SdkKeyShape.validate("https://app.avsb.cloud/projects/p_1/settings");
        assertFalse(result.valid());
        assertTrue(result.message().contains("URL"), result.message());
    }

    @Test
    @DisplayName("an API token is recognised as an API token")
    void pastedToken() {
        SdkKeyShape.Result result = SdkKeyShape.validate("avsb_pat_abcdefghijklmnop");
        assertFalse(result.valid());
        assertTrue(result.message().contains("API token"), result.message());
    }

    @Test
    @DisplayName("a key prefix that has never existed says so")
    void inventedPrefix() {
        for (String key : new String[]{"sdk-abc12345", "pub_abc12345", "srv_abc12345"}) {
            SdkKeyShape.Result result = SdkKeyShape.validate(key);
            assertFalse(result.valid(), key);
            assertTrue(result.message().contains("never existed"), result.message());
        }
    }

    @Test
    @DisplayName("a copy-paste that picked up whitespace is named as whitespace")
    void whitespace() {
        SdkKeyShape.Result result = SdkKeyShape.validate(" sdk_production_ttqm0eaj4vth1krcb2xn ");
        assertFalse(result.valid());
        assertTrue(result.message().contains("whitespace"), result.message());
    }

    @Test
    @DisplayName("a truncated key is named as truncated")
    void truncated() {
        assertTrue(SdkKeyShape.validate("sdk_production_abc").message().contains("truncated"));
        assertTrue(SdkKeyShape.validate("sdk_production").message().contains("truncated"));
    }

    @Test
    @DisplayName("an empty or null key does not throw")
    void emptyAndNull() {
        assertFalse(SdkKeyShape.validate("").valid());
        assertFalse(SdkKeyShape.validate(null).valid());
        assertTrue(SdkKeyShape.validate(null).message().contains("no SDK key was passed"));
    }

    @Test
    @DisplayName("the message redacts the id tail so it can be pasted into a bug report")
    void describeRedacts() {
        assertEquals("(empty)", SdkKeyShape.describe(""));
        assertEquals("sdk_short", SdkKeyShape.describe("sdk_short"));
        // The environment name is the useful half; the generated id is not.
        String described = SdkKeyShape.describe("sdk_production_ttqm0eaj4vth1krcb2xn");
        assertTrue(described.startsWith("sdk_producti"), described);
        assertTrue(described.contains("..."), described);
        assertFalse(described.contains("ttqm0eaj4vth1krc"), "the id body is not reproduced");
    }

    @Test
    @DisplayName("the message names the shape and where the real key lives")
    void messageIsActionable() {
        String message = SdkKeyShape.validate("nonsense").message();
        assertTrue(message.contains("sdk_<environment>_<id>"), message);
        assertTrue(message.contains("Environments"), message);
        assertTrue(message.contains("404"),
                "and connects the symptom the user will see next: " + message);
    }
}
