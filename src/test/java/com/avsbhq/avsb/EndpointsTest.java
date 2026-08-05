package com.avsbhq.avsb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The data-plane endpoint contract, asserted against the {@code endpoints} conformance
 * fixture family ({@code packages/avsb-conformance/fixtures/endpoints/manifest.json}).
 *
 * <p>Every expectation below is a case in that manifest. It is duplicated here as a unit test
 * because the endpoints family is a language-neutral spec rather than a datafile plus a
 * context, so the generated corpus runner cannot execute it.
 */
@DisplayName("Endpoints — data-plane contract")
class EndpointsTest {

    private static final String KEY = "sdk_production_abc123";

    @Test
    @DisplayName("the datafile URL is {cdnHost}/{sdkKey}/datafile.json")
    void datafileUrl() {
        // /datafiles/{key}.json was this SDK's shape and it 404s at the edge.
        assertEquals("https://cdn.avsb.cloud/sdk_production_abc123/datafile.json",
                Endpoints.datafileUrl(KEY));
        assertEquals("https://cdn.example.test/sdk_production_abc123/datafile.json",
                Endpoints.datafileUrl(KEY, "https://cdn.example.test"));
        assertEquals("https://cdn.example.test/sdk_production_abc123/datafile.json",
                Endpoints.datafileUrl(KEY, "https://cdn.example.test/"),
                "a trailing slash is trimmed, never doubled into the path");
        assertEquals("http://localhost:3000/sdk_development_local1/datafile.json",
                Endpoints.datafileUrl("sdk_development_local1", "http://localhost:3000"));
    }

