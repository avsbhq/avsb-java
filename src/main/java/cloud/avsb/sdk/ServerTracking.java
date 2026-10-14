package cloud.avsb.sdk;

import cloud.avsb.sdk.core.Context;
import cloud.avsb.sdk.core.Datafile;
import cloud.avsb.sdk.tracking.ServerTracker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Event delivery for {@link AvsbServer}: conversions, purchases, the tracker, the decision
 * log and the liveness heartbeat. Package-private; split out of AvsbServer.java (FU-31).
 * Every field is shared with the server, which builds this once in its constructor.
 */
final class ServerTracking {

    private final String sdkKey;
    private final Logger logger;
    private final boolean trackingEnabled;
    private final DecisionLog decisionLog;
    private final HttpClient http;
    private final Duration fetchTimeout;
    private final AtomicReference<ServerTracker> trackerRef;
    private final AtomicBoolean closed;

    ServerTracking(
            String sdkKey,
            Logger logger,
            boolean trackingEnabled,
            DecisionLog decisionLog,
            HttpClient http,
            Duration fetchTimeout,
            AtomicReference<ServerTracker> trackerRef,
            AtomicBoolean closed) {
        this.sdkKey = sdkKey;
        this.logger = logger;
        this.trackingEnabled = trackingEnabled;
        this.decisionLog = decisionLog;
        this.http = http;
        this.fetchTimeout = fetchTimeout;
        this.trackerRef = trackerRef;
        this.closed = closed;
    }

    /** The body of {@link AvsbServer#track(String, EvalContext, TrackPayload)}. */
    void track(String eventName, EvalContext context, TrackPayload payload) {
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

    /** The body of {@link AvsbServer#trackPurchase(EvalContext, PurchaseOrder)}. */
    void trackPurchase(EvalContext context, PurchaseOrder order) {
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

    /** The body of {@link AvsbServer#flushEvents()}. */
    void flushEvents() {
        ServerTracker tracker = trackerRef.get();
        if (tracker != null) {
            tracker.flush();
        }
    }

    /** Stop the tracker and flush what it holds. Called once, from {@link AvsbServer#close()}. */
    void closeTracker() {
        ServerTracker tracker = trackerRef.getAndSet(null);
        if (tracker != null) {
            tracker.close();
        }
    }

    void startTrackerIfNeeded(Datafile.FlagDatafileDoc doc) {
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
    void sendHeartbeat(Datafile.FlagDatafileDoc doc) {
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

    void recordDecision(String flagKey, EvalContext ctx, Flag<?> flag) {
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
}
