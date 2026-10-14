package cloud.avsb.sdk;

import cloud.avsb.sdk.core.Datafile;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The datafile lifecycle of {@link AvsbServer}: the fetch, its failure message, and the
 * background refresh loop. Package-private; split out of AvsbServer.java (FU-31).
 */
final class ServerDatafile {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Plus or minus this fraction of the poll interval, so fleets do not fetch in lockstep. */
    private static final double POLL_JITTER = 0.1;

    private final Logger logger;
    private final String datafileUrl;
    private final Duration fetchTimeout;
    private final Duration pollInterval;
    private final boolean pollingEnabled;
    private final HttpClient http;
    private final AtomicReference<Datafile.FlagDatafileDoc> datafileRef;
    private final AtomicBoolean closed;
    private final ServerTracking tracking;
    private volatile ScheduledExecutorService refreshScheduler;

    ServerDatafile(
            Logger logger,
            String datafileUrl,
            Duration fetchTimeout,
            Duration pollInterval,
            boolean pollingEnabled,
            HttpClient http,
            AtomicReference<Datafile.FlagDatafileDoc> datafileRef,
            AtomicBoolean closed,
            ServerTracking tracking) {
        this.logger = logger;
        this.datafileUrl = datafileUrl;
        this.fetchTimeout = fetchTimeout;
        this.pollInterval = pollInterval;
        this.pollingEnabled = pollingEnabled;
        this.http = http;
        this.datafileRef = datafileRef;
        this.closed = closed;
        this.tracking = tracking;
    }

    /**
     * The datafile URL. It comes from the endpoint contract, never from string surgery.
     * Every previous shape ("/datafiles/{key}.json" here, "/v1/datafiles/..." in .NET,
     * "datafile.avsb.cloud/v2/..." in Go) 404s, which is why nothing initialised.
     */
    static String resolveUrl(String explicitUrl, String sdkKey, String cdnHost) {
        return explicitUrl != null && !explicitUrl.isBlank()
                ? explicitUrl
                : Endpoints.datafileUrl(sdkKey, cdnHost);
    }

    InitResult fetchAndApply() {
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
            tracking.startTrackerIfNeeded(doc);
            tracking.sendHeartbeat(doc);
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

    void startRefreshLoop() {
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

    /** Stop the refresh loop, if one was started. Called once, from {@link AvsbServer#close()}. */
    void stopRefreshLoop() {
        ScheduledExecutorService scheduler = refreshScheduler;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
