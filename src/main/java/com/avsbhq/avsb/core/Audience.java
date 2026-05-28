package com.avsbhq.avsb.core;

import com.avsbhq.avsb.EvalContext;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Server-side audience evaluation engine. Mirrors {@code evaluateAudience},
 * {@code evaluateGroup}, and {@code evaluateCondition} in
 * {@code @avsbhq/core/src/audience.ts}.
 *
 * <p>Browser-only condition types ({@code DEVICE}, {@code BROWSER},
 * {@code PLATFORM}, {@code LANGUAGE}, {@code COOKIE}, {@code QUERY_PARAM})
 * are not evaluated on the server — they always return {@code false}.
 */
public final class Audience {

    private Audience() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Public entry points
    // -------------------------------------------------------------------------

    public static boolean evaluateAudience(Datafile.AudienceEntry audience, EvalContext ctx) {
        if (audience == null || audience.rules() == null) return true;
        return evaluateGroup(audience.rules(), ctx);
    }

    public static boolean evaluateGroup(Datafile.ConditionGroupNode group, EvalContext ctx) {
        if (group == null) return true;
        List<Object> conditions = group.conditions();
        if (conditions == null || conditions.isEmpty()) return true;

        if ("AND".equals(group.logic())) {
            for (Object item : conditions) {
                if (!evalItem(item, ctx)) return false;
            }
            return true;
        }
        // OR
        for (Object item : conditions) {
            if (evalItem(item, ctx)) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static boolean evalItem(Object item, EvalContext ctx) {
        if (item instanceof Datafile.ConditionGroupNode group) {
            return evaluateGroup(group, ctx);
        }
        if (item instanceof Datafile.ConditionNode condition) {
            return evaluateCondition(condition, ctx);
        }
        // Jackson deserialises nested objects as LinkedHashMap — convert
        if (item instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            if (map.containsKey("logic") && map.containsKey("conditions")) {
                Datafile.ConditionGroupNode node = MAPPER.convertValue(map, Datafile.ConditionGroupNode.class);
                return evaluateGroup(node, ctx);
            }
            Datafile.ConditionNode node = MAPPER.convertValue(map, Datafile.ConditionNode.class);
            return evaluateCondition(node, ctx);
        }
        return false;
    }

    public static boolean evaluateCondition(Datafile.ConditionNode condition, EvalContext ctx) {
        return switch (condition.type()) {
            case "CUSTOM_ATTRIBUTE" -> evalCustomAttribute(condition, ctx);
            // Browser-only types — server returns false
            case "DEVICE", "BROWSER", "PLATFORM", "LANGUAGE", "COOKIE", "QUERY_PARAM" -> false;
            default -> false;
        };
    }

    // -------------------------------------------------------------------------
    // Custom-attribute evaluation
    // -------------------------------------------------------------------------

    private static boolean evalCustomAttribute(Datafile.ConditionNode condition, EvalContext ctx) {
        String attrKey = condition.attribute();
        if (attrKey == null || attrKey.isEmpty()) return false;

        // When contextKind is declared, the sub-context must be present.
        String contextKind = condition.contextKind();
        if (contextKind != null && !contextKind.isEmpty()) {
            EvalContext.SingleContext sub = Context.getContextByKind(ctx, contextKind);
            if (sub == null) return false;
        }

        Object actual = resolveAttribute(ctx, contextKind, attrKey);
        Object rawExpected = condition.value();
        String expected = toExpectedSingle(rawExpected);

        return switch (condition.operator()) {
            case "exists"       -> actual != null;
            case "not_exists"   -> actual == null;
            case "equals"       -> actual != null && str(actual).equals(expected);
            case "not_equals"   -> actual == null || !str(actual).equals(expected);
            case "contains"     -> actual != null && str(actual).toLowerCase().contains(expected.toLowerCase());
            case "not_contains" -> actual == null || !str(actual).toLowerCase().contains(expected.toLowerCase());
            case "starts_with"  -> actual != null && str(actual).toLowerCase().startsWith(expected.toLowerCase());
            case "ends_with"    -> actual != null && str(actual).toLowerCase().endsWith(expected.toLowerCase());
            case "regex"        -> {
                if (actual == null) yield false;
                try {
                    yield Pattern.compile(expected, Pattern.CASE_INSENSITIVE).matcher(str(actual)).find();
                } catch (PatternSyntaxException e) {
                    yield false;
                }
            }
            case "greater_than" -> actual != null && toDouble(actual) > toDouble(expected);
            case "less_than"    -> actual != null && toDouble(actual) < toDouble(expected);
            case "gte"          -> actual != null && toDouble(actual) >= toDouble(expected);
            case "lte"          -> actual != null && toDouble(actual) <= toDouble(expected);
            case "in_list"      -> {
                if (actual == null) yield false;
                List<String> list = toList(rawExpected);
                yield list.stream().anyMatch(v -> str(actual).equals(v));
            }
            case "not_in_list"  -> {
                if (actual == null) yield true;
                List<String> list = toList(rawExpected);
                yield list.stream().noneMatch(v -> str(actual).equals(v));
            }
            default -> false;
        };
    }

    // -------------------------------------------------------------------------
    // Attribute resolution helpers
    // -------------------------------------------------------------------------

    private static Object resolveAttribute(EvalContext ctx, String contextKind, String attribute) {
        if (contextKind != null && !contextKind.isEmpty()) {
            EvalContext.SingleContext sub = Context.getContextByKind(ctx, contextKind);
            if (sub == null) return null;
            return Context.readSubContextAttribute(sub, attribute);
        }
        // No contextKind — legacy single-context lookup
        EvalContext.SingleContext primary = switch (ctx) {
            case EvalContext.SingleContext s -> s;
            case EvalContext.MultiContext m  -> m.get("user");
        };
        if (primary == null) return null;
        if ("userId".equals(attribute)) return primary.key();
        return Context.readSubContextAttribute(primary, attribute);
    }

    // -------------------------------------------------------------------------
    // Value coercion helpers
    // -------------------------------------------------------------------------

    private static String str(Object v) { return v == null ? "" : String.valueOf(v); }

    private static double toDouble(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (NumberFormatException e) { return 0; }
    }

    private static String toExpectedSingle(Object raw) {
        if (raw == null) return "";
        if (raw instanceof List<?> list) return list.isEmpty() ? "" : str(list.get(0));
        return str(raw);
    }

    @SuppressWarnings("unchecked")
    private static List<String> toList(Object raw) {
        if (raw instanceof List<?> list) return (List<String>) list;
        if (raw instanceof String s) return List.of(s);
        return List.of();
    }
}
