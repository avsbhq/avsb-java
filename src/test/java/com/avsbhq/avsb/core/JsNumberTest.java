package com.avsbhq.avsb.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JavaScript coercion parity. Every expectation here is what {@code String(x)},
 * {@code Number(x)}, or {@code Boolean(x)} produces in the reference engine, because the
 * conformance corpus records reason strings that were built by JavaScript interpolation and
 * buckets that were hashed from JavaScript strings.
 */
@DisplayName("JsNumber — JavaScript value coercion")
class JsNumberTest {

    @Test
    @DisplayName("an integral double prints without a decimal point")
    void integralDoublesHaveNoTrailingZero() {
        // String.valueOf(1000.0) is "1000.0" in Java, which fails every allocation reason.
        assertEquals("1000", JsNumber.stringify(1000.0));
        assertEquals("3330", JsNumber.stringify(3330.0));
        assertEquals("0", JsNumber.stringify(0.0));
        assertEquals("0", JsNumber.stringify(-0.0));
        assertEquals("-42", JsNumber.stringify(-42.0));
    }

    @Test
    @DisplayName("a fractional threshold keeps exactly the digits the reference prints")
    void fractionalThresholds() {
        assertEquals("1234.5", JsNumber.stringify(0.12345 * 10_000));
        assertEquals("0.25", JsNumber.stringify(0.25));
        assertEquals("-2.5", JsNumber.stringify(-2.5));
        assertEquals("6666", JsNumber.stringify(0.3333 * 10_000 + 0.3333 * 10_000));
    }

    @Test
    @DisplayName("exponential form switches at the same boundaries as JavaScript")
    void exponentialBoundaries() {
        assertEquals("1e+21", JsNumber.stringify(1e21));
        assertEquals("100000000000000000000", JsNumber.stringify(1e20));
        assertEquals("0.000001", JsNumber.stringify(1e-6));
        assertEquals("1e-7", JsNumber.stringify(1e-7));
    }

    @Test
    @DisplayName("non-finite values print as JavaScript names")
    void nonFinite() {
        assertEquals("NaN", JsNumber.stringify(Double.NaN));
        assertEquals("Infinity", JsNumber.stringify(Double.POSITIVE_INFINITY));
        assertEquals("-Infinity", JsNumber.stringify(Double.NEGATIVE_INFINITY));
    }

    @Test
    @DisplayName("stringOf mirrors String(value) for every JSON shape")
    void stringOfShapes() {
        assertEquals("undefined", JsNumber.stringOf(null));
        assertEquals("true", JsNumber.stringOf(Boolean.TRUE));
        assertEquals("42", JsNumber.stringOf(42));
        assertEquals("42", JsNumber.stringOf(42L));
        assertEquals("42", JsNumber.stringOf(42.0d));
        assertEquals("42.5", JsNumber.stringOf(42.5d));
        assertEquals("hello", JsNumber.stringOf("hello"));
        // Array.prototype.join with holes rendered empty, exactly as the reference does it.
        List<Object> mixed = new ArrayList<>();
        mixed.add(1);
        mixed.add(null);
        mixed.add("two");
        assertEquals("1,,two", JsNumber.stringOf(mixed));
        assertEquals("[object Object]", JsNumber.stringOf(new LinkedHashMap<>()));
    }

    @Test
    @DisplayName("numberOf mirrors Number(value), including its odd cases")
    void numberOfShapes() {
        assertEquals(0.0, JsNumber.numberOf(""), "Number(\"\") is 0 in JavaScript");
        assertEquals(5.0, JsNumber.numberOf("5"));
        assertEquals(5.5, JsNumber.numberOf(" 5.5 "), "leading and trailing space is trimmed");
        assertEquals(1000.0, JsNumber.numberOf("1e3"));
        assertEquals(1.0, JsNumber.numberOf(Boolean.TRUE));
        assertTrue(Double.isNaN(JsNumber.numberOf("abc")));
        assertTrue(Double.isNaN(JsNumber.numberOf(null)));
        assertTrue(Double.isNaN(JsNumber.numberOf(Map.of())));
        // Java's parseDouble accepts these; JavaScript does not, and accepting them would
        // make a targeting comparison match on one platform and miss on another.
        assertTrue(Double.isNaN(JsNumber.numberOf("5d")));
        assertTrue(Double.isNaN(JsNumber.numberOf("5f")));
    }

    @Test
    @DisplayName("truthiness is the JavaScript table, not Java's or Python's")
    void truthinessTable() {
        assertFalse(JsNumber.isTruthy(null));
        assertFalse(JsNumber.isTruthy(false));
        assertFalse(JsNumber.isTruthy(0));
        assertFalse(JsNumber.isTruthy(0.0));
        assertFalse(JsNumber.isTruthy(-0.0));
        assertFalse(JsNumber.isTruthy(Double.NaN));
        assertFalse(JsNumber.isTruthy(""));

        assertTrue(JsNumber.isTruthy(true));
        assertTrue(JsNumber.isTruthy(1));
        assertTrue(JsNumber.isTruthy(-1));
        assertTrue(JsNumber.isTruthy("off"));
        assertTrue(JsNumber.isTruthy("false"), "the STRING false is truthy; do not parse it");
        assertTrue(JsNumber.isTruthy("0"), "the STRING zero is truthy; PHP would disagree");
        assertTrue(JsNumber.isTruthy(List.of()), "an empty array is truthy; Python would disagree");
        assertTrue(JsNumber.isTruthy(Map.of()), "an empty object is truthy");
    }
}
