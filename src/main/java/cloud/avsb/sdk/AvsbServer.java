package cloud.avsb.sdk;

import cloud.avsb.sdk.core.Bandits;
import cloud.avsb.sdk.core.Datafile;
import cloud.avsb.sdk.sticky.StickyBucketService;
import cloud.avsb.sdk.tracking.ServerTracker;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Server-side A vs B SDK. Thread safe; build one per process and share it.
 *
 * <pre>{@code
 * AvsbServer server = AvsbServer.builder("sdk_production_xxxxxxxxxxxx").build();
 * server.init();                                        // blocking, returns InitResult
 *
 * Flag<Boolean> flag = server.getBoolFlag("checkout_v2", false, EvalContext.user("alice"));
 * if (flag.isEnabled()) { renderNewCheckout(); }
 *
 * server.track("signup_completed", EvalContext.user("alice"));
 * server.close();                                       // flushes queued events
 * }</pre>
 *
 * <p>Full evaluation pipeline, in order. The first three steps live here, at the client
 * layer, exactly as {@code @avsbhq/node} arranges them; the rest are the pure evaluator.
 *
 * <ol>
 *   <li>Runtime override, from {@code builder().runtimeOverrides(...)}.</li>
 *   <li>Sticky lookup, when a {@link StickyBucketService} is configured.</li>
 *   <li>Datafile user override.</li>
 *   <li>Holdout.</li>
 *   <li>Rules in declared order, including bandit dispatch.</li>
 *   <li>The flag's default variation.</li>
 * </ol>
 *
 * <p>Before a datafile exists, every getter returns YOUR default value with source
 * {@link EvaluationSource#NOT_READY} and a reason naming what to call. It is not
 * {@code not_found}: "the flag does not exist" and "the SDK never started" are different
 * problems and used to be reported identically.
 */
public final class AvsbServer implements AutoCloseable {

    private static final Duration DEFAULT_FETCH_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(30);

    private final DecisionLog decisionLog;

    private final AtomicReference<Datafile.FlagDatafileDoc> datafileRef = new AtomicReference<>();
    private final AtomicReference<ServerTracker> trackerRef = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // Package-private collaborators that share the state above and do the work (FU-31).
    private final ServerTracking tracking;
    private final ServerEvaluation evaluation;
    private final ServerDatafile datafileLifecycle;

    private AvsbServer(ServerBuilder builder) {
        Logger logger = builder.logger != null ? builder.logger : Logger.defaultLogger();
        Duration fetchTimeout = builder.fetchTimeout != null ? builder.fetchTimeout : DEFAULT_FETCH_TIMEOUT;
        Duration pollInterval = builder.pollInterval != null ? builder.pollInterval : DEFAULT_POLL_INTERVAL;
        Map<String, String> runtimeOverrides = builder.runtimeOverrides != null
                ? Map.copyOf(builder.runtimeOverrides)
                : Map.of();
        this.decisionLog = new DecisionLog(builder.decisionLogCapacity);
        HttpClient http = HttpClient.newBuilder().connectTimeout(fetchTimeout).build();

        String datafileUrl = ServerDatafile.resolveUrl(builder.datafileUrl, builder.sdkKey, builder.cdnHost);

        this.tracking = new ServerTracking(
                builder.sdkKey, logger, builder.trackingEnabled, decisionLog, http, fetchTimeout,
                trackerRef, closed);
        this.evaluation = new ServerEvaluation(
                logger, runtimeOverrides, builder.stickyBucketService, builder.banditModelStore,
                datafileRef, trackerRef, closed, tracking);
        this.datafileLifecycle = new ServerDatafile(
                logger, datafileUrl, fetchTimeout, pollInterval, builder.pollingEnabled, http,
                datafileRef, closed, tracking);

        // Shape-check the key and say something actionable, then carry on: an unrecognised
        // key may still be valid, and the fetch is the real arbiter.
        SdkKeyShape.Result keyShape = SdkKeyShape.validate(builder.sdkKey);
        if (!keyShape.valid()) {
            logger.error(keyShape.message());
        }

        if (builder.bootstrapDatafile != null) loadDatafile(builder.bootstrapDatafile);
    }

    // ---- Lifecycle ----

    /**
     * Fetch and parse the datafile, then start the background refresh loop.
     *
     * <p>Blocks until the fetch completes or {@code fetchTimeout} elapses. The refresh loop
     * starts EVEN WHEN THE FIRST FETCH FAILS, so a network blip at deploy time costs one
     * polling interval instead of bricking flags until the process restarts.
     *
     * @return {@link InitResult#success()} true when a datafile is loaded. A failed fetch
     *         with a bootstrap datafile in hand resolves degraded (serving the bootstrap);
     *         a failed fetch with nothing in hand resolves {@code success=false}.
     */
    public InitResult init() {
        InitResult result = datafileLifecycle.fetchAndApply();
        datafileLifecycle.startRefreshLoop();
        return result;
    }

    /** Load a datafile from a JSON string. Used for bootstrap and in tests. */
    public void loadDatafile(String json) throws IOException {
        Datafile.FlagDatafileDoc doc = ServerDatafile.MAPPER.readValue(json, Datafile.FlagDatafileDoc.class);
        datafileRef.set(doc);
        tracking.startTrackerIfNeeded(doc);
    }

    /** Load an already parsed datafile. */
    public void loadDatafile(Datafile.FlagDatafileDoc doc) {
        datafileRef.set(doc);
        tracking.startTrackerIfNeeded(doc);
    }

    /** The datafile currently in effect, or null before one loads. */
    public Datafile.FlagDatafileDoc datafile() {
        return datafileRef.get();
    }

    /** True once a datafile is loaded and flags can be read. */
    public boolean isReady() {
        return datafileRef.get() != null;
    }

    /**
     * Stop the refresh loop and flush queued events.
     *
     * <p>Blocks for the final flush. After this returns, evaluation reports
     * {@link EvaluationSource#NOT_READY} rather than serving a stale datafile, because a
     * closed client answering questions is how "why is production still on the old
     * variation" starts.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        datafileLifecycle.stopRefreshLoop();
        tracking.closeTracker();
        datafileRef.set(null);
    }

    // ---- Flag evaluation ----

    /**
     * Evaluate a boolean flag.
     *
     * @param key          Flag key.
     * @param defaultValue Returned when the flag is missing, the SDK is not ready, or the
     *                     flag is not a boolean.
     * @param context      Evaluation context. Build it with {@link EvalContext#user}.
     */
    public Flag<Boolean> getBoolFlag(String key, boolean defaultValue, EvalContext context) {
        return getFlag(key, defaultValue, FlagValueType.BOOLEAN, context);
    }

    /** Evaluate a string flag. A non-string value returns {@code defaultValue}. */
    public Flag<String> getStringFlag(String key, String defaultValue, EvalContext context) {
        return getFlag(key, defaultValue, FlagValueType.STRING, context);
    }

    /**
     * Evaluate a number flag. Every number flag resolves to a {@link Double}, because the
     * reference engine has a single numeric type and cannot distinguish 3 from 3.0.
     */
    public Flag<Double> getNumberFlag(String key, double defaultValue, EvalContext context) {
        return getFlag(key, defaultValue, FlagValueType.NUMBER, context);
    }

    /**
     * Evaluate a JSON flag as a raw document: a {@link Map}, a {@link List}, a boxed
     * primitive, or null.
     */
    public Flag<Object> getJsonFlag(String key, Object defaultValue, EvalContext context) {
        return getFlag(key, defaultValue, FlagValueType.JSON, context);
    }

    /**
     * Evaluate a JSON flag into a type of your own.
     *
     * <pre>{@code
     * record Pricing(String plan, double amount) {}
     *
     * Flag<Pricing> pricing = server.getJsonFlag(
     *         "pricing", new Pricing("standard", 9.99),
     *         FlagValueType.json(Pricing.class), EvalContext.user("alice"));
     * }</pre>
     *
     * <p>A document that will not map into {@code type} returns {@code defaultValue} with
     * source {@link EvaluationSource#NOT_FOUND} and a reason, never an exception.
     */
    public <T> Flag<T> getJsonFlag(
            String key, T defaultValue, FlagValueType<T> type, EvalContext context) {
        return getFlag(key, defaultValue, type, context);
    }

    /**
     * Evaluate a flag against an explicit type token.
     *
     * <p>This is the one method the four typed getters delegate to, and the place the type
     * mismatch is caught. Java erases generics, so a blind {@code (T) raw} cast cannot fail
     * where it is written: it fails later, at the caller's own {@code flag.value()}, with a
     * {@link ClassCastException} pointing at application code. The type token moves the
     * check here, where the SDK can log the flag key, the declared type, and what it
     * actually got, and where the fallback is a real branch instead of dead code.
     */
    public <T> Flag<T> getFlag(
            String key, T defaultValue, FlagValueType<T> type, EvalContext context) {
        return evaluation.getFlag(key, defaultValue, type, context);
    }

    /**
     * Evaluate every flag in the datafile. Returns flag key to the raw JSON value, which is
     * what a server-rendered page needs to bootstrap a browser client.
     *
     * <p>Values are raw and untyped by design: a bootstrap payload carries whatever the
     * datafile holds, so no declared-type check applies. Use the typed getters when you are
     * branching on one flag.
     *
     * <p>Exposures fire for each decided flag, exactly as if each had been read
     * individually, so bootstrapping a page does not lose the experiment data.
     */
    public Map<String, Object> getAllFlags(EvalContext context) {
        return evaluation.getAllFlags(context);
    }

    // ---- Tracking ----

    /**
     * Record a conversion or metric event.
     *
     * <p>Queued and sent in batches to the ingest endpoint the datafile publishes, retried
     * with jittered backoff, and flushed by {@link #close()}. It used to log a debug line
     * and drop the event while the Javadoc claimed it was forwarded, which is worse than a
     * missing method: nothing told the caller their conversion data was being discarded.
     *
     * @param eventName Metric key as configured in the dashboard. An unknown key is stored,
     *                  so a typo shows up as an empty metric rather than an error.
     * @param context   Identity the conversion belongs to.
     * @param payload   Optional numeric value and revenue.
     */
    public void track(String eventName, EvalContext context, TrackPayload payload) {
        tracking.track(eventName, context, payload);
    }

    /** Record a conversion with no numeric value. */
    public void track(String eventName, EvalContext context) {
        track(eventName, context, TrackPayload.empty());
    }

    /**
     * Record a purchase. Sent immediately rather than batched, because an order is the event
     * a customer least wants buffered.
     *
     * @param context Identity the order belongs to.
     * @param order   Order details. {@code total} is decimal major units as displayed
     *                (99.95, not 9995) and must be greater than zero.
     */
    public void trackPurchase(EvalContext context, PurchaseOrder order) {
        tracking.trackPurchase(context, order);
    }

    /** Send anything queued now, without waiting for the flush timer. */
    public void flushEvents() {
        tracking.flushEvents();
    }

    /** The decision log for this client. Bounded; see {@link DecisionLog}. */
    public DecisionLog decisionLog() {
        return decisionLog;
    }

    // ---- Builder ----

    /** Start building a client. The SDK key is the only required input. */
    public static ServerBuilder builder(String sdkKey) {
        return new ServerBuilder(sdkKey);
    }

    /** Fluent builder for {@link AvsbServer}. Every option has a documented default. */
    public static final class ServerBuilder {
        private final String sdkKey;
        private Logger logger;
        private String datafileUrl;
        private String cdnHost;
        private Duration fetchTimeout;
        private Duration pollInterval;
        private boolean pollingEnabled = true;
        private Map<String, String> runtimeOverrides;
        private Datafile.FlagDatafileDoc bootstrapDatafile;
        private StickyBucketService stickyBucketService;
        private Bandits.ModelStore banditModelStore;
        private int decisionLogCapacity = DecisionLog.DEFAULT_CAPACITY;
        private boolean trackingEnabled = true;

        private ServerBuilder(String sdkKey) {
            if (sdkKey == null || sdkKey.isBlank()) {
                throw new IllegalArgumentException(
                        "sdkKey must not be blank. Pass the environment SDK key (it looks like"
                                + " \"sdk_production_xxxxxxxx\"), which you can copy from Settings,"
                                + " then Environments, in the A vs B dashboard.");
            }
            this.sdkKey = sdkKey;
        }

        /** Where SDK logs go. Default: warnings and errors to standard error. */
        public ServerBuilder logger(Logger logger) { this.logger = logger; return this; }

        /**
         * Override the whole datafile URL. Prefer {@link #cdnHost(String)} unless you are
         * pointing at a file this SDK did not publish.
         */
        public ServerBuilder datafileUrl(String url) { this.datafileUrl = url; return this; }

        /**
         * Serve the datafile from a different origin, for a self-hosted proxy or local
         * development. The path shape is unchanged: {@code {cdnHost}/{sdkKey}/datafile.json}.
         */
        public ServerBuilder cdnHost(String cdnHost) { this.cdnHost = cdnHost; return this; }

        /** Per-request HTTP timeout for datafile fetches. Default 10 seconds. */
        public ServerBuilder fetchTimeout(Duration timeout) { this.fetchTimeout = timeout; return this; }

        /** How often the background loop refetches the datafile. Default 30 seconds. */
        public ServerBuilder pollInterval(Duration interval) { this.pollInterval = interval; return this; }

        /**
         * Turn the background refresh loop off. Default on. With it off, a flag change needs
         * a process restart, so only do this when something else calls
         * {@link #loadDatafile(String)}.
         */
        public ServerBuilder polling(boolean enabled) { this.pollingEnabled = enabled; return this; }

        /**
         * Force flags for this process: flag key to VARIATION KEY, resolved against the
         * datafile. A variation key that does not exist is logged and ignored.
         */
        public ServerBuilder runtimeOverrides(Map<String, String> overrides) {
            this.runtimeOverrides = overrides;
            return this;
        }

        /**
         * Serve this datafile immediately, so flags work before the first fetch completes.
         * A later fetch replaces it.
         */
        public ServerBuilder bootstrapDatafile(Datafile.FlagDatafileDoc doc) {
            this.bootstrapDatafile = doc;
            return this;
        }

        /** Keep users in the variation they were first assigned. Default: not configured. */
        public ServerBuilder stickyBucketService(StickyBucketService service) {
            this.stickyBucketService = service;
            return this;
        }

        /**
         * Learned bandit models. Default: none, in which case a bandit rule still dispatches
         * using a deterministic cold-start model derived from the datafile.
         */
        public ServerBuilder banditModelStore(Bandits.ModelStore store) {
            this.banditModelStore = store;
            return this;
        }

        /**
         * How many decision-log entries to retain. Default
         * {@link DecisionLog#DEFAULT_CAPACITY}; zero turns recording off.
         */
        public ServerBuilder decisionLogCapacity(int capacity) {
            this.decisionLogCapacity = capacity;
            return this;
        }

        /**
         * Turn event sending off. Default on. With it off, {@code track} and exposure
         * recording become no-ops that say so, and server-side experiments produce no
         * results.
         */
        public ServerBuilder tracking(boolean enabled) { this.trackingEnabled = enabled; return this; }

        public AvsbServer build() {
            return new AvsbServer(this);
        }
    }
}
