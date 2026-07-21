# avsb-sdk: Java SDK

Official Java SDK for the [A vs B](https://app.avsb.cloud) feature-flag and experimentation platform.

Java 17+. Spring Boot 3, Micronaut 4, and Quarkus 3 integrations. OpenFeature provider. Published to Maven Central.

---

## 1. Install

### Gradle (build.gradle.kts)

```kotlin
dependencies {
    implementation("com.avsbhq:avsb-sdk:1.0.0")
}
```

### Maven (pom.xml)

```xml
<dependency>
    <groupId>com.avsbhq</groupId>
    <artifactId>avsb-sdk</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Runtime requirement:** Java 17 or later. Jackson `jackson-databind` 2.17+ is the only required transitive dependency.

### Spring Boot starter

```kotlin
dependencies {
    implementation("com.avsbhq:avsb-spring-boot-starter:1.0.0")
}
```

---

## 2. Quickstart

```java
import com.avsbhq.avsb.AvsbServer;
import com.avsbhq.avsb.EvalContext;
import com.avsbhq.avsb.Flag;
import com.avsbhq.avsb.InitResult;

AvsbServer server = AvsbServer.builder("sdk-my-key").build();
InitResult result = server.init(); // blocks until datafile fetched (10s default timeout)

if (!result.isSuccess()) {
    // Log and decide whether to continue on defaults or abort startup.
}

EvalContext ctx = EvalContext.user("alice", Map.of("plan", "pro"));
Flag<Boolean> flag = server.getBoolFlag("checkout-v2", false, ctx);

if (flag.isEnabled()) {
    // serve new checkout experience
}
System.out.printf("variation=%s source=%s%n", flag.variationKey(), flag.source().wireValue());
```

---

## 3. SDK keys

Get a **server SDK key** from your A vs B project's Environments page. Store it in an environment variable; never hard-code it.

```bash
export AVSB_SDK_KEY=sdk-...
```

```java
String sdkKey = System.getenv("AVSB_SDK_KEY");
AvsbServer server = AvsbServer.builder(sdkKey).build();
```

Never use the public (client-side) SDK key in server code. Server SDK keys carry elevated trust and must not be included in HTTP responses served to browsers.

---

## 4. Identity

`EvalContext` is a sealed interface with two concrete implementations: `SingleContext` and `MultiContext`. Static factory methods cover the common shapes.

### `EvalContext.user(key)` / `EvalContext.user(key, attributes)`

```java
import com.avsbhq.avsb.EvalContext;
import java.util.Map;

// User context (most common)
EvalContext ctx = EvalContext.user("alice", Map.of("plan", "pro", "betaTester", true));
```

### `EvalContext.of(kind, key)` / `EvalContext.of(kind, key, attributes)`

```java
// Arbitrary kind
EvalContext orgCtx = EvalContext.of("organization", "acme", Map.of("tier", "enterprise"));
```

### `identify(context)`: notify downstream integrations

```java
server.identify(ctx);
```

### `track(eventName, context)` / `track(eventName, context, payload)`

```java
server.track("checkout_viewed", ctx);
```

See section 7 for the full `TrackPayload` signature.

---

## 5. Multi-context

```java
import com.avsbhq.avsb.EvalContext;
import java.util.Map;

EvalContext ctx = EvalContext.multi(Map.of(
    "user",         EvalContext.user("alice", Map.of("plan", "pro")),
    "organization", EvalContext.of("organization", "acme", Map.of("tier", "enterprise"))
));

Flag<Boolean> flag = server.getBoolFlag("enterprise-feature", false, ctx);
```

`EvalContext.multi` takes a `Map<String, SingleContext>` keyed by context kind. When a rule specifies `hashAttribute: "organization.key"`, all members of the same organisation receive the same variation regardless of their user key.

---

## 6. Reading flags

### Typed methods

```java
Flag<Boolean> boolFlag   = server.getBoolFlag("promo-banner",  false, ctx);
Flag<String>  stringFlag = server.getStringFlag("ui-theme",    "light", ctx);
Flag<Double>  numFlag    = server.getNumberFlag("max-retries", 3.0, ctx);
Flag<Object>  jsonFlag   = server.getJsonFlag("api-config",    Map.of("timeout", 5000), ctx);
```

A `defaultValue` is required on every call. It is returned when the flag is not found, the server is not initialised, or evaluation fails.

### `Flag<T>`: full evaluation envelope

```java
Flag<Boolean> flag = server.getBoolFlag("checkout-v2", false, ctx);

flag.value()          // T: the evaluated value
flag.variationKey()   // Optional<String>: matched variation key
flag.source()         // EvaluationSource enum
flag.ruleId()         // Optional<String>: matched rule id
flag.ruleType()       // Optional<RuleType> enum
flag.reasons()        // List<String>
flag.evaluatedAt()    // long: ms epoch
flag.durationMicros() // long: microseconds in evaluator
flag.isEnabled()      // boolean: source==RULE && truthy value
flag.exists()         // boolean: source != NOT_FOUND
```

`EvaluationSource` is an enum:

```java
switch (flag.source()) {
    case RULE    -> serveVariation(flag.variationKey().orElse("unknown"));
    case HOLDOUT -> serveControl();
    default      -> serveDefault();
}
```

### `ServerBuilder` options

```java
AvsbServer server = AvsbServer.builder(sdkKey)
    .datafileUrl("https://cdn.avsb.cloud/datafiles/" + sdkKey + ".json")
    .fetchTimeout(Duration.ofSeconds(5))
    .bootstrapDatafile(preloadedDoc)       // skip init() network call
    .runtimeOverrides(Map.of("flagA", true))
    .logger(mySlf4jLogger)
    .build();
```

---

## 7. Tracking events

```java
import com.avsbhq.avsb.TrackPayload;

server.track(
    "purchase_completed",
    ctx,
    TrackPayload.builder()
        .value(49.99)
        .properties(Map.of("plan", "pro", "currency", "usd"))
        .build()
);
```

`value` maps to the numeric metric quantity. `properties` is forwarded as-is to the collection endpoint. Both are optional; `TrackPayload.empty()` is a convenience constant.

---

## 8. Error handling

`AvsbServer` uses SLF4J for logging. Pass any SLF4J `Logger`-compatible wrapper at construction via `ServerBuilder.logger(...)`. If you supply no logger, the SDK defaults to a no-op logger:

```java
import org.slf4j.LoggerFactory;

AvsbServer server = AvsbServer.builder(sdkKey)
    .logger(LoggerFactory.getLogger("avsb"))
    .build();
```

`init()` returns an `InitResult` record rather than throwing. Check it at startup:

```java
InitResult result = server.init();
if (!result.isSuccess() && !result.isDegraded()) {
    throw new IllegalStateException("AvsB SDK failed to initialise: " + result.error());
}
// Degraded: datafile unavailable but bootstrap was supplied, safe to continue on defaults.
```

`get*Flag` methods never throw. Evaluation failures return the supplied `defaultValue` with `source == EvaluationSource.NOT_FOUND`.

---

## 9. Pre-evaluating flags for server-rendered responses

`AvsbServer` is thread-safe and designed as a singleton. In Spring Boot, it is auto-configured as a singleton bean. Pre-evaluate flag state at the top of each request handler and pass the results to your view or API response:

```java
@GetMapping("/checkout")
public String checkout(Model model, HttpServletRequest request) {
    String userId = getPrincipalId(request);
    EvalContext ctx = EvalContext.user(userId, Map.of("plan", getPlan(request)));

    model.addAttribute("newCheckout", server.getBoolFlag("checkout-v2",   false, ctx).isEnabled());
    model.addAttribute("oneClick",    server.getBoolFlag("one-click-buy", false, ctx).isEnabled());
    return "checkout";
}
```

### Spring Boot auto-configuration

Add `application.properties`:

```properties
avsb.sdk-key=sdk-my-key
avsb.polling-interval-seconds=30
```

The `AvsbServer` bean is automatically registered and initialised. Inject it anywhere:

```java
@RestController
public class FeatureController {
    @Autowired private AvsbServer avsbServer;
}
```

### Spring Boot interceptor

The starter registers `AvsbInterceptor` which resolves an `EvalContext` from the security principal on every request. Access it from `HttpServletRequest.getAttribute`:

```java
EvalContext ctx = (EvalContext) request.getAttribute(AvsbInterceptor.CONTEXT_ATTR);
Flag<Boolean> flag = avsbServer.getBoolFlag("my-feature", false, ctx);
```

### Micronaut integration

```java
@Singleton
public class FeatureService {
    @Inject AvsbServer avsbServer;

    public boolean isNewCheckout(String userId) {
        return avsbServer.getBoolFlag("checkout-v2", false, EvalContext.user(userId)).isEnabled();
    }
}
```

### Quarkus integration

```java
@ApplicationScoped
public class FeatureBean {
    @Inject AvsbServer avsbServer;
}
```

---

## 10. Graceful shutdown

`AvsbServer` implements `AutoCloseable`. Use try-with-resources in short-lived processes:

```java
try (AvsbServer server = AvsbServer.builder(sdkKey).build()) {
    server.init();
    // serve requests
}
// close() stops the polling scheduler and flushes pending events
```

In a long-running Spring Boot application, the SDK auto-registers a `DisposableBean` when using the starter. For manual lifecycle management, add a shutdown hook:

```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    try {
        server.close();
    } catch (Exception e) {
        // log
    }
}));
```

`close()` cancels the background polling `ScheduledExecutorService` and flushes any buffered decision log entries.

---

## 11. Testing

`TestUtils` provides factory methods for building an `AvsbServer` from inline JSON without network access:

```java
import com.avsbhq.avsb.TestUtils;
import com.avsbhq.avsb.AvsbServer;
import com.avsbhq.avsb.EvalContext;
import com.avsbhq.avsb.Flag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CheckoutFlagTest {

    private static final String DATAFILE_JSON = """
        {
          "version": 2,
          "sdkKey": "sdk-test",
          "flags": [{
            "id": "flag_checkout-v2",
            "key": "checkout-v2",
            "type": "boolean",
            "enabled": true,
            "defaultVariationId": "var_off",
            "variations": [
              {"id": "var_off", "key": "off", "value": false},
              {"id": "var_on",  "key": "on",  "value": true}
            ],
            "rules": []
          }],
          "audiences": []
        }
        """;

    @Test
    void flagExists() {
        AvsbServer server = TestUtils.serverFromJson("sdk-test", DATAFILE_JSON);

        EvalContext ctx  = EvalContext.user("alice");
        Flag<Boolean> flag = server.getBoolFlag("checkout-v2", false, ctx);

        assertThat(flag.exists()).isTrue();
        assertThat(flag.value()).isNotNull();
    }
}
```

`TestUtils.serverFromFile(sdkKey, Path)` reads a datafile from disk, useful for conformance fixture replay:

```java
AvsbServer server = TestUtils.serverFromFile("sdk-test",
    Path.of("packages/avsb-conformance/fixtures/datafiles/basic.json"));
