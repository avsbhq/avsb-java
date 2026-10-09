package com.avsbhq.avsb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The version this SDK reports (heartbeat sdkVersion, User-Agent) is the project version in
 * build.gradle.kts, the one Maven Central publishes. A hand-kept constant drifted from the
 * package in the JavaScript SDKs (N0-9); here Gradle filters the version into a resource, and
 * this test proves the filtered value reaches the wire.
 */
@DisplayName("SdkMetadata — the reported version is the build version")
class SdkMetadataTest {

    private static final Pattern PROJECT_VERSION = Pattern.compile("(?m)^version = \"([^\"]+)\"$");

    private static String buildVersion() throws IOException {
        // Gradle runs tests with the project directory (packages/avsb-java) as user.dir.
        Path build = Paths.get(System.getProperty("user.dir", ".")).resolve("build.gradle.kts");
        Matcher matcher = PROJECT_VERSION.matcher(Files.readString(build));
        assertTrue(matcher.find(), "no top-level version in " + build);
        return matcher.group(1);
    }

    @Test
    @DisplayName("sdkVersion is the build.gradle.kts version")
    void sdkVersionIsTheBuildVersion() throws IOException {
        assertEquals(buildVersion(), SdkMetadata.sdkVersion());
    }

    @Test
    @DisplayName("the User-Agent carries the same version")
    void userAgentCarriesTheVersion() throws IOException {
        assertEquals("avsb-java/" + buildVersion(), SdkMetadata.userAgent());
    }

    @Test
    @DisplayName("the version is never the unfiltered template or the dev fallback")
    void versionIsResolved() {
        assertFalse(SdkMetadata.sdkVersion().contains("${"), "the resource was not filtered");
        assertNotEquals("0.0.0-dev", SdkMetadata.sdkVersion());
        assertEquals("com.avsbhq:avsb-sdk", SdkMetadata.SDK_TYPE);
    }
}
