package com.avsbhq.avsb;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Who this SDK says it is on the wire.
 *
 * <p>Both values go into the heartbeat body ({@code sdkType}, {@code sdkVersion}), which is
 * what drives the Connected, Stale, and Not connected states on the dashboard's
 * Environments page.
 *
 * <p>The version is READ FROM BUILD METADATA, not from a literal in this file. A
 * hand-maintained version constant drifts from the published artifact the first time
 * someone forgets it, and then the dashboard reports a version nobody can find. Gradle
 * filters {@code src/main/resources/avsb-version.properties} at build time from the project
 * version; if the resource is missing (running straight from a source tree, for instance)
 * the value falls back to {@code "0.0.0-dev"} rather than guessing.
 */
public final class SdkMetadata {

    private SdkMetadata() {
        // Constants holder.
    }

    /** Maven coordinates of this artifact, exactly as published. */
    public static final String SDK_TYPE = "com.avsbhq:avsb-sdk";

    private static final String VERSION_RESOURCE = "/avsb-version.properties";

    private static final String FALLBACK_VERSION = "0.0.0-dev";

    private static final String VERSION = readVersion();

    /** The published package version. */
    public static String sdkVersion() {
        return VERSION;
    }

    /** {@code com.avsbhq:avsb-sdk/1.2.3}, for the HTTP User-Agent. */
    public static String userAgent() {
        return "avsb-java/" + VERSION;
    }

    private static String readVersion() {
        try (InputStream in = SdkMetadata.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (in == null) {
                return FALLBACK_VERSION;
            }
            Properties props = new Properties();
            props.load(in);
            String version = props.getProperty("version");
            return version == null || version.isBlank() ? FALLBACK_VERSION : version.trim();
        } catch (IOException e) {
            return FALLBACK_VERSION;
        }
    }
}
