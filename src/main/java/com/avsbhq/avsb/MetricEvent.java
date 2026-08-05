package com.avsbhq.avsb;

/**
 * One conversion or metric event, exactly as the ingestion API accepts it.
 *
 * <p>Field names are FROZEN VOCABULARY (spec section 1.1, locked by the
 * {@code wire-shape} conformance fixture family).
 *
 * <p>There is deliberately NO {@code properties} field. Unlike the exposure batch, the
 * metric batch has none: anything sent under that name is stripped before storage, so it
 * would look delivered and be gone. Put the number in {@link #value()} instead.
 *
 * <p>There is also no experiment or variation: attribution happens server-side from the
 * visitor's recorded exposures.
 *
 * @param eventId   UUID stamped once at enqueue time so retries deduplicate.
 * @param visitorId The identity the conversion belongs to.
 * @param eventName The metric key as configured in the dashboard. An unknown key is
 *                  accepted and stored, so a typo shows up as an empty metric rather than
 *                  an error.
 * @param revenue   Decimal major units, or null for a non-revenue conversion.
 * @param value     Numeric metric value for average-value metrics; null when the metric is
 *                  a count.
 * @param timestamp Milliseconds since the Unix epoch.
 * @param pageUrl   Empty string from a server SDK.
 */
public record MetricEvent(
        String eventId,
        String visitorId,
        String eventName,
        Double revenue,
        Double value,
        long timestamp,
        String pageUrl
) {

    /** Copy carrying a freshly stamped event id. */
    public MetricEvent withEventId(String newEventId) {
        return new MetricEvent(newEventId, visitorId, eventName, revenue, value, timestamp, pageUrl);
    }
}
