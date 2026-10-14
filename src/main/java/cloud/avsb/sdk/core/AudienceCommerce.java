package cloud.avsb.sdk.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Commerce audience conditions, ported from
 * {@code @avsbhq/core/src/audienceCommerce.ts}.
 *
 * <p>Three condition families, all fed from caller-supplied context attributes:
 *
 * <ul>
 *   <li>{@code CART_VALUE} reads the {@code cartValue} attribute, a decimal number in
 *       major units.</li>
 *   <li>{@code PRODUCTS_VIEWED} reads {@code viewedSkus} or {@code viewedCategories}.</li>
 *   <li>{@code PURCHASE_HISTORY} reads the {@code purchaseHistory} object, whose
 *       {@code hasPurchased} boolean is mandatory for any operator to answer.</li>
 * </ul>
 *
 * <p>Two rules carried over verbatim because they are deliberate product decisions, not
 * implementation detail:
 *
 * <ol>
 *   <li>Money on the condition is encoded in MINOR units and converted with the
 *       datafile's {@code currencyExponent} (absent means 2). A zero-exponent currency
 *       such as JPY publishes exponent 0, so {@code "25000"} means 25000, not 250.00.</li>
 *   <li>Every "cannot know" path is false: a missing or mistyped attribute, a
 *       non-numeric condition value, dataset-backed membership, or an unknown operator.
 *       That includes {@code has_not_purchased} against an absent
 *       {@code purchaseHistory}: server callers own their data, so absence means "not
 *       provided", not "never purchased".</li>
 * </ol>
 *
 * <p>Nothing in this class throws.
 */
public final class AudienceCommerce {

    private AudienceCommerce() {
        // Static helpers only.
    }

    /** C5: currencies publish their own exponent; absent means the 2-decimal default. */
    public static final int DEFAULT_CURRENCY_EXPONENT = 2;

    private static final long MS_PER_DAY = 86_400_000L;

    /**
     * Evaluate one commerce condition.
     *
     * @param condition        the wire condition.
     * @param resolveAttribute reads an attribute off the evaluation context, honouring the
     *                         condition's {@code contextKind}.
     * @param currencyExponent the datafile's exponent, already defaulted by the caller.
     * @return true only when the condition positively matched.
     */
    public static boolean evaluateCommerceCondition(
            Datafile.ConditionNode condition,
            Function<String, Object> resolveAttribute,
            int currencyExponent) {
        String type = condition.type() == null ? "" : condition.type();
        return switch (type) {
            case "CART_VALUE" -> evalCartValue(condition, resolveAttribute, currencyExponent);
            case "PRODUCTS_VIEWED" -> evalProductsViewed(condition, resolveAttribute);
            case "PURCHASE_HISTORY" -> evalPurchaseHistory(condition, resolveAttribute, currencyExponent);
            default -> false;
        };
    }

    /** Normalise the datafile field, treating a negative or absent exponent as the default. */
    public static int currencyExponentOf(Integer datafileExponent) {
        if (datafileExponent == null || datafileExponent < 0) {
            return DEFAULT_CURRENCY_EXPONENT;
        }
        return datafileExponent;
    }

    // -------------------------------------------------------------------------
    // CART_VALUE
    // -------------------------------------------------------------------------

    private static boolean evalCartValue(
            Datafile.ConditionNode condition,
            Function<String, Object> resolveAttribute,
            int exponent) {
        Object actual = resolveAttribute.apply("cartValue");
        // cartValue is a decimal number in major units. A string, a null, or an object is
        // mistyped and fails closed rather than being coerced into a match.
        if (!(actual instanceof Number number)) {
            return false;
        }
        double cart = number.doubleValue();
        if (!Double.isFinite(cart)) {
            return false;
        }

        String operator = operatorOf(condition);
        if ("between".equals(operator)) {
            List<?> bounds = asList(condition.value());
            if (bounds == null || bounds.size() < 2) {
                return false;
            }
            Double lower = parseMinorToMajor(bounds.get(0), exponent);
            Double upper = parseMinorToMajor(bounds.get(1), exponent);
            if (lower == null || upper == null) {
                return false;
            }
            // C1: between is inclusive on both ends.
            return cart >= lower && cart <= upper;
        }

        Double threshold = parseMinorToMajor(singleValue(condition), exponent);
        if (threshold == null) {
            return false;
        }
        return switch (operator) {
            case "greater_than" -> cart > threshold;
            case "gte" -> cart >= threshold;
            case "less_than" -> cart < threshold;
            case "lte" -> cart <= threshold;
            default -> false;
        };
    }

    // -------------------------------------------------------------------------
    // PRODUCTS_VIEWED
    // -------------------------------------------------------------------------

