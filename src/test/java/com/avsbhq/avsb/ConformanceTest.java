package com.avsbhq.avsb;

import com.avsbhq.avsb.core.Datafile;
import com.avsbhq.avsb.core.Evaluator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance test suite: runs every case declared in the conformance corpus manifest and
 * asserts that the Java evaluator produces exactly the same output as the TypeScript
 * reference recorded in the fixture.
 *
 * <p>FAIL-HARD RULE (conformance README): a missing manifest, an empty manifest, or a
 * missing per-case file FAILS. This class used to emit a passing dynamic test literally
 * named "manifest not found, skipped", so the published avsb-java repo (where the
 * monorepo-relative fixture path does not exist) ran zero cases and reported green.
 *
 * <p>Fixture resolution walks up from the working directory looking for the monorepo copy,
 * then falls back to the copy vendored into this package's test resources, which is what the
 * published repo carries.
 *
 * <p>Value comparison is TYPED. It used to compare {@code String.valueOf(expected)} against
 * {@code String.valueOf(actual)}, which passes when a number flag hands back the string
 * {@code "1"} instead of the number {@code 1}, when a JSON object's key order differs, and
 * when the boolean {@code true} arrives as the text {@code "true"}: the exact type-fidelity
 * failures the {@code typed} fixture family exists to catch. Both sides are now converted to
 * JSON nodes and compared structurally.
 *
 * <p>The fixture resolution, JSON reading and context parsing are package-private so
 * {@link ConformanceAllocationTest} replays the allocation family through the same code.
 */
@DisplayName("Conformance — all fixture cases")
class ConformanceTest {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** How far up the tree to look for the monorepo packages directory. */
    private static final int MAX_UPWARD_LEVELS = 6;

    // -------------------------------------------------------------------------
    // Fixture discovery
    // -------------------------------------------------------------------------

    private static List<Path> fixtureCandidates() {
        Path projectDir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        List<Path> candidates = new ArrayList<>();

        // Monorepo: walk up looking for <...>/avsb-conformance/fixtures. Gradle sets
        // user.dir to packages/avsb-java, but the walk keeps this working when it is invoked
        // from the repo root instead.
        Path cursor = projectDir;
        for (int i = 0; i < MAX_UPWARD_LEVELS && cursor != null; i++) {
            candidates.add(cursor.resolve(Paths.get("avsb-conformance", "fixtures")).normalize());
            candidates.add(cursor.resolve(Paths.get("packages", "avsb-conformance", "fixtures")).normalize());
            cursor = cursor.getParent();
        }

        // Published repo: vendored copy in this package's test resources.
        candidates.add(projectDir.resolve(
                Paths.get("src", "test", "resources", "avsb-conformance", "fixtures")).normalize());

        return candidates;
    }

