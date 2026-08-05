package com.avsbhq.avsb.core;

import com.avsbhq.avsb.EvalContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Audience — evaluateCondition operators")
class AudienceTest {

    // Helper: build a ConditionNode with the given operator and value.
    private static Datafile.ConditionNode cond(String attribute, String operator, Object value) {
        return new Datafile.ConditionNode(
                "c1", "CUSTOM_ATTRIBUTE", attribute, operator, value, null, null, null);
    }

    private static Datafile.ConditionNode condKind(String contextKind, String attribute, String operator, Object value) {
        return new Datafile.ConditionNode(
                "c1", "CUSTOM_ATTRIBUTE", attribute, operator, value, contextKind, null, null);
    }

    private static EvalContext userCtx(String key, Map<String, Object> attrs) {
        return EvalContext.user(key, attrs);
    }

    @Test @DisplayName("equals operator matches string attribute") void equalsMatch() {
        EvalContext ctx = userCtx("alice", Map.of("plan", "pro"));
        assertTrue(Audience.evaluateCondition(cond("plan", "equals", "pro"), ctx));
    }

    @Test @DisplayName("equals operator rejects non-matching value") void equalsMiss() {
        EvalContext ctx = userCtx("alice", Map.of("plan", "free"));
        assertFalse(Audience.evaluateCondition(cond("plan", "equals", "pro"), ctx));
    }

    @Test @DisplayName("not_equals operator") void notEquals() {
        EvalContext ctx = userCtx("alice", Map.of("plan", "free"));
        assertTrue(Audience.evaluateCondition(cond("plan", "not_equals", "pro"), ctx));
    }

    @Test @DisplayName("contains operator (case-insensitive)") void contains() {
        EvalContext ctx = userCtx("alice", Map.of("email", "alice@example.com"));
        assertTrue(Audience.evaluateCondition(cond("email", "contains", "@EXAMPLE"), ctx));
    }

    @Test @DisplayName("starts_with operator") void startsWith() {
        EvalContext ctx = userCtx("alice", Map.of("plan", "pro-annual"));
        assertTrue(Audience.evaluateCondition(cond("plan", "starts_with", "pro"), ctx));
    }

    @Test @DisplayName("ends_with operator") void endsWith() {
        EvalContext ctx = userCtx("alice", Map.of("plan", "pro-annual"));
        assertTrue(Audience.evaluateCondition(cond("plan", "ends_with", "annual"), ctx));
    }

    @Test @DisplayName("greater_than operator") void greaterThan() {
        EvalContext ctx = userCtx("alice", Map.of("age", 30));
        assertTrue(Audience.evaluateCondition(cond("age", "greater_than", "25"), ctx));
        assertFalse(Audience.evaluateCondition(cond("age", "greater_than", "35"), ctx));
    }

    @Test @DisplayName("less_than operator") void lessThan() {
        EvalContext ctx = userCtx("alice", Map.of("age", 20));
        assertTrue(Audience.evaluateCondition(cond("age", "less_than", "25"), ctx));
    }

    @Test @DisplayName("gte / lte operators") void gteLte() {
        EvalContext ctx = userCtx("alice", Map.of("score", 100));
        assertTrue(Audience.evaluateCondition(cond("score", "gte", "100"), ctx));
        assertTrue(Audience.evaluateCondition(cond("score", "lte", "100"), ctx));
    }

    @Test @DisplayName("in_list operator matches when value is in list") void inList() {
        EvalContext ctx = userCtx("alice", Map.of("plan", "pro"));
        assertTrue(Audience.evaluateCondition(cond("plan", "in_list", List.of("free", "pro", "enterprise")), ctx));
        assertFalse(Audience.evaluateCondition(cond("plan", "in_list", List.of("free", "basic")), ctx));
    }

    @Test @DisplayName("not_in_list operator") void notInList() {
        EvalContext ctx = userCtx("alice", Map.of("plan", "free"));
        assertTrue(Audience.evaluateCondition(cond("plan", "not_in_list", List.of("pro", "enterprise")), ctx));
    }

    @Test @DisplayName("regex operator (case-insensitive)") void regex() {
        EvalContext ctx = userCtx("alice", Map.of("email", "Alice@Example.COM"));
        assertTrue(Audience.evaluateCondition(cond("email", "regex", "^alice@"), ctx));
    }

    @Test @DisplayName("exists / not_exists operators") void existsNotExists() {
        EvalContext ctxWith = userCtx("alice", Map.of("plan", "pro"));
        EvalContext ctxWithout = userCtx("alice", Map.of());
        assertTrue(Audience.evaluateCondition(cond("plan", "exists", ""), ctxWith));
        assertTrue(Audience.evaluateCondition(cond("plan", "not_exists", ""), ctxWithout));
        assertFalse(Audience.evaluateCondition(cond("plan", "exists", ""), ctxWithout));
    }

