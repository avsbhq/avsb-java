package cloud.avsb.sdk.core;

import cloud.avsb.sdk.EvalContext;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Server-side audience evaluation. Mirrors {@code evaluateAudience},
 * {@code evaluateGroup}, and {@code evaluateCondition} in
 * {@code @avsbhq/core/src/audience.ts}.
 *
 * <p>Three families of condition reach this evaluator:
 *
 * <ul>
 *   <li>{@code CUSTOM_ATTRIBUTE}, answered from the evaluation context.</li>
 *   <li>The commerce families ({@code CART_VALUE}, {@code PRODUCTS_VIEWED},
 *       {@code PURCHASE_HISTORY}), delegated to {@link AudienceCommerce}.</li>
 *   <li>Browser-detected families ({@code DEVICE}, {@code BROWSER}, {@code PLATFORM},
 *       {@code LANGUAGE}, {@code COOKIE}, {@code QUERY_PARAM}), which a server cannot
 *       answer and which therefore return false.</li>
 * </ul>
 *
 * <p>Operator aliases matter more than they look. The dashboard audience builder writes
 * {@code is}, {@code is_not}, {@code does_not_contain}, and {@code does_not_exist} for the
 * device-style condition types, and those spellings reach CUSTOM_ATTRIBUTE conditions too.
 * Before they were handled here, an audience saved with {@code is} fell through to the
 * default arm and matched nobody, with nothing logged.
 *
 * <p>Comparisons follow JavaScript coercion through {@link JsNumber} so a numeric
 * attribute compares identically in every SDK. Case-insensitive comparisons use
 * {@link Locale#ROOT}: a JVM running under a Turkish locale would otherwise lowercase
 * {@code "I"} to a dotless i and stop matching.
 */
public final class Audience {

    private Audience() {
        // Static helpers only.
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Public entry points
    // -------------------------------------------------------------------------

    /** Evaluate an audience with the default currency exponent. */
    public static boolean evaluateAudience(Datafile.AudienceEntry audience, EvalContext ctx) {
        return evaluateAudience(audience, ctx, AudienceCommerce.DEFAULT_CURRENCY_EXPONENT);
    }

    /**
     * Evaluate an audience.
     *
     * @param currencyExponent the datafile's minor-unit exponent, used by commerce money
     *                         conditions. Resolve it with
     *                         {@link AudienceCommerce#currencyExponentOf(Integer)}.
     */
    public static boolean evaluateAudience(
            Datafile.AudienceEntry audience, EvalContext ctx, int currencyExponent) {
        if (audience == null || audience.rules() == null) {
            return true;
        }
        return evaluateGroup(audience.rules(), ctx, currencyExponent);
    }

    /** Evaluate a condition group with the default currency exponent. */
    public static boolean evaluateGroup(Datafile.ConditionGroupNode group, EvalContext ctx) {
        return evaluateGroup(group, ctx, AudienceCommerce.DEFAULT_CURRENCY_EXPONENT);
    }

    /** Evaluate a condition group. An empty group matches, mirroring the reference. */
    public static boolean evaluateGroup(
            Datafile.ConditionGroupNode group, EvalContext ctx, int currencyExponent) {
        if (group == null) {
            return true;
        }
        List<Object> conditions = group.conditions();
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }

        if ("AND".equals(group.logic())) {
            for (Object item : conditions) {
                if (!evalItem(item, ctx, currencyExponent)) {
                    return false;
                }
            }
            return true;
        }
        for (Object item : conditions) {
            if (evalItem(item, ctx, currencyExponent)) {
                return true;
            }
        }
        return false;
    }

    /** Evaluate one condition with the default currency exponent. */
    public static boolean evaluateCondition(Datafile.ConditionNode condition, EvalContext ctx) {
        return evaluateCondition(condition, ctx, AudienceCommerce.DEFAULT_CURRENCY_EXPONENT);
    }

    /** Evaluate one condition. */
    public static boolean evaluateCondition(
            Datafile.ConditionNode condition, EvalContext ctx, int currencyExponent) {
        String type = condition.type() == null ? "" : condition.type();
        return switch (type) {
            case "CUSTOM_ATTRIBUTE" -> evalCustomAttribute(condition, ctx);
            case "CART_VALUE", "PRODUCTS_VIEWED", "PURCHASE_HISTORY" ->
                    AudienceCommerce.evaluateCommerceCondition(
                            condition,
                            attribute -> resolveAttribute(ctx, condition.contextKind(), attribute),
                            currencyExponent);
            // Device, browser, platform, language, cookie, and query-param conditions are
            // answered from a live browser. A server has none of those signals, so they
            // are false here rather than guessed.
            default -> false;
        };
    }

    @SuppressWarnings("unchecked")
    private static boolean evalItem(Object item, EvalContext ctx, int currencyExponent) {
        if (item instanceof Datafile.ConditionGroupNode group) {
            return evaluateGroup(group, ctx, currencyExponent);
        }
        if (item instanceof Datafile.ConditionNode condition) {
            return evaluateCondition(condition, ctx, currencyExponent);
        }
        // Jackson deserialises the mixed conditions list as LinkedHashMap entries, so the
        // group-or-condition discrimination happens here rather than in a custom
        // deserialiser. A node carrying both "logic" and "conditions" is a nested group.
        if (item instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            if (map.containsKey("logic") && map.containsKey("conditions")) {
                Datafile.ConditionGroupNode node =
                        MAPPER.convertValue(map, Datafile.ConditionGroupNode.class);
                return evaluateGroup(node, ctx, currencyExponent);
            }
            Datafile.ConditionNode node = MAPPER.convertValue(map, Datafile.ConditionNode.class);
            return evaluateCondition(node, ctx, currencyExponent);
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Custom-attribute evaluation
    // -------------------------------------------------------------------------

    private static boolean evalCustomAttribute(Datafile.ConditionNode condition, EvalContext ctx) {
        String attrKey = condition.attribute();
        if (attrKey == null || attrKey.isEmpty()) {
            return false;
        }

        // A declared contextKind that is absent from the context is a hard no-match. That
        // is NOT the same as a missing attribute, which can still satisfy does_not_exist.
        String contextKind = condition.contextKind();
        if (contextKind != null && !contextKind.isEmpty()
                && Context.getContextByKind(ctx, contextKind) == null) {
            return false;
        }

        Object actual = resolveAttribute(ctx, contextKind, attrKey);
        Object rawExpected = condition.value();
        String expected = JsNumber.stringOf(toExpectedSingle(rawExpected));
        String actualText = JsNumber.stringOf(actual);
        String operator = condition.operator() == null ? "" : condition.operator();

        return switch (operator) {
            case "exists" -> actual != null;
            case "not_exists", "does_not_exist" -> actual == null;
            case "equals", "is" -> actualText.equals(expected);
            case "not_equals", "is_not" -> !actualText.equals(expected);
            case "contains" -> lower(actualText).contains(lower(expected));
            case "not_contains", "does_not_contain" -> !lower(actualText).contains(lower(expected));
            case "starts_with" -> lower(actualText).startsWith(lower(expected));
            case "ends_with" -> lower(actualText).endsWith(lower(expected));
            case "regex" -> matchesRegex(actualText, expected);
            case "greater_than" -> JsNumber.numberOf(actual) > JsNumber.numberOf(expected);
            case "less_than" -> JsNumber.numberOf(actual) < JsNumber.numberOf(expected);
            case "gte" -> JsNumber.numberOf(actual) >= JsNumber.numberOf(expected);
            case "lte" -> JsNumber.numberOf(actual) <= JsNumber.numberOf(expected);
            case "in_list" -> anyValueMatches(actualText, rawExpected);
            case "not_in_list" -> !anyValueMatches(actualText, rawExpected);
            default -> false;
        };
    }

    // -------------------------------------------------------------------------
    // Attribute resolution
    // -------------------------------------------------------------------------

    /**
     * Resolve a (contextKind, attribute) pair. With no declared kind, the lookup falls back
     * to the primary context, where {@code userId} still addresses its key for v1
     * datafiles.
     */
    static Object resolveAttribute(EvalContext ctx, String contextKind, String attribute) {
        if (contextKind != null && !contextKind.isEmpty()) {
            EvalContext.SingleContext sub = Context.getContextByKind(ctx, contextKind);
            if (sub == null) {
                return null;
            }
            return Context.readSubContextAttribute(sub, attribute);
        }
        // An instanceof chain rather than a pattern switch, which needs Java 21; the SDK floor is 17.
        java.util.Objects.requireNonNull(ctx, "ctx");
        EvalContext.SingleContext primary;
        if (ctx instanceof EvalContext.SingleContext s) {
            primary = s;
        } else if (ctx instanceof EvalContext.MultiContext m) {
            primary = m.get("user");
        } else {
            throw new IllegalArgumentException("Unsupported EvalContext type: " + ctx.getClass().getName());
        }
        if (primary == null) {
            return null;
        }
        if ("userId".equals(attribute)) {
            return primary.key();
        }
        return Context.readSubContextAttribute(primary, attribute);
    }

    // -------------------------------------------------------------------------
    // Value coercion helpers
    // -------------------------------------------------------------------------

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static boolean matchesRegex(String actual, String pattern) {
        try {
            return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(actual).find();
        } catch (PatternSyntaxException e) {
            // The reference swallows an invalid pattern and reports no match.
            return false;
        }
    }

    private static Object toExpectedSingle(Object raw) {
        if (raw instanceof List<?> list) {
            return list.isEmpty() ? null : list.get(0);
        }
        return raw;
    }

    private static boolean anyValueMatches(String actualText, Object rawExpected) {
        // singletonList, not List.of: a null condition value is legal on the wire and
        // List.of would throw on it.
        List<?> list = rawExpected instanceof List<?> l
                ? l
                : Collections.singletonList(rawExpected);
        for (Object candidate : list) {
            if (actualText.equals(JsNumber.stringOf(candidate))) {
                return true;
            }
        }
        return false;
    }
}
