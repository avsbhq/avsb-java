package com.avsbhq.avsb.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bandit evaluation engine: epsilon-greedy, Thompson sampling, UCB1.
 *
 * <p>Mirrors {@code @avsbhq/core/src/bandits.ts}. All algorithms are pure
 * functions of {@code (perAction, actions, hashBucket)}; no
 * {@link Math#random()} is used. Every stochastic step is seeded from
 * {@link Hash#murmurhash3} so a {@code (banditKey, context)} pair always
 * produces the same decision.
 */
public final class Bandits {

    private Bandits() {}

    private static final int MAX_BUCKET = 10_000;
    private static final long HASH_RANGE = 0x100000000L; // 2^32

    // -------------------------------------------------------------------------
    // Public result types
    // -------------------------------------------------------------------------

    public record BanditPerActionStats(double mean, double variance, int samples) {}

    public record EpsilonGreedyResult(String actionId, double actionProbability, boolean exploreFired) {}

    public record ThompsonSampleResult(String actionId, double actionProbability) {}

    public record Ucb1Result(String actionId, double actionProbability) {}

    // -------------------------------------------------------------------------
    // Seeded pseudo-randomness (matches bandits.ts)
    // -------------------------------------------------------------------------

    /** Derive a value in (0, 1] from a seed and a salt integer. */
    static double uniformFromSeed(int seed, int salt) {
        int hashed = Hash.murmurhash3(seed + ":" + salt);
        long unsigned = Integer.toUnsignedLong(hashed);
        return (unsigned + 1.0) / (HASH_RANGE + 1.0);
    }

    /**
     * Produce a single standard-normal sample deterministically from
     * {@code seed}. Mirrors {@code seededGaussian} in bandits.ts.
     */
    public static double seededGaussian(int seed) {
        double u1 = uniformFromSeed(seed, 1);
        double u2 = uniformFromSeed(seed, 2);
        return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Return a stable alphabetically-sorted copy of the action ids. */
    private static List<String> sortedActionIds(List<String> ids) {
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(String::compareTo);
        return sorted;
    }

    private static BanditPerActionStats statsFor(Map<String, BanditPerActionStats> perAction, String actionId) {
        BanditPerActionStats existing = perAction.get(actionId);
        return existing != null ? existing : new BanditPerActionStats(0, 1, 0);
    }

    // -------------------------------------------------------------------------
    // Epsilon-greedy
    // -------------------------------------------------------------------------

    /**
     * Select an action using the epsilon-greedy algorithm.
     *
     * @param perAction     Per-action reward statistics.
     * @param actionIds     Available action ids (will be sorted alphabetically).
     * @param hashBucket    Pre-computed 0..9999 bucket value.
     * @param explorationRate Exploration fraction (default 0.1).
     */
    public static EpsilonGreedyResult epsilonGreedy(
            Map<String, BanditPerActionStats> perAction,
            List<String> actionIds,
            int hashBucket,
            double explorationRate
    ) {
        if (actionIds.isEmpty()) throw new IllegalArgumentException("epsilonGreedy: actions list is empty");

        List<String> sorted = sortedActionIds(actionIds);
        int n = sorted.size();
        double bucketFraction = hashBucket / (double) MAX_BUCKET;
        boolean exploreFired = bucketFraction < explorationRate;

        if (exploreFired) {
            String action = sorted.get(hashBucket % n);
            return new EpsilonGreedyResult(action, explorationRate / n, true);
        }

        // Exploit — highest mean, alphabetical tie-break
        String bestId = null;
        double bestMean = Double.NEGATIVE_INFINITY;
        int bestCount = 0;
        for (String id : sorted) {
            double mean = statsFor(perAction, id).mean();
            if (mean > bestMean) { bestMean = mean; bestId = id; bestCount = 1; }
            else if (mean == bestMean) { bestCount++; }
        }

        double exploitMass = (1 - explorationRate) / bestCount;
        return new EpsilonGreedyResult(bestId, exploitMass + explorationRate / n, false);
    }

    // -------------------------------------------------------------------------
    // Thompson sampling
    // -------------------------------------------------------------------------

    public static ThompsonSampleResult thompsonSample(
            Map<String, BanditPerActionStats> perAction,
            List<String> actionIds,
            int hashBucket
    ) {
        if (actionIds.isEmpty()) throw new IllegalArgumentException("thompsonSample: actions list is empty");

        List<String> sorted = sortedActionIds(actionIds);
        String bestId = sorted.get(0);
        double bestSample = Double.NEGATIVE_INFINITY;

        for (String id : sorted) {
            BanditPerActionStats stats = statsFor(perAction, id);
            int seed = Hash.murmurhash3(hashBucket + ":" + id);
            double z = seededGaussian(seed);
            double stddev = Math.sqrt(stats.variance() / Math.max(stats.samples(), 1));
            double sample = stats.mean() + z * stddev;
            if (sample > bestSample) { bestSample = sample; bestId = id; }
        }

        return new ThompsonSampleResult(bestId, 1.0 / sorted.size());
    }

    // -------------------------------------------------------------------------
    // UCB1
    // -------------------------------------------------------------------------

    public static Ucb1Result ucb1(
            Map<String, BanditPerActionStats> perAction,
            List<String> actionIds
    ) {
        if (actionIds.isEmpty()) throw new IllegalArgumentException("ucb1: actions list is empty");

        List<String> sorted = sortedActionIds(actionIds);

        // Cold-start — pick the first unseen action
        for (String id : sorted) {
            if (statsFor(perAction, id).samples() == 0) {
                return new Ucb1Result(id, 1.0);
            }
        }

        long totalSamples = 0;
        for (String id : sorted) totalSamples += statsFor(perAction, id).samples();
        double lnTotal = Math.log(Math.max(totalSamples, 1));

        String bestId = sorted.get(0);
        double bestScore = Double.NEGATIVE_INFINITY;
        for (String id : sorted) {
            BanditPerActionStats stats = statsFor(perAction, id);
            double score = stats.mean() + Math.sqrt(2 * lnTotal / Math.max(stats.samples(), 1));
            if (score > bestScore) { bestScore = score; bestId = id; }
        }

        return new Ucb1Result(bestId, 1.0);
    }
}