    private static boolean evalProductsViewed(
            Datafile.ConditionNode condition,
            Function<String, Object> resolveAttribute) {
        // Dataset-backed membership is resolved from browser-side dataset windows, so the
        // server cannot answer it. Both operators degrade to false: matching all traffic
        // would be worse than matching none.
        if (condition.datasetSlug() != null && !condition.datasetSlug().isEmpty()) {
            return false;
        }

        String attributeName =
                "category".equals(condition.match()) ? "viewedCategories" : "viewedSkus";
        Object actual = resolveAttribute.apply(attributeName);
        if (!(actual instanceof List<?> actualList)) {
            return false;
        }
        Set<String> viewed = new LinkedHashSet<>();
        for (Object item : actualList) {
            if (item instanceof String s) {
                viewed.add(s);
            }
        }

        List<?> raw = asList(condition.value());
        List<?> candidates = raw != null ? raw : List.of(condition.value());
        boolean anyTarget = false;
        boolean anyViewed = false;
        for (Object candidate : candidates) {
            if (candidate instanceof String target && !target.isEmpty()) {
                anyTarget = true;
                if (viewed.contains(target)) {
                    anyViewed = true;
                }
            }
        }
        // No membership source means neither operator can answer (C1 asymmetry rule).
        if (!anyTarget) {
            return false;
        }

        return switch (operatorOf(condition)) {
            case "viewed_any" -> anyViewed;
            case "not_viewed_any" -> !anyViewed;
            default -> false;
        };
    }

    // -------------------------------------------------------------------------
    // PURCHASE_HISTORY
    // -------------------------------------------------------------------------

    private static boolean evalPurchaseHistory(
            Datafile.ConditionNode condition,
            Function<String, Object> resolveAttribute,
            int exponent) {
        Object raw = resolveAttribute.apply("purchaseHistory");
        if (!(raw instanceof Map<?, ?> profile)) {
            return false;
        }
        Object hasPurchasedRaw = profile.get("hasPurchased");
        if (!(hasPurchasedRaw instanceof Boolean hasPurchased)) {
            return false;
        }

        return switch (operatorOf(condition)) {
            case "has_purchased" -> hasPurchased;
            case "has_not_purchased" -> !hasPurchased;
            case "last_purchase_older_than" -> {
                Double days = parseNumeric(singleValue(condition));
                if (days == null) {
                    yield false;
                }
                Double lastPurchaseAt = finiteNumber(profile.get("lastPurchaseAt"));
                if (lastPurchaseAt != null) {
                    yield lastPurchaseAt < System.currentTimeMillis() - days * MS_PER_DAY;
                }
                // No usable timestamp: vacuously older only for a visitor who never
                // purchased. A purchaser with an unknown date is a "cannot know".
                yield !hasPurchased;
            }
            case "lifetime_spend_gt" -> {
                Double threshold = parseMinorToMajor(singleValue(condition), exponent);
                Double spend = finiteNumber(profile.get("lifetimeSpend"));
                if (threshold == null || spend == null) {
                    yield false;
                }
                yield spend > threshold;
            }
            case "order_count_gt" -> {
                Double minimum = parseNumeric(singleValue(condition));
                Double count = finiteNumber(profile.get("orderCount"));
                if (minimum == null || count == null) {
                    yield false;
                }
                yield count > minimum;
            }
            default -> false;
        };
    }

    // -------------------------------------------------------------------------
    // Value helpers
    // -------------------------------------------------------------------------

    private static String operatorOf(Datafile.ConditionNode condition) {
        return condition.operator() == null ? "" : condition.operator();
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : null;
    }

    private static Object singleValue(Datafile.ConditionNode condition) {
        List<?> list = asList(condition.value());
        if (list != null) {
            return list.isEmpty() ? null : list.get(0);
        }
        return condition.value();
    }

    /**
     * Parse a minor-units integer string (C1 money encoding) into decimal major units.
     * Returns null for anything that is not a numeric string, which the caller treats as
     * condition-evaluates-false.
     */
    private static Double parseMinorToMajor(Object raw, int exponent) {
        if (!(raw instanceof String s) || s.trim().isEmpty()) {
            return null;
        }
        double minor = JsNumber.numberOf(s);
        if (!Double.isFinite(minor)) {
            return null;
        }
        return minor / Math.pow(10, exponent);
    }

    /** Parse a plain numeric string condition value (days, counts). */
    private static Double parseNumeric(Object raw) {
        if (!(raw instanceof String s) || s.trim().isEmpty()) {
            return null;
        }
        double parsed = JsNumber.numberOf(s);
        return Double.isFinite(parsed) ? parsed : null;
    }

    /** A finite numeric attribute value, or null when the attribute is absent or mistyped. */
    private static Double finiteNumber(Object raw) {
        if (raw instanceof Number number && Double.isFinite(number.doubleValue())) {
            return number.doubleValue();
        }
        return null;
    }
}
