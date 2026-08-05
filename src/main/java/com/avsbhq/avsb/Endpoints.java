package com.avsbhq.avsb;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Data-plane endpoint contract (SDK/API DX overhaul spec §1.1).
 *
 * <p>Single source of truth for every network target this SDK talks to. Mirror of
 * {@code packages/avsb-core/src/endpoints.ts}; both are locked by the {@code endpoints}
 * conformance fixture family
 * ({@code packages/avsb-conformance/fixtures/endpoints/manifest.json}).
 *
 * <p>Nothing in this SDK may compose a data-plane URL by hand. Change a URL here and the
 * fixture changes with it, in the same commit, or every SDK's conformance run fails.
 *
 * <p>Credential posture:
 * <ul>
 *   <li>Datafile and preview reads are keyless by path. The SDK key is the path segment:
 *       no header, no query string, no Authorization.</li>
 *   <li>Event sends carry {@code sdkKey} in the JSON body envelope.</li>
 *   <li>The stream ticket request is the one read that takes the key as a query
 *       parameter.</li>
 *   <li>No Authorization header anywhere on the data plane.</li>
 * </ul>
 */
public final class Endpoints {

    /**
     * Default hosts. Overridable for self-hosted proxies, local dev, and tests. Nothing here
     * points at a host that does not exist: {@code datafile.avsb.cloud} and
     * {@code stream.avsb.cloud} are NOT real and must never appear as defaults.
     */
    public static final String DEFAULT_CDN_HOST = "https://cdn.avsb.cloud";

    public static final String DEFAULT_COLLECT_HOST = "https://ingest.avsb.cloud";

    public static final String DEFAULT_APP_HOST = "https://app.avsb.cloud";

    /** Ingestion paths. Absolute paths appended to a collect ORIGIN (see collectUrl). */
    public static final String COLLECT_PATH_SINGLE = "/v1/collect";

    public static final String COLLECT_PATH_BATCH = "/v1/collect/batch";

    public static final String COLLECT_PATH_METRIC_BATCH = "/v1/collect/metric-batch";

    public static final String COLLECT_PATH_ORDERS = "/v1/collect/orders";

    public static final String COLLECT_PATH_ERRORS = "/v1/collect/errors";

    public static final String COLLECT_PATH_PING = "/v1/ping";

    public static final String COLLECT_PATH_GEO = "/v1/geo";

    /** Kind name to ingestion path. Keys match the fixtures and the other ports. */
    public static final Map<String, String> COLLECT_PATHS = Map.of(
            "single", COLLECT_PATH_SINGLE,
            "batch", COLLECT_PATH_BATCH,
            "metricBatch", COLLECT_PATH_METRIC_BATCH,
            "orders", COLLECT_PATH_ORDERS,
            "errors", COLLECT_PATH_ERRORS,
            "ping", COLLECT_PATH_PING,
            "geo", COLLECT_PATH_GEO);

    public static final String STREAM_TICKET_PATH = "/api/sdk/stream";

    public static final String HEARTBEAT_PATH = "/api/sdk/heartbeat";

    public static final String DATAFILE_FILENAME = "datafile.json";

    public static final String PREVIEW_FILENAME = "preview.json";

    public static final String STREAM_UNAVAILABLE_REASON =
            "Streaming is on, but there is no stream endpoint to connect to: the datafile carries"
                    + " no \"streamEndpoint\" field and no streamingEndpoint option was set."
                    + " Republish the environment from the A vs B dashboard (Settings, then"
                    + " Environments) to pick up the field, or set streamingEndpoint explicitly."
                    + " Polling stays on in the meantime, so flag changes still arrive, just on the"
                    + " polling interval instead of instantly.";

    private Endpoints() {
        // Constants holder.
    }

