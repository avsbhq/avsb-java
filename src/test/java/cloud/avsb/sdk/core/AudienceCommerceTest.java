package cloud.avsb.sdk.core;

import cloud.avsb.sdk.EvalContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Commerce audience conditions. An e-commerce audience built in the dashboard used to
 * evaluate false on every server SDK with no warning, so a targeted campaign quietly reached
 * nobody.
 *
 * <p>Two contract rules are asserted repeatedly because they are the ones a port gets wrong:
 * condition money is MINOR units converted with the datafile's exponent, and every
 * cannot-know path is false.
 */
@DisplayName("AudienceCommerce — commerce operators")
class AudienceCommerceTest {

    private static Datafile.ConditionNode cond(String type, String operator, Object value) {
        return new Datafile.ConditionNode("c1", type, null, operator, value, "user", null, null);
    }

    private static Datafile.ConditionNode viewed(
            String operator, Object value, String match, String datasetSlug) {
        return new Datafile.ConditionNode(
                "c1", "PRODUCTS_VIEWED", null, operator, value, "user", match, datasetSlug);
    }

    private static boolean evaluate(Datafile.ConditionNode condition, EvalContext ctx) {
        return Audience.evaluateCondition(condition, ctx, AudienceCommerce.DEFAULT_CURRENCY_EXPONENT);
    }

    private static boolean evaluate(
            Datafile.ConditionNode condition, EvalContext ctx, int currencyExponent) {
        return Audience.evaluateCondition(condition, ctx, currencyExponent);
    }

    // -------------------------------------------------------------------------
    // CART_VALUE
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("between is inclusive on both ends, with minor-unit bounds")
    void cartValueBetween() {
        // "5000" and "10000" minor units are 50.00 and 100.00 at exponent 2.
        Datafile.ConditionNode condition =
                cond("CART_VALUE", "between", List.of("5000", "10000"));
        assertTrue(evaluate(condition, EvalContext.user("u", Map.of("cartValue", 100))),
                "the upper bound is inclusive");
        assertTrue(evaluate(condition, EvalContext.user("u", Map.of("cartValue", 50))),
                "the lower bound is inclusive");
        assertTrue(evaluate(condition, EvalContext.user("u", Map.of("cartValue", 75.5))));
        assertFalse(evaluate(condition, EvalContext.user("u", Map.of("cartValue", 49.99))));
        assertFalse(evaluate(condition, EvalContext.user("u", Map.of("cartValue", 100.01))));
    }

    @Test
    @DisplayName("between needs two bounds and both must be numeric")
    void cartValueBetweenMalformed() {
        EvalContext ctx = EvalContext.user("u", Map.of("cartValue", 75));
        assertFalse(evaluate(cond("CART_VALUE", "between", List.of("5000")), ctx));
        assertFalse(evaluate(cond("CART_VALUE", "between", "5000"), ctx));
        assertFalse(evaluate(cond("CART_VALUE", "between", List.of("abc", "10000")), ctx));
    }

    @Test
    @DisplayName("the comparison operators read the same minor-unit encoding")
    void cartValueComparisons() {
        EvalContext ctx = EvalContext.user("u", Map.of("cartValue", 75.0));
        assertTrue(evaluate(cond("CART_VALUE", "greater_than", "5000"), ctx));
        assertFalse(evaluate(cond("CART_VALUE", "greater_than", "10000"), ctx));
        assertTrue(evaluate(cond("CART_VALUE", "lte", "7500"), ctx));
        assertTrue(evaluate(cond("CART_VALUE", "gte", "7500"), ctx));
        assertTrue(evaluate(cond("CART_VALUE", "less_than", "10000"), ctx));
    }

    @Test
    @DisplayName("a missing or mistyped cartValue fails closed")
    void cartValueMistyped() {
        assertFalse(evaluate(cond("CART_VALUE", "greater_than", "5000"),
                EvalContext.user("u", Map.of())));
        assertFalse(evaluate(cond("CART_VALUE", "greater_than", "5000"),
                EvalContext.user("u", Map.of("cartValue", "75"))),
                "a string cart value is mistyped, not something to parse");
    }

