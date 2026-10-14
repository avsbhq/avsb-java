package cloud.avsb.sdk.streaming;

import cloud.avsb.sdk.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Lightweight Server-Sent Events (SSE) client that reconnects on failure.
 *
 * <p>NOT WIRED INTO {@link cloud.avsb.sdk.AvsbServer} YET. Streaming needs an endpoint,
 * and the only correct source for one is the datafile's {@code streamEndpoint} field
 * resolved through {@link cloud.avsb.sdk.Endpoints#resolveStreamEndpoint}. Defaulting to a
 * guessed host is what gave Go customers an infinite reconnect loop against DNS that does
 * not resolve, so this class stays available and unwired until the streaming session lands.
 * The background polling loop keeps flags fresh in the meantime.
 *
 * <p>Each line starting with {@code "data:"} carries an updated datafile JSON payload.
 *
 * <pre>{@code
 * Endpoints.StreamEndpointResolution resolved =
 *         Endpoints.resolveStreamEndpoint(null, server.datafile().streamEndpoint());
 * if (resolved.available()) {
 *     SseClient sse = new SseClient(
 *             URI.create(Endpoints.streamTicketUrl(resolved.endpoint(), sdkKey)),
 *             Logger.console());
 *     sse.start(json -> server.loadDatafile(json));
 *     // ... later:
 *     sse.stop();
 * }
 * }</pre>
 */
public final class SseClient {

    private static final Duration RECONNECT_DELAY = Duration.ofSeconds(3);

    private final URI uri;
    private final Logger logger;
    private final HttpClient httpClient;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "avsb-sse");
        t.setDaemon(true);
        return t;
    });

    public SseClient(URI uri, Logger logger) {
        this.uri = uri;
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Start listening. Each {@code data:} payload is forwarded to {@code onData}. */
    public void start(Consumer<String> onData) {
        if (!running.compareAndSet(false, true)) return;
        executor.submit(() -> {
            while (running.get()) {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(uri)
                            .header("Accept", "text/event-stream")
                            .GET()
                            .build();
                    HttpResponse<java.io.InputStream> response = httpClient.send(
                            request, HttpResponse.BodyHandlers.ofInputStream());
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                        String line;
                        while (running.get() && (line = reader.readLine()) != null) {
                            if (line.startsWith("data:")) {
                                String payload = line.substring(5).trim();
                                if (!payload.isEmpty()) {
                                    onData.accept(payload);
                                }
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (running.get()) {
                        logger.warn("SSE connection lost, reconnecting in " + RECONNECT_DELAY.toSeconds() + "s: " + e.getMessage());
                        try { Thread.sleep(RECONNECT_DELAY.toMillis()); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    }
                }
            }
        });
    }

    /** Stop the SSE listener. */
    public void stop() {
        running.set(false);
        executor.shutdown();
    }
}
