package com.avsbhq.avsb;

import com.avsbhq.avsb.core.Datafile;
import com.avsbhq.avsb.core.Evaluator;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The conformance allocation family: targeted delivery rollouts (N1-1).
 *
 * <p>Six generated fixtures, one rollout rule each (5, 30 and 50 percent, a fractional 0.12345
 * boundary, 100 percent and no allocation), each with 200 contexts whose outcomes the TypeScript
 * reference recorded. Every one of the 1,200 evaluations is replayed, reasons included, so a
 * visitor is inside or outside a rollout identically here and in every other SDK.
 *
 * <p>FAIL-HARD RULE (conformance README, "The allocation family"): a missing manifest, an empty
 * or miscounted fixture list, a missing file, a case that differs from its manifest entry, a
 * fixture with the wrong number of evaluations, or a JSON file the manifest does not name all
 * fail. Fixtures resolve exactly as {@link ConformanceTest} resolves them (monorepo first, the
 * vendored copy second).
 */
@DisplayName("Conformance — allocation family (targeted delivery rollouts)")
class ConformanceAllocationTest {

    private static final String HINT =
            "Run `npm run generate` then `npm run vendor` in packages/avsb-conformance.";

    /** What replaying one fixture produced. */
    private record Replay(int ran, List<String> drift) {}

    private static Path allocationDir() {
        return ConformanceTest.fixturesRoot().resolve("allocation");
    }

    private static JsonNode allocationManifest() {
        Path dir = allocationDir();
        JsonNode manifest = ConformanceTest.readJson(dir.resolve("manifest.json"), "allocation manifest");
        JsonNode fixtures = manifest.get("fixtures");
        if (fixtures == null || !fixtures.isArray() || fixtures.isEmpty()) {
            throw new IllegalStateException("The allocation manifest at " + dir
                    + " lists no fixtures. An empty family guarantees nothing. " + HINT);
        }
        int declared = manifest.path("fixtureCount").asInt(-1);
        if (declared != fixtures.size()) {
            throw new IllegalStateException("The allocation manifest says fixtureCount=" + declared
                    + " but lists " + fixtures.size() + " fixtures, so it was hand-edited. " + HINT);
        }
        if (manifest.path("contextsPerFixture").asInt(0) <= 0) {
            throw new IllegalStateException("The allocation manifest declares no contextsPerFixture.");
        }
        return manifest;
    }

    private static JsonNode loadFixture(JsonNode manifest, JsonNode entry) {
        String file = entry.get("file").asText();
        JsonNode fixture = ConformanceTest.readJson(allocationDir().resolve(file), "allocation fixture " + file);
        String declared = fixture.path("case").asText();
        if (!entry.get("case").asText().equals(declared)) {
            throw new IllegalStateException("Allocation fixture " + file + " declares case '" + declared
                    + "' but the manifest names it '" + entry.get("case").asText() + "'.");
        }
        JsonNode evaluations = fixture.get("evaluations");
        int perFixture = manifest.get("contextsPerFixture").asInt();
        if (evaluations == null || !evaluations.isArray() || evaluations.size() != perFixture) {
            throw new IllegalStateException("Allocation fixture " + file + " does not carry "
                    + perFixture + " evaluations. " + HINT);
        }
        return fixture;
    }