```

`TestUtils.parseDatafile(json)` parses to a `Datafile.FlagDatafileDoc` record for direct inspection.

Run the test suite:

```bash
./gradlew test
```

The conformance test (`ConformanceTest.java`) automatically loads all fixtures from `packages/avsb-conformance/fixtures/manifest.json`.

---

## 12. Migration from LaunchDarkly / Statsig

### From LaunchDarkly Java SDK

```java
// LaunchDarkly
import com.launchdarkly.sdk.*;
import com.launchdarkly.sdk.server.*;

LDClient client = new LDClient("sdk-key");
LDContext user  = LDContext.create("alice");
boolean value   = client.boolVariation("checkout-v2", user, false);
```

```java
// A vs B
import com.avsbhq.avsb.*;

AvsbServer server = AvsbServer.builder(System.getenv("AVSB_SDK_KEY")).build();
server.init();

EvalContext ctx = EvalContext.user("alice", Map.of("plan", "pro"));
boolean value   = server.getBoolFlag("checkout-v2", false, ctx).value();
```

```java
// LaunchDarkly (multi-context)
LDContext multi = LDContext.createMulti(
    LDContext.create("alice"),
    LDContext.builder("acme").kind("organization").set("tier", "enterprise").build()
);
```

```java
// A vs B (multi-context)
EvalContext ctx = EvalContext.multi(Map.of(
    "user",         EvalContext.user("alice"),
    "organization", EvalContext.of("organization", "acme", Map.of("tier", "enterprise"))
));
```

Key differences:
- `getBoolFlag` requires an explicit `defaultValue`. No untyped `variation()`.
- Returns `Flag<T>` with full metadata. No separate `variationDetail()` call needed.
- `EvalContext` is a sealed interface with `SingleContext` and `MultiContext` record implementations; there's no builder for the common case.

### From Statsig Java SDK

```java
// Statsig
import com.statsig.sdk.*;

StatsigServer.initializeSync("secret-key");
StatsigUser user  = new StatsigUser("alice");
user.setCustom(Map.of("plan", "pro"));
boolean gate = StatsigServer.checkGate(user, "checkout-v2");
```

```java
// A vs B
EvalContext ctx = EvalContext.user("alice", Map.of("plan", "pro"));
boolean gate    = server.getBoolFlag("checkout-v2", false, ctx).isEnabled();
```

```java
// Statsig (log event)
StatsigServer.logEvent(user, "purchase", "49.99", Map.of("plan", "pro"));
```

```java
// A vs B (track event)
server.track("purchase", ctx, TrackPayload.builder().value(49.99)
    .properties(Map.of("plan", "pro")).build());
```

### OpenFeature provider

```java
import dev.openfeature.sdk.*;
import com.avsbhq.avsb.OpenFeatureProvider;

OpenFeatureAPI.getInstance().setProvider(new OpenFeatureProvider(server));
Client client = OpenFeatureAPI.getInstance().getClient();

boolean value = client.getBooleanValue("checkout-v2", false,
    new ImmutableContext("alice"));
```