    @Test @DisplayName("contextKind resolves from multi-context sub-context") void contextKindMatch() {
        EvalContext.SingleContext org = EvalContext.of("organization", "acme", Map.of("tier", "enterprise"));
        EvalContext multi = EvalContext.multi(Map.of(
                "user", EvalContext.user("alice"),
                "organization", org));
        assertTrue(Audience.evaluateCondition(condKind("organization", "tier", "equals", "enterprise"), multi));
    }

    @Test @DisplayName("contextKind returns false when kind is absent from multi-context") void contextKindMissing() {
        EvalContext multi = EvalContext.multi(Map.of("user", EvalContext.user("alice")));
        assertFalse(Audience.evaluateCondition(condKind("organization", "tier", "equals", "enterprise"), multi));
    }

    @Test @DisplayName("AND group requires all conditions to match") void andGroup() {
        Datafile.ConditionGroupNode group = new Datafile.ConditionGroupNode("g1", "AND",
                List.of(cond("plan", "equals", "pro"), cond("age", "greater_than", "18")));
        EvalContext ctx = userCtx("alice", Map.of("plan", "pro", "age", 25));
        assertTrue(Audience.evaluateGroup(group, ctx));
        EvalContext ctxFail = userCtx("bob", Map.of("plan", "pro", "age", 15));
        assertFalse(Audience.evaluateGroup(group, ctxFail));
    }

    @Test @DisplayName("browser-only condition types return false on server") void browserConditionFalse() {
        EvalContext ctx = EvalContext.user("alice");
        Datafile.ConditionNode deviceCond = new Datafile.ConditionNode(
                "c1", "DEVICE", null, "equals", "mobile", null, null, null);
        assertFalse(Audience.evaluateCondition(deviceCond, ctx));
    }

    // -------------------------------------------------------------------------
    // Alias operators (spec section 1.4 operator matrix)
    //
    // The dashboard audience builder writes these spellings for the device-style
    // condition types, and they reach CUSTOM_ATTRIBUTE conditions too. Before they were
    // handled, an audience saved with `is` fell through to the default arm and matched
    // nobody, with nothing logged.
    // -------------------------------------------------------------------------

    @Test @DisplayName("is / is_not alias equals / not_equals") void aliasIsAndIsNot() {
        EvalContext pro = userCtx("alice", Map.of("plan", "pro"));
        assertTrue(Audience.evaluateCondition(cond("plan", "is", "pro"), pro));
        assertFalse(Audience.evaluateCondition(cond("plan", "is", "free"), pro));
        assertTrue(Audience.evaluateCondition(cond("plan", "is_not", "free"), pro));
        assertFalse(Audience.evaluateCondition(cond("plan", "is_not", "pro"), pro));
    }

    @Test @DisplayName("does_not_contain aliases not_contains") void aliasDoesNotContain() {
        EvalContext ctx = userCtx("alice", Map.of("email", "alice@example.com"));
        assertTrue(Audience.evaluateCondition(cond("email", "does_not_contain", "@other"), ctx));
        assertFalse(Audience.evaluateCondition(cond("email", "does_not_contain", "@EXAMPLE"), ctx));
    }

    @Test @DisplayName("does_not_exist aliases not_exists") void aliasDoesNotExist() {
        EvalContext without = userCtx("alice", Map.of());
        EvalContext with = userCtx("alice", Map.of("plan", "pro"));
        assertTrue(Audience.evaluateCondition(cond("plan", "does_not_exist", ""), without));
        assertFalse(Audience.evaluateCondition(cond("plan", "does_not_exist", ""), with));
    }

    @Test @DisplayName("an unknown operator matches nobody rather than everybody") void unknownOperator() {
        EvalContext ctx = userCtx("alice", Map.of("plan", "pro"));
        assertFalse(Audience.evaluateCondition(cond("plan", "sounds_like", "pro"), ctx));
    }

    // -------------------------------------------------------------------------
    // JavaScript coercion in comparisons
    // -------------------------------------------------------------------------

    @Test @DisplayName("a numeric attribute compares as JavaScript would stringify it") void numericEquals() {
        // Java's String.valueOf(42.0) is "42.0", which would miss a condition written as 42.
        assertTrue(Audience.evaluateCondition(
                cond("seats", "equals", "42"), userCtx("alice", Map.of("seats", 42))));
        assertTrue(Audience.evaluateCondition(
                cond("seats", "equals", "42"), userCtx("alice", Map.of("seats", 42.0d))));
        assertTrue(Audience.evaluateCondition(
                cond("ratio", "equals", "0.25"), userCtx("alice", Map.of("ratio", 0.25d))));
    }

    @Test @DisplayName("a numeric comparison against a missing attribute never matches") void missingNumeric() {
        EvalContext ctx = userCtx("alice", Map.of());
        assertFalse(Audience.evaluateCondition(cond("age", "greater_than", "18"), ctx));
        assertFalse(Audience.evaluateCondition(cond("age", "less_than", "18"), ctx));
        assertFalse(Audience.evaluateCondition(cond("age", "gte", "0"), ctx));
    }
}
