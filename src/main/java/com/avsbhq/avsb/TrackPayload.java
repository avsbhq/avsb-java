package com.avsbhq.avsb;

import java.util.Map;

/**
 * Payload for a metric-track call. Mirrors {@code TrackPayload} (§2.6) in
 * types.ts.
 *
 * @param value      Optional numeric metric value (revenue, count, latency…).
 * @param properties Optional free-form properties forwarded to analytics.
 * @param context    Optional context override (server SDK only).
 */
public record TrackPayload(
        Double value,
        Map<String, Object> properties,
        EvalContext context
) {

    public static TrackPayload of(double value) {
        return new TrackPayload(value, null, null);
    }

    public static TrackPayload withContext(EvalContext context) {
        return new TrackPayload(null, null, context);
    }

    public static TrackPayload empty() {
        return new TrackPayload(null, null, null);
    }
}
