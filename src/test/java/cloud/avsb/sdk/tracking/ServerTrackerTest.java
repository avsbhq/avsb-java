package cloud.avsb.sdk.tracking;

import cloud.avsb.sdk.ExposureEvent;
import cloud.avsb.sdk.Logger;
import cloud.avsb.sdk.MetricEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tracker behaviour and the exact wire bodies.
 *
 * <p>The bodies are asserted field by field against
 * {@code packages/avsb-conformance/fixtures/wire/manifest.json}, because the field names are
 * frozen vocabulary: the ingestion worker validates these names and the dashboard reads the
 * columns they land in, so a renamed field is silently dropped data rather than a build
 * error. Two subtleties the fixtures call out and this asserts: {@code revenue} is required
 * and nullable, while {@code value}, {@code properties}, and {@code attributes} are optional
 * and must be OMITTED rather than sent as null (the ingestion schema treats them as optional,
 * not nullable, so a null fails the whole batch).
 *
 * <p>Every tracker here points at a dead local port with retries off, so nothing leaves the
 * machine and a flush fails immediately.
 */
@DisplayName("ServerTracker — batching, bounds, and wire bodies")
class ServerTrackerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SDK_KEY = "sdk_production_abc123";
    /** Nothing listens here, so a send fails at once instead of reaching the network. */
    private static final String DEAD_ENDPOINT = "http://127.0.0.1:1";

    private static ServerTracker.Options options(int batchSize, int maxQueueSize, Logger logger) {
        return new ServerTracker.Options(
                SDK_KEY, DEAD_ENDPOINT,
                Duration.ofHours(1),      // the timer must not fire during a test
                batchSize, maxQueueSize,
                0,                        // no retries: one attempt, then the loud failure
                Duration.ofMillis(250),
                logger);
    }

    private static ExposureEvent exposure(String eventId, String visitorId) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("flagKey", "checkout-v2");
        properties.put("flagId", "flag_ab_1");
        return new ExposureEvent(eventId, visitorId, "rule_ab_split", "var_treatment",
                ExposureEvent.FLAG_EXPOSURE, null, null, 1780000000000L, "",
                properties, Map.of("plan", "pro"));
    }

    // -------------------------------------------------------------------------
    // Wire bodies
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("an exposure batch carries sdkKey plus the eight required event fields")
    void exposureBatchBody() throws Exception {
        ServerTracker tracker = ServerTracker.start(options(1_000, 1_000, Logger.noOp()));
        String body = tracker.exposureBatchBody(List.of(exposure("evt-1", "user_42")));

        JsonNode json = MAPPER.readTree(body);
        assertEquals(SDK_KEY, json.get("sdkKey").asText(),
                "ingest routes are not key-pathed, so the key rides in the envelope");
        assertTrue(json.get("events").isArray());

        JsonNode event = json.get("events").get(0);
        assertEquals("evt-1", event.get("eventId").asText());
        assertEquals("user_42", event.get("visitorId").asText());
        assertEquals("rule_ab_split", event.get("experimentId").asText());
        assertEquals("var_treatment", event.get("variationId").asText());
        assertEquals("flag_exposure", event.get("eventName").asText());
        assertTrue(event.has("revenue"), "revenue is required even when it is null");
        assertTrue(event.get("revenue").isNull(), "an exposure carries no money");
        assertEquals(1780000000000L, event.get("timestamp").asLong());
        assertEquals("", event.get("pageUrl").asText(), "there is no page on a server");
        assertEquals("checkout-v2", event.get("properties").get("flagKey").asText());
        assertEquals("pro", event.get("attributes").get("plan").asText());
        assertFalse(event.has("value"), "an absent optional field is omitted, never sent as null");
    }

    @Test
    @DisplayName("a metric batch uses metricEvents and carries no properties field")
    void metricBatchBody() throws Exception {
        ServerTracker tracker = ServerTracker.start(options(1_000, 1_000, Logger.noOp()));
        String body = tracker.metricBatchBody(List.of(new MetricEvent(
                "evt-2", "user_42", "signup_completed", null, 1.0, 1780000000000L, "")));

        JsonNode json = MAPPER.readTree(body);
        assertEquals(SDK_KEY, json.get("sdkKey").asText());
        JsonNode event = json.get("metricEvents").get(0);
        assertEquals("evt-2", event.get("eventId").asText());
        assertEquals("signup_completed", event.get("eventName").asText());
        assertTrue(event.get("revenue").isNull());
        assertEquals(1.0, event.get("value").asDouble());
        // The metric batch has no properties field: anything sent under that name is stripped
        // before storage, so it would look delivered and be gone.
        assertFalse(event.has("properties"));
        assertFalse(event.has("experimentId"), "attribution happens server-side from exposures");
    }

    @Test
    @DisplayName("revenue rides in the metric body when the caller supplies it")
    void metricRevenue() throws Exception {
        ServerTracker tracker = ServerTracker.start(options(1_000, 1_000, Logger.noOp()));
        String body = tracker.metricBatchBody(List.of(new MetricEvent(
                "evt-3", "user_42", "purchase", 99.95, null, 1780000000000L, "")));
        JsonNode event = MAPPER.readTree(body).get("metricEvents").get(0);
        assertEquals(99.95, event.get("revenue").asDouble());
        assertFalse(event.has("value"));
    }

    // -------------------------------------------------------------------------
    // Queue behaviour
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("an event without an id is stamped once at enqueue, so retries deduplicate")
    void stampsEventIdAtEnqueue() {
        ServerTracker tracker = ServerTracker.start(options(1_000, 1_000, Logger.noOp()));
        assertNull(exposure(null, "user_42").eventId(), "the caller did not supply one");

        tracker.queue(exposure(null, "user_42"));

        List<ExposureEvent> queued = tracker.snapshotExposures();
        assertEquals(1, queued.size());
        String stamped = queued.get(0).eventId();
        assertNotNull(stamped, "the id is stamped at enqueue, not at send");
        assertFalse(stamped.isEmpty());

        // Stamping at enqueue rather than at send is what makes a retried batch carry the
        // same ids, so ingestion deduplicates instead of double counting a conversion.
        tracker.queue(exposure(null, "user_43"));
        assertNotEquals(stamped, tracker.snapshotExposures().get(1).eventId());
    }

    @Test
    @DisplayName("a caller-supplied event id is preserved rather than replaced")
    void preservesSuppliedEventId() {
        ServerTracker tracker = ServerTracker.start(options(1_000, 1_000, Logger.noOp()));
        tracker.queue(exposure("evt-supplied", "user_42"));
        assertEquals("evt-supplied", tracker.snapshotExposures().get(0).eventId());
    }

    @Test
    @DisplayName("the queue is bounded and says how many events it dropped")
    void queueIsBounded() {
        List<String> warnings = new ArrayList<>();
        ServerTracker tracker = ServerTracker.start(options(1_000, 3, new CollectingLogger(warnings)));

        for (int i = 0; i < 6; i++) {
            tracker.queue(exposure("evt-" + i, "user_" + i));
        }

        assertEquals(3, tracker.queuedExposures(), "a broken endpoint cannot grow the heap");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("queue full")),
                "dropping data silently is the failure mode this replaces: " + warnings);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("dropping 1 oldest")),
                "the warning counts what went: " + warnings);
    }

    @Test
    @DisplayName("close flushes what is queued instead of discarding the last batch")
    void closeFlushes() {
        List<String> messages = new ArrayList<>();
        ServerTracker tracker = ServerTracker.start(options(1_000, 1_000, new CollectingLogger(messages)));
        tracker.queue(exposure("evt-1", "user_42"));

        tracker.close();

        assertEquals(0, tracker.queuedExposures(), "the queue was drained");
        // The send fails because the endpoint is dead, and it says so with the count. Before
        // this, a process exiting threw the last batch away with no trace at all.
        assertTrue(messages.stream().anyMatch(m -> m.contains("Failed to send 1 exposures")),
                "a failed flush must name what was lost: " + messages);
    }

    @Test
    @DisplayName("close is idempotent and a queue after close is a no-op")
    void closeIsIdempotent() {
        ServerTracker tracker = ServerTracker.start(options(1_000, 1_000, Logger.noOp()));
        tracker.close();
        tracker.close();
        tracker.queue(exposure("evt-late", "user_42"));
        assertEquals(0, tracker.queuedExposures());
    }

    // -------------------------------------------------------------------------
    // Backoff
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("backoff climbs, caps, and jitters within twenty percent")
    void backoffCurve() {
        Backoff.State first = Backoff.next(null, null);
        assertEquals(1, first.attempt());
        assertTrue(first.delayMs() >= 800 && first.delayMs() <= 1200,
                "one second plus or minus twenty percent, got " + first.delayMs());

        Backoff.State second = Backoff.next(first, null);
        assertEquals(2, second.attempt());
        assertTrue(second.delayMs() >= 1600 && second.delayMs() <= 2400,
                "two seconds plus or minus twenty percent, got " + second.delayMs());

        Backoff.State late = new Backoff.State(20, 60_000);
        Backoff.State capped = Backoff.next(late, null);
        assertTrue(capped.delayMs() <= 72_000, "capped at a minute plus jitter");
    }

    @Test
    @DisplayName("a server Retry-After wins for one attempt and the counter still advances")
    void retryAfterWins() {
        Backoff.State state = Backoff.next(null, 5_000L);
        assertEquals(5_000, state.delayMs());
        assertEquals(1, state.attempt());

        Backoff.State next = Backoff.next(state, null);
        assertEquals(2, next.attempt(), "the next miss climbs the curve rather than restarting");
    }

    @Test
    @DisplayName("only the numeric Retry-After form is honoured")
    void retryAfterParsing() {
        assertEquals(5_000L, Backoff.parseRetryAfter("5"));
        assertEquals(1_500L, Backoff.parseRetryAfter("1.5"));
        assertNull(Backoff.parseRetryAfter(null));
        assertNull(Backoff.parseRetryAfter(""));
        assertNull(Backoff.parseRetryAfter("-1"));
        assertNull(Backoff.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"),
                "the HTTP-date form is not supported, and guessing would be worse");
    }

    /** Captures logged messages so loud-failure contracts can be asserted. */
    private static final class CollectingLogger implements Logger {
        private final List<String> messages;

        CollectingLogger(List<String> messages) {
            this.messages = messages;
        }

        @Override public synchronized void debug(String m, Map<String, Object> d) { messages.add(m); }
        @Override public synchronized void info(String m, Map<String, Object> d) { messages.add(m); }
        @Override public synchronized void warn(String m, Map<String, Object> d) { messages.add(m); }
        @Override public synchronized void error(String m, Map<String, Object> d) { messages.add(m); }
    }
}
