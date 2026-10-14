import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

plugins {
    `java-library`
    // Publishes to Maven Central through the Central Portal (central.sonatype.com) and signs
    // what it uploads. 0.37.0 needs Gradle 9, which the committed wrapper pins.
    id("com.vanniktech.maven.publish") version "0.37.0"
}

// The group is the verified Central Portal namespace (a DNS TXT record on avsb.cloud), and the
// Java package matches it: cloud.avsb.sdk.
group = "cloud.avsb"
version = "1.0.1"

java {
    // The floor is Java 17: the README promises it, so nothing in src/main may need a newer
    // language level (pattern matching in switch, for one, is Java 21).
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

repositories { mavenCentral() }

dependencies {
    // Jackson is `implementation`, not `api`, deliberately. As an api dependency it leaked
    // a heavyweight, frequently-CVE'd library onto every consumer's COMPILE classpath, so a
    // Jackson version conflict in the application became this SDK's problem and a Jackson
    // advisory became this SDK's advisory. No public type in cloud.avsb.sdk exposes a
    // Jackson type, so nothing needs it at compile time: it stays on the runtime classpath
    // where the SDK actually uses it.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    compileOnly("org.springframework.boot:spring-boot-starter-web:3.3.0")
    compileOnly("io.micronaut:micronaut-http:4.6.0")
    compileOnly("io.quarkus:quarkus-resteasy-reactive:3.13.0")
    compileOnly("dev.openfeature:sdk:1.12.0")
    compileOnly("redis.clients:jedis:5.1.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.springframework.boot:spring-boot-starter-test:3.3.0")
    testImplementation("dev.openfeature:sdk:1.12.0")
    testImplementation("redis.clients:jedis:5.1.0")
    // Gradle 9 no longer puts the JUnit Platform launcher on the test classpath itself.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// SdkMetadata reads the version from this resource rather than a literal in Java source, so
// the heartbeat's sdkVersion can never drift from the published artifact.
// The value is read at configuration time: touching `project` inside the task action is
// deprecated in Gradle 9 and an error in Gradle 10.
val avsbVersion = project.version.toString()
tasks.processResources {
    inputs.property("avsbVersion", avsbVersion)
    filesMatching("avsb-version.properties") {
        expand("avsbVersion" to avsbVersion)
    }
}

tasks.test { useJUnitPlatform() }

val signingKeyPresent = providers.gradleProperty("signingInMemoryKey").isPresent

mavenPublishing {
    configure(JavaLibrary(javadocJar = JavadocJar.Javadoc(), sourcesJar = SourcesJar.Sources()))

    // Uploads to the Central Portal as a deployment the founder releases by hand on
    // central.sonatype.com (automaticRelease = false). Credentials come from Gradle properties,
    // usually supplied as environment variables, never from a file in this repository:
    //   ORG_GRADLE_PROJECT_mavenCentralUsername, ORG_GRADLE_PROJECT_mavenCentralPassword
    // (a Central Portal user token), see docs/ops/sdk-publish-checklist.md.
    publishToMavenCentral(automaticRelease = false)

    // Maven Central rejects unsigned artifacts, so a real upload always has the key:
    //   ORG_GRADLE_PROJECT_signingInMemoryKey, ORG_GRADLE_PROJECT_signingInMemoryKeyPassword
    // Without it (a local publishToMavenLocal, a contributor's machine) signing is skipped
    // rather than failing the build.
    if (signingKeyPresent) {
        signAllPublications()
    }

    // One source of truth: the project version above, which SdkMetadata also reports.
    coordinates(group.toString(), "avsb-sdk", version.toString())

    pom {
        name.set("A vs B Java SDK")
        description.set("Official Java SDK for the A vs B feature-flag and experimentation platform.")
        url.set("https://github.com/avsbhq/avsb-java")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("avsbhq")
                name.set("AvsB Platform")
                email.set("sdk@avsb.cloud")
                organization.set("AvsB")
                organizationUrl.set("https://avsb.cloud")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/avsbhq/avsb-java.git")
            developerConnection.set("scm:git:ssh://github.com:avsbhq/avsb-java.git")
            url.set("https://github.com/avsbhq/avsb-java/tree/main")
        }
    }
}

// Fail before anything is uploaded when a Maven Central task runs without the signing key,
// instead of uploading unsigned files the Central Portal then rejects.
gradle.taskGraph.whenReady {
    if (!signingKeyPresent && allTasks.any { it.name.contains("MavenCentral") }) {
        throw GradleException(
            "Publishing to Maven Central needs ORG_GRADLE_PROJECT_signingInMemoryKey and " +
                "ORG_GRADLE_PROJECT_signingInMemoryKeyPassword; see docs/ops/sdk-publish-checklist.md.",
        )
    }
}

// Javadoc still checks HTML, links and syntax; it no longer lists every method without an
// @param or @return, which buried the warnings that matter.
tasks.javadoc {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
}

