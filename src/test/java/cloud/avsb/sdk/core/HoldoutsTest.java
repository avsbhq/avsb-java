package cloud.avsb.sdk.core;

import cloud.avsb.sdk.EvalContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Holdouts — evaluateHoldout")
class HoldoutsTest {

    private static Datafile.HoldoutEntry holdout(String id, String key, double traffic,
                                                  List<String> flagIds, String hashAttr,
                                                  Map<String, String> defaultVarByFlag) {
        return new Datafile.HoldoutEntry(id, key, traffic, flagIds, hashAttr, defaultVarByFlag);
    }

    @Test
    @DisplayName("returns heldOut=false when holdout list is empty")
    void noHoldouts() {
        Holdouts.HoldoutEvaluation result = Holdouts.evaluateHoldout(
                EvalContext.user("alice"), "flag_1", List.of());
        assertFalse(result.heldOut());
    }

    @Test
    @DisplayName("returns heldOut=false when holdout does not include the flag")
    void flagNotInHoldout() {
        Datafile.HoldoutEntry h = holdout("h1", "q1-holdout", 0.5,
                List.of("other_flag"), "user.key", Map.of("other_flag", "var_off"));
        Holdouts.HoldoutEvaluation result = Holdouts.evaluateHoldout(
                EvalContext.user("alice"), "flag_1", List.of(h));
        assertFalse(result.heldOut());
    }

    @Test
    @DisplayName("returns heldOut=true for context inside 99.9% holdout window")
    void heldOutAt999() {
        // The fixture user "alice" with holdout key "q2-2026-holdout" using hashAttr="user.key"
        // is recorded as heldOut=true (99.9% window covers almost all users).
        Datafile.HoldoutEntry h = holdout("holdout_q2", "q2-2026-holdout", 0.999,
                List.of("flag_holdout_target"), "user.key",
                Map.of("flag_holdout_target", "var_off"));
        Holdouts.HoldoutEvaluation result = Holdouts.evaluateHoldout(
                EvalContext.user("alice"), "flag_holdout_target", List.of(h));
        assertTrue(result.heldOut());
        assertEquals("var_off", result.variationId());
        assertNotNull(result.holdout());
        assertEquals("holdout_q2", result.holdout().id());
    }

    @Test
    @DisplayName("returns heldOut=false when traffic allocation is zero")
    void zeroAllocation() {
        Datafile.HoldoutEntry h = holdout("h1", "holdout-zero", 0.0,
                List.of("flag_1"), "user.key", Map.of("flag_1", "var_off"));
        Holdouts.HoldoutEvaluation result = Holdouts.evaluateHoldout(
                EvalContext.user("alice"), "flag_1", List.of(h));
        assertFalse(result.heldOut());
    }

    @Test
    @DisplayName("holdout variationId resolves from defaultVariationByFlag")
    void variationIdResolution() {
        Datafile.HoldoutEntry h = holdout("h1", "big-holdout", 0.999,
                List.of("my_flag"), "user.key", Map.of("my_flag", "control_var"));
        Holdouts.HoldoutEvaluation result = Holdouts.evaluateHoldout(
                EvalContext.user("alice"), "my_flag", List.of(h));
        // May or may not be held out depending on hash, but variationId must be control_var if held out
        if (result.heldOut()) {
            assertEquals("control_var", result.variationId());
        }
    }

    @Test
    @DisplayName("bucketing is consistent for the same (holdoutKey, userKey) pair")
    void deterministicBucketing() {
        Datafile.HoldoutEntry h = holdout("h1", "stable-holdout", 0.5,
                List.of("flag_1"), "user.key", Map.of("flag_1", "var_off"));
        EvalContext ctx = EvalContext.user("deterministic-user");
        boolean first  = Holdouts.evaluateHoldout(ctx, "flag_1", List.of(h)).heldOut();
        boolean second = Holdouts.evaluateHoldout(ctx, "flag_1", List.of(h)).heldOut();
        assertEquals(first, second, "Bucketing must be deterministic");
    }
}
