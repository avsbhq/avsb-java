# avsb-sdk — Java SDK

Official Java SDK for the A vs B feature-flag and experimentation platform.

- **Java 17+** (records, sealed interfaces, switch expressions)
- **Jackson** for JSON parsing
- Framework integrations: Spring Boot 3, Micronaut 4, Quarkus 3
- OpenFeature provider included
- Sticky-bucket support: in-memory + Redis (Jedis)

## Installation

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.avsbhq:avsb-sdk:1.0.0")
}
```

## Quick start

```java
AvsbServer server = AvsbServer.builder("sdk-my-key").build();
server.init(); // blocks until datafile is fetched

Flag<Boolean> flag = server.getBoolFlag("new-checkout", false, EvalContext.user("alice"));
if (flag.isEnabled()) {
    // serve new checkout experience
}
```

## Spring Boot auto-configuration

Add to `application.properties`:

```properties
avsb.sdk-key=sdk-my-key
```

The `AvsbServer` bean is automatically registered. Inject it anywhere:

```java
@Autowired AvsbServer avsbServer;
```

## Multi-context evaluation

```java
EvalContext ctx = EvalContext.multi(Map.of(
    "user",         EvalContext.user("alice"),
    "organization", EvalContext.of("organization", "acme", Map.of("tier", "enterprise"))
));
Flag<Boolean> flag = server.getBoolFlag("org-feature", false, ctx);
```

## OpenFeature

```java
OpenFeatureAPI.getInstance().setProvider(new OpenFeatureProvider(server));
Client client = OpenFeatureAPI.getInstance().getClient();
boolean value = client.getBooleanValue("my-flag", false);
```

## Building

```bash
./gradlew build
./gradlew test
```