    /**
     * Canonical datafile URL: {@code {cdnHost}/{sdkKey}/datafile.json}.
     *
     * <p>This is the ONLY correct shape. {@code /datafiles/{key}.json},
     * {@code /v1/datafiles/{key}.json}, and {@code datafile.avsb.cloud/v2/{key}} were all bugs
     * and all 404.
     *
     * @param cdnHost CDN origin, or {@code null} for the public CDN.
     */
    public static String datafileUrl(String sdkKey, String cdnHost) {
        String key = requireSdkKey(sdkKey, "datafileUrl");
        return hostOrDefault(cdnHost, DEFAULT_CDN_HOST) + "/" + key + "/" + DATAFILE_FILENAME;
    }

    /** Canonical datafile URL against the public CDN. */
    public static String datafileUrl(String sdkKey) {
        return datafileUrl(sdkKey, null);
    }

    /** Per-environment preview snapshot. Served with {@code no-store}. */
    public static String previewUrl(String sdkKey, String cdnHost) {
        String key = requireSdkKey(sdkKey, "previewUrl");
        return hostOrDefault(cdnHost, DEFAULT_CDN_HOST) + "/" + key + "/" + PREVIEW_FILENAME;
    }

    /** Per-environment preview snapshot against the public CDN. */
    public static String previewUrl(String sdkKey) {
        return previewUrl(sdkKey, null);
    }

    /** CDN object key for the live datafile. */
    public static String datafileObjectKey(String sdkKey) {
        return requireSdkKey(sdkKey, "datafileObjectKey") + "/" + DATAFILE_FILENAME;
    }

    /** CDN object key for the preview snapshot. */
    public static String previewObjectKey(String sdkKey) {
        return requireSdkKey(sdkKey, "previewObjectKey") + "/" + PREVIEW_FILENAME;
    }

    /**
     * Normalise a datafile {@code collectEndpoint} value down to a bare origin.
     *
     * <p>The flag datafile publishes an origin; the web-experiment datafile publishes the full
     * batch URL. Both are valid inputs, so an SDK can never produce
     * {@code /v1/collect/batch/v1/collect/batch}.
     */
    public static String collectOriginFrom(String collectEndpoint) {
        String trimmed = trimTrailingSlashes(collectEndpoint == null ? "" : collectEndpoint.trim());
        for (String path : COLLECT_PATHS.values()) {
            if (trimmed.endsWith(path)) {
                return trimmed.substring(0, trimmed.length() - path.length());
            }
        }
        return trimmed;
    }

    /**
     * Build an ingestion URL.
     *
     * @param kind a key of {@link #COLLECT_PATHS}.
     * @param collectEndpoint the datafile's {@code collectEndpoint}, or {@code null} for the
     *     public ingest host.
     */
    public static String collectUrl(String kind, String collectEndpoint) {
        String path = COLLECT_PATHS.get(kind);
        if (path == null) {
            List<String> known = new ArrayList<>(COLLECT_PATHS.keySet());
            Collections.sort(known);
            throw new IllegalArgumentException(
                    "collectUrl got an unknown endpoint kind \"" + kind + "\". Known kinds: "
                            + String.join(", ", known) + ".");
        }
        String origin = (collectEndpoint == null || collectEndpoint.isBlank())
                ? DEFAULT_COLLECT_HOST
                : collectOriginFrom(collectEndpoint);
        return origin + path;
    }

    /**
     * Outcome of {@link #resolveStreamEndpoint}. When {@code available} is false, {@code reason}
     * explains what to do and {@code endpoint} is {@code null}: never guess a host.
     */
    public record StreamEndpointResolution(
            boolean available, String endpoint, String source, String reason) {}

