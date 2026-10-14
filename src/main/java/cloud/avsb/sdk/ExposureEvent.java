package cloud.avsb.sdk;

import java.util.Map;

/**
 * One exposure event, exactly as the ingestion API accepts it.
 *
 * <p>Field names are FROZEN VOCABULARY (spec section 1.1, locked by the
 * {@code wire-shape} conformance fixture family). The ingestion worker validates these
 * exact names and the dashboard reads the columns they land in, so a renamed field is
 * silently dropped data, not a compile error.
 *
 * @param eventId      UUID stamped once at enqueue time, so a retried batch carries the
 *                     same ids and ingestion can deduplicate.
 * @param visitorId    The bucketing identity the decision was made for.
 * @param experimentId The rule id for a flag exposure, the holdout id for a holdout
 *                     exposure.
 * @param variationId  The served variation id.
 * @param eventName    {@code flag_exposure} or {@code flag_holdout_exposure}.
 * @param revenue      Always null on an exposure. Only conversions and orders carry money.
 * @param value        Optional numeric value. Null on an exposure.
 * @param timestamp    Milliseconds since the Unix epoch.
 * @param pageUrl      Required by the ingestion schema; an empty string is what a server
 *                     SDK sends, because there is no page on a server.
 * @param properties   Decision metadata: {@code flagKey}, {@code flagId}, and for holdouts
 *                     {@code holdoutId} and {@code holdoutKey}, for bandits
 *                     {@code banditKey} and {@code actionId}.
 * @param attributes   Registered attributes only. Unregistered keys are dropped
 *                     server-side, so registering an attribute in the dashboard is what
 *                     makes it queryable.
 */
public record ExposureEvent(
        String eventId,
        String visitorId,
        String experimentId,
        String variationId,
        String eventName,
        Double revenue,
        Double value,
        long timestamp,
        String pageUrl,
        Map<String, Object> properties,
        Map<String, String> attributes
) {

    /** Event name for a flag rule or bandit exposure. */
    public static final String FLAG_EXPOSURE = "flag_exposure";

    /** Event name for a holdout exposure. */
    public static final String FLAG_HOLDOUT_EXPOSURE = "flag_holdout_exposure";

    /** Copy carrying a freshly stamped event id. */
    public ExposureEvent withEventId(String newEventId) {
        return new ExposureEvent(newEventId, visitorId, experimentId, variationId, eventName,
                revenue, value, timestamp, pageUrl, properties, attributes);
    }
}
