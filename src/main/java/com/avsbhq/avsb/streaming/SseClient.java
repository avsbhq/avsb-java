package com.avsbhq.avsb.streaming;

import com.avsbhq.avsb.Logger;

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
 * <p>The A vs B SSE stream endpoint delivers datafile updates in real time.
 * Each line starting with {@code "data:"} contains the updated datafile JSON.
 * Pass a {@code Consumer<String>} to {@link #start} to receive each JSON payload.
 *
 * <pre>{@code
 * SseClient sse = new SseClient(
 *     URI.create("https://stream.avsb.cloud/v1/stream/sdk-key"),
 *     Logger.console()
 * );
 * sse.start(json -> server.loadDatafile(json));
 * // ... later:
 * sse.stop();
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