    // -------------------------------------------------------------------------
    // PRODUCTS_VIEWED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("viewed_any matches on any overlap and not_viewed_any is its inverse")
    void viewedAny() {
        EvalContext ctx = EvalContext.user("u",
                Map.of("viewedSkus", List.of("SKU-GREEN-09", "SKU-BLUE-02")));
        assertTrue(evaluate(viewed("viewed_any", List.of("SKU-RED-01", "SKU-BLUE-02"), null, null), ctx));
        assertFalse(evaluate(viewed("viewed_any", List.of("SKU-RED-01"), null, null), ctx));
        assertTrue(evaluate(viewed("not_viewed_any", List.of("SKU-RED-01"), null, null), ctx));
        assertFalse(evaluate(viewed("not_viewed_any", List.of("SKU-BLUE-02"), null, null), ctx));
    }

    @Test
    @DisplayName("match=category reads viewedCategories instead of viewedSkus")
    void viewedCategories() {
        EvalContext ctx = EvalContext.user("u", Map.of(
                "viewedSkus", List.of("SKU-RED-01"),
                "viewedCategories", List.of("outerwear")));
        assertTrue(evaluate(viewed("viewed_any", List.of("outerwear"), "category", null), ctx));
        assertFalse(evaluate(viewed("viewed_any", List.of("SKU-RED-01"), "category", null), ctx),
                "with match=category the SKU list is not consulted");
    }

    @Test
    @DisplayName("a dataset-backed condition is false for BOTH operators")
    void datasetBackedIsAlwaysFalse() {
        // Dataset windows live in the browser. Degrading to false beats an accidental
        // full-traffic match.
        EvalContext ctx = EvalContext.user("u", Map.of("viewedSkus", List.of("SKU-RED-01")));
        assertFalse(evaluate(
                viewed("viewed_any", List.of("SKU-RED-01"), null, "recently-viewed"), ctx));
        assertFalse(evaluate(
                viewed("not_viewed_any", List.of("SKU-RED-01"), null, "recently-viewed"), ctx));
    }

    @Test
    @DisplayName("no membership source means neither operator can answer")
    void viewedWithoutSource() {
        assertFalse(evaluate(viewed("viewed_any", List.of("SKU-RED-01"), null, null),
                EvalContext.user("u", Map.of())), "no viewedSkus attribute at all");
        assertFalse(evaluate(viewed("not_viewed_any", List.of(), null, null),
                EvalContext.user("u", Map.of("viewedSkus", List.of("SKU-RED-01")))),
                "an empty target list is not a question that can be answered");
    }

    // -------------------------------------------------------------------------
    // PURCHASE_HISTORY
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("has_purchased and has_not_purchased read the hasPurchased flag")
    void hasPurchased() {
        EvalContext buyer = EvalContext.user("u",
                Map.of("purchaseHistory", Map.of("hasPurchased", true)));
        EvalContext browser = EvalContext.user("u",
                Map.of("purchaseHistory", Map.of("hasPurchased", false)));
        assertTrue(evaluate(cond("PURCHASE_HISTORY", "has_purchased", ""), buyer));
        assertFalse(evaluate(cond("PURCHASE_HISTORY", "has_purchased", ""), browser));
        assertTrue(evaluate(cond("PURCHASE_HISTORY", "has_not_purchased", ""), browser));
        assertFalse(evaluate(cond("PURCHASE_HISTORY", "has_not_purchased", ""), buyer));
    }

    @Test
    @DisplayName("an absent purchaseHistory makes every operator false, including the negative one")
    void absentPurchaseHistory() {
        // Frozen contract: server callers own their data, so absence means "not provided",
        // not "never purchased". This deliberately differs from the snippet's KV-miss
        // semantics.
        EvalContext ctx = EvalContext.user("u", Map.of());
        assertFalse(evaluate(cond("PURCHASE_HISTORY", "has_purchased", ""), ctx));
        assertFalse(evaluate(cond("PURCHASE_HISTORY", "has_not_purchased", ""), ctx));
        assertFalse(evaluate(cond("PURCHASE_HISTORY", "lifetime_spend_gt", "1000"), ctx));
    }

    @Test
    @DisplayName("a purchaseHistory without a boolean hasPurchased is unusable")
    void malformedPurchaseHistory() {
        EvalContext ctx = EvalContext.user("u",
                Map.of("purchaseHistory", Map.of("orderCount", 4)));
        assertFalse(evaluate(cond("PURCHASE_HISTORY", "order_count_gt", "3"), ctx));
    }

