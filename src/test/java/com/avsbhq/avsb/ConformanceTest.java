package com.avsbhq.avsb;

import com.avsbhq.avsb.core.Datafile;
import com.avsbhq.avsb.core.Evaluator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance test suite — runs every case declared in
 * {@code packages/avsb-conformance/fixtures/manifest.json} and asserts that
 * the Java evaluator produces exactly the same output as the TypeScript
 * reference recorded in the fixture.
 *
 * <p>Fixture path resolution: the test walks up from the Gradle project dir
 * ({@code packages/avsb-java/}) by two levels to reach the monorepo root,
 * then descends into {@code packages/avsb-conformance/fixtures/}.
 */
@DisplayName("Conformance — all fixture cases")
class ConformanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // -------------------------------------------------------------------------
    // Fixture discovery
    // -------------------------------------------------------------------------

    private Path fixturesRoot() {
        // Gradle sets user.dir to the project root (packages/avsb-java/).
        // Walk up two levels to monorepo root, then into conformance fixtures.
        Path projectDir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        Path fixturesPath = projectDir.resolve(Paths.get("..", "..", "avsb-conformance", "fixtures")).normalize();
        if (!Files.isDirectory(fixturesPath)) {
            // Fallback: try sibling path from project parent
            fixturesPath = projectDir.getParent().resolve(Paths.get("avsb-conformance", "fixtures")).normalize();
        }
        return fixturesPath;
    }

    // -------------------------------------------------------------------------
    // Dynamic test factory
    // -------------------------------------------------------------------------

    @TestFactory
    @DisplayName("fixture cases from manifest.json")
    Stream<DynamicTest> conformanceCases() throws IOException {
        Path fixtures = fixturesRoot();
        Path manifestPath = fixtures.resolve("manifest.json");

        if (!Files.exists(manifestPath)) {
            // Graceful skip when running outside the monorepo
            return Stream.of(DynamicTest.dynamicTest("manifest not found — skipped", () ->
                    System.out.println("Conformance fixtures not found at " + manifestPath + " — skipping")));
        }

        JsonNode manifest = MAPPER.readTree(manifestPath.toFile());
        JsonNode cases = manifest.get("cases");
        List<DynamicTest> tests = new ArrayList<>();

        for (JsonNode c : cases) {
            String caseName = c.get("case").asText();
            String expectedFile = c.get("expected").asText();
            tests.add(DynamicTest.dynamicTest(caseName, () ->
                    runConformanceCase(fixtures, fixtures.resolve(expectedFile))));
        }
        return tests.stream();
    }

    // -------------------------------------------------------------------------
    // Per-case runner
    // -------------------------------------------------------------------------

    private void runConformanceCase(Path fixturesRoot, Path expectedPath) throws IOException {
        JsonNode expected = MAPPER.readTree(expectedPath.toFile());

        // Load datafile
        String datafileRel = expected.get("datafile").asText();
        Path datafilePath = fixturesRoot.resolve(datafileRel);
        Datafile.FlagDatafileDoc datafile = MAPPER.readValue(datafilePath.toFile(), Datafile.FlagDatafileDoc.class);

        // Load context
        String contextRel = expected.get("context").asText();
        Path contextPath = fixturesRoot.resolve(contextRel);
        JsonNode contextJson = MAPPER.readTree(contextPath.toFile());
        EvalContext ctx = parseContext(contextJson);

        // Evaluate
        String flagKey = expected.get("flagKey").asText();
        JsonNode defaultValueNode = expected.get("defaultValue");
        Object defaultValue = jsonToObject(defaultValueNode);

        @SuppressWarnings("unchecked")
        Flag<Object> flag = Evaluator.evaluateFlag(flagKey, defaultValue, ctx, datafile);

        // Assert
        JsonNode exp = expected.get("expected");

        // value
        Object expectedValue = jsonToObject(exp.get("value"));
        assertEquals(String.valueOf(expectedValue), String.valueOf(flag.value()),
                "value mismatch for case " + expectedPath.getFileName());

        // variationKey
        String expectedVarKey = exp.has("variationKey") && !exp.get("variationKey").isNull()
                ? exp.get("variationKey").asText() : null;
        assertEquals(expectedVarKey, flag.variationKey(),
                "variationKey mismatch for case " + expectedPath.getFileName());

        // source (when declared)
        if (exp.has("source") && !exp.get("source").isNull()) {
            EvaluationSource expectedSource = EvaluationSource.fromWire(exp.get("source").asText());
            assertEquals(expectedSource, flag.source(),
                    "source mismatch for case " + expectedPath.getFileName());
        }

        // ruleId (when declared)
        if (exp.has("ruleId") && !exp.get("ruleId").isNull()) {
            assertEquals(exp.get("ruleId").asText(), flag.ruleId(),
                    "ruleId mismatch for case " + expectedPath.getFileName());
        }

        // ruleType (when declared)
        if (exp.has("ruleType") && !exp.get("ruleType").isNull()) {
            RuleType expectedRuleType = RuleType.fromWire(exp.get("ruleType").asText());
            assertEquals(expectedRuleType, flag.ruleType(),
                    "ruleType mismatch for case " + expectedPath.getFileName());
        }

        // reasons (when declared — full list match)
        if (exp.has("reasons") && exp.get("reasons").isArray()) {
            List<String> expectedReasons = new ArrayList<>();
            for (JsonNode r : exp.get("reasons")) expectedReasons.add(r.asText());
            assertEquals(expectedReasons, flag.reasons(),
                    "reasons mismatch for case " + expectedPath.getFileName());
        }
    }

    // -------------------------------------------------------------------------
    // Context deserialisation from fixture JSON
    // -------------------------------------------------------------------------

    private static EvalContext parseContext(JsonNode json) {
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

    private static Object jsonToObject(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isBoolean())  return node.asBoolean();
        if (node.isDouble() || node.isFloat()) return node.asDouble();
        if (node.isIntegralNumber()) return node.asLong();
        if (node.isTextual())  return node.asText();
        if (node.isObject())   return MAPPER.convertValue(node, Map.class);
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(e -> list.add(jsonToObject(e)));
            return list;
        }
        return node.asText();
    }
}