    private static Replay replay(JsonNode fixture) {
        Datafile.FlagDatafileDoc datafile;
        try {
            datafile = ConformanceTest.MAPPER.treeToValue(fixture.get("datafile"), Datafile.FlagDatafileDoc.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot parse the inline datafile of " + fixture.path("case"), e);
        }
        String flagKey = fixture.get("flagKey").asText();
        Object defaultValue = ConformanceTest.jsonToObject(fixture.get("defaultValue"));

        int ran = 0;
        List<String> drift = new ArrayList<>();
        for (JsonNode evaluation : fixture.get("evaluations")) {
            EvalContext ctx = ConformanceTest.parseContext(evaluation.get("context"));
            // The evaluator always collects reasons, and they are part of the contract.
            Flag<Object> flag = Evaluator.evaluateFlag(flagKey, defaultValue, ctx, datafile);
            ran++;
            String problem = mismatch(evaluation.get("expected"), flag);
            if (problem != null) {
                drift.add(evaluation.get("context").path("key").asText() + ": " + problem);
            }
        }
        return new Replay(ran, drift);
    }

    /** Compares all six contract fields; names the first that differs, or returns null. */
    private static String mismatch(JsonNode exp, Flag<Object> flag) {
        JsonNode actualValue = flag.value() == null
                ? ConformanceTest.MAPPER.nullNode()
                : ConformanceTest.MAPPER.valueToTree(flag.value());
        if (!exp.get("value").equals(actualValue)) {
            return "value " + actualValue + ", want " + exp.get("value");
        }
        String variationKey = exp.get("variationKey").isNull() ? null : exp.get("variationKey").asText();
        if (!Objects.equals(variationKey, flag.variationKey())) {
            return "variationKey " + flag.variationKey() + ", want " + variationKey;
        }
        EvaluationSource source = EvaluationSource.fromWire(exp.get("source").asText());
        if (source != flag.source()) {
            return "source " + flag.source() + ", want " + source;
        }
        String ruleId = exp.get("ruleId").isNull() ? null : exp.get("ruleId").asText();
        if (!Objects.equals(ruleId, flag.ruleId())) {
            return "ruleId " + flag.ruleId() + ", want " + ruleId;
        }
        RuleType ruleType = exp.get("ruleType").isNull() ? null : RuleType.fromWire(exp.get("ruleType").asText());
        if (ruleType != flag.ruleType()) {
            return "ruleType " + flag.ruleType() + ", want " + ruleType;
        }
        List<String> reasons = new ArrayList<>();
        for (JsonNode reason : exp.get("reasons")) reasons.add(reason.asText());
        if (!reasons.equals(flag.reasons())) {
            return "reasons " + flag.reasons() + ", want " + reasons;
        }
        return null;
    }

    @TestFactory
    @DisplayName("every recorded evaluation matches the reference")
    Stream<DynamicTest> everyRecordedEvaluationMatchesTheReference() {
        JsonNode manifest = allocationManifest();
        int perFixture = manifest.get("contextsPerFixture").asInt();
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode entry : manifest.get("fixtures")) {
            String caseName = entry.get("case").asText();
            tests.add(DynamicTest.dynamicTest(caseName, () -> {
                Replay result = replay(loadFixture(manifest, entry));
                assertEquals(perFixture, result.ran(), caseName + ": evaluations run");
                assertEquals(List.of(), result.drift(), caseName + ": contexts that drifted");
            }));
        }
        return tests.stream();
    }

    @Test
    @DisplayName("the whole family ran, so nothing can pass by running nothing")
    void theWholeFamilyRan() {
        JsonNode manifest = allocationManifest();
        int total = 0;
        for (JsonNode entry : manifest.get("fixtures")) {
            total += replay(loadFixture(manifest, entry)).ran();
        }
        assertEquals(manifest.get("fixtureCount").asInt() * manifest.get("contextsPerFixture").asInt(), total);
        assertEquals(1200, total);
    }

    @Test
    @DisplayName("the family carries the six rollout levels")
    void carriesTheSixRolloutLevels() {
        List<String> cases = new ArrayList<>();
        for (JsonNode entry : allocationManifest().get("fixtures")) cases.add(entry.get("case").asText());
        assertEquals(List.of(
                "targeted_delivery_allocation_0.05",
                "targeted_delivery_allocation_0.3",
                "targeted_delivery_allocation_0.5",
                "targeted_delivery_allocation_fractional_boundary",
                "targeted_delivery_allocation_1.0",
                "targeted_delivery_allocation_null"), cases);
    }

    @Test
    @DisplayName("no allocation fixture is left out of the manifest")
    void noOrphanAllocationFixtures() throws IOException {
        Set<String> named = new HashSet<>();
        for (JsonNode entry : allocationManifest().get("fixtures")) named.add(entry.get("file").asText());

        List<String> orphans = new ArrayList<>();
        try (Stream<Path> files = Files.list(allocationDir())) {
            files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json") && !"manifest.json".equals(name))
                    .filter(name -> !named.contains(name))
                    .forEach(orphans::add);
        }
        assertEquals(List.of(), orphans, "allocation fixtures not named by the manifest");
    }

    @Test
    @DisplayName("the fractional fixture puts its searched boundary keys first")
    void fractionalProbesComeFirst() {
        JsonNode manifest = allocationManifest();
        JsonNode fractional = null;
        for (JsonNode entry : manifest.get("fixtures")) {
            if ("targeted_delivery_allocation_fractional_boundary".equals(entry.get("case").asText())) {
                fractional = loadFixture(manifest, entry);
            }
        }
        assertNotNull(fractional, "the fractional boundary fixture is missing");

        JsonNode probes = fractional.get("probes");
        assertEquals(1234, probes.get(0).get("bucket").asInt());
        assertEquals(1235, probes.get(1).get("bucket").asInt());
        JsonNode evaluations = fractional.get("evaluations");
        for (int i = 0; i < 2; i++) {
            assertEquals(probes.get(i).get("resolvedKey").asText(),
                    evaluations.get(i).get("context").get("key").asText());
        }
        // Bucket 1234 is inside the 1234.5 threshold; a port that floors it would say no.
        assertEquals("rule", evaluations.get(0).get("expected").get("source").asText());
        assertEquals("default", evaluations.get(1).get("expected").get("source").asText());
    }
}
