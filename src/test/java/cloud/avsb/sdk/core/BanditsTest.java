package cloud.avsb.sdk.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Bandits — algorithms")
class BanditsTest {

    private static final Map<String, Bandits.BanditPerActionStats> EQUAL_STATS = Map.of(
            "action_a", new Bandits.BanditPerActionStats(0.5, 1, 10),
            "action_b", new Bandits.BanditPerActionStats(0.5, 1, 10)
    );

    private static final Map<String, Bandits.BanditPerActionStats> BIASED_STATS = Map.of(
            "action_a", new Bandits.BanditPerActionStats(0.8, 0.1, 50),
            "action_b", new Bandits.BanditPerActionStats(0.2, 0.1, 50)
    );

    @Test
    @DisplayName("seededGaussian is deterministic for the same seed")
    void seededGaussianDeterminism() {
        double g1 = Bandits.seededGaussian(42);
        double g2 = Bandits.seededGaussian(42);
        assertEquals(g1, g2);
    }

    @Test
    @DisplayName("seededGaussian differs for different seeds")
    void seededGaussianVariance() {
        double g1 = Bandits.seededGaussian(1);
        double g2 = Bandits.seededGaussian(2);
        assertNotEquals(g1, g2);
    }

    @Test
    @DisplayName("epsilonGreedy throws on empty action list")
    void epsilonGreedyEmpty() {
        assertThrows(IllegalArgumentException.class,
                () -> Bandits.epsilonGreedy(Map.of(), List.of(), 100, 0.1));
    }

    @Test
    @DisplayName("epsilonGreedy exploit selects highest-mean action")
    void epsilonGreedyExploit() {
        // Use a hash bucket above the exploration threshold to force exploit
        int exploitBucket = 5000; // 0.5 > epsilon 0.1 → exploit branch
        Bandits.EpsilonGreedyResult result = Bandits.epsilonGreedy(
                BIASED_STATS, List.of("action_a", "action_b"), exploitBucket, 0.1);
        assertFalse(result.exploreFired());
        assertEquals("action_a", result.actionId());
    }

    @Test
    @DisplayName("epsilonGreedy explore fires when bucket < epsilon threshold")
    void epsilonGreedyExplore() {
        // bucket 50 / 10000 = 0.005 < epsilon 0.1 → explore branch
        Bandits.EpsilonGreedyResult result = Bandits.epsilonGreedy(
                BIASED_STATS, List.of("action_a", "action_b"), 50, 0.1);
        assertTrue(result.exploreFired());
    }

    @Test
    @DisplayName("thompsonSample is deterministic for same (hashBucket, actionIds)")
    void thompsonDeterminism() {
        Bandits.ThompsonSampleResult r1 = Bandits.thompsonSample(EQUAL_STATS, List.of("action_a", "action_b"), 777);
        Bandits.ThompsonSampleResult r2 = Bandits.thompsonSample(EQUAL_STATS, List.of("action_a", "action_b"), 777);
        assertEquals(r1.actionId(), r2.actionId());
    }

    @Test
    @DisplayName("ucb1 selects cold-start (unseen) action first")
    void ucb1ColdStart() {
        Map<String, Bandits.BanditPerActionStats> stats = Map.of(
                "action_a", new Bandits.BanditPerActionStats(0.9, 0.1, 100),
                "action_b", new Bandits.BanditPerActionStats(0.0, 1.0, 0)  // unseen
        );
        Bandits.Ucb1Result result = Bandits.ucb1(stats, List.of("action_a", "action_b"));
        assertEquals("action_b", result.actionId());
    }

    @Test
    @DisplayName("ucb1 selects highest UCB score when all actions are seen")
    void ucb1ExploitHighScore() {
        Map<String, Bandits.BanditPerActionStats> stats = Map.of(
                "action_a", new Bandits.BanditPerActionStats(0.8, 0.1, 50),
                "action_b", new Bandits.BanditPerActionStats(0.2, 0.1, 50)
        );
        Bandits.Ucb1Result result = Bandits.ucb1(stats, List.of("action_a", "action_b"));
        assertEquals("action_a", result.actionId());
    }
}
