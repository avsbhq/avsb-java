package com.avsbhq.avsb;

import com.avsbhq.avsb.core.Datafile;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test helper utilities. Provides factory methods for constructing an
 * {@link AvsbServer} pre-loaded with an in-memory datafile, useful in unit
 * and conformance tests without requiring a live network connection.
 */
public final class TestUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private TestUtils() {}

    /**
     * Create an {@link AvsbServer} loaded from a JSON string, with polling, event sending, and
     * logging all off, so a test never touches the network and never prints.
     *
     * @param sdkKey       SDK key. Any non-blank string works; a key that is not real-shaped
     *                     would normally log an actionable error, which the no-op logger
     *                     swallows here.
     * @param jsonDatafile Datafile JSON.
     */
    public static AvsbServer serverFromJson(String sdkKey, String jsonDatafile) {
        try {
            Datafile.FlagDatafileDoc doc = MAPPER.readValue(jsonDatafile, Datafile.FlagDatafileDoc.class);
            return AvsbServer.builder(sdkKey)
                    .logger(Logger.noOp())
                    .polling(false)
                    .tracking(false)
                    .bootstrapDatafile(doc)
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse datafile JSON", e);
        }
    }

    /**
     * Create an {@link AvsbServer} loaded from a file on disk.
     *
     * @param sdkKey       SDK key.
     * @param datafilePath Path to the datafile JSON file.
     */
    public static AvsbServer serverFromFile(String sdkKey, Path datafilePath) {
        try {
            byte[] bytes = Files.readAllBytes(datafilePath);
            return serverFromJson(sdkKey, new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read datafile from " + datafilePath, e);
        }
    }

    /**
     * Parse a JSON string into a {@link Datafile.FlagDatafileDoc} record.
     * Useful when a test needs to inspect the parsed datafile directly.
     */
    public static Datafile.FlagDatafileDoc parseDatafile(String json) {
        try {
            return MAPPER.readValue(json, Datafile.FlagDatafileDoc.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse datafile JSON", e);
        }
    }

    /**
     * Parse a JSON file into a {@link Datafile.FlagDatafileDoc} record.
     */
    public static Datafile.FlagDatafileDoc parseDatafileFromPath(Path path) {
        try {
            return MAPPER.readValue(path.toFile(), Datafile.FlagDatafileDoc.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse datafile from " + path, e);
        }
    }

    /**
     * Read the content of a classpath resource as a UTF-8 string.
     */
    public static String readResource(String resourcePath) {
        try (InputStream is = TestUtils.class.getResourceAsStream(resourcePath)) {
            if (is == null) throw new IllegalArgumentException("Resource not found: " + resourcePath);
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
