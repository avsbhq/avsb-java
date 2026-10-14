package cloud.avsb.sdk;

import java.util.List;

/**
 * A purchase, exactly as the ingestion API accepts it.
 *
 * <p>Field names are FROZEN VOCABULARY (spec section 1.1, locked by the
 * {@code wire-shape} conformance fixture family). Orders are sent immediately rather than
 * batched on a timer, because an order is the event a customer least wants buffered.
 *
 * <p>Money is DECIMAL MAJOR UNITS as displayed (99.95, not 9995). {@link #total()} must be
 * finite and greater than zero; a zero or negative total is rejected by ingestion.
 *
 * @param orderId  Required. Unique order or transaction identifier.
 * @param total    Required. Order total in major units.
 * @param currency ISO 4217 code.
 * @param coupon   Coupon or promotion code applied.
 * @param test     True stores the order but excludes it from every revenue, profit,
 *                 recommendation, and audience calculation. Only send it when you are
 *                 deliberately testing the integration.
 * @param items    Line items.
 */
public record PurchaseOrder(
        String orderId,
        double total,
        String currency,
        Double subtotal,
        Double shipping,
        Double tax,
        Double discount,
        String coupon,
        Boolean test,
        List<Item> items
) {

    /**
     * One line item.
     *
     * @param sku        Required. Stock keeping unit.
     * @param productKey Canonical product-grain key. Pass it when known so order items join
     *                   catalog and recommendation analytics without alias resolution;
     *                   ingestion derives it from {@code sku} otherwise.
     */
    public record Item(
            String sku,
            String productKey,
            String name,
            Double price,
            Integer quantity,
            String category
    ) {

        /** The two fields most orders carry. */
        public static Item of(String sku, double price, int quantity) {
            return new Item(sku, null, null, price, quantity, null);
        }
    }

    /** The minimum an order needs: an id and a total. */
    public static PurchaseOrder of(String orderId, double total) {
        return new PurchaseOrder(orderId, total, null, null, null, null, null, null, null, null);
    }

    /** An order with a currency and line items. */
    public static PurchaseOrder of(String orderId, double total, String currency, List<Item> items) {
        return new PurchaseOrder(orderId, total, currency, null, null, null, null, null, null, items);
    }
}
