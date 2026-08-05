package com.avsbhq.avsb;

/**
 * Optional extras for {@link AvsbServer#track}.
 *
 * <p>There is deliberately no {@code properties} field. The metric ingestion body has none:
 * anything sent under that name is stripped before storage, so it would look delivered and
 * be gone. Put the number in {@link #value()}, and use registered context attributes for
 * anything you want to segment by.
 *
 * @param value   Numeric metric value, for average-value metrics. Null for a plain count.
 * @param revenue Money in DECIMAL MAJOR UNITS as displayed (9.99, not 999). Null for a
 *                non-revenue conversion. For a full order with line items use
 *                {@link AvsbServer#trackPurchase} instead.
 * @param context Identity override for this one event, when it differs from the context you
 *                passed to {@code track}.
 */
public record TrackPayload(
        Double value,
        Double revenue,
        EvalContext context
) {

    /** A numeric metric value, such as a latency or a cart size. */
    public static TrackPayload value(double value) {
        return new TrackPayload(value, null, null);
    }

    /** Money in decimal major units. */
    public static TrackPayload revenue(double revenue) {
        return new TrackPayload(null, revenue, null);
    }

    /** Both a numeric value and money. */
    public static TrackPayload valueAndRevenue(double value, double revenue) {
        return new TrackPayload(value, revenue, null);
    }

    /** Attribute this event to a different identity than the caller's context. */
    public static TrackPayload withContext(EvalContext context) {
        return new TrackPayload(null, null, context);
    }

    /** No extras: a plain conversion count. */
    public static TrackPayload empty() {
        return new TrackPayload(null, null, null);
    }

    /** This payload with a different identity attached. */
    public TrackPayload forContext(EvalContext newContext) {
        return new TrackPayload(value, revenue, newContext);
    }
}