    static Path fixturesRoot() {
        List<Path> candidates = fixtureCandidates();
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate.resolve("manifest.json"))) {
                return candidate;
            }
        }
        StringBuilder looked = new StringBuilder();
        for (Path candidate : candidates) {
            looked.append("\n  ").append(candidate);
        }
        throw new IllegalStateException(
                "Conformance fixtures not found. Looked for manifest.json in:" + looked
                        + "\nIn the monorepo, run `npm run generate` in packages/avsb-conformance."
                        + " For this repo standalone, run `npm run vendor` there and commit"
                        + " src/test/resources/avsb-conformance.");
    }

    static JsonNode readJson(Path path, String what) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(
                    "Conformance fixture missing: " + path + " (" + what + ")."
                            + " Run `npm run generate` in packages/avsb-conformance.");
        }
        try {
            return MAPPER.readTree(path.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Conformance fixture is not valid JSON: " + path, e);
        }
    }

    private static JsonNode manifest(Path fixtures) {
        JsonNode manifest = readJson(fixtures.resolve("manifest.json"), "manifest");
        JsonNode cases = manifest.get("cases");
        if (cases == null || !cases.isArray() || cases.isEmpty()) {
            throw new IllegalStateException(
                    "Conformance manifest at " + fixtures + " lists no cases."
                            + " An empty corpus guarantees nothing.");
        }
        JsonNode declared = manifest.get("caseCount");
        if (declared != null && declared.asInt() != cases.size()) {
            throw new IllegalStateException(
                    "Conformance manifest says caseCount=" + declared.asInt() + " but carries "
                            + cases.size() + " cases, so it was hand-edited.");
        }
        return manifest;
    }

    // -------------------------------------------------------------------------
    // Corpus integrity
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("the corpus is present and not empty")
    void corpusIsNotEmpty() {
        Path fixtures = fixturesRoot();
        assertTrue(manifest(fixtures).get("cases").size() > 0);
    }

    @Test
    @DisplayName("no fixture file is left behind by a renamed or deleted case")
    void noOrphanFixtures() throws IOException {
        Path fixtures = fixturesRoot();
        Set<String> named = new HashSet<>();
        for (JsonNode c : manifest(fixtures).get("cases")) {
            named.add(c.get("case").asText() + ".json");
        }

        List<String> orphans = new ArrayList<>();
        try (Stream<Path> files = Files.list(fixtures.resolve("expected"))) {
            files.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .filter(name -> !named.contains(name))
                    .forEach(orphans::add);
        }
        assertEquals(List.of(), orphans, "fixtures not named by the manifest");
    }

    // -------------------------------------------------------------------------
    // Dynamic test factory
    // -------------------------------------------------------------------------

    @TestFactory
    @DisplayName("fixture cases from manifest.json")
    Stream<DynamicTest> conformanceCases() {
        Path fixtures = fixturesRoot();
        JsonNode cases = manifest(fixtures).get("cases");

        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : cases) {
            String caseName = c.get("case").asText();
            String expectedFile = c.get("expected").asText();
            tests.add(DynamicTest.dynamicTest(caseName, () ->
                    runConformanceCase(fixtures, expectedFile, caseName)));
        }
        return tests.stream();
    }

    // -------------------------------------------------------------------------
    // Per-case runner
    // -------------------------------------------------------------------------

    private void runConformanceCase(Path fixturesRoot, String expectedRel, String caseName) {
        JsonNode expected = readJson(fixturesRoot.resolve(expectedRel), "manifest case " + caseName);
        assertEquals(caseName, expected.get("case").asText(),
                "fixture " + expectedRel + " declares a different case id than the manifest");

        // Load datafile
        Path datafilePath = fixturesRoot.resolve(expected.get("datafile").asText());
        if (!Files.isRegularFile(datafilePath)) {
            throw new IllegalStateException("Conformance datafile missing: " + datafilePath);
        }
        Datafile.FlagDatafileDoc datafile;
        try {
            datafile = MAPPER.readValue(datafilePath.toFile(), Datafile.FlagDatafileDoc.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot parse datafile " + datafilePath, e);
        }

        // Load context
        JsonNode contextJson = readJson(
                fixturesRoot.resolve(expected.get("context").asText()), "context for " + caseName);
        EvalContext ctx = parseContext(contextJson);

        // Evaluate. Reasons are part of the corpus, so the evaluator always collects them.
        String flagKey = expected.get("flagKey").asText();
        Object defaultValue = jsonToObject(expected.get("defaultValue"));
        Flag<Object> flag = Evaluator.evaluateFlag(flagKey, defaultValue, ctx, datafile);

        JsonNode exp = expected.get("expected");

        // value — structural JSON comparison, so type fidelity is actually asserted.
        JsonNode expectedValue = exp.get("value");
        // valueToTree(null) hands back a Java null rather than a NullNode, which would make
        // the comparison below throw instead of failing with a message.
        JsonNode actualValue = flag.value() == null
                ? MAPPER.nullNode()
                : MAPPER.valueToTree(flag.value());
        assertEquals(expectedValue, actualValue,
                "value mismatch for case " + caseName + ": expected " + expectedValue
                        + " (" + nodeKind(expectedValue) + ") but got " + actualValue
                        + " (" + nodeKind(actualValue) + ")");

        // variationKey
        String expectedVarKey = exp.get("variationKey").isNull()
                ? null : exp.get("variationKey").asText();
        assertEquals(expectedVarKey, flag.variationKey(),
                "variationKey mismatch for case " + caseName);

        // source — always asserted; a fixture without one is malformed.
        EvaluationSource expectedSource = EvaluationSource.fromWire(exp.get("source").asText());
        assertEquals(expectedSource, flag.source(), "source mismatch for case " + caseName);

        // ruleId
        String expectedRuleId = exp.get("ruleId").isNull() ? null : exp.get("ruleId").asText();
        assertEquals(expectedRuleId, flag.ruleId(), "ruleId mismatch for case " + caseName);

        // ruleType
        RuleType expectedRuleType = exp.get("ruleType").isNull()
                ? null : RuleType.fromWire(exp.get("ruleType").asText());
        assertEquals(expectedRuleType, flag.ruleType(), "ruleType mismatch for case " + caseName);

        // reasons — exact, ordered comparison (spec section 1.4).
        List<String> expectedReasons = new ArrayList<>();
        for (JsonNode r : exp.get("reasons")) expectedReasons.add(r.asText());
        assertEquals(expectedReasons, flag.reasons(), "reasons mismatch for case " + caseName);
    }

    /** Node type name, so a value mismatch says string-versus-number rather than 1 vs 1. */
    private static String nodeKind(JsonNode node) {
        return node == null ? "absent" : node.getNodeType().toString().toLowerCase(Locale.ROOT);
    }

    // -------------------------------------------------------------------------
    // Context deserialisation from fixture JSON
    // -------------------------------------------------------------------------

    static EvalContext parseContext(JsonNode json) {
        String kind = json.has("kind") ? json.get("kind").asText() : "user";
        if ("multi".equals(kind)) {
            Map<String, EvalContext.SingleContext> subCtxs = new LinkedHashMap<>();
            json.fields().forEachRemaining(entry -> {
                if ("kind".equals(entry.getKey())) return;
                JsonNode subNode = entry.getValue();
                String subKind = subNode.has("kind") ? subNode.get("kind").asText() : entry.getKey();
                String subKey  = subNode.has("key") ? subNode.get("key").asText() : "";
                Map<String, Object> attrs = new LinkedHashMap<>();
                subNode.fields().forEachRemaining(attr -> {
                    if (!"kind".equals(attr.getKey()) && !"key".equals(attr.getKey()) && !"name".equals(attr.getKey())) {
                        attrs.put(attr.getKey(), jsonToObject(attr.getValue()));
                    }
                });
                subCtxs.put(subKind, EvalContext.of(subKind, subKey, attrs));
            });
            return EvalContext.multi(subCtxs);
        }
        // SingleContext
        String key = json.has("key") ? json.get("key").asText() : "";
        Map<String, Object> attrs = new LinkedHashMap<>();
        json.fields().forEachRemaining(entry -> {
            if (!"kind".equals(entry.getKey()) && !"key".equals(entry.getKey()) && !"name".equals(entry.getKey())) {
                attrs.put(entry.getKey(), jsonToObject(entry.getValue()));
            }
        });
        return EvalContext.of(kind, key, attrs);
    }

    // -------------------------------------------------------------------------
    // JSON value coercion
    // -------------------------------------------------------------------------

    /**
     * Convert a fixture node to the same plain-Java shapes Jackson produces when it parses a
     * datafile into {@code Object}: Boolean, Integer, Long, Double, String, LinkedHashMap,
     * ArrayList, or null.
     *
     * <p>Using the mapper rather than a hand-written switch matters for the default-value
     * cases. A hand-written {@code asLong()} branch turned the fixture default {@code -1}
     * into a Long, which is not equal to the Integer the datafile side produces, so the
     * typed comparison would fail on the value's box type rather than on real behaviour.
     */
    static Object jsonToObject(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return MAPPER.convertValue(node, Object.class);
    }
}
