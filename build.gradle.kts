plugins {
    `java-library`
    `maven-publish`
}

group = "com.avsbhq"
version = "1.0.1"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
    withSourcesJar()
    withJavadocJar()
}

repositories { mavenCentral() }

dependencies {
    // Jackson is `implementation`, not `api`, deliberately. As an api dependency it leaked
    // a heavyweight, frequently-CVE'd library onto every consumer's COMPILE classpath, so a
    // Jackson version conflict in the application became this SDK's problem and a Jackson
    // advisory became this SDK's advisory. No public type in com.avsbhq.avsb exposes a
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
}

// SdkMetadata reads the version from this resource rather than a literal in Java source, so
// the heartbeat's sdkVersion can never drift from the published artifact.
tasks.processResources {
    filesMatching("avsb-version.properties") {
        expand("avsbVersion" to project.version.toString())
    }
}

tasks.test { useJUnitPlatform() }

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = "com.avsbhq"
            artifactId = "avsb-sdk"
            // One source of truth: the project version above, which SdkMetadata also reports.
            version = project.version.toString()
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
    }
}
