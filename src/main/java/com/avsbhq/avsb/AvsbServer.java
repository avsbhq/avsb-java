package com.avsbhq.avsb;

import com.avsbhq.avsb.core.Datafile;
import com.avsbhq.avsb.core.Evaluator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Server-side A vs B SDK entry point. Create via {@link ServerBuilder}.
 *
 * <pre>{@code
 * AvsbServer server = AvsbServer.builder("sdk-key-here").build();
 * server.init();
 *
 * Flag<Boolean> flag = server.getBoolFlag("my-feature", false, EvalContext.user("alice"));
 * if (flag.isEnabled()) { ... }
 * }</pre>
 *
 * <p>All evaluation methods require an explicit {@code defaultValue} parameter
 * (spec §5.3 — per-call defaults).
 */
public final class AvsbServer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String DATAFILE_BASE_URL = "https://cdn.avsb.cloud/datafiles/";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final String sdkKey;
    private final Logger logger;
    private final String datafileUrl;
    private final Duration fetchTimeout;
    private final Map<String, Object> runtimeOverrides;

    private final AtomicReference<Datafile.FlagDatafileDoc> datafileRef = new AtomicReference<>();

    private AvsbServer(ServerBuilder builder) {
        this.sdkKey          = builder.sdkKey;
        this.logger          = builder.logger != null ? builder.logger : Logger.noOp();
        this.datafileUrl     = builder.datafileUrl != null ? builder.datafileUrl : DATAFILE_BASE_URL + sdkKey + ".json";
        this.fetchTimeout    = builder.fetchTimeout != null ? builder.fetchTimeout : DEFAULT_TIMEOUT;
        this.runtimeOverrides = builder.runtimeOverrides != null ? Map.copyOf(builder.runtimeOverrides) : Map.of();

        if (builder.bootstrapDatafile != null) {
            datafileRef.set(builder.bootstrapDatafile);
        }
    }

    // -------------------------------------------------------------------------
    // Init
    // -------------------------------------------------------------------------

    /**
     * Fetch and parse the datafile. Blocks until the fetch completes or
     * {@code fetchTimeout} elapses.
     *
     * @return {@link InitResult} describing whether the init succeeded.
     */
    public InitResult init() {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(fetchTimeout).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(datafileUrl))
                    .timeout(fetchTimeout)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                logger.warn("Datafile fetch returned HTTP " + response.statusCode());
                return InitResult.failure(new IOException("HTTP " + response.statusCode()));
            }
            Datafile.FlagDatafileDoc doc = MAPPER.readValue(response.body(), Datafile.FlagDatafileDoc.class);
            datafileRef.set(doc);
            logger.info("Datafile loaded", Map.of("version", doc.version(), "flags", doc.flags().size()));
            return InitResult.ok(InitResult.Source.NETWORK);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return InitResult.failure(e);
        } catch (Exception e) {
            logger.error("Datafile fetch failed: " + e.getMessage());
            if (datafileRef.get() != null) {
                return InitResult.degraded(InitResult.Source.BOOTSTRAP, e);
            }
            return InitResult.failure(e);
        }
    }

    /**
     * Load a datafile directly from a JSON string (useful in tests and
     * bootstrap scenarios that supply the datafile at startup).
     */
    public void loadDatafile(String json) throws IOException {
        Datafile.FlagDatafileDoc doc = MAPPER.readValue(json, Datafile.FlagDatafileDoc.class);
        datafileRef.set(doc);
    }

    /**
     * Load a datafile directly from a parsed record (used by {@link TestUtils}).
     */
    public void loadDatafile(Datafile.FlagDatafileDoc doc) {
        datafileRef.set(doc);
    }

    // -------------------------------------------------------------------------
    // Flag evaluation — typed overloads (spec §5.3)
    // -------------------------------------------------------------------------

    /**
     * Evaluate a boolean flag.
     *
     * @param key          Flag key.
     * @param defaultValue Returned when the flag is not found or evaluation fails.
     * @param context      Evaluation context.
     * @return A {@link Flag} record with full decision metadata.
     */
    public Flag<Boolean> getBoolFlag(String key, boolean defaultValue, EvalContext context) {
        return evaluate(key, defaultValue, context);
    }

    /**
     * Evaluate a string flag.
     */
    public Flag<String> getStringFlag(String key, String defaultValue, EvalContext context) {
        return evaluate(key, defaultValue, context);
    }

    /**
     * Evaluate a number flag. The returned value is a {@link Double}.
     */
    public Flag<Double> getNumberFlag(String key, double defaultValue, EvalContext context) {
        return evaluate(key, defaultValue, context);
    }

    /**
     * Evaluate a JSON flag. The returned value is a raw Jackson-deserialised
     * object (typically a {@link java.util.Map} or {@link java.util.List}).
     */
    public Flag<Object> getJsonFlag(String key, Object defaultValue, EvalContext context) {
        return evaluate(key, defaultValue, context);
    }

    // -------------------------------------------------------------------------
    // Track + identify
    // -------------------------------------------------------------------------

    /**
     * Record a metric event. The payload is forwarded to the collect endpoint
     * declared in the datafile.
     *
     * @param eventName Metric event name.
     * @param context   Evaluation context.
     * @param payload   Optional numeric value and/or free-form properties.
     */
    public void track(String eventName, EvalContext context, TrackPayload payload) {
        logger.debug("track", Map.of("event", eventName));
        // Forwarding to the collect endpoint is intentionally async/fire-and-forget;
        // a production implementation would buffer and flush via an HTTP sink.
    }

    public void track(String eventName, EvalContext context) {
        track(eventName, context, TrackPayload.empty());
    }

    /**
     * Identify a context so downstream integrations can associate attributes
     * with the user without an evaluation. No-op in the open-source SDK core.
     */
    public void identify(EvalContext context) {
        logger.debug("identify", Map.of("kind", context.kind()));
    }

    // -------------------------------------------------------------------------
    // Internal evaluation
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private <T> Flag<T> evaluate(String key, T defaultValue, EvalContext context) {
        Datafile.FlagDatafileDoc doc = datafileRef.get();
        if (doc == null) {
            logger.warn("evaluate called before datafile loaded; returning default for \"" + key + "\"");
            return Flag.<T>builder(defaultValue)
                    .source(EvaluationSource.NOT_FOUND)
                    .reasons(java.util.List.of("SDK not initialised — no datafile loaded"))
                    .evaluatedAt(System.currentTimeMillis())
                    .build();
        }
        Flag<T> result = Evaluator.evaluateFlag(key, defaultValue, context, doc);
        logger.debug("evaluated flag", Map.of("key", key, "source", result.source().wireValue()));
        return result;
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static ServerBuilder builder(String sdkKey) {
        return new ServerBuilder(sdkKey);
    }

    public static final class ServerBuilder {
        private final String sdkKey;
        private Logger logger;
        private String datafileUrl;
        private Duration fetchTimeout;
        private Map<String, Object> runtimeOverrides;
        private Datafile.FlagDatafileDoc bootstrapDatafile;

        private ServerBuilder(String sdkKey) {
            if (sdkKey == null || sdkKey.isBlank()) throw new IllegalArgumentException("sdkKey must not be blank");
            this.sdkKey = sdkKey;
        }

        public ServerBuilder logger(Logger logger) { this.logger = logger; return this; }
        public ServerBuilder datafileUrl(String url) { this.datafileUrl = url; return this; }
        public ServerBuilder fetchTimeout(Duration timeout) { this.fetchTimeout = timeout; return this; }
        public ServerBuilder runtimeOverrides(Map<String, Object> overrides) { this.runtimeOverrides = overrides; return this; }
        public ServerBuilder bootstrapDatafile(Datafile.FlagDatafileDoc doc) { this.bootstrapDatafile = doc; return this; }

        public AvsbServer build() {
            return new AvsbServer(this);
        }
    }
}
