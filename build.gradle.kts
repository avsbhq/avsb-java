plugins {
    `java-library`
    `maven-publish`
}

group = "com.avsbhq"
version = "1.0.0"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
    withSourcesJar()
    withJavadocJar()
}

repositories { mavenCentral() }

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    compileOnly("org.springframework.boot:spring-boot-starter-web:3.3.0")
    compileOnly("io.micronaut:micronaut-http:4.6.0")
    compileOnly("io.quarkus:quarkus-resteasy-reactive:3.13.0")
    compileOnly("dev.openfeature:sdk:1.12.0")
    compileOnly("redis.clients:jedis:5.1.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.springframework.boot:spring-boot-starter-test:3.3.0")
    testImplementation("dev.openfeature:sdk:1.12.0")
}

tasks.test { useJUnitPlatform() }

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = "com.avsbhq"
            artifactId = "avsb-sdk"
            version = "1.0.0"
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
