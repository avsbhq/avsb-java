# avsb-sdk: Java SDK

Official Java SDK for the [A vs B](https://app.avsb.cloud) feature-flag and experimentation platform.

Java 17 or later. Evaluation happens in your process against a datafile read from a globally cached CDN, so a flag check is a few microseconds of local work with no network call and no proxy to operate.

Spring Boot 3, Micronaut 4, and Quarkus 3 wiring included. OpenFeature provider included.

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

One artifact, `com.avsbhq:avsb-sdk`. There is no separate Spring Boot starter: the Spring auto-configuration ships inside this jar and activates when `avsb.sdk-key` is set (see section 9).

**Runtime requirements**

- Java 17 or later.
- Jackson `jackson-databind` 2.17 or later at RUNTIME. It is declared `implementation`, not `api`, so it is not on your compile classpath and no public type in this SDK exposes a Jackson type. Your application is free to compile against a different Jackson version.

Optional, only if you use the matching feature:

| Dependency | Needed for |
| --- | --- |
| `dev.openfeature:sdk` | the OpenFeature provider |
| `redis.clients:jedis` | `RedisStickyBucketService` |
| Spring Boot / Micronaut / Quarkus | the matching framework wiring |

---

## 2. Quickstart

```java
import com.avsbhq.avsb.AvsbServer;
import com.avsbhq.avsb.EvalContext;
import com.avsbhq.avsb.Flag;
import com.avsbhq.avsb.InitResult;

public final class Main {
    public static void main(String[] args) {
        AvsbServer server = AvsbServer.builder(System.getenv("AVSB_SDK_KEY")).build();

        InitResult init = server.init();
        if (!init.success()) {
            // Flags still work: every call returns the default you pass, and the SDK has
            // already logged the URL it tried, the status it got, and a curl to reproduce it.
            System.err.println("AvsB init failed: " + init.error());
        }

        Flag<Boolean> flag = server.getBoolFlag(
                "checkout-v2", false, EvalContext.user("user_42"));

        if (flag.isEnabled()) {
            // new checkout
        }

        server.track("signup_completed", EvalContext.user("user_42"));

        server.close();   // stops the refresh loop and flushes queued events
    }
}
```

Copy your SDK key from the A vs B dashboard: Settings, then Environments. Keys look like `sdk_production_ttqm0eaj4vth1krcb2xn`. Nothing else is required: the datafile URL, the ingest endpoint, and the heartbeat endpoint are all derived.

---

## 3. What the SDK does for you

| | |
| --- | --- |
| Datafile | Fetched from `https://cdn.avsb.cloud/{sdkKey}/datafile.json` on `init()`, then refreshed every 30 seconds (jittered) on a daemon thread. |
| Evaluation | Local, synchronous, no network call. |
| Events | `track` and exposures are queued, batched, retried with jittered backoff, and flushed on `close()`. |
| Liveness | A heartbeat after each successful datafile load drives the Connected state on the dashboard's Environments page. |
| Failure | Loud. Every failure names the value involved and what to do about it. |

Threads it starts, both daemons, so neither can keep your JVM alive:

- `avsb-datafile-refresh`, one per client, started by `init()`.
- `avsb-tracker`, one per client, started when a datafile first loads.

---

## 4. Identity

### `EvalContext`

```java
public sealed interface EvalContext permits EvalContext.SingleContext, EvalContext.MultiContext {
    String kind();

    static SingleContext user(String key);
    static SingleContext user(String key, Map<String, Object> attributes);
    static SingleContext of(String kind, String key);
    static SingleContext of(String kind, String key, Map<String, Object> attributes);
    static MultiContext multi(Map<String, SingleContext> contexts);
}

public record SingleContext(
        String kind,                        // never null or blank
        String key,                         // never null; may be empty, which warns
        String name,                        // nullable
        Map<String, Object> attributes       // never null; immutable
) implements EvalContext {
    Object getAttribute(String attrKey);     // nullable
}

public record MultiContext(
        Map<String, SingleContext> contexts   // at least one entry; immutable
) implements EvalContext {
    SingleContext get(String kind);           // nullable
}
```

```java
EvalContext ctx = EvalContext.user("user_42", Map.of("plan", "pro", "seats", 25));
```

The `key` is what buckets the user, so it must be stable for the same person. An empty key means every request lands in the same bucket, which corrupts an experiment invisibly, so the SDK warns once per client naming the call to add.

### Multi-context, for organisation-keyed targeting

```java
EvalContext ctx = EvalContext.multi(Map.of(
        "user", EvalContext.of("user", "user_42", Map.of("plan", "pro")),
        "organization", EvalContext.of("organization", "org_7", Map.of("tier", "enterprise"))));
```

An audience condition can then target `organization.tier`, and a rule can bucket by `organization.key` so every member of an organisation sees the same variation.

---

## 5. Reading flags

### The four typed getters

```java
Flag<Boolean> getBoolFlag(String key, boolean defaultValue, EvalContext context);
Flag<String>  getStringFlag(String key, String defaultValue, EvalContext context);
Flag<Double>  getNumberFlag(String key, double defaultValue, EvalContext context);
Flag<Object>  getJsonFlag(String key, Object defaultValue, EvalContext context);
```

Plus the generic forms:

```java
<T> Flag<T> getJsonFlag(String key, T defaultValue, FlagValueType<T> type, EvalContext context);
<T> Flag<T> getFlag(String key, T defaultValue, FlagValueType<T> type, EvalContext context);
Map<String, Object> getAllFlags(EvalContext context);
```

Notes that are not obvious:

- `getNumberFlag` always resolves to a `Double`, never an `Integer`. The reference engine has a single numeric type and cannot distinguish `3` from `3.0`, so a Java SDK that sometimes handed back an `Integer` would make `Flag<Double>` unsafe to read.
- `getJsonFlag(key, default, context)` returns the document as deserialised: a `Map<String, Object>`, a `List<Object>`, a boxed primitive, or null.
- `getAllFlags` returns raw untyped values for bootstrapping a browser client from a server-rendered page. It fires exposures for every decided flag, exactly as if each had been read individually.

### Type safety, and what happens on a mismatch

Java erases generics, so a cast inside the SDK cannot fail where it is written. This SDK previously did:

```java
try { typed = (T) raw; } catch (ClassCastException e) { typed = defaultValue; }
```

The catch was dead code. The `ClassCastException` was thrown later, in YOUR code, at `flag.value()`, when the JVM inserted its checkcast: a JSON number reaching a `Flag<Double>` blew up at the unbox with a stack trace pointing at your application.

Every typed getter now takes a type token, so the check happens during evaluation. Stated once, true for all of them:

| Situation | What you get |
| --- | --- |
| Value resolves and the type matches | the value |
| Resolved value is the wrong type | your default, `source = not_found`, one reason naming the flag and what it actually resolved to, plus a logged warning |
| Datafile declares a different type than the getter asked for | your default, `source = not_found`, one reason naming both types and the getter to use instead |
| No datafile loaded yet | your default, `source = not_ready` |

It never throws, and it never coerces. A string flag read through `getBoolFlag` does not become `true`.

### Reading a JSON flag into your own type

```java
record Pricing(String plan, double amount) {}

Flag<Pricing> pricing = server.getJsonFlag(
        "pricing",
        new Pricing("standard", 9.99),
        FlagValueType.json(Pricing.class),
        EvalContext.user("user_42"));

double amount = pricing.value().amount();
```

A document that will not map into `Pricing` gives you the default plus a reason, not a deserialisation exception.

### `FlagValueType<T>`

```java
public final class FlagValueType<T> {
    public static final FlagValueType<Boolean> BOOLEAN;   // JSON boolean only
    public static final FlagValueType<String>  STRING;    // JSON string only
    public static final FlagValueType<Double>  NUMBER;    // any JSON number, widened to double
    public static final FlagValueType<Object>  JSON;      // any JSON document

    public static <T> FlagValueType<T> json(Class<T> as);

    public String declaredType();                          // "boolean" | "string" | "number" | "json"
    public Coerced<T> coerce(Object raw);
    public boolean matchesDeclaredType(String datafileType);

    public record Coerced<T>(boolean matched, T value, String mismatch) {}
}
```

### `Flag<T>`

```java
public record Flag<T>(
        T value,                    // your default when the datafile could not answer
        String variationKey,        // nullable: null when no variation was served
        EvaluationSource source,    // never null
        String ruleId,              // nullable: null unless a rule or holdout decided
        RuleType ruleType,          // nullable: null unless a rule or holdout decided
        List<String> reasons,       // never null, immutable, ordered
        long evaluatedAt,           // ms since the Unix epoch
        long durationMicros         // microseconds spent inside the evaluator
) {
    boolean isEnabled();
    boolean exists();
}
```

`isEnabled()` is true when BOTH hold:

1. `source` is one of `rule`, `holdout`, `bandit`, `runtimeOverride`, `datafileOverride`, `sticky`. A flag serving its DEFAULT variation is not enabled, however truthy that default is. Neither is a disabled flag, a missing flag, or an SDK that has not initialised.
2. The value is truthy under JAVASCRIPT rules, because the reference engine is TypeScript and the answer has to be identical in every A vs B SDK: `false`, `0`, `-0`, `NaN`, `""`, and null are falsy. Everything else is truthy, INCLUDING the string `"false"`, the string `"0"`, an empty list, and an empty map.

`exists()` is false for `not_found` and for `not_ready`, and true for everything else including `disabled`. A disabled flag exists: the customer turned it off, which is not the same as it being absent.

### `EvaluationSource`

```java
public enum EvaluationSource {
    DATAFILE_OVERRIDE,   // "datafileOverride" — a per-user override in the datafile
    RUNTIME_OVERRIDE,    // "runtimeOverride"  — builder().runtimeOverrides(...)
    STICKY,              // "sticky"           — a stored sticky-bucket assignment
    RULE,                // "rule"             — a targeted delivery or AB test matched
    HOLDOUT,             // "holdout"          — inside a holdout window
    BANDIT,              // "bandit"           — the bandit engine chose
    DEFAULT,             // "default"          — no rule matched, flag default variation
    NOT_FOUND,           // "not_found"        — the key is not in the datafile
    DISABLED,            // "disabled"         — the flag is turned off
    NOT_READY            // "not_ready"        — there is no datafile to look in
}
```

`NOT_READY` is the one to know about. It means init has not completed, the fetch failed with no bootstrap, or the client was closed. It is deliberately NOT `NOT_FOUND`: "the flag does not exist" sent people hunting through the dashboard for a flag that was there all along, when the real problem was the init call.

### `RuleType`

```java
public enum RuleType { TARGETED_DELIVERY, AB_TEST, HOLDOUT, BANDIT }
```

---

## 6. Builder options

```java
AvsbServer server = AvsbServer.builder("sdk_production_ttqm0eaj4vth1krcb2xn")
        .logger(myLogger)                                  // default: warn and error to stderr
        .cdnHost("https://cdn.example.test")               // default: https://cdn.avsb.cloud
        .datafileUrl("https://internal/df.json")            // default: derived from the key
        .fetchTimeout(Duration.ofSeconds(10))              // default: 10 seconds
        .pollInterval(Duration.ofSeconds(30))              // default: 30 seconds
        .polling(true)                                      // default: true
        .tracking(true)                                     // default: true
        .runtimeOverrides(Map.of("checkout-v2", "on"))      // default: none
        .bootstrapDatafile(parsedDoc)                       // default: none
        .stickyBucketService(new InMemoryStickyBucketService())
        .banditModelStore(myStore)                          // default: cold start
        .decisionLogCapacity(1000)                          // default: 1000; 0 turns it off
        .build();
```

`runtimeOverrides` maps a flag key to a VARIATION KEY, and the SDK resolves it against the datafile so the served value and its key are real. A variation key that does not exist is logged and ignored rather than serving something invented.

---

## 7. Tracking events

```java
void track(String eventName, EvalContext context);
void track(String eventName, EvalContext context, TrackPayload payload);
void trackPurchase(EvalContext context, PurchaseOrder order);
void flushEvents();
```

```java
server.track("signup_completed", ctx);
server.track("cart_value", ctx, TrackPayload.value(129.50));
server.track("upgrade", ctx, TrackPayload.revenue(49.00));

server.trackPurchase(ctx, PurchaseOrder.of(
        "ORD-1001", 99.95, "USD",
        List.of(PurchaseOrder.Item.of("SKU-1", 99.95, 1))));
```

- `eventName` is the metric key as configured in the dashboard. An unknown key is accepted and stored, so a typo shows up as an empty metric rather than an error.
- Money is DECIMAL MAJOR UNITS as displayed: `99.95`, not `9995`.
- Metric events carry no free-form properties. The ingestion body has no such field, so anything sent under that name would look delivered and be gone. Put the number in `TrackPayload.value(...)` and segment on registered context attributes instead.
- Purchases are sent immediately rather than batched, because an order is the event you least want buffered.

`TrackPayload`:

```java
public record TrackPayload(
        Double value,        // nullable: a numeric metric value
        Double revenue,      // nullable: money in decimal major units
        EvalContext context   // nullable: identity override for this one event
) {
    static TrackPayload value(double value);
    static TrackPayload revenue(double revenue);
    static TrackPayload valueAndRevenue(double value, double revenue);
    static TrackPayload withContext(EvalContext context);
    static TrackPayload empty();
    TrackPayload forContext(EvalContext newContext);
}
```

### Exposures

Every AB test, bandit, and holdout decision records an exposure automatically. Without exposures a server-side experiment produces no results, so this is on by default and needs no code.

Turn all event sending off with `.tracking(false)`. Then `track` says so in a warning rather than dropping the event silently.

---

## 8. Errors and diagnostics

Nothing fails quietly. Concretely:

- **Datafile fetch failure** logs the URL tried, the status, what a 404 or 403 usually means, and a `curl -i` line that reproduces it.
- **A key that is not shaped like an SDK key** logs at construction, names what it looks like instead (a URL, an API token, a truncated copy), and the SDK proceeds anyway because the fetch is the real arbiter.
- **An empty bucketing key** warns once per client, naming `EvalContext.user("<your user id>")`.
- **A dropped event** names the event and says why sending is not running.
- **A bandit rule that cannot dispatch** appends a reason naming the rule and the reason, so it never behaves like "no rules matched" in silence.
- **A type mismatch** logs the flag, the declared type, and the getter to use instead.

### Logging

```java
public interface Logger {
    void debug(String message, Map<String, Object> data);
    void info(String message, Map<String, Object> data);
    void warn(String message, Map<String, Object> data);
    void error(String message, Map<String, Object> data);

    default Logger child(String prefix);

    static Logger noOp();
    static Logger console();
    static Logger warnLevelConsole();
    static Logger defaultLogger();
    static boolean isProduction();
}
```

The default is `defaultLogger()`: warnings and errors to standard error, debug and info dropped. It drops to `noOp()` when the process declares itself production through `AVSB_ENV`, `APP_ENV`, `ENVIRONMENT`, `SPRING_PROFILES_ACTIVE` (any value containing "prod"), or the `avsb.env` system property.

Route it into your own framework by implementing the interface:

```java
org.slf4j.Logger slf4j = LoggerFactory.getLogger("avsb");

AvsbServer server = AvsbServer.builder(key)
        .logger(new Logger() {
            @Override public void debug(String m, Map<String, Object> d) { slf4j.debug(m); }
            @Override public void info(String m, Map<String, Object> d)  { slf4j.info(m); }
            @Override public void warn(String m, Map<String, Object> d)  { slf4j.warn(m); }
            @Override public void error(String m, Map<String, Object> d) { slf4j.error(m); }
        })
        .build();
```

### Decision log

Every evaluation appends one entry, so you have an audit trail for "why did that user see that variation".

```java
for (DecisionLogEntry entry : server.decisionLog().entries()) {
    System.out.println(entry.flagKey() + " -> " + entry.variationKey()
            + " (" + entry.source().wireValue() + ")");
}
```

The log is BOUNDED: it keeps the most recent 1000 entries and drops the oldest, so a service that never reads it pays a fixed cost. Change the bound with `.decisionLogCapacity(n)`, or pass 0 to turn recording off. Attribute values you mark private are redacted out of the reason strings:

```java
EvalContext ctx = EvalContext.user("user_42", Map.of(
        "email", "alice@example.com",
        "_meta", Map.of("privateAttributes", List.of("email"))));
```

---

## 9. Framework wiring

### Spring Boot 3

Auto-configuration lives in this jar and activates when `avsb.sdk-key` is set. It registers one `AvsbServer` bean and calls `init()`.

```properties
avsb.sdk-key=sdk_production_ttqm0eaj4vth1krcb2xn
# Optional:
avsb.datafile-url=https://cdn.avsb.cloud/sdk_production_ttqm0eaj4vth1krcb2xn/datafile.json
avsb.fetch-timeout=10s
```

```java
@RestController
public class CheckoutController {

    private final AvsbServer avsb;

    public CheckoutController(AvsbServer avsb) {
        this.avsb = avsb;
    }

    @GetMapping("/checkout")
    public String checkout(Principal principal) {
        EvalContext ctx = EvalContext.user(principal.getName());
        return avsb.getBoolFlag("checkout-v2", false, ctx).isEnabled() ? "v2" : "v1";
    }
}
```

To bind a context once per request instead of per controller, register `AvsbInterceptor` and override `buildContext`:

```java
@Bean
public AvsbInterceptor avsbInterceptor() {
    return new AvsbInterceptor() {
        @Override
        protected EvalContext buildContext(HttpServletRequest request) {
            return EvalContext.user((String) request.getSession().getAttribute("userId"));
        }
    };
}

// then, inside a handler:
EvalContext ctx = AvsbInterceptor.currentContext();
```

### Micronaut 4

`AvsbFactory` produces the singleton; `AvsbFilter` binds a per-request context.

```yaml
avsb:
  sdk-key: sdk_production_ttqm0eaj4vth1krcb2xn
```

### Quarkus 3

`AvsbProducer` produces an application-scoped bean; `AvsbRequestFilter` binds a per-request context.

```properties
avsb.sdk-key=sdk_production_ttqm0eaj4vth1krcb2xn
```

---

## 10. Sticky bucketing

Keeps a user in the variation they were first assigned, even if the rule's split changes later.

```java
public interface StickyBucketService {
    StickyAssignment lookup(String userId, String flagKey);           // nullable
    void save(String userId, String flagKey, StickyAssignment assignment);
}

public record StickyAssignment(
        String variationId,   // the variation's ID, not its key
        String ruleId,
        RuleType ruleType,
        long assignedAt       // ms since the Unix epoch
) {}
```

```java
AvsbServer server = AvsbServer.builder(key)
        .stickyBucketService(new InMemoryStickyBucketService())   // or RedisStickyBucketService
        .build();
```

How it behaves, so nothing here surprises you:

- The lookup runs BEFORE the evaluator, so a stored assignment wins over a rule and even over a datafile override. A user who was already bucketed stays bucketed.
- A save happens ONLY for an `ab_test` rule that served a variation. A targeted delivery is a rollout, not an assignment, and a holdout or an override is not the user's bucket.
- What is stored is the variation's ID. Saving the key would corrupt the store, because lookup resolves by id and would never match again.
- If the stored variation has since been deleted from the flag, the lookup is treated as a MISS and the evaluator answers instead. Serving a variation that no longer exists is impossible.
- If the variation still exists but its rule no longer serves it, the stored value is still served, a warning is logged, and the exposure is suppressed, because the rule it would be attributed to is gone.

---

## 11. Bandits

A `bandit` rule dispatches through the bandit engine. With no model store configured the engine runs its documented cold-start policy, so a bandit rule serves a real decision from day one instead of behaving like a rule that never matched.

```java
Bandits.ModelStore store = Bandits.inMemoryStore(Map.of(
        "checkout-copy-bandit", new Bandits.BanditModel("v1", "ucb1", Map.of(
                "action_bold",  new Bandits.BanditPerActionStats(0.42, 0.05, 800),
                "action_plain", new Bandits.BanditPerActionStats(0.31, 0.05, 790)))));

AvsbServer server = AvsbServer.builder(key).banditModelStore(store).build();
```

Every path a bandit rule can take appends a reason: dispatched, no config, unresolvable `banditId`, no actions, no model in a supplied store, or an action pointing at a variation the flag no longer has.

---

## 12. Testing

`TestUtils` builds a client from a datafile string or file, with polling, event sending, and logging all off, so a test never touches the network.

```java
import com.avsbhq.avsb.TestUtils;

AvsbServer server = TestUtils.serverFromJson("sdk_test_abcdefgh", """
        {
          "version": 2,
          "projectType": "FEATURE_FLAG",
          "sdkKey": "sdk_test_abcdefgh",
          "environmentKey": "test",
          "publishedAt": "2026-05-29T00:00:00.000Z",
          "collectEndpoint": "https://ingest.avsb.cloud",
          "flags": [
            {
              "id": "f1", "key": "checkout-v2", "type": "boolean", "enabled": true,
              "defaultVariationId": "var_off",
              "variations": [
                {"id": "var_off", "key": "off", "value": false},
                {"id": "var_on",  "key": "on",  "value": true}
              ],
              "overrides": [],
              "rules": [
                {
                  "id": "r1", "type": "targeted_delivery", "enabled": true,
                  "audienceIds": [], "hashAttribute": "user.key",
                  "trafficAllocation": null,
                  "variations": [{"variationId": "var_on", "percentage": 1}]
                }
              ]
            }
          ],
          "audiences": []
        }
        """);

assertTrue(server.getBoolFlag("checkout-v2", false, EvalContext.user("alice")).isEnabled());
```

For a fixed answer without a datafile, use `runtimeOverrides`:

```java
AvsbServer server = AvsbServer.builder("sdk_test_abcdefgh")
        .bootstrapDatafile(TestUtils.parseDatafile(json))
        .runtimeOverrides(Map.of("checkout-v2", "on"))
        .polling(false)
        .tracking(false)
        .build();
```

`TestUtils` surface:

```java
static AvsbServer serverFromJson(String sdkKey, String jsonDatafile);
static AvsbServer serverFromFile(String sdkKey, Path datafilePath);
static Datafile.FlagDatafileDoc parseDatafile(String json);
static Datafile.FlagDatafileDoc parseDatafileFromPath(Path path);
static String readResource(String resourcePath);
```

### Conformance

`src/test/java/com/avsbhq/avsb/ConformanceTest.java` runs the shared A vs B conformance corpus: the same datafiles, contexts, and expected outcomes every other A vs B SDK runs, generated from the TypeScript reference engine. Values, variation keys, sources, rule ids, rule types, and the exact ordered reason strings are all compared.

A missing or empty corpus FAILS the build. It used to emit a passing test named "manifest not found, skipped", which meant the published standalone repo ran zero cases and reported green.

---

## 13. OpenFeature

```java
OpenFeatureAPI.getInstance().setProviderAndWait(new OpenFeatureProvider(server));
Client client = OpenFeatureAPI.getInstance().getClient();

boolean enabled = client.getBooleanValue("checkout-v2", false,
        new MutableContext("user_42"));
```

Context mapping, including the kinds OpenFeature has no native concept of:

```java
// Multi-kind: organisation-keyed targeting survives the bridge.
EvaluationContext ctx = new MutableContext("user_42")
        .add("kind", "multi")
        .add("user", new MutableStructure().add("key", "user_42").add("plan", "pro"))
        .add("organization", new MutableStructure()
                .add("key", "org_7")
                .add("tier", "enterprise"));

// Single non-user kind.
EvaluationContext orgCtx = new MutableContext("org_7")
        .add("kind", "organization")
        .add("tier", "enterprise");

// No kind at all becomes a user context.
EvaluationContext simple = new MutableContext("user_42");
```

Error codes are mapped so an OpenFeature caller can tell the two failures apart: an SDK with no datafile yet reports `PROVIDER_NOT_READY`, and a missing flag reports `FLAG_NOT_FOUND`.

`Flag.source()` carries more detail than the OpenFeature reason vocabulary does, so several sources collapse to `TARGETING_MATCH`. Hold the `AvsbServer` directly if you need the precise source.

---

## 14. Shutdown

```java
server.close();
```

`close()` stops the refresh loop, flushes queued events (blocking for that final send), and puts the client into a not-ready state. Both background threads are daemons, so forgetting `close()` costs the last batch of events rather than a hung JVM.

In Spring Boot the bean's lifecycle handles this if you expose it:

```java
@Bean(destroyMethod = "close")
public AvsbServer avsbServer(AvsbProperties props) { /* ... */ }
```

---

## 15. Migrating

### From the LaunchDarkly Java SDK

| LaunchDarkly | A vs B |
| --- | --- |
| `LDClient client = new LDClient(sdkKey)` | `AvsbServer server = AvsbServer.builder(sdkKey).build(); server.init();` |
| `client.boolVariation(key, ctx, false)` | `server.getBoolFlag(key, false, ctx).value()` |
| `client.boolVariationDetail(...)` | `server.getBoolFlag(...)` returns the detail already |
| `LDContext.builder("user-key").build()` | `EvalContext.user("user-key")` |
| `LDContext.createMulti(a, b)` | `EvalContext.multi(Map.of(...))` |
| `client.track("event", ctx)` | `server.track("event", ctx)` |
| `client.isFlagKnown(key)` | `server.getBoolFlag(key, false, ctx).exists()` |
| `client.close()` | `server.close()` |

### From the Statsig Java SDK

| Statsig | A vs B |
| --- | --- |
| `Statsig.initialize(secret)` | `AvsbServer.builder(sdkKey).build().init()` |
| `Statsig.checkGate(user, "gate")` | `server.getBoolFlag("gate", false, ctx).isEnabled()` |
| `Statsig.getConfig(user, "config")` | `server.getJsonFlag("config", Map.of(), ctx).value()` |
| `StatsigUser("user-id")` | `EvalContext.user("user-id")` |
| `Statsig.logEvent(user, "event")` | `server.track("event", ctx)` |
| `Statsig.shutdown()` | `server.close()` |

---

## 16. Licence

MIT. See [LICENSE](./LICENSE).