    @Test
    @DisplayName("an empty SDK key raises instead of building a double-slash URL")
    void emptyKeyRaises() {
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> Endpoints.datafileUrl(""));
        assertTrue(error.getMessage().contains("Environments"),
                "the message says where to find the key: " + error.getMessage());
    }

    @Test
    @DisplayName("the preview snapshot sits beside the datafile under the same key prefix")
    void previewUrl() {
        assertEquals("https://cdn.avsb.cloud/sdk_production_abc123/preview.json",
                Endpoints.previewUrl(KEY));
        assertEquals("sdk_production_abc123/datafile.json", Endpoints.datafileObjectKey(KEY));
        assertEquals("sdk_production_abc123/preview.json", Endpoints.previewObjectKey(KEY));
    }

    @Test
    @DisplayName("every ingestion kind resolves to its locked path")
    void collectPaths() {
        assertEquals("https://ingest.avsb.cloud/v1/collect", Endpoints.collectUrl("single", null));
        assertEquals("https://ingest.avsb.cloud/v1/collect/batch", Endpoints.collectUrl("batch", null));
        assertEquals("https://ingest.avsb.cloud/v1/collect/metric-batch",
                Endpoints.collectUrl("metricBatch", null));
        assertEquals("https://ingest.avsb.cloud/v1/collect/orders", Endpoints.collectUrl("orders", null));
        assertEquals("https://ingest.avsb.cloud/v1/collect/errors", Endpoints.collectUrl("errors", null));
        assertEquals("https://ingest.avsb.cloud/v1/ping", Endpoints.collectUrl("ping", null));
        assertEquals("https://ingest.avsb.cloud/v1/geo", Endpoints.collectUrl("geo", null));
    }

    @Test
    @DisplayName("a datafile collectEndpoint that already carries a path is normalised, never doubled")
    void collectEndpointNormalisation() {
        assertEquals("https://events.customer.test/v1/collect/batch",
                Endpoints.collectUrl("batch", "https://events.customer.test"));
        assertEquals("https://ingest.avsb.cloud/v1/collect/batch",
                Endpoints.collectUrl("batch", "https://ingest.avsb.cloud/v1/collect/batch"),
                "the web-experiment datafile publishes the full batch URL");
        assertEquals("https://ingest.avsb.cloud/v1/collect/orders",
                Endpoints.collectUrl("orders", "https://ingest.avsb.cloud/v1/collect/batch"),
                "and a sibling send resolves off the same origin");
    }

    @Test
    @DisplayName("an unknown ingestion kind names the known kinds instead of guessing")
    void unknownCollectKind() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Endpoints.collectUrl("conversions", null));
        assertTrue(error.getMessage().contains("metricBatch"),
                "the message lists the real kinds: " + error.getMessage());
    }

    @Test
    @DisplayName("streaming resolves from the option, then the datafile, then not at all")
    void streamResolution() {
        Endpoints.StreamEndpointResolution fromOption = Endpoints.resolveStreamEndpoint(
                "https://stream.example.test/ticket", "https://app.avsb.cloud/api/sdk/stream");
        assertTrue(fromOption.available());
        assertEquals("https://stream.example.test/ticket", fromOption.endpoint());
        assertEquals("option", fromOption.source());

        Endpoints.StreamEndpointResolution fromDatafile =
                Endpoints.resolveStreamEndpoint(null, "https://app.avsb.cloud/api/sdk/stream");
        assertTrue(fromDatafile.available());
        assertEquals("datafile", fromDatafile.source());

        // No SDK may guess a host. The stream.avsb.cloud default gave every Go customer an
        // infinite reconnect loop against DNS that does not resolve.
        Endpoints.StreamEndpointResolution absent = Endpoints.resolveStreamEndpoint(null, null);
        assertFalse(absent.available());
        assertNull(absent.endpoint());
        assertNull(absent.source());
        assertNotNull(absent.reason(), "and it explains what to do instead");
    }

    @Test
    @DisplayName("stream URLs percent-encode a query value, never form-encode it")
    void streamUrlEncoding() {
        assertEquals("https://app.avsb.cloud/api/sdk/stream?sdkKey=sdk_production_abc123",
                Endpoints.streamTicketUrl("https://app.avsb.cloud/api/sdk/stream", KEY));
        assertEquals("https://stream.example.test/subscribe"
                        + "?sdkKey=sdk_production_abc123&token=tkn_1",
                Endpoints.streamSubscribeUrl("https://stream.example.test/subscribe", KEY, "tkn_1"));
        assertEquals("https://stream.example.test/subscribe"
                        + "?sdkKey=sdk_production_abc123&token=tk%2Bn%2F1%3D",
                Endpoints.streamSubscribeUrl("https://stream.example.test/subscribe", KEY, "tk+n/1="));
        // A space must encode as %20, never as +. URLEncoder is form encoding, so a port that
        // forwards it straight into a query VALUE sends a different token than the reference.
        assertEquals("https://stream.example.test/subscribe"
                        + "?sdkKey=sdk_production_abc123&token=tk%20n%2B1",
                Endpoints.streamSubscribeUrl("https://stream.example.test/subscribe", KEY, "tk n+1"));
    }

    @Test
    @DisplayName("an absent heartbeat endpoint means send nothing, not default to the app host")
    void heartbeatResolution() {
        assertEquals("https://app.avsb.cloud/api/sdk/heartbeat",
                Endpoints.resolveHeartbeatEndpoint("https://app.avsb.cloud/api/sdk/heartbeat"));
        // A proxied deployment publishes its own origin. Posting to the public host would
        // report liveness nowhere while looking healthy.
        assertNull(Endpoints.resolveHeartbeatEndpoint(null));
        assertNull(Endpoints.resolveHeartbeatEndpoint(""));
        assertEquals("https://app.avsb.cloud/api/sdk/heartbeat", Endpoints.heartbeatUrl(null));
        assertEquals("https://app.avsb.cloud/api/sdk/stream", Endpoints.streamEndpointUrl(null));
    }

    @Test
    @DisplayName("no default host is one of the four that do not exist")
    void noForbiddenHosts() {
        String defaults = Endpoints.DEFAULT_CDN_HOST + Endpoints.DEFAULT_COLLECT_HOST
                + Endpoints.DEFAULT_APP_HOST;
        for (String forbidden : new String[]{
                "datafile.avsb.cloud", "stream.avsb.cloud", "sdk.avsb.cloud", "collect.avsb.cloud"}) {
            assertFalse(defaults.contains(forbidden),
                    forbidden + " does not resolve and must never be a default");
        }
    }
}
