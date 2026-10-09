package com.avsbhq.avsb;

import com.avsbhq.avsb.core.Bandits;
import com.avsbhq.avsb.core.Context;
import com.avsbhq.avsb.core.Datafile;
import com.avsbhq.avsb.core.EvaluateOptions;
import com.avsbhq.avsb.core.Evaluator;
import com.avsbhq.avsb.sticky.StickyBucketService;
import com.avsbhq.avsb.tracking.ServerTracker;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
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

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final Duration DEFAULT_FETCH_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(30);
    /** Plus or minus this fraction of the poll interval, so fleets do not fetch in lockstep. */
    private static final double POLL_JITTER = 0.1;

    private final String sdkKey;
    private final Logger logger;
    private final String datafileUrl;
    private final Duration fetchTimeout;
    private final Duration pollInterval;
    private final boolean pollingEnabled;
    private final Map<String, String> runtimeOverrides;
    private final StickyBucketService stickyBucketService;
    private final Bandits.ModelStore banditModelStore;
    private final DecisionLog decisionLog;
    private final boolean trackingEnabled;
    private final HttpClient http;

    private final AtomicReference<Datafile.FlagDatafileDoc> datafileRef = new AtomicReference<>();
    private final AtomicReference<ServerTracker> trackerRef = new AtomicReference<>();
    private final AtomicBoolean emptyContextWarned = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile ScheduledExecutorService refreshScheduler;

    private AvsbServer(ServerBuilder builder) {
        this.sdkKey = builder.sdkKey;
        this.logger = builder.logger != null ? builder.logger : Logger.defaultLogger();
        this.fetchTimeout = builder.fetchTimeout != null ? builder.fetchTimeout : DEFAULT_FETCH_TIMEOUT;
        this.pollInterval = builder.pollInterval != null ? builder.pollInterval : DEFAULT_POLL_INTERVAL;
        this.pollingEnabled = builder.pollingEnabled;
        this.runtimeOverrides = builder.runtimeOverrides != null
                ? Map.copyOf(builder.runtimeOverrides)
                : Map.of();
        this.stickyBucketService = builder.stickyBucketService;
        this.banditModelStore = builder.banditModelStore;
        this.decisionLog = new DecisionLog(builder.decisionLogCapacity);
        this.trackingEnabled = builder.trackingEnabled;
        this.http = HttpClient.newBuilder().connectTimeout(this.fetchTimeout).build();

        // The datafile URL comes from the endpoint contract, never from string surgery.
        // Every previous shape ("/datafiles/{key}.json" here, "/v1/datafiles/..." in .NET,
        // "datafile.avsb.cloud/v2/..." in Go) 404s, which is why nothing initialised.
        this.datafileUrl = builder.datafileUrl != null && !builder.datafileUrl.isBlank()
                ? builder.datafileUrl
                : Endpoints.datafileUrl(this.sdkKey, builder.cdnHost);

        // Shape-check the key and say something actionable, then carry on: an unrecognised
        // key may still be valid, and the fetch is the real arbiter.
        SdkKeyShape.Result keyShape = SdkKeyShape.validate(this.sdkKey);
        if (!keyShape.valid()) {
            this.logger.error(keyShape.message());
        }

        if (builder.bootstrapDatafile != null) {
            datafileRef.set(builder.bootstrapDatafile);
            startTrackerIfNeeded(builder.bootstrapDatafile);
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

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
        InitResult result = fetchAndApply();
        startRefreshLoop();
        return result;
    }

    /** Load a datafile from a JSON string. Used for bootstrap and in tests. */
    public void loadDatafile(String json) throws IOException {
        Datafile.FlagDatafileDoc doc = MAPPER.readValue(json, Datafile.FlagDatafileDoc.class);
        datafileRef.set(doc);
        startTrackerIfNeeded(doc);
    }

    /** Load an already parsed datafile. */
    public void loadDatafile(Datafile.FlagDatafileDoc doc) {
        datafileRef.set(doc);
        startTrackerIfNeeded(doc);
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
        ScheduledExecutorService scheduler = refreshScheduler;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        ServerTracker tracker = trackerRef.getAndSet(null);
        if (tracker != null) {
            tracker.close();
        }
        datafileRef.set(null);
    }

    // -------------------------------------------------------------------------
    // Flag evaluation
    // -------------------------------------------------------------------------

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
        Datafile.FlagDatafileDoc doc = datafileRef.get();
        if (doc == null) {
            String reason = closed.get()
                    ? "AvsB SDK is closed, so flag \"" + key + "\" cannot be evaluated. Build a"
                            + " new AvsbServer if you need flags again."
                    : "AvsB SDK is not ready: no datafile is loaded, so flag \"" + key + "\" fell"
                            + " back to your default. Call server.init() (or pass"
                            + " bootstrapDatafile) before evaluating.";
            logger.warn(reason);
            return Flag.notReady(defaultValue, reason);
        }

        EvalContext ctx = context != null ? context : EvalContext.user("");
        warnOnEmptyContextOnce(ctx);

        Flag<Object> raw = evaluateRaw(doc, key, defaultValue, ctx);
        Flag<T> typed = coerce(key, defaultValue, type, raw, doc);
        recordDecision(key, ctx, typed);
        return typed;
    }

    /**
     * The three client-layer steps plus the evaluator, with no type coercion. This is the
     * one path every read goes through.
     */
    private Flag<Object> evaluateRaw(
            Datafile.FlagDatafileDoc doc, String key, Object defaultValue, EvalContext ctx) {
        // 1. Runtime override, per flag key, resolved against the datafile's variations.
        Flag<Object> raw = resolveRuntimeOverride(doc, key);

        // 2. Sticky lookup, before the evaluator, so a stored assignment beats a rule and
        //    even beats a datafile override (the node pattern, locked by the corpus).
        if (raw == null) {
            raw = resolveSticky(doc, key, ctx);
        }

        // 3. The evaluator. The caller's default goes in, so a datafile that cannot answer
        //    (missing key, broken defaultVariationId) hands back the caller's own value
        //    with the real source rather than something invented.
        if (raw == null) {
            raw = Evaluator.evaluateFlag(key, defaultValue, ctx, doc, evaluateOptions());
            persistStickyAssignment(doc, key, ctx, raw);
        }
        return raw;
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
        Datafile.FlagDatafileDoc doc = datafileRef.get();
        if (doc == null) {
            logger.warn("AvsB SDK is not ready: getAllFlags returned an empty map because no"
                    + " datafile is loaded. Call server.init() first.");
            return Map.of();
        }
        EvalContext ctx = context != null ? context : EvalContext.user("");
        warnOnEmptyContextOnce(ctx);
        Map<String, Object> out = new LinkedHashMap<>();
        List<Datafile.FlagEntry> flags = doc.flags() == null ? List.of() : doc.flags();
        for (Datafile.FlagEntry flag : flags) {
            Flag<Object> resolved = evaluateRaw(doc, flag.key(), null, ctx);
            recordDecision(flag.key(), ctx, resolved);
            out.put(flag.key(), resolved.value());
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Tracking
    // -------------------------------------------------------------------------

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
        ServerTracker tracker = trackerRef.get();
        if (tracker == null) {
            logger.warn("track(\"" + eventName + "\") was dropped because event sending is not"
                    + " running: either the SDK has no datafile yet (call server.init() first) or"
                    + " tracking was disabled on the builder.");
            return;
        }
        TrackPayload effective = payload != null ? payload : TrackPayload.empty();
        EvalContext ctx = effective.context() != null ? effective.context() : context;
        String visitorId = ctx != null ? Context.bucketingKey(ctx) : "";
        tracker.queueMetric(new MetricEvent(
                null,
                visitorId,
                eventName,
                effective.revenue(),
                effective.value(),
                System.currentTimeMillis(),
                ""));
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
        ServerTracker tracker = trackerRef.get();
        if (tracker == null) {
            logger.warn("trackPurchase(\"" + (order == null ? "" : order.orderId()) + "\") was"
                    + " dropped because event sending is not running: either the SDK has no"
                    + " datafile yet (call server.init() first) or tracking was disabled on the"
                    + " builder.");
            return;
        }
        tracker.trackPurchase(context != null ? Context.bucketingKey(context) : "", order);
    }

    /** Send anything queued now, without waiting for the flush timer. */
    public void flushEvents() {
        ServerTracker tracker = trackerRef.get();
        if (tracker != null) {
            tracker.flush();
        }
    }

    /** The decision log for this client. Bounded; see {@link DecisionLog}. */
    public DecisionLog decisionLog() {
        return decisionLog;
    }

    // -------------------------------------------------------------------------
    // Internals: evaluation layers
    // -------------------------------------------------------------------------

    private EvaluateOptions evaluateOptions() {
        ServerTracker tracker = trackerRef.get();
        return new EvaluateOptions(tracker == null ? null : tracker::queue, banditModelStore);
    }

    /**
     * A runtime override is a flag key mapped to a VARIATION KEY, resolved against the
     * datafile so the served value and its key are real. A key that names no variation is
     * logged and ignored rather than serving something invented.
     */
    private Flag<Object> resolveRuntimeOverride(Datafile.FlagDatafileDoc doc, String flagKey) {
        String variationKey = runtimeOverrides.get(flagKey);
        if (variationKey == null) {
            return null;
        }
        Datafile.FlagEntry flag = findFlag(doc, flagKey);
        if (flag == null || flag.variations() == null) {
            logger.warn("Runtime override for flag \"" + flagKey + "\" was ignored: that flag is"
                    + " not in the datafile for this environment.");
            return null;
        }
        for (Datafile.VariationEntry variation : flag.variations()) {
            if (variationKey.equals(variation.key())) {
                return Flag.builder(variation.value())
                        .variationKey(variation.key())
                        .source(EvaluationSource.RUNTIME_OVERRIDE)
                        .reasons(List.of("matched runtime override for flagKey=\"" + flagKey + "\""))
                        .build();
            }
        }
        logger.warn("Runtime override for flag \"" + flagKey + "\" was ignored: variation \""
                + variationKey + "\" is not one of that flag's variations ("
                + variationKeys(flag) + ").");
        return null;
    }

    /**
     * Sticky lookup. Runs BEFORE the evaluator, so a stored assignment wins over rules and
     * over a datafile override: a user who was already bucketed stays bucketed.
     *
     * <p>Two cases the contract calls out:
     * <ul>
     *   <li>Stored variation no longer on the flag: treat the lookup as a MISS and fall
     *       through. The variation was deleted; serving it is impossible.</li>
     *   <li>Stored variation still exists but its rule is gone: still serve the sticky
     *       value, warn, and SUPPRESS the exposure, because the rule it would be attributed
     *       to no longer exists.</li>
     * </ul>
     */
    private Flag<Object> resolveSticky(
            Datafile.FlagDatafileDoc doc, String flagKey, EvalContext ctx) {
        if (stickyBucketService == null) {
            return null;
        }
        String userId = Context.bucketingKey(ctx);
        if (userId == null || userId.isEmpty()) {
            return null;
        }
        StickyAssignment assignment = stickyBucketService.lookup(userId, flagKey);
        if (assignment == null) {
            return null;
        }
        Datafile.FlagEntry flag = findFlag(doc, flagKey);
        if (flag == null || flag.variations() == null) {
            return null;
        }
        Datafile.VariationEntry stored = null;
        for (Datafile.VariationEntry variation : flag.variations()) {
            if (variation.id().equals(assignment.variationId())) {
                stored = variation;
                break;
            }
        }
        if (stored == null) {
            // Deleted variation: a miss, not an error.
            return null;
        }

        boolean ruleStillPresent = false;
        if (flag.rules() != null) {
            for (Datafile.RuleEntry rule : flag.rules()) {
                if (!rule.id().equals(assignment.ruleId())) continue;
                if (rule.variations() == null) continue;
                for (Datafile.RuleVariation rv : rule.variations()) {
                    if (stored.id().equals(rv.variationId())) {
                        ruleStillPresent = true;
                        break;
                    }
                }
                break;
            }
        }
        if (!ruleStillPresent) {
            logger.warn("Sticky assignment for flag \"" + flagKey + "\" references a rule that no"
                    + " longer serves that variation, so the stored value is being served without"
                    + " an exposure event.", Map.of(
                    "flagKey", flagKey,
                    "userId", userId,
                    "assignedRuleId", String.valueOf(assignment.ruleId()),
                    "assignedVariationId", String.valueOf(assignment.variationId())));
        }

        return Flag.builder(stored.value())
                .variationKey(stored.key())
                .source(EvaluationSource.STICKY)
                .ruleId(assignment.ruleId())
                .ruleType(assignment.ruleType())
                .reasons(List.of("matched sticky bucket assignment for userId=\"" + userId + "\""))
                .build();
    }

    /**
     * Persist a sticky assignment. ONLY for an ab_test rule that served a variation: a
     * targeted delivery is a rollout, not an assignment, and a holdout or an override is
     * not the user's bucket.
     *
     * <p>The stored field is the variation's ID, not its key. Saving the key corrupts the
     * store, because lookup resolves by id and never matches again.
     */
    private void persistStickyAssignment(
            Datafile.FlagDatafileDoc doc, String flagKey, EvalContext ctx, Flag<Object> result) {
        if (stickyBucketService == null) {
            return;
        }
        if (result.source() != EvaluationSource.RULE
                || result.ruleType() != RuleType.AB_TEST
                || result.ruleId() == null
                || result.variationKey() == null) {
            return;
        }
        String userId = Context.bucketingKey(ctx);
        if (userId == null || userId.isEmpty()) {
            return;
        }
        Datafile.FlagEntry flag = findFlag(doc, flagKey);
        if (flag == null || flag.variations() == null) {
            return;
        }
        for (Datafile.VariationEntry variation : flag.variations()) {
            if (result.variationKey().equals(variation.key())) {
                stickyBucketService.save(userId, flagKey, new StickyAssignment(
                        variation.id(),
                        result.ruleId(),
                        result.ruleType(),
                        System.currentTimeMillis()));
                return;
            }
        }
    }

    /**
     * Apply the type token, then the datafile's declared type. A mismatch on either returns
     * the caller's default with a reason, and warns once per evaluation.
     */
    private <T> Flag<T> coerce(
            String key, T defaultValue, FlagValueType<T> type, Flag<Object> raw,
            Datafile.FlagDatafileDoc doc) {
        // findFlag returns null for an absent key, which is also the only case with no
        // declared type to check. The value in hand is then the caller's own default, so
        // the coercion below passes and the evaluator's source and reason survive.
        Datafile.FlagEntry entry = findFlag(doc, key);
        if (entry != null && !type.matchesDeclaredType(entry.type())) {
            String reason = "flag \"" + key + "\" is declared as \"" + entry.type()
                    + "\" in the datafile but a \"" + type.declaredType()
                    + "\" value was requested; returning your default. Use get"
                    + getterName(entry.type()) + "Flag instead.";
            logger.warn(reason);
            return Flag.notFound(defaultValue, reason);
        }

        FlagValueType.Coerced<T> coerced = type.coerce(raw.value());
        if (!coerced.matched()) {
            String reason = "flag \"" + key + "\" " + coerced.mismatch()
                    + "; returning your default.";
            logger.warn(reason);
            return Flag.notFound(defaultValue, reason);
        }

        T value = coerced.value() != null ? coerced.value() : defaultValue;
        return Flag.builder(value)
                .variationKey(raw.variationKey())
                .source(raw.source())
                .ruleId(raw.ruleId())
                .ruleType(raw.ruleType())
                .reasons(raw.reasons())
                .evaluatedAt(raw.evaluatedAt())
                .durationMicros(raw.durationMicros())
                .build();
    }

    private void recordDecision(String flagKey, EvalContext ctx, Flag<?> flag) {
        if (decisionLog.capacity() == 0) {
            return;
        }
        Set<String> privateAttrs = privateAttributesFrom(ctx);
        decisionLog.append(new DecisionLogEntry(
                flagKey,
                flag.value(),
                flag.variationKey(),
                flag.source(),
                flag.ruleId(),
                flag.ruleType(),
                flag.reasons(),
                flag.evaluatedAt(),
                flag.durationMicros(),
                Context.contextSummary(ctx),
                false), privateAttrs);
    }

    /**
     * Warn once per client about an empty bucketing key. Every caller who forgets the
     * context lands the whole fleet in one bucket, which corrupts an experiment invisibly,
     * so the message names the exact call to add.
     */
    private void warnOnEmptyContextOnce(EvalContext ctx) {
        String key = Context.bucketingKey(ctx);
        if (key != null && !key.isEmpty()) {
            return;
        }
        if (emptyContextWarned.compareAndSet(false, true)) {
            logger.warn("AvsB evaluated a flag with an empty bucketing key, so every request will"
                    + " land in the same bucket and experiment results will be wrong. Pass a real"
                    + " identity: EvalContext.user(\"<your user id>\").");
        }
    }

    // -------------------------------------------------------------------------
    // Internals: datafile transport
    // -------------------------------------------------------------------------

    private InitResult fetchAndApply() {
        boolean hadDatafile = datafileRef.get() != null;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(datafileUrl))
                    .timeout(fetchTimeout)
                    // Datafile reads are keyless by path: the SDK key is the path segment.
                    // No Authorization header, no X-SDK-Key, nothing to leak in a proxy log.
                    .header("User-Agent", SdkMetadata.userAgent())
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                logger.error(fetchFailureMessage("HTTP " + response.statusCode(),
                        response.statusCode()));
                return hadDatafile
                        ? InitResult.degraded(InitResult.Source.BOOTSTRAP,
                                new IOException("HTTP " + response.statusCode()))
                        : InitResult.failure(new IOException("HTTP " + response.statusCode()));
            }
            Datafile.FlagDatafileDoc doc =
                    MAPPER.readValue(response.body(), Datafile.FlagDatafileDoc.class);
            datafileRef.set(doc);
            logger.info("Datafile loaded", Map.of(
                    "version", doc.version(),
                    "flags", doc.flags() == null ? 0 : doc.flags().size()));
            startTrackerIfNeeded(doc);
            sendHeartbeat(doc);
            return InitResult.ok(InitResult.Source.NETWORK);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return InitResult.failure(e);
        } catch (Exception e) {
            logger.error(fetchFailureMessage(String.valueOf(e.getMessage()), 0));
            return hadDatafile
                    ? InitResult.degraded(InitResult.Source.BOOTSTRAP, e)
                    : InitResult.failure(e);
        }
    }

    /**
     * A datafile failure names the URL tried, what came back, and hands over a curl that
     * reproduces it. Three strings is the difference between "flags do not work" and a fix.
     */
    private String fetchFailureMessage(String what, int status) {
        StringBuilder message = new StringBuilder();
        message.append("AvsB datafile fetch failed: ").append(what)
                .append(" for ").append(datafileUrl).append(". ");
        if (status == 404) {
            message.append("A 404 means the key is not published in this environment: check the"
                    + " SDK key against Environments in the project sidebar, in the A vs B dashboard, and"
                    + " publish the environment if it has never been published. ");
        } else if (status == 403) {
            message.append("A 403 usually means a proxy or firewall in front of cdn.avsb.cloud is"
                    + " blocking the request. ");
        } else if (status == 0) {
            message.append("No HTTP status came back, so this is a network, DNS, TLS, or timeout"
                    + " problem rather than a rejected request. ");
        }
        message.append("Reproduce it with: curl -i ").append(datafileUrl);
        message.append(" . Until a datafile loads, every flag returns the default you passed and"
                + " reports source not_ready.");
        return message.toString();
    }

    private void startRefreshLoop() {
        if (!pollingEnabled || closed.get() || refreshScheduler != null) {
            return;
        }
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "avsb-datafile-refresh");
            // Daemon: the refresh loop must never keep a JVM alive on its own.
            thread.setDaemon(true);
            return thread;
        });
        refreshScheduler = scheduler;
        long baseMs = Math.max(1_000, pollInterval.toMillis());
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                // Jitter each cycle so a fleet that booted together does not stampede the
                // CDN on the same second forever.
                long jitter = (long) (baseMs * POLL_JITTER
                        * (ThreadLocalRandom.current().nextDouble() * 2 - 1));
                if (jitter > 0) {
                    Thread.sleep(jitter);
                }
                fetchAndApply();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                // A refresh failure must never kill the loop: the next cycle is the retry.
                logger.warn("Datafile refresh failed", Map.of("error", String.valueOf(e)));
            }
        }, baseMs, baseMs, TimeUnit.MILLISECONDS);
    }

    private void startTrackerIfNeeded(Datafile.FlagDatafileDoc doc) {
        if (!trackingEnabled || closed.get() || trackerRef.get() != null) {
            return;
        }
        ServerTracker tracker = ServerTracker.start(
                ServerTracker.Options.defaults(sdkKey, doc.collectEndpoint(), logger));
        if (!trackerRef.compareAndSet(null, tracker)) {
            tracker.close();
        }
    }

    /**
     * Report liveness after a successful datafile load. This is what turns an environment
     * from "Not connected" into "Connected" on the dashboard, so an SDK that never sends one
     * looks offline no matter how well it works.
     *
     * <p>Fire and forget: a heartbeat is diagnostics, and it must never delay or fail an
     * init. When the datafile carries no heartbeat endpoint, nothing is sent: a proxied
     * deployment publishes its own origin, and posting to the public app host would report
     * liveness nowhere while looking healthy.
     */
    private void sendHeartbeat(Datafile.FlagDatafileDoc doc) {
        String endpoint = Endpoints.resolveHeartbeatEndpoint(doc.heartbeatEndpoint());
        if (endpoint == null) {
            return;
        }
        String body = "{\"sdkKey\":\"" + sdkKey + "\",\"sdkType\":\"" + SdkMetadata.SDK_TYPE
                + "\",\"sdkVersion\":\"" + SdkMetadata.sdkVersion() + "\"}";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(fetchTimeout)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", SdkMetadata.userAgent())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(error -> {
                        logger.debug("Heartbeat failed", Map.of("error", String.valueOf(error)));
                        return null;
                    });
        } catch (RuntimeException e) {
            logger.debug("Heartbeat could not be sent", Map.of("error", String.valueOf(e)));
        }
    }

    // -------------------------------------------------------------------------
    // Small helpers
    // -------------------------------------------------------------------------

    private static Datafile.FlagEntry findFlag(Datafile.FlagDatafileDoc doc, String flagKey) {
        if (doc.flags() == null) return null;
        for (Datafile.FlagEntry flag : doc.flags()) {
            if (flagKey.equals(flag.key())) return flag;
        }
        return null;
    }

    private static String variationKeys(Datafile.FlagEntry flag) {
        List<String> keys = new ArrayList<>();
        for (Datafile.VariationEntry variation : flag.variations()) {
            keys.add(variation.key());
        }
        return String.join(", ", keys);
    }

    private static String getterName(String declaredType) {
        return switch (declaredType == null ? "" : declaredType) {
            case "boolean" -> "Bool";
            case "string" -> "String";
            case "number" -> "Number";
            case "json" -> "Json";
            default -> "Json";
        };
    }

    /**
     * Attribute keys the caller marked private, read from a {@code _meta} entry on any
     * sub-context: {@code Map.of("_meta", Map.of("privateAttributes", List.of("email")))}.
     * Their values are redacted out of decision-log reasons.
     *
     * <p>{@code _meta} is a reserved key, so it is never usable as a targeting attribute;
     * it lives in the same attribute map only because that is the shape the wire format
     * uses.
     */
    private static Set<String> privateAttributesFrom(EvalContext ctx) {
        Set<String> out = new LinkedHashSet<>();
        collectPrivateAttributes(Context.primaryContext(ctx), out);
        if (ctx instanceof EvalContext.MultiContext multi) {
            for (EvalContext.SingleContext sub : multi.contexts().values()) {
                collectPrivateAttributes(sub, out);
            }
        }
        return out;
    }

    private static void collectPrivateAttributes(
            EvalContext.SingleContext sub, Set<String> target) {
        if (sub == null) {
            return;
        }
        Object meta = sub.attributes().get("_meta");
        if (!(meta instanceof Map<?, ?> metaMap)) {
            return;
        }
        Object declared = metaMap.get("privateAttributes");
        if (!(declared instanceof List<?> list)) {
            return;
        }
        for (Object entry : list) {
            if (entry instanceof String key) {
                target.add(key);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

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