    @Test
    @DisplayName("lifetime_spend_gt converts minor units with the datafile exponent")
    void lifetimeSpend() {
        EvalContext ctx = EvalContext.user("u",
                Map.of("purchaseHistory", Map.of("hasPurchased", true, "lifetimeSpend", 250.01)));
        // Exponent 2: "25000" minor units is 250.00.
        assertTrue(evaluate(cond("PURCHASE_HISTORY", "lifetime_spend_gt", "25000"), ctx));
        assertFalse(evaluate(cond("PURCHASE_HISTORY", "lifetime_spend_gt", "25001"), ctx));
    }

    @Test
    @DisplayName("a zero-exponent currency does not divide by a hundred")
    void zeroExponentCurrency() {
        // JPY publishes currencyExponent 0, so "25000" means 25000, not 250.00. A port that
        // hardcodes two decimals would match every visitor who spent 250 yen.
        EvalContext ctx = EvalContext.user("u",
                Map.of("purchaseHistory", Map.of("hasPurchased", true, "lifetimeSpend", 25001)));
        assertTrue(evaluate(cond("PURCHASE_HISTORY", "lifetime_spend_gt", "25000"), ctx, 0));

        EvalContext modest = EvalContext.user("u",
                Map.of("purchaseHistory", Map.of("hasPurchased", true, "lifetimeSpend", 300)));
        assertFalse(evaluate(cond("PURCHASE_HISTORY", "lifetime_spend_gt", "25000"), modest, 0));
        assertTrue(evaluate(cond("PURCHASE_HISTORY", "lifetime_spend_gt", "25000"), modest, 2),
                "the same data matches at exponent 2, which is why the exponent must be read");
    }

    @Test
    @DisplayName("order_count_gt is strictly greater than")
    void orderCount() {
        EvalContext ctx = EvalContext.user("u",
                Map.of("purchaseHistory", Map.of("hasPurchased", true, "orderCount", 4)));
        assertTrue(evaluate(cond("PURCHASE_HISTORY", "order_count_gt", "3"), ctx));
        assertFalse(evaluate(cond("PURCHASE_HISTORY", "order_count_gt", "4"), ctx));
    }

    @Test
    @DisplayName("last_purchase_older_than compares against the recorded timestamp")
    void lastPurchaseOlderThan() {
        EvalContext ancient = EvalContext.user("u",
                Map.of("purchaseHistory", Map.of("hasPurchased", true, "lastPurchaseAt", 0)));
        EvalContext future = EvalContext.user("u", Map.of("purchaseHistory",
                Map.of("hasPurchased", true, "lastPurchaseAt", 4102444800000L)));
        assertTrue(evaluate(cond("PURCHASE_HISTORY", "last_purchase_older_than", "30"), ancient));
        assertFalse(evaluate(cond("PURCHASE_HISTORY", "last_purchase_older_than", "30"), future));
    }

    @Test
    @DisplayName("without a timestamp, only a non-purchaser is vacuously older")
    void lastPurchaseWithoutTimestamp() {
        EvalContext neverBought = EvalContext.user("u",
                Map.of("purchaseHistory", Map.of("hasPurchased", false)));
        EvalContext boughtSomewhen = EvalContext.user("u",
                Map.of("purchaseHistory", Map.of("hasPurchased", true)));
        assertTrue(evaluate(cond("PURCHASE_HISTORY", "last_purchase_older_than", "30"), neverBought));
        assertFalse(evaluate(cond("PURCHASE_HISTORY", "last_purchase_older_than", "30"), boughtSomewhen),
                "a purchaser with an unknown date is a cannot-know");
    }

    @Test
    @DisplayName("the currency exponent defaults to two when the datafile omits it")
    void exponentDefault() {
        assertEquals(2, AudienceCommerce.currencyExponentOf(null));
        assertEquals(0, AudienceCommerce.currencyExponentOf(0));
        assertEquals(3, AudienceCommerce.currencyExponentOf(3));
        assertEquals(2, AudienceCommerce.currencyExponentOf(-1), "a negative exponent is nonsense");
    }
}