    /**
     * Resolve the stream ticket endpoint. Precedence: explicit option, then the
     * server-controlled datafile field, then nothing.
     *
     * <p>A default of {@code stream.avsb.cloud} gave every Go customer an infinite reconnect
     * loop against DNS that does not resolve. This returns unavailable instead.
     */
    public static StreamEndpointResolution resolveStreamEndpoint(
            String optionEndpoint, String datafileStreamEndpoint) {
        if (optionEndpoint != null && !optionEndpoint.isBlank()) {
            return new StreamEndpointResolution(
                    true, trimTrailingSlashes(optionEndpoint.trim()), "option", null);
        }
        if (datafileStreamEndpoint != null && !datafileStreamEndpoint.isBlank()) {
            return new StreamEndpointResolution(
                    true, trimTrailingSlashes(datafileStreamEndpoint.trim()), "datafile", null);
        }
        return new StreamEndpointResolution(false, null, null, STREAM_UNAVAILABLE_REASON);
    }

    /** Step 1 of streaming: the ticket request URL. */
    public static String streamTicketUrl(String streamEndpoint, String sdkKey) {
        String key = requireSdkKey(sdkKey, "streamTicketUrl");
        return trimTrailingSlashes(streamEndpoint) + "?sdkKey=" + encode(key);
    }

    /**
     * Step 2 of streaming: the SSE URL.
     *
     * <p>{@code subscribeBaseUrl} is the {@code url} field the TICKET RESPONSE handed back, not
     * the ticket endpoint itself. The token rides in the query string because SSE clients cannot
     * set request headers; tokens expire in 60 seconds.
     */
    public static String streamSubscribeUrl(String subscribeBaseUrl, String sdkKey, String token) {
        String key = requireSdkKey(sdkKey, "streamSubscribeUrl");
        return trimTrailingSlashes(subscribeBaseUrl)
                + "?sdkKey=" + encode(key)
                + "&token=" + encode(token);
    }

    /** Fallback stream ticket URL, for docs and tests. {@code null} host means the public app. */
    public static String streamEndpointUrl(String appHost) {
        return hostOrDefault(appHost, DEFAULT_APP_HOST) + STREAM_TICKET_PATH;
    }

    /** Fallback heartbeat URL, for docs and tests. {@code null} host means the public app. */
    public static String heartbeatUrl(String appHost) {
        return hostOrDefault(appHost, DEFAULT_APP_HOST) + HEARTBEAT_PATH;
    }

    /**
     * Returns the datafile's heartbeat endpoint, or {@code null} meaning "do not send
     * heartbeats".
     *
     * <p>Deliberately not defaulted to {@code app.avsb.cloud}: a proxied deployment publishes
     * its own origin, and heartbeats posted to the wrong host would report liveness nowhere
     * while looking healthy.
     */
    public static String resolveHeartbeatEndpoint(String datafileHeartbeatEndpoint) {
        if (datafileHeartbeatEndpoint == null || datafileHeartbeatEndpoint.isBlank()) {
            return null;
        }
        return datafileHeartbeatEndpoint.trim();
    }

    private static String encode(String value) {
        // URLEncoder is form encoding: a space becomes '+', which is wrong for a query VALUE.
        // SDK keys and edge tokens never contain spaces, but fix it anyway so this helper is
        // safe for any value and matches encodeURIComponent in the reference implementation.
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static String hostOrDefault(String host, String fallback) {
        if (host == null || host.isBlank()) {
            return fallback;
        }
        return trimTrailingSlashes(host.trim());
    }

    private static String trimTrailingSlashes(String host) {
        String value = host == null ? "" : host;
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * Loud guard: an empty key builds {@code https://cdn.avsb.cloud//datafile.json}, which 404s
     * at the edge with nothing in the message to explain why. Full key-shape validation belongs
     * in the builder (spec §1.2); this only catches "nothing was passed".
     */
    private static String requireSdkKey(String sdkKey, String fnName) {
        String trimmed = sdkKey == null ? "" : sdkKey.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(
                    fnName + " was called without an SDK key. Pass the environment SDK key (it"
                            + " looks like \"sdk_production_xxxxxxxx\"), which you can copy from"
                            + " Settings, then Environments, in the A vs B dashboard.");
        }
        return trimmed;
    }
}
