package com.avsbhq.avsb.tracking;

import com.avsbhq.avsb.Endpoints;
import com.avsbhq.avsb.ExposureEvent;
import com.avsbhq.avsb.Logger;
import com.avsbhq.avsb.MetricEvent;
import com.avsbhq.avsb.PurchaseOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Batched event sender. Mirrors {@code createServerTracker} in {@code @avsbhq/node}, so
 * every server SDK batches, retries, and flushes on the same terms.
 *
 * <p>What it guarantees:
 *
 * <ul>
 *   <li><b>Bounded memory.</b> The queues hold at most {@code maxQueueSize} events each.
 *       Past that the OLDEST are dropped with a warning naming the count, so a broken
 *       ingest endpoint cannot grow the heap without limit or fail silently.</li>
 *   <li><b>Stable event ids.</b> A UUID is stamped once, at enqueue time. Every retry of a
 *       batch carries the same ids, so ingestion deduplicates instead of double counting a
 *       conversion.</li>
 *   <li><b>Jittered retries.</b> {@link Backoff} on the shared curve, honouring
 *       {@code Retry-After}.</li>
 *   <li><b>Flush on close.</b> {@link #close()} sends what is queued before returning, so a
 *       graceful shutdown does not throw away the last batch.</li>
 *   <li><b>Daemon threads.</b> The flush timer never keeps a JVM alive. A forgotten
 *       {@code close()} costs the last batch, not a hung process.</li>
 * </ul>
 *
 * <p>The JSON bodies are built field by field rather than reflected off the record types.
 * The field names are frozen vocabulary that the ingestion worker validates and the
 * dashboard reads, and optional fields must be OMITTED rather than sent as null (the
 * ingestion schema treats them as optional, not nullable), so the wire shape is spelled out
 * here where it can be reviewed against the {@code wire-shape} conformance fixtures.
 */
public final class ServerTracker implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Ingestion accepts at most 100 items per batch. */
    public static final int MAX_BATCH_SIZE = 100;

    /**
     * Tracker configuration.
     *
     * @param sdkKey          Goes into every body envelope: the ingest routes are not
     *                        key-pathed, so this is how an event is attributed to an
     *                        environment.
     * @param collectEndpoint The datafile's {@code collectEndpoint}, or null for the public
     *                        ingest host. Normalised through {@link Endpoints}.
     * @param flushInterval   How often the timer flushes.
     * @param batchSize       Flush early once this many events are queued. Capped at
     *                        {@link #MAX_BATCH_SIZE}.
     * @param maxQueueSize    Per-queue cap before the oldest events are dropped.
     * @param retryAttempts   Retries after the first failure.
     * @param requestTimeout  Per-request HTTP timeout.
     */
    public record Options(
            String sdkKey,
            String collectEndpoint,
            Duration flushInterval,
            int batchSize,
            int maxQueueSize,
            int retryAttempts,
            Duration requestTimeout,
            Logger logger
    ) {

        /** Defaults matching {@code @avsbhq/node}: 10s flush, 50 per batch, 10k queued. */
        public static Options defaults(String sdkKey, String collectEndpoint, Logger logger) {
            return new Options(sdkKey, collectEndpoint, Duration.ofSeconds(10), 50, 10_000, 3,
                    Duration.ofSeconds(10), logger);
        }
    }

    private final Options options;
    private final Logger logger;
    private final HttpClient http;
    private final ScheduledExecutorService scheduler;

    private final ArrayDeque<ExposureEvent> exposureQueue = new ArrayDeque<>();
    private final ArrayDeque<MetricEvent> metricQueue = new ArrayDeque<>();
    private final Object queueLock = new Object();

    private final String batchUrl;
    private final String metricUrl;
    private final String ordersUrl;

    private volatile boolean closed = false;

    private ServerTracker(Options options) {
        this.options = options;
        this.logger = options.logger() != null ? options.logger() : Logger.noOp();
        this.http = HttpClient.newBuilder()
                .connectTimeout(options.requestTimeout())
                .build();
        this.batchUrl = Endpoints.collectUrl("batch", options.collectEndpoint());
        this.metricUrl = Endpoints.collectUrl("metricBatch", options.collectEndpoint());
        this.ordersUrl = Endpoints.collectUrl("orders", options.collectEndpoint());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "avsb-tracker");
            // Daemon: a tracker nobody closed must never hold the JVM open.
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Create a tracker and start its flush timer. */
    public static ServerTracker start(Options options) {
        ServerTracker tracker = new ServerTracker(options);
        long periodMs = Math.max(1_000, options.flushInterval().toMillis());
        tracker.scheduler.scheduleWithFixedDelay(
                tracker::flushQuietly, periodMs, periodMs, TimeUnit.MILLISECONDS);
        return tracker;
    }

    /** Queue an exposure. Stamps an event id if the event does not carry one. */
    public void queue(ExposureEvent event) {
        if (closed || event == null) return;
        ExposureEvent stamped = event.eventId() == null || event.eventId().isEmpty()
                ? event.withEventId(UUID.randomUUID().toString())
                : event;
        boolean shouldFlush;
        synchronized (queueLock) {
            exposureQueue.addLast(stamped);
            dropOldest(exposureQueue, "Event");
            shouldFlush = exposureQueue.size() >= effectiveBatchSize();
        }
        if (shouldFlush) {
            submit(this::flushQuietly);
        }
    }

    /** Queue a conversion or metric event. Stamps an event id if absent. */
    public void queueMetric(MetricEvent event) {
        if (closed || event == null) return;
        MetricEvent stamped = event.eventId() == null || event.eventId().isEmpty()
                ? event.withEventId(UUID.randomUUID().toString())
                : event;
        boolean shouldFlush;
        synchronized (queueLock) {
            metricQueue.addLast(stamped);
            dropOldest(metricQueue, "Metric");
            shouldFlush = metricQueue.size() >= effectiveBatchSize();
        }
        if (shouldFlush) {
            submit(this::flushQuietly);
        }
    }

    /**
     * Send a purchase immediately rather than on the batch timer, because an order is the
     * event a customer least wants buffered. Returns as soon as the send is scheduled.
     */
    public void trackPurchase(String visitorId, PurchaseOrder order) {
        if (closed || order == null) return;
        String eventId = UUID.randomUUID().toString();
        long timestamp = System.currentTimeMillis();
        submit(() -> {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("sdkKey", options.sdkKey());
            ArrayNode orders = body.putArray("orders");
            orders.add(orderNode(eventId, visitorId, order, timestamp));
            post(ordersUrl, body.toString(), 1, "order " + order.orderId());
        });
    }

    /**
     * Hand work to the flush thread, tolerating the shutdown race. A caller racing
     * {@link #close()} must not see a RejectedExecutionException thrown out of
     * {@code track}: the close path flushes what is queued anyway.
     */
    private void submit(Runnable task) {
        try {
            scheduler.execute(task);
        } catch (RejectedExecutionException e) {
            logger.debug("Tracker is shutting down; queued work will flush on close");
        }
    }

    /**
     * Send everything queued, on the calling thread. Safe to call concurrently with the
     * flush timer: the queues are drained under a lock and sent outside it.
     */
    public void flush() {
        List<ExposureEvent> exposures;
        List<MetricEvent> metrics;
        synchronized (queueLock) {
            if (exposureQueue.isEmpty() && metricQueue.isEmpty()) {
                return;
            }
            exposures = new ArrayList<>(exposureQueue);
            metrics = new ArrayList<>(metricQueue);
            exposureQueue.clear();
            metricQueue.clear();
        }

        int chunkSize = effectiveBatchSize();
        for (int i = 0; i < exposures.size(); i += chunkSize) {
            List<ExposureEvent> chunk = exposures.subList(i, Math.min(i + chunkSize, exposures.size()));
            post(batchUrl, exposureBatchBody(chunk), chunk.size(), "exposures");
        }
        for (int i = 0; i < metrics.size(); i += chunkSize) {
            List<MetricEvent> chunk = metrics.subList(i, Math.min(i + chunkSize, metrics.size()));
            post(metricUrl, metricBatchBody(chunk), chunk.size(), "metric events");
        }
    }

    /**
     * Stop the timer and flush what is queued. Idempotent. Blocks for the final flush,
     * which is the point: a process exiting must not discard the last batch.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        scheduler.shutdown();
        try {
            // Let an in-flight scheduled flush finish before draining on this thread, so
            // the same batch is not sent twice.
            if (!scheduler.awaitTermination(options.requestTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        flush();
    }

    /** Queued exposure count. For tests and diagnostics. */
    public int queuedExposures() {
        synchronized (queueLock) {
            return exposureQueue.size();
        }
    }

    /** Queued metric-event count. For tests and diagnostics. */
    public int queuedMetricEvents() {
        synchronized (queueLock) {
            return metricQueue.size();
        }
    }

    /** Queued exposures, in order. Package-private: the enqueue-time id stamp is asserted. */
    List<ExposureEvent> snapshotExposures() {
        synchronized (queueLock) {
            return List.copyOf(exposureQueue);
        }
    }

    // -------------------------------------------------------------------------
    // Wire bodies
    // -------------------------------------------------------------------------

    /** {@code {sdkKey, events: [...]}} exactly as the batch fixture declares it. */
    String exposureBatchBody(List<ExposureEvent> events) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("sdkKey", options.sdkKey());
        ArrayNode array = body.putArray("events");
        for (ExposureEvent event : events) {
            ObjectNode node = array.addObject();
            node.put("eventId", event.eventId());
            node.put("visitorId", event.visitorId());
            node.put("experimentId", event.experimentId());
            node.put("variationId", event.variationId());
            node.put("eventName", event.eventName());
            // revenue is REQUIRED and nullable: null on an exposure, never omitted.
            if (event.revenue() == null) {
                node.putNull("revenue");
            } else {
                node.put("revenue", event.revenue());
            }
            node.put("timestamp", event.timestamp());
            node.put("pageUrl", event.pageUrl() == null ? "" : event.pageUrl());
            // value, properties, and attributes are OPTIONAL, which in the ingestion schema
            // means absent rather than null. Sending null fails validation for the whole
            // batch, so omit them.
            if (event.value() != null) {
                node.put("value", event.value());
            }
            if (event.properties() != null && !event.properties().isEmpty()) {
                ObjectNode properties = node.putObject("properties");
                for (Map.Entry<String, Object> entry : event.properties().entrySet()) {
                    putUnknown(properties, entry.getKey(), entry.getValue());
                }
            }
            if (event.attributes() != null && !event.attributes().isEmpty()) {
                ObjectNode attributes = node.putObject("attributes");
                for (Map.Entry<String, String> entry : event.attributes().entrySet()) {
                    attributes.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return body.toString();
    }

    /**
     * {@code {sdkKey, metricEvents: [...]}}. Note there is no {@code properties} field: the
     * metric batch has none, and anything sent under that name is stripped before storage,
     * so it would look delivered and be gone.
     */
    String metricBatchBody(List<MetricEvent> events) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("sdkKey", options.sdkKey());
        ArrayNode array = body.putArray("metricEvents");
        for (MetricEvent event : events) {
            ObjectNode node = array.addObject();
            node.put("eventId", event.eventId());
            node.put("visitorId", event.visitorId());
            node.put("eventName", event.eventName());
            if (event.revenue() == null) {
                node.putNull("revenue");
            } else {
                node.put("revenue", event.revenue());
            }
            node.put("timestamp", event.timestamp());
            node.put("pageUrl", event.pageUrl() == null ? "" : event.pageUrl());
            if (event.value() != null) {
                node.put("value", event.value());
            }
        }
        return body.toString();
    }

    private ObjectNode orderNode(
            String eventId, String visitorId, PurchaseOrder order, long timestamp) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("eventId", eventId);
        node.put("visitorId", visitorId == null ? "" : visitorId);
        node.put("orderId", order.orderId());
        node.put("total", order.total());
        node.put("timestamp", timestamp);
        node.put("pageUrl", "");
        if (order.currency() != null) node.put("currency", order.currency());
        if (order.subtotal() != null) node.put("subtotal", order.subtotal());
        if (order.shipping() != null) node.put("shipping", order.shipping());
        if (order.tax() != null) node.put("tax", order.tax());
        if (order.discount() != null) node.put("discount", order.discount());
        if (order.coupon() != null) node.put("coupon", order.coupon());
        if (order.test() != null) node.put("test", order.test());
        if (order.items() != null && !order.items().isEmpty()) {
            ArrayNode items = node.putArray("items");
            for (PurchaseOrder.Item item : order.items()) {
                ObjectNode itemNode = items.addObject();
                itemNode.put("sku", item.sku());
                if (item.productKey() != null) itemNode.put("productKey", item.productKey());
                if (item.name() != null) itemNode.put("name", item.name());
                if (item.price() != null) itemNode.put("price", item.price());
                if (item.quantity() != null) itemNode.put("quantity", item.quantity());
                if (item.category() != null) itemNode.put("category", item.category());
            }
        }
        return node;
    }

    private static void putUnknown(ObjectNode target, String key, Object value) {
        if (value == null) {
            target.putNull(key);
        } else if (value instanceof String s) {
            target.put(key, s);
        } else if (value instanceof Boolean b) {
            target.put(key, b);
        } else if (value instanceof Integer i) {
            target.put(key, i);
        } else if (value instanceof Long l) {
            target.put(key, l);
        } else if (value instanceof Number n) {
            target.put(key, n.doubleValue());
        } else {
            target.set(key, MAPPER.valueToTree(value));
        }
    }

    // -------------------------------------------------------------------------
    // Transport
    // -------------------------------------------------------------------------

    private void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException e) {
            logger.error("Auto-flush failed", Map.of("error", String.valueOf(e)));
        }
    }

    private void post(String url, String body, int itemCount, String what) {
        Backoff.State backoff = null;
        Long pendingRetryAfterMs = null;

        for (int attempt = 0; attempt <= options.retryAttempts(); attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(options.requestTimeout())
                        .header("Content-Type", "application/json")
                        .header("User-Agent", com.avsbhq.avsb.SdkMetadata.userAgent())
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<Void> response =
                        http.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    logger.debug("Flushed " + itemCount + " " + what + " to " + url);
                    return;
                }
                pendingRetryAfterMs = Backoff.parseRetryAfter(
                        response.headers().firstValue("retry-after").orElse(null));
                if (attempt >= options.retryAttempts()) {
                    logger.error("Failed to send " + itemCount + " " + what + " after "
                            + options.retryAttempts() + " retries", Map.of(
                            "endpoint", url,
                            "status", response.statusCode()));
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while sending " + itemCount + " " + what + " to " + url);
                return;
            } catch (Exception e) {
                if (attempt >= options.retryAttempts()) {
                    logger.error("Failed to send " + itemCount + " " + what + " after "
                            + options.retryAttempts() + " retries", Map.of(
                            "endpoint", url,
                            "error", String.valueOf(e.getMessage())));
                    return;
                }
            }

            backoff = Backoff.next(backoff, pendingRetryAfterMs);
            pendingRetryAfterMs = null;
            try {
                Thread.sleep(backoff.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Queue helpers
    // -------------------------------------------------------------------------

    private int effectiveBatchSize() {
        return Math.max(1, Math.min(options.batchSize(), MAX_BATCH_SIZE));
    }

    private void dropOldest(ArrayDeque<?> queue, String what) {
        int max = Math.max(1, options.maxQueueSize());
        if (queue.size() <= max) {
            return;
        }
        int dropped = 0;
        while (queue.size() > max) {
            queue.pollFirst();
            dropped++;
        }
        logger.warn(what + " queue full (" + max + "), dropping " + dropped + " oldest events");
    }
}
